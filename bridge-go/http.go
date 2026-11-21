package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"strconv"
	"strings"
)

type statusWriter struct {
	http.ResponseWriter
	status int
	wrote  bool
}

func (w *statusWriter) WriteHeader(status int) {
	if !w.wrote {
		w.status = status
		w.wrote = true
	}
	w.ResponseWriter.WriteHeader(status)
}

func (w *statusWriter) Write(data []byte) (int, error) {
	if !w.wrote {
		w.status = http.StatusOK
		w.wrote = true
	}
	return w.ResponseWriter.Write(data)
}

func (w *statusWriter) Flush() {
	if flusher, ok := w.ResponseWriter.(http.Flusher); ok {
		flusher.Flush()
	}
}

type bridgeHandler struct {
	bridge *Bridge
}

func newHandler(bridge *Bridge) http.Handler {
	return &bridgeHandler{bridge: bridge}
}

func (h *bridgeHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	writer := &statusWriter{ResponseWriter: w, status: http.StatusOK}
	path := r.URL.Path
	if path == "" {
		path = "/"
	}
	defer func() {
		fmt.Fprintf(os.Stderr, "[bridge] %s %s %d\n", r.Method, path, writer.status)
	}()
	h.route(writer, r, path)
}

func (h *bridgeHandler) route(w http.ResponseWriter, r *http.Request, path string) {
	switch r.Method {
	case http.MethodGet:
		h.get(w, r, path)
	case http.MethodPost:
		h.post(w, r, path)
	case http.MethodPatch:
		h.patch(w, r, path)
	case http.MethodDelete:
		h.delete(w, r, path)
	default:
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "not_found", "path": path})
	}
}

func (h *bridgeHandler) get(w http.ResponseWriter, r *http.Request, path string) {
	if path == "/health" {
		writeJSON(w, http.StatusOK, map[string]any{"status": "ok"})
		return
	}
	if strings.HasPrefix(path, "/chat/") && strings.HasSuffix(path, "/stream") {
		requestID := strings.TrimSuffix(strings.TrimPrefix(path, "/chat/"), "/stream")
		afterID := 0
		if raw := r.URL.Query().Get("from"); raw != "" {
			parsed, err := strconv.Atoi(strings.TrimSpace(raw))
			if err != nil {
				writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid_from"})
				return
			}
			afterID = parsed
		}
		session := h.bridge.Session(requestID)
		if session == nil {
			writeJSON(w, http.StatusNotFound, map[string]any{"error": "unknown_request", "requestId": requestID})
			return
		}
		h.stream(w, session, afterID)
		return
	}
	if path == "/conversations" {
		list := h.bridge.adapter.ListConversations()
		if list == nil {
			list = []Conversation{}
		}
		writeJSON(w, http.StatusOK, list)
		return
	}
	if strings.HasPrefix(path, "/conversations/") && strings.HasSuffix(path, "/messages") {
		conversationID := strings.TrimSuffix(strings.TrimPrefix(path, "/conversations/"), "/messages")
		limit := 50
		if raw := r.URL.Query().Get("limit"); raw != "" {
			parsed, err := strconv.Atoi(strings.TrimSpace(raw))
			if err != nil {
				writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid_limit"})
				return
			}
			limit = parsed
			if limit < 1 {
				limit = 1
			}
			if limit > 500 {
				limit = 500
			}
		}
		messages := h.bridge.adapter.ConversationMessages(conversationID, limit)
		if messages == nil {
			messages = []HistoryMessage{}
		}
		writeJSON(w, http.StatusOK, messages)
		return
	}
	if path == "/files" {
		workspace := h.bridge.workspace
		if workspace == nil {
			writeJSON(w, http.StatusNotImplemented, map[string]any{"error": "no_workspace"})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"root":  workspace.DisplayRoot(),
			"files": workspace.ListFiles(),
		})
		return
	}
	if strings.HasPrefix(path, "/files/") {
		workspace := h.bridge.workspace
		if workspace == nil {
			writeJSON(w, http.StatusNotImplemented, map[string]any{"error": "no_workspace"})
			return
		}
		name := strings.TrimPrefix(path, "/files/")
		target, err := workspace.ResolveDownload(name)
		if err != nil {
			writeJSON(w, http.StatusForbidden, map[string]any{"error": "unsafe_path"})
			return
		}
		info, err := os.Stat(target)
		if err != nil || !info.Mode().IsRegular() {
			writeJSON(w, http.StatusNotFound, map[string]any{"error": "not_found", "name": name})
			return
		}
		handle, err := os.Open(target)
		if err != nil {
			writeJSON(w, http.StatusNotFound, map[string]any{"error": "not_found", "name": name})
			return
		}
		defer handle.Close()
		w.Header().Set("Content-Type", guessMime(name))
		w.Header().Set("Content-Length", strconv.FormatInt(info.Size(), 10))
		w.WriteHeader(http.StatusOK)
		_, _ = io.Copy(w, handle)
		return
	}
	writeJSON(w, http.StatusNotFound, map[string]any{"error": "not_found", "path": path})
}

