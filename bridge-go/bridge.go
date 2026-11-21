package main

import (
	"crypto/rand"
	"encoding/hex"
	"sync"
	"time"
)

func newRequestID() string {
	var raw [16]byte
	if _, err := rand.Read(raw[:]); err != nil {
		panic(err)
	}
	return hex.EncodeToString(raw[:])
}

// RequestSession is one POST /chat: its event log plus completion state.
type RequestSession struct {
	RequestID      string
	ConversationID string

	mu          sync.Mutex
	log         *EventLog
	notify      chan struct{}
	completed   bool
	completedAt time.Time
}

func newRequestSession(requestID, conversationID string) *RequestSession {
	return &RequestSession{
		RequestID:      requestID,
		ConversationID: conversationID,
		log:            newEventLog(),
		notify:         make(chan struct{}),
	}
}

func (s *RequestSession) signalLocked() {
	close(s.notify)
	s.notify = make(chan struct{})
}

func (s *RequestSession) appendEvent(name string, data any) {
	s.mu.Lock()
	s.log.Append(name, data)
	s.signalLocked()
	s.mu.Unlock()
}

func (s *RequestSession) wait(timeout time.Duration) {
	s.mu.Lock()
	ch := s.notify
	s.mu.Unlock()
	select {
	case <-ch:
	case <-time.After(timeout):
	}
}

type job struct {
	session *RequestSession
	message string
}

// Bridge serializes work per conversationId and broadcasts events to streams.
type Bridge struct {
	adapter   Adapter
	workspace *Workspace

	sessionsMu sync.Mutex
	sessions   map[string]*RequestSession

	workersMu sync.Mutex
	workers   map[string]chan job
}

func newBridge(adapter Adapter, workspace *Workspace) *Bridge {
	return &Bridge{
		adapter:   adapter,
		workspace: workspace,
		sessions:  map[string]*RequestSession{},
		workers:   map[string]chan job{},
	}
}

func (b *Bridge) Submit(conversationID, message string) string {
	requestID := newRequestID()
	session := newRequestSession(requestID, conversationID)
	b.sessionsMu.Lock()
	b.sessions[requestID] = session
	b.sessionsMu.Unlock()
	b.worker(conversationID) <- job{session: session, message: message}
	return requestID
}

func (b *Bridge) Session(requestID string) *RequestSession {
	b.sessionsMu.Lock()
	defer b.sessionsMu.Unlock()
	return b.sessions[requestID]
}

func (b *Bridge) worker(conversationID string) chan job {
	b.workersMu.Lock()
	defer b.workersMu.Unlock()
	queue, ok := b.workers[conversationID]
	if !ok {
		queue = make(chan job, 256)
		b.workers[conversationID] = queue
		go b.workLoop(queue)
	}
	return queue
}

func (b *Bridge) workLoop(queue chan job) {
	for task := range queue {
		b.execute(task.session, task.message)
	}
}

func (b *Bridge) execute(session *RequestSession, message string) {
	emit := func(name string, data any) {
		session.appendEvent(name, data)
	}
	if err := b.adapter.Run(session.ConversationID, message, emit); err != nil {
		emit("error", map[string]any{"message": err.Error()})
	}

	session.mu.Lock()
	last, ok := session.log.Last()
	if !ok || last.Name != "done" {
		session.log.Append("done", doneStop())
	}
	session.completed = true
	session.completedAt = time.Now()
	session.signalLocked()
	session.mu.Unlock()
}

// Stop ends a stream immediately, then asks the upstream to abort.
func (b *Bridge) Stop(requestID string) bool {
	session := b.Session(requestID)
	if session == nil {
		return false
	}
	session.mu.Lock()
	if !session.completed {
		last, ok := session.log.Last()
		if !ok || last.Name != "done" {
			session.log.Append("done", doneStop())
		}
		session.completed = true
		session.completedAt = time.Now()
		session.signalLocked()
	}
	conversationID := session.ConversationID
	session.mu.Unlock()

	go b.adapter.Abort(conversationID)
	return true
}

// Stream writes SSE blocks until done has been delivered. afterID is the last
// event id the client already has (0 = from the beginning).
func (b *Bridge) Stream(session *RequestSession, afterID int, write func(string) error) error {
	session.mu.Lock()
	completed := session.completed
	completedAt := session.completedAt
	session.mu.Unlock()

	if completed && time.Since(completedAt) > doneTTL {
		return write(sseBlock("done", doneStop(), nil))
	}

	lastSent := afterID
	warned := false
	for {
		session.mu.Lock()
		pending, truncated := session.log.Replay(lastSent)
		completed = session.completed
		session.mu.Unlock()

		if truncated && !warned {
			warned = true
			if err := write(sseBlock("error", map[string]any{"message": "history_truncated"}, nil)); err != nil {
				return err
			}
		}
		for _, event := range pending {
			lastSent = event.ID
			if err := write(event.SSE()); err != nil {
				return err
			}
		}
		if completed {
			return nil
		}
		session.wait(500 * time.Millisecond)
	}
}
