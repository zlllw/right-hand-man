package main

import "testing"

func TestEventLogIDsAndReplay(t *testing.T) {
	log := newEventLog()
	log.Append("text", map[string]any{"delta": "a"})
	log.Append("text", map[string]any{"delta": "b"})

	events, truncated := log.Replay(0)
	if truncated {
		t.Fatal("fresh log must not be truncated")
	}
	if len(events) != 2 || events[0].ID != 1 || events[1].ID != 2 {
		t.Fatalf("unexpected events: %+v", events)
	}

	events, truncated = log.Replay(1)
	if truncated || len(events) != 1 || events[0].ID != 2 {
		t.Fatalf("replay must be exclusive: %+v truncated=%v", events, truncated)
	}

	events, truncated = log.Replay(2)
	if len(events) != 0 || truncated {
		t.Fatalf("expected empty tail, got %+v", events)
	}
}

func TestEventLogTruncation(t *testing.T) {
	log := &EventLog{capacity: 3, nextID: 1}
	for index := 0; index < 5; index++ {
		log.Append("text", map[string]any{"delta": index})
	}

	events, truncated := log.Replay(0)
	if !truncated || len(events) != 3 || events[0].ID != 3 || events[2].ID != 5 {
		t.Fatalf("expected ids 3..5 truncated, got %+v truncated=%v", events, truncated)
	}
	events, truncated = log.Replay(2)
	if truncated || len(events) != 3 {
		t.Fatalf("after 2 is recoverable: %+v truncated=%v", events, truncated)
	}
	events, truncated = log.Replay(4)
	if truncated || len(events) != 1 || events[0].ID != 5 {
		t.Fatalf("expected only id 5: %+v", events)
	}
}

func TestEventLogEmpty(t *testing.T) {
	log := newEventLog()
	if events, truncated := log.Replay(0); len(events) != 0 || truncated {
		t.Fatalf("empty replay = %+v truncated=%v", events, truncated)
	}
	if events, truncated := log.Replay(7); len(events) != 0 || truncated {
		t.Fatalf("empty replay = %+v truncated=%v", events, truncated)
	}
}

func TestEventSSEEncoding(t *testing.T) {
	log := newEventLog()
	event := log.Append("text", map[string]any{"delta": "hi"})
	want := "id: 1\nevent: text\ndata: {\"delta\":\"hi\"}\n\n"
	if got := event.SSE(); got != want {
		t.Fatalf("SSE = %q, want %q", got, want)
	}
}

func TestDoneTTLWindow(t *testing.T) {
	if doneTTL.Seconds() != 60 {
		t.Fatalf("done TTL = %v", doneTTL)
	}
}
