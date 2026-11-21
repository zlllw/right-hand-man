package main

import (
	"encoding/json"
	"net/http"
	"strings"
	"testing"
	"time"
)

func chatAndDrain(t *testing.T, client *http.Client, base, message, conversationID string) {
	t.Helper()
	requestID := postChat(t, client, base, message, conversationID)
	raw := readSSE(t, client, base+"/chat/"+requestID+"/stream")
	if !strings.Contains(raw, "event: done") {
		t.Fatalf("stream did not finish: %q", raw)
	}
}

func requestStatus(t *testing.T, client *http.Client, method, url string, payload any) int {
	t.Helper()
	var body *strings.Reader
	if payload == nil {
		body = strings.NewReader("")
	} else {
		encoded, _ := json.Marshal(payload)
		body = strings.NewReader(string(encoded))
	}
	request, err := http.NewRequest(method, url, body)
	if err != nil {
		t.Fatal(err)
	}
	if payload != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	response, err := client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	return response.StatusCode
}

func TestConversationsAndMessagesShape(t *testing.T) {
	server := startTestBridge(t, newFakeAdapter())
	client := server.Client()

	chatAndDrain(t, client, server.URL, "first question", "c1")
	chatAndDrain(t, client, server.URL, "second question", "c1")
	chatAndDrain(t, client, server.URL, "other conversation", "c2")

	_, raw := getJSON(t, client, server.URL+"/conversations")
	conversations := raw.([]any)
	if len(conversations) != 2 {
		t.Fatalf("conversations %+v", conversations)
	}
	var title string
	for _, item := range conversations {
		object := item.(map[string]any)
		if object["conversationId"] == "c1" {
			title = object["title"].(string)
		}
	}
	if title != "first question" {
		t.Fatalf("title %q", title)
	}

	_, raw = getJSON(t, client, server.URL+"/conversations/c1/messages")
	messages := raw.([]any)
	want := [][2]string{
		{"user", "first question"},
		{"assistant", "echo: first question"},
		{"user", "second question"},
		{"assistant", "echo: second question"},
	}
	if len(messages) != len(want) {
		t.Fatalf("messages %+v", messages)
	}
	for index, pair := range want {
		object := messages[index].(map[string]any)
		if object["role"] != pair[0] || object["text"] != pair[1] {
			t.Fatalf("message %d = %+v", index, object)
		}
	}
}

func TestLimitReturnsLatest(t *testing.T) {
	server := startTestBridge(t, newFakeAdapter())
	client := server.Client()
	for index := 0; index < 5; index++ {
		chatAndDrain(t, client, server.URL, "m"+string(rune('0'+index)), "c3")
	}
	_, raw := getJSON(t, client, server.URL+"/conversations/c3/messages?limit=2")
	messages := raw.([]any)
	if len(messages) != 2 {
		t.Fatalf("messages %+v", messages)
	}
	last := messages[len(messages)-1].(map[string]any)
	if last["text"] != "echo: m4" {
		t.Fatalf("last %+v", last)
	}
}

func TestUnknownConversationIsEmpty(t *testing.T) {
	server := startTestBridge(t, newFakeAdapter())
	_, raw := getJSON(t, server.Client(), server.URL+"/conversations/nope/messages")
	if messages, ok := raw.([]any); !ok || len(messages) != 0 {
		t.Fatalf("messages %+v", raw)
	}
}

func TestConversationOrdering(t *testing.T) {
	server := startTestBridge(t, newFakeAdapter())
	client := server.Client()
	chatAndDrain(t, client, server.URL, "old", "cold")
	time.Sleep(10 * time.Millisecond)
	chatAndDrain(t, client, server.URL, "new", "cnew")

	_, raw := getJSON(t, client, server.URL+"/conversations")
	conversations := raw.([]any)
	if len(conversations) != 2 {
		t.Fatalf("conversations %+v", conversations)
	}
	if conversations[0].(map[string]any)["conversationId"] != "cnew" {
		t.Fatalf("order %+v", conversations)
	}
}

func TestRenameConversation(t *testing.T) {
	server := startTestBridge(t, newFakeAdapter())
	client := server.Client()
	chatAndDrain(t, client, server.URL, "hello", "cr")

	if status := requestStatus(t, client, http.MethodPatch, server.URL+"/conversations/cr", map[string]any{"title": "改过的标题"}); status != 200 {
		t.Fatalf("status %d", status)
	}
	_, raw := getJSON(t, client, server.URL+"/conversations")
	for _, item := range raw.([]any) {
		object := item.(map[string]any)
		if object["conversationId"] == "cr" && object["title"] != "改过的标题" {
			t.Fatalf("title %+v", object)
		}
	}
}

func TestDeleteConversation(t *testing.T) {
	server := startTestBridge(t, newFakeAdapter())
	client := server.Client()
	chatAndDrain(t, client, server.URL, "hello", "cd")
	chatAndDrain(t, client, server.URL, "keep", "ck")

	if status := requestStatus(t, client, http.MethodDelete, server.URL+"/conversations/cd", nil); status != 200 {
		t.Fatalf("status %d", status)
	}
	_, raw := getJSON(t, client, server.URL+"/conversations")
	for _, item := range raw.([]any) {
		if item.(map[string]any)["conversationId"] == "cd" {
			t.Fatal("cd still listed")
		}
	}
	_, messages := getJSON(t, client, server.URL+"/conversations/cd/messages")
	if list, ok := messages.([]any); !ok || len(list) != 0 {
		t.Fatalf("messages %+v", messages)
	}
}

func TestRenameAndDeleteUnknown(t *testing.T) {
	server := startTestBridge(t, newFakeAdapter())
	client := server.Client()
	if status := requestStatus(t, client, http.MethodPatch, server.URL+"/conversations/none", map[string]any{"title": "x"}); status != 404 {
		t.Fatalf("rename status %d", status)
	}
	if status := requestStatus(t, client, http.MethodDelete, server.URL+"/conversations/none", nil); status != 404 {
		t.Fatalf("delete status %d", status)
	}
}
