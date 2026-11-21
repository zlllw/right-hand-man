package main

import (
	"errors"
	"sort"
	"strings"
	"sync"
	"time"
)

type fakeStep struct {
	name string
	data map[string]any
}

var defaultFakeScript = []fakeStep{
	{"text", map[string]any{"delta": "echo: "}},
	{"text", map[string]any{"delta": "{message}"}},
	{"done", map[string]any{"finishReason": "stop"}},
}

type fakeCall struct {
	ConversationID string
	Start          time.Time
	End            time.Time
	Message        string
}

// FakeAdapter keeps the protocol testable on a desktop without opencode.
type FakeAdapter struct {
	mu        sync.Mutex
	script    []fakeStep
	stepDelay time.Duration
	failWith  string
	calls     []fakeCall
	history   map[string][]HistoryMessage
	titles    map[string]string
	aborted   map[string]bool
}

func newFakeAdapter() *FakeAdapter {
	return &FakeAdapter{
		script:  defaultFakeScript,
		history: map[string][]HistoryMessage{},
		titles:  map[string]string{},
		aborted: map[string]bool{},
	}
}

func (f *FakeAdapter) Name() string { return "fake" }

func (f *FakeAdapter) Start() error { return nil }

func (f *FakeAdapter) Stop() {}

func (f *FakeAdapter) Health() bool { return true }

func (f *FakeAdapter) SetStepDelay(delay time.Duration) {
	f.mu.Lock()
	f.stepDelay = delay
	f.mu.Unlock()
}

func (f *FakeAdapter) Run(conversationID, message string, emit EmitFunc) error {
	started := time.Now()
	reply := strings.Builder{}
	defer func() {
		now := time.Now()
		f.mu.Lock()
		f.calls = append(f.calls, fakeCall{
			ConversationID: conversationID,
			Start:          started,
			End:            now,
			Message:        message,
		})
		entries := append(f.history[conversationID], HistoryMessage{
			Role: "user", Text: message, TS: float64(now.UnixNano()) / 1e9,
		})
		entries = append(entries, HistoryMessage{
			Role: "assistant", Text: reply.String(), TS: float64(now.UnixNano()) / 1e9,
		})
		f.history[conversationID] = entries
		f.mu.Unlock()
	}()

	if f.failWith != "" {
		return errors.New(f.failWith)
	}

	f.mu.Lock()
	script := append([]fakeStep(nil), f.script...)
	delay := f.stepDelay
	f.mu.Unlock()

	for _, step := range script {
		if delay > 0 {
			time.Sleep(delay)
		}
		payload := make(map[string]any, len(step.data))
		for key, value := range step.data {
			if text, ok := value.(string); ok {
				payload[key] = strings.ReplaceAll(text, "{message}", message)
			} else {
				payload[key] = value
			}
		}
		if step.name == "text" {
			if delta, ok := payload["delta"].(string); ok {
				reply.WriteString(delta)
			}
		}
		emit(step.name, payload)
	}
	return nil
}

func (f *FakeAdapter) ListConversations() []Conversation {
	f.mu.Lock()
	defer f.mu.Unlock()
	out := make([]Conversation, 0, len(f.history))
	for conversationID, entries := range f.history {
		firstUser := ""
		for _, entry := range entries {
			if entry.Role == "user" {
				firstUser = entry.Text
				break
			}
		}
		title := f.titles[conversationID]
		if title == "" {
			runes := []rune(firstUser)
			if len(runes) > 40 {
				title = string(runes[:40])
			} else {
				title = firstUser
			}
		}
		if title == "" {
			title = conversationID
		}
		updated := 0.0
		if len(entries) > 0 {
			updated = entries[len(entries)-1].TS
		}
		out = append(out, Conversation{
			ConversationID: conversationID,
			Title:          title,
			UpdatedAt:      updated,
		})
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].UpdatedAt > out[j].UpdatedAt })
	return out
}

func (f *FakeAdapter) ConversationMessages(conversationID string, limit int) []HistoryMessage {
	f.mu.Lock()
	defer f.mu.Unlock()
	entries := append([]HistoryMessage(nil), f.history[conversationID]...)
	if limit > 0 && len(entries) > limit {
		entries = entries[len(entries)-limit:]
	}
	return entries
}

func (f *FakeAdapter) RenameConversation(conversationID, title string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	if _, ok := f.history[conversationID]; !ok {
		return false
	}
	f.titles[conversationID] = title
	return true
}

func (f *FakeAdapter) DeleteConversation(conversationID string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	if _, ok := f.history[conversationID]; !ok {
		return false
	}
	delete(f.history, conversationID)
	delete(f.titles, conversationID)
	return true
}

func (f *FakeAdapter) Abort(conversationID string) bool {
	f.mu.Lock()
	f.aborted[conversationID] = true
	f.mu.Unlock()
	return true
}

func (f *FakeAdapter) Aborted(conversationID string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.aborted[conversationID]
}

func (f *FakeAdapter) Calls() []fakeCall {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]fakeCall(nil), f.calls...)
}

func (f *FakeAdapter) Spans(conversationID string) []fakeCall {
	f.mu.Lock()
	defer f.mu.Unlock()
	var spans []fakeCall
	for _, call := range f.calls {
		if call.ConversationID == conversationID {
			spans = append(spans, call)
		}
	}
	sort.SliceStable(spans, func(i, j int) bool { return spans[i].Start.Before(spans[j].Start) })
	return spans
}

func (f *FakeAdapter) Overlaps(conversationID string) bool {
	spans := f.Spans(conversationID)
	for index := 1; index < len(spans); index++ {
		if spans[index].Start.Before(spans[index-1].End) {
			return true
		}
	}
	return false
}
