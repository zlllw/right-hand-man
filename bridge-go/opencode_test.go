package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func writeJSONResponse(w http.ResponseWriter, payload any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(payload)
}

func TestVerifyDoc(t *testing.T) {
	paths := map[string]any{}
	for _, path := range requiredPaths {
		paths[path] = map[string]any{}
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		writeJSONResponse(w, map[string]any{"paths": paths})
	}))
	defer server.Close()

	adapter := newOpencodeAdapter(0)
	adapter.base = server.URL
	if err := adapter.verifyDoc(); err != nil {
		t.Fatalf("verifyDoc: %v", err)
	}

	delete(paths, "/event")
	if err := adapter.verifyDoc(); err == nil {
		t.Fatal("verifyDoc should fail when a required path is missing")
	}
}

func TestOpencodeMultibyteStream(t *testing.T) {
	chinese := "你好，我是手信里运行的开源助手。"
	event := map[string]any{
		"type": "message.part.updated",
		"properties": map[string]any{
			"sessionID": "s1",
			"part":      map[string]any{"id": "p1", "type": "text", "text": chinese},
		},
	}
	encoded, _ := json.Marshal(event)
	block := append([]byte("data: "), encoded...)
	block = append(block, []byte("\n\n")...)

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		flusher := w.(http.Flusher)
		for index := 0; index < len(block); index += 3 {
			end := index + 3
			if end > len(block) {
				end = len(block)
			}
			_, _ = w.Write(block[index:end])
			flusher.Flush()
			time.Sleep(time.Millisecond)
		}
	}))
	defer server.Close()

	adapter := newOpencodeAdapter(0)
	adapter.base = server.URL
	queue := adapter.queueFor("s1")

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- adapter.consumeEventStream(ctx) }()

	select {
	case received := <-queue:
		properties := received["properties"].(map[string]any)
		part := properties["part"].(map[string]any)
		if part["text"] != chinese {
			t.Fatalf("text = %q", part["text"])
		}
	case <-time.After(10 * time.Second):
		t.Fatal("no event received")
	}
	<-done
}

func TestOpencodeRunNormalisation(t *testing.T) {
	prompted := make(chan struct{})
	var once sync.Once
	events := []map[string]any{
		{"type": "message.part.updated", "properties": map[string]any{
			"sessionID": "s1", "part": map[string]any{"id": "p1", "type": "text"},
		}},
		{"type": "message.part.delta", "properties": map[string]any{
			"sessionID": "s1", "partID": "p1", "field": "text", "delta": "你",
		}},
		{"type": "message.part.updated", "properties": map[string]any{
			"sessionID": "s1", "part": map[string]any{"id": "r1", "type": "reasoning"},
		}},
		{"type": "message.part.delta", "properties": map[string]any{
			"sessionID": "s1", "partID": "r1", "field": "text", "delta": "思考过程",
		}},
		{"type": "message.part.updated", "properties": map[string]any{
			"sessionID": "s1", "part": map[string]any{
				"id": "t1", "type": "tool", "tool": "bash",
				"state": map[string]any{"status": "running", "input": map[string]any{"cmd": "ls"}},
			},
		}},
		{"type": "message.part.updated", "properties": map[string]any{
			"sessionID": "s1", "part": map[string]any{
				"id": "t1", "type": "tool", "tool": "bash",
				"state": map[string]any{"status": "completed", "output": "file.txt"},
			},
		}},
		{"type": "message.part.updated", "properties": map[string]any{
			"sessionID": "s1", "part": map[string]any{
				"id": "f1", "type": "file", "filename": "a.png", "mime": "image/png",
				"size": 12, "url": "file:///a.png",
			},
		}},
		{"type": "session.idle", "properties": map[string]any{"sessionID": "s1"}},
	}

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && r.URL.Path == "/session":
			writeJSONResponse(w, map[string]any{"id": "s1"})
		case r.Method == http.MethodPost && r.URL.Path == "/session/s1/prompt_async":
			once.Do(func() { close(prompted) })
			w.WriteHeader(http.StatusAccepted)
		case r.Method == http.MethodGet && r.URL.Path == "/event":
			select {
			case <-prompted:
			case <-time.After(5 * time.Second):
				return
			}
			w.Header().Set("Content-Type", "text/event-stream")
			flusher := w.(http.Flusher)
			for _, event := range events {
				encoded, _ := json.Marshal(event)
				_, _ = io.WriteString(w, "data: "+string(encoded)+"\n\n")
				flusher.Flush()
				time.Sleep(2 * time.Millisecond)
			}
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	adapter := newOpencodeAdapter(0)
	adapter.base = server.URL
	adapter.sessions = map[string]string{}
	adapter.mapPath = filepath.Join(t.TempDir(), "sessions.json")

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = adapter.consumeEventStream(ctx) }()

	type emittedEvent struct {
		name string
		data map[string]any
	}
	var emitted []emittedEvent
	err := adapter.Run("c1", "hi", func(name string, data any) {
		object, _ := data.(map[string]any)
		emitted = append(emitted, emittedEvent{name: name, data: object})
	})
	if err != nil {
		t.Fatalf("run: %v", err)
	}

	names := make([]string, len(emitted))
	for index, event := range emitted {
		names[index] = event.name
	}
	want := "text,tool_call,tool_result,file,done"
	if strings.Join(names, ",") != want {
		t.Fatalf("events %v", names)
	}
	if emitted[0].data["delta"] != "你" {
		t.Fatalf("text delta %+v", emitted[0].data)
	}
	if emitted[1].data["name"] != "bash" {
		t.Fatalf("tool_call %+v", emitted[1].data)
	}
	if emitted[2].data["output"] != "file.txt" {
		t.Fatalf("tool_result %+v", emitted[2].data)
	}
	file := emitted[3].data
	if file["path"] != "a.png" || file["mime"] != "image/png" || file["size"].(float64) != 12 {
		t.Fatalf("file %+v", file)
	}
	if adapter.sessions["c1"] != "s1" {
		t.Fatalf("session map %+v", adapter.sessions)
	}
}