func (h *bridgeHandler) stream(w http.ResponseWriter, session *RequestSession, afterID int) {
	w.Header().Set("Content-Type", "text/event-stream; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "close")
	w.WriteHeader(http.StatusOK)
	flusher, canFlush := w.(http.Flusher)
	if canFlush {
		flusher.Flush()
	}
	_ = h.bridge.Stream(session, afterID, func(block string) error {
		if _, err := io.WriteString(w, block); err != nil {
			return err
		}
		if canFlush {
			flusher.Flush()
		}
		return nil
	})
}

func (h *bridgeHandler) post(w http.ResponseWriter, r *http.Request, path string) {
	switch path {
	case "/chat":
		body, ok := readJSONMap(r)
		if !ok {
			writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid_json"})
			return
		}
		message, okMessage := body["message"].(string)
		conversationID, okConversation := body["conversationId"].(string)
		if !okMessage || !okConversation {
			writeJSON(w, http.StatusBadRequest, map[string]any{"error": "missing_fields"})
			return
		}
		requestID := h.bridge.Submit(conversationID, message)
		writeJSON(w, http.StatusOK, map[string]any{"requestId": requestID})
	case "/abort":
		body, ok := readJSONMap(r)
		if !ok {
			writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid_json"})
			return
		}
		requestID, ok := body["requestId"].(string)
		if !ok {
			writeJSON(w, http.StatusBadRequest, map[string]any{"error": "missing_fields"})
			return
		}
		if !h.bridge.Stop(requestID) {
			writeJSON(w, http.StatusNotFound, map[string]any{"error": "unknown_request", "requestId": requestID})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	case "/files":
		h.upload(w, r)
	default:
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "not_found", "path": path})
	}
}

