package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"
)

func startTestBridge(t *testing.T, adapter Adapter) *httptest.Server {
	t.Helper()
	server := httptest.NewServer(newHandler(newBridge(adapter, nil)))
	t.Cleanup(server.Close)
	return server
}

func postJSON(t *testing.T, client *http.Client, url string, payload any) (int, map[string]any) {
	t.Helper()
	body, _ := json.Marshal(payload)
	response, err := client.Post(url, "application/json", strings.NewReader(string(body)))
	if err != nil {
		t.Fatalf("POST %s: %v", url, err)
	}
	defer response.Body.Close()
	raw, _ := io.ReadAll(response.Body)
	var decoded map[string]any
	_ = json.Unmarshal(raw, &decoded)
	return response.StatusCode, decoded
}

func getJSON(t *testing.T, client *http.Client, url string) (int, any) {
	t.Helper()
	response, err := client.Get(url)
	if err != nil {
		t.Fatalf("GET %s: %v", url, err)
	}
	defer response.Body.Close()
	raw, _ := io.ReadAll(response.Body)
	var decoded any
	_ = json.Unmarshal(raw, &decoded)
	return response.StatusCode, decoded
}

func postChat(t *testing.T, client *http.Client, base, message, conversationID string) string {
	t.Helper()
	status, body := postJSON(t, client, base+"/chat", map[string]any{
		"message": message, "conversationId": conversationID,
	})
	if status != 200 {
		t.Fatalf("POST /chat status %d", status)
	}
	requestID, _ := body["requestId"].(string)
	if requestID == "" {
		t.Fatalf("missing requestId: %+v", body)
	}
	return requestID
}

func readSSE(t *testing.T, client *http.Client, url string) string {
	t.Helper()
	response, err := client.Get(url)
	if err != nil {
		t.Fatalf("GET %s: %v", url, err)
	}
	defer response.Body.Close()
	raw, _ := io.ReadAll(response.Body)
	return string(raw)
}

type parsedEvent struct {
	id    int
	name  string
	data  map[string]any
	hasID bool
}

func parseSSE(t *testing.T, raw string) []parsedEvent {
	t.Helper()
	var events []parsedEvent
	for _, block := range strings.Split(raw, "\n\n") {
		if strings.TrimSpace(block) == "" {
			continue
		}
		event := parsedEvent{id: -1}
		for _, line := range strings.Split(block, "\n") {
			switch {
			case strings.HasPrefix(line, "id: "):
				parsed, err := strconv.Atoi(line[4:])
				if err != nil {
					t.Fatalf("bad id line %q", line)
				}
				event.id = parsed
				event.hasID = true
			case strings.HasPrefix(line, "event: "):
				event.name = line[7:]
			case strings.HasPrefix(line, "data: "):
				if err := json.Unmarshal([]byte(line[6:]), &event.data); err != nil {
					t.Fatalf("bad data line %q: %v", line, err)
				}
			}
		}
		events = append(events, event)
	}
	return events
}

func eventNames(events []parsedEvent) []string {
	names := make([]string, len(events))
	for index, event := range events {
		names[index] = event.name
	}
	return names
}

func eventIDs(events []parsedEvent) []int {
	ids := make([]int, len(events))
	for index, event := range events {
		ids[index] = event.id
	}
	return ids
}

func TestHealth(t *testing.T) {
	server := startTestBridge(t, newFakeAdapter())
	client := server.Client()
	status, body := getJSON(t, client, server.URL+"/health")
	if status != 200 {
		t.Fatalf("status %d", status)
	}
	object := body.(map[string]any)
	if object["status"] != "ok" {
		t.Fatalf("body %+v", object)
	}
}

func TestChatThenStream(t *testing.T) {
	server := startTestBridge(t, newFakeAdapter())
	client := server.Client()
	requestID := postChat(t, client, server.URL, "hello", "c1")

	events := parseSSE(t, readSSE(t, client, server.URL+"/chat/"+requestID+"/stream"))
	if got := eventNames(events); strings.Join(got, ",") != "text,text,done" {
		t.Fatalf("events %v", got)
	}
	if got := eventIDs(events); got[0] != 1 || got[1] != 2 || got[2] != 3 {
		t.Fatalf("ids %v", got)
	}
	joined := events[0].data["delta"].(string) + events[1].data["delta"].(string)
	if !strings.Contains(joined, "hello") {
		t.Fatalf("joined %q", joined)
	}
	if events[2].data["finishReason"] != "stop" {
		t.Fatalf("done data %+v", events[2].data)
	}
}

