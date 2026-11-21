package main

import (
	"bytes"
	"encoding/json"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func startFilesBridge(t *testing.T, workspace *Workspace) *httptest.Server {
	t.Helper()
	server := httptest.NewServer(newHandler(newBridge(newFakeAdapter(), workspace)))
	t.Cleanup(server.Close)
	return server
}

func uploadFile(t *testing.T, client *http.Client, base, filename string, data []byte) (int, map[string]any) {
	t.Helper()
	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	part, err := writer.CreateFormFile("file", filename)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := part.Write(data); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	request, err := http.NewRequest(http.MethodPost, base+"/files", &body)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Content-Type", writer.FormDataContentType())
	response, err := client.Do(request)
	if err != nil {
		t.Fatalf("upload: %v", err)
	}
	defer response.Body.Close()
	raw, _ := io.ReadAll(response.Body)
	var decoded map[string]any
	_ = json.Unmarshal(raw, &decoded)
	return response.StatusCode, decoded
}

func deleteURL(t *testing.T, client *http.Client, url string) int {
	t.Helper()
	request, err := http.NewRequest(http.MethodDelete, url, nil)
	if err != nil {
		t.Fatal(err)
	}
	response, err := client.Do(request)
	if err != nil {
		t.Fatalf("DELETE %s: %v", url, err)
	}
	response.Body.Close()
	return response.StatusCode
}

func TestUploadListDownloadRoundtrip(t *testing.T) {
	root := t.TempDir()
	workspace, err := newWorkspace(root, maxUploadBytes)
	if err != nil {
		t.Fatal(err)
	}
	server := startFilesBridge(t, workspace)
	client := server.Client()

	status, saved := uploadFile(t, client, server.URL, "hello.txt", []byte("bridge-content"))
	if status != 200 {
		t.Fatalf("upload status %d", status)
	}
	if saved["name"] != "hello.txt" || saved["size"].(float64) != float64(len("bridge-content")) {
		t.Fatalf("saved %+v", saved)
	}

	_, listing := getJSON(t, client, server.URL+"/files")
	object := listing.(map[string]any)
	files := object["files"].([]any)
	if len(files) != 1 || files[0].(map[string]any)["name"] != "hello.txt" {
		t.Fatalf("listing %+v", object)
	}

	response, err := client.Get(server.URL + "/files/hello.txt")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	content, _ := io.ReadAll(response.Body)
	if string(content) != "bridge-content" {
		t.Fatalf("content %q", content)
	}
}

func TestDuplicateNameIsRenamed(t *testing.T) {
	workspace, _ := newWorkspace(t.TempDir(), maxUploadBytes)
	server := startFilesBridge(t, workspace)
	client := server.Client()

	uploadFile(t, client, server.URL, "note.md", []byte("first"))
	_, second := uploadFile(t, client, server.URL, "note.md", []byte("second"))
	if second["name"] != "note (2).md" {
		t.Fatalf("second %+v", second)
	}
	content, _ := os.ReadFile(filepath.Join(workspace.root, "note.md"))
	if string(content) != "first" {
		t.Fatalf("original overwritten: %q", content)
	}
}

func TestFilenameWithPathIsSanitized(t *testing.T) {
	workspace, _ := newWorkspace(t.TempDir(), maxUploadBytes)
	server := startFilesBridge(t, workspace)
	client := server.Client()

	_, saved := uploadFile(t, client, server.URL, "../../etc/passwd", []byte("x"))
	if saved["name"] != "passwd" {
		t.Fatalf("saved %+v", saved)
	}
	if _, err := os.Stat(filepath.Join(workspace.root, "passwd")); err != nil {
		t.Fatal(err)
	}
}

func TestOversizeUploadIsRejectedWithoutTruncating(t *testing.T) {
	root := t.TempDir()
	workspace, _ := newWorkspace(filepath.Join(root, "small"), 16)
	server := startFilesBridge(t, workspace)
	client := server.Client()

	status, _ := uploadFile(t, client, server.URL, "big.bin", bytes.Repeat([]byte("x"), 64))
	if status != http.StatusRequestEntityTooLarge {
		t.Fatalf("status %d", status)
	}
	entries, _ := os.ReadDir(workspace.root)
	if len(entries) != 0 {
		t.Fatalf("workspace should be empty: %v", entries)
	}
}

func TestDownloadRejectsEscape(t *testing.T) {
	workspace, _ := newWorkspace(t.TempDir(), maxUploadBytes)
	server := startFilesBridge(t, workspace)
	client := server.Client()
	uploadFile(t, client, server.URL, "keep.txt", []byte("safe"))

	for _, bad := range []string{"../server.py", "%2e%2e%2fserver.py", "%2Fetc%2Fhosts"} {
		status, _ := getJSON(t, client, server.URL+"/files/"+bad)
		if status != 400 && status != 403 && status != 404 {
			t.Fatalf("%s status %d", bad, status)
		}
	}
}

func TestDeleteFile(t *testing.T) {
	workspace, _ := newWorkspace(t.TempDir(), maxUploadBytes)
	server := startFilesBridge(t, workspace)
	client := server.Client()
	uploadFile(t, client, server.URL, "gone.txt", []byte("bye"))

	if status := deleteURL(t, client, server.URL+"/files/gone.txt"); status != 200 {
		t.Fatalf("delete status %d", status)
	}
	_, listing := getJSON(t, client, server.URL+"/files")
	files := listing.(map[string]any)["files"].([]any)
	if len(files) != 0 {
		t.Fatalf("listing %+v", files)
	}
	if status := deleteURL(t, client, server.URL+"/files/gone.txt"); status != 404 {
		t.Fatalf("second delete status %d", status)
	}
}

func TestDeleteRejectsEscape(t *testing.T) {
	workspace, _ := newWorkspace(t.TempDir(), maxUploadBytes)
	server := startFilesBridge(t, workspace)
	client := server.Client()
	uploadFile(t, client, server.URL, "keep.txt", []byte("safe"))

	for _, bad := range []string{"../server.py", "%2e%2e%2fserver.py"} {
		status := deleteURL(t, client, server.URL+"/files/"+bad)
		if status != 403 && status != 404 {
			t.Fatalf("%s status %d", bad, status)
		}
	}
}

func TestDownloadMissingFileIs404(t *testing.T) {
	workspace, _ := newWorkspace(t.TempDir(), maxUploadBytes)
	server := startFilesBridge(t, workspace)
	status, _ := getJSON(t, server.Client(), server.URL+"/files/nope.txt")
	if status != 404 {
		t.Fatalf("status %d", status)
	}
}

func TestNonMultipartPostIs400(t *testing.T) {
	workspace, _ := newWorkspace(t.TempDir(), maxUploadBytes)
	server := startFilesBridge(t, workspace)
	client := server.Client()
	response, err := client.Post(server.URL+"/files", "application/octet-stream", strings.NewReader("raw"))
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != 400 {
		t.Fatalf("status %d", response.StatusCode)
	}
}
