package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"strings"
	"time"
)

const (
	ringCapacity = 500
	doneTTL      = 60 * time.Second
)

// Event is one buffered SSE event. Data is serialized once at append time so
// replays are byte-identical to the original emission.
type Event struct {
	ID   int
	Name string
	Data string
}

func compactJSON(v any) string {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return "null"
	}
	return strings.TrimSuffix(buf.String(), "\n")
}

func (e Event) SSE() string {
	return fmt.Sprintf("id: %d\nevent: %s\ndata: %s\n\n", e.ID, e.Name, e.Data)
}

// sseBlock builds a block without a buffered id (history_truncated, TTL done).
func sseBlock(name string, data any, id *int) string {
	prefix := ""
	if id != nil {
		prefix = fmt.Sprintf("id: %d\n", *id)
	}
	return fmt.Sprintf("%sevent: %s\ndata: %s\n\n", prefix, name, compactJSON(data))
}

// EventLog is the per-request ring buffer.
type EventLog struct {
	capacity int
	events   []Event
	nextID   int
}

func newEventLog() *EventLog {
	return &EventLog{capacity: ringCapacity, nextID: 1}
}

func (l *EventLog) Append(name string, data any) Event {
	event := Event{ID: l.nextID, Name: name, Data: compactJSON(data)}
	l.nextID++
	l.events = append(l.events, event)
	if overflow := len(l.events) - l.capacity; overflow > 0 {
		kept := make([]Event, len(l.events)-overflow)
		copy(kept, l.events[overflow:])
		l.events = kept
	}
	return event
}

func (l *EventLog) Last() (Event, bool) {
	if len(l.events) == 0 {
		return Event{}, false
	}
	return l.events[len(l.events)-1], true
}

func (l *EventLog) EarliestID() (int, bool) {
	if len(l.events) == 0 {
		return 0, false
	}
	return l.events[0].ID, true
}

// Replay returns events strictly after afterID and whether ids between
// afterID and the buffer's earliest event were already evicted.
func (l *EventLog) Replay(afterID int) ([]Event, bool) {
	if len(l.events) == 0 {
		return nil, false
	}
	earliest := l.events[0].ID
	truncated := afterID < earliest-1
	out := make([]Event, 0, len(l.events))
	for _, event := range l.events {
		if event.ID > afterID {
			out = append(out, event)
		}
	}
	return out, truncated
}