func TestReconnectFromDoesNotReplay(t *testing.T) {
	server := startTestBridge(t, newFakeAdapter())
	client := server.Client()
	requestID := postChat(t, client, server.URL, "hello", "c2")
	readSSE(t, client, server.URL+"/chat/"+requestID+"/stream")

	events := parseSSE(t, readSSE(t, client, server.URL+"/chat/"+requestID+"/stream?from=2"))
	if len(events) != 1 || events[0].id != 3 || events[0].name != "done" {
		t.Fatalf("events %+v", events)
	}
}

func TestStreamFromZeroReturnsFullSequence(t *testing.T) {
	server := startTestBridge(t, newFakeAdapter())
	client := server.Client()
	requestID := postChat(t, client, server.URL, "hello", "c3")
	readSSE(t, client, server.URL+"/chat/"+requestID+"/stream")

	events := parseSSE(t, readSSE(t, client, server.URL+"/chat/"+requestID+"/stream?from=0"))
	if got := eventIDs(events); len(got) != 3 || got[0] != 1 || got[2] != 3 {
		t.Fatalf("ids %v", got)
	}
}

func TestUnknownRequestIs404(t *testing.T) {
	server := startTestBridge(t, newFakeAdapter())
	status, _ := getJSON(t, server.Client(), server.URL+"/chat/nope/stream")
	if status != 404 {
		t.Fatalf("status %d", status)
	}
}

func TestInvalidFromIs400(t *testing.T) {
	server := startTestBridge(t, newFakeAdapter())
	client := server.Client()
	requestID := postChat(t, client, server.URL, "hello", "c4")
	status, _ := getJSON(t, client, server.URL+"/chat/"+requestID+"/stream?from=nope")
	if status != 400 {
		t.Fatalf("status %d", status)
	}
}

func TestAdapterErrorBecomesErrorThenDone(t *testing.T) {
	adapter := newFakeAdapter()
	adapter.failWith = "adapter exploded"
	server := startTestBridge(t, adapter)
	client := server.Client()
	requestID := postChat(t, client, server.URL, "boom", "c-err")

	events := parseSSE(t, readSSE(t, client, server.URL+"/chat/"+requestID+"/stream"))
	if strings.Join(eventNames(events), ",") != "error,done" {
		t.Fatalf("events %v", eventNames(events))
	}
	if !strings.Contains(events[0].data["message"].(string), "adapter exploded") {
		t.Fatalf("error %+v", events[0].data)
	}
}

func TestSameConversationIsSerialised(t *testing.T) {
	adapter := newFakeAdapter()
	adapter.SetStepDelay(50 * time.Millisecond)
	server := startTestBridge(t, adapter)
	client := server.Client()

	first := postChat(t, client, server.URL, "one", "same")
	second := postChat(t, client, server.URL, "two", "same")
	readSSE(t, client, server.URL+"/chat/"+first+"/stream")
	readSSE(t, client, server.URL+"/chat/"+second+"/stream")

	if adapter.Overlaps("same") {
		t.Fatal("same conversation must be serialised")
	}
	spans := adapter.Spans("same")
	if len(spans) != 2 || spans[0].Message != "one" || spans[1].Message != "two" {
		t.Fatalf("spans %+v", spans)
	}
}

func TestDifferentConversationsRunConcurrently(t *testing.T) {
	adapter := newFakeAdapter()
	adapter.SetStepDelay(50 * time.Millisecond)
	server := startTestBridge(t, adapter)
	client := server.Client()

	first := postChat(t, client, server.URL, "one", "conv-a")
	second := postChat(t, client, server.URL, "two", "conv-b")
	readSSE(t, client, server.URL+"/chat/"+first+"/stream")
	readSSE(t, client, server.URL+"/chat/"+second+"/stream")

	calls := adapter.Calls()
	if len(calls) != 2 {
		t.Fatalf("calls %d", len(calls))
	}
	if len(calls) == 2 && !calls[0].Start.Before(calls[1].End) && !calls[1].Start.Before(calls[0].End) {
		t.Fatal("different conversations should overlap")
	}
}