func TestOpencodeHistoryMapping(t *testing.T) {
	sessions := []map[string]any{
		{"id": "s1", "title": "hello", "time": map[string]any{"updated": 1763737777000.0}},
		{"id": "s2", "parentID": "s1", "title": "child", "time": map[string]any{"updated": 1763737000000.0}},
		{"id": "s3", "title": "other", "time": map[string]any{"created": 1763736000000.0}},
	}
	messages := []map[string]any{
		{
			"info":  map[string]any{"role": "user", "time": map[string]any{"created": 1763737770000.0}},
			"parts": []any{map[string]any{"type": "text", "text": "hi"}},
		},
		{
			"info": map[string]any{"role": "assistant", "time": map[string]any{"completed": 1763737772000.0}},
			"parts": []any{
				map[string]any{"type": "text", "text": "a"},
				map[string]any{"type": "text", "text": "b"},
			},
		},
	}

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/session":
			writeJSONResponse(w, sessions)
		case "/session/s1/message":
			writeJSONResponse(w, messages)
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	adapter := newOpencodeAdapter(0)
	adapter.base = server.URL
	adapter.sessions = map[string]string{"c1": "s1"}
	adapter.mapPath = filepath.Join(t.TempDir(), "sessions.json")

	conversations := adapter.ListConversations()
	if len(conversations) != 2 {
		t.Fatalf("conversations %+v", conversations)
	}
	first := conversations[0]
	if first.ConversationID != "c1" || first.Title != "hello" || first.UpdatedAt != 1763737777 {
		t.Fatalf("first %+v", first)
	}
	if conversations[1].ConversationID != "oc-s3" {
		t.Fatalf("second %+v", conversations[1])
	}

	history := adapter.ConversationMessages("c1", 0)
	if len(history) != 2 || history[0].Role != "user" || history[1].Text != "a\nb" {
		t.Fatalf("history %+v", history)
	}
	if limited := adapter.ConversationMessages("c1", 1); len(limited) != 1 || limited[0].Role != "assistant" {
		t.Fatalf("limited %+v", limited)
	}
}
