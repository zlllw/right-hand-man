package main

import (
	"strings"
	"testing"
	"time"
)

func TestStopEndsStreamWithoutWaitingForUpstream(t *testing.T) {
	adapter := newFakeAdapter()
	adapter.SetStepDelay(400 * time.Millisecond)
	server := startTestBridge(t, adapter)
	client := server.Client()

	requestID := postChat(t, client, server.URL, "long task", "cs")
	time.Sleep(100 * time.Millisecond)

	status, _ := postJSON(t, client, server.URL+"/abort", map[string]any{"requestId": requestID})
	if status != 200 {
		t.Fatalf("abort status %d", status)
	}

	started := time.Now()
	raw := readSSE(t, client, server.URL+"/chat/"+requestID+"/stream")
	if elapsed := time.Since(started); elapsed >= time.Second {
		t.Fatalf("stream took %v after abort", elapsed)
	}
	if !strings.Contains(raw, "event: done") {
		t.Fatalf("stream without done: %q", raw)
	}

	deadline := time.Now().Add(2 * time.Second)
	for !adapter.Aborted("cs") && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	if !adapter.Aborted("cs") {
		t.Fatal("adapter was not told to abort")
	}
}

func TestAbortUnknownRequest(t *testing.T) {
	server := startTestBridge(t, newFakeAdapter())
	status, _ := postJSON(t, server.Client(), server.URL+"/abort", map[string]any{"requestId": "nope"})
	if status != 404 {
		t.Fatalf("status %d", status)
	}
}