func (h *bridgeHandler) upload(w http.ResponseWriter, r *http.Request) {
	workspace := h.bridge.workspace
	if workspace == nil {
		writeJSON(w, http.StatusNotImplemented, map[string]any{"error": "no_workspace"})
		return
	}
	if !strings.Contains(r.Header.Get("Content-Type"), "multipart/form-data") {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "multipart_required"})
		return
	}
	declared := r.ContentLength
	if declared <= 0 || declared > workspace.MaxBytes() {
		_, _ = io.Copy(io.Discard, r.Body)
		writeJSON(w, http.StatusRequestEntityTooLarge, map[string]any{
			"error": "file_too_large", "maxBytes": workspace.MaxBytes(),
		})
		return
	}

	reader, err := r.MultipartReader()
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "bad_upload", "detail": err.Error()})
		return
	}
	filename := ""
	var data []byte
	found := false
	for {
		part, err := reader.NextPart()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]any{"error": "bad_upload", "detail": err.Error()})
			return
		}
		name := part.FileName()
		if name == "" {
			part.Close()
			continue
		}
		payload, readErr := io.ReadAll(io.LimitReader(part, workspace.MaxBytes()+1))
		part.Close()
		if readErr != nil {
			writeJSON(w, http.StatusBadRequest, map[string]any{"error": "bad_upload", "detail": readErr.Error()})
			return
		}
		if int64(len(payload)) > workspace.MaxBytes() {
			writeJSON(w, http.StatusRequestEntityTooLarge, map[string]any{
				"error": "file_too_large", "maxBytes": workspace.MaxBytes(),
			})
			return
		}
		filename, data, found = name, payload, true
		break
	}
	if !found {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "bad_upload", "detail": errNoFilePart.Error()})
		return
	}
	saved, err := workspace.SaveUpload(filename, data)
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "bad_upload", "detail": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"path": saved["displayPath"],
		"name": saved["name"],
		"size": saved["size"],
	})
}

func (h *bridgeHandler) patch(w http.ResponseWriter, r *http.Request, path string) {
	if strings.HasPrefix(path, "/conversations/") {
		conversationID := strings.TrimPrefix(path, "/conversations/")
		if conversationID == "" || strings.Contains(conversationID, "/") {
			writeJSON(w, http.StatusNotFound, map[string]any{"error": "not_found", "path": path})
			return
		}
		body, ok := readJSONMap(r)
		if !ok {
			writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid_json"})
			return
		}
		title, ok := body["title"].(string)
		title = strings.TrimSpace(title)
		if !ok || title == "" {
			writeJSON(w, http.StatusBadRequest, map[string]any{"error": "missing_title"})
			return
		}
		if !h.bridge.adapter.RenameConversation(conversationID, title) {
			writeJSON(w, http.StatusNotFound, map[string]any{"error": "unknown_conversation"})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
		return
	}
	writeJSON(w, http.StatusNotFound, map[string]any{"error": "not_found", "path": path})
}

func (h *bridgeHandler) delete(w http.ResponseWriter, r *http.Request, path string) {
	if strings.HasPrefix(path, "/files/") {
		workspace := h.bridge.workspace
		if workspace == nil {
			writeJSON(w, http.StatusNotImplemented, map[string]any{"error": "no_workspace"})
			return
		}
		name := strings.TrimPrefix(path, "/files/")
		removed, err := workspace.Delete(name)
		if errors.Is(err, errUnsafePath) {
			writeJSON(w, http.StatusForbidden, map[string]any{"error": "unsafe_path"})
			return
		}
		if !removed {
			writeJSON(w, http.StatusNotFound, map[string]any{"error": "not_found", "name": name})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
		return
	}
	if strings.HasPrefix(path, "/conversations/") {
		conversationID := strings.TrimPrefix(path, "/conversations/")
		if conversationID == "" || strings.Contains(conversationID, "/") {
			writeJSON(w, http.StatusNotFound, map[string]any{"error": "not_found", "path": path})
			return
		}
		if !h.bridge.adapter.DeleteConversation(conversationID) {
			writeJSON(w, http.StatusNotFound, map[string]any{"error": "unknown_conversation"})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
		return
	}
	writeJSON(w, http.StatusNotFound, map[string]any{"error": "not_found", "path": path})
}

func readJSONMap(r *http.Request) (map[string]any, bool) {
	body, err := io.ReadAll(io.LimitReader(r.Body, 4<<20))
	if err != nil || len(body) == 0 {
		return nil, false
	}
	var value any
	if err := json.Unmarshal(body, &value); err != nil {
		return nil, false
	}
	object, ok := value.(map[string]any)
	return object, ok
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	body := compactJSON(payload)
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Content-Length", strconv.Itoa(len(body)))
	w.WriteHeader(status)
	_, _ = io.WriteString(w, body)
}
