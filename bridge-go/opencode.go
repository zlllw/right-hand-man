package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

const (
	healthTimeout = 120 * time.Second
	promptTimeout = 600 * time.Second
)

// Paths this adapter depends on, verified against opencode's runtime /doc.
var requiredPaths = []string{
	"/session",
	"/session/{sessionID}/message",
	"/session/{sessionID}/prompt_async",
	"/session/{sessionID}/abort",
	"/event",
	"/global/health",
}

type lockedBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *lockedBuffer) Write(data []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(data)
}

func (b *lockedBuffer) Tail(limit int) string {
	b.mu.Lock()
	defer b.mu.Unlock()
	text := b.buf.String()
	if len(text) > limit {
		text = text[len(text)-limit:]
	}
	return text
}

// OpencodeAdapter spawns `opencode serve`, subscribes to /event, and
// normalizes its parts into the Bridge's event vocabulary.
type OpencodeAdapter struct {
	port       int
	executable string
	hostname   string
	workdir    string
	base       string
	client     *http.Client

	mu          sync.Mutex
	cmd         *exec.Cmd
	done        chan struct{}
	eventCancel context.CancelFunc
	sessions    map[string]string
	queues      map[string]chan map[string]any

	running atomic.Bool
	stderr  lockedBuffer
	mapPath string
}

func newOpencodeAdapter(port int) *OpencodeAdapter {
	home, _ := os.UserHomeDir()
	adapter := &OpencodeAdapter{
		port:       port,
		executable: "opencode",
		hostname:   "127.0.0.1",
		workdir:    filepath.Join(home, "rhmdir"),
		base:       fmt.Sprintf("http://127.0.0.1:%d", port),
		client:     &http.Client{},
		sessions:   map[string]string{},
		queues:     map[string]chan map[string]any{},
		mapPath:    filepath.Join(home, ".bridge", "sessions.json"),
	}
	adapter.loadSessionMap()
	return adapter
}

func (a *OpencodeAdapter) Name() string { return "opencode" }

func (a *OpencodeAdapter) Start() error {
	env := make([]string, 0, len(os.Environ())+2)
	hasTermuxAutoUpdate := false
	hasDisableAutoUpdate := false
	for _, pair := range os.Environ() {
		if strings.HasPrefix(pair, "LD_PRELOAD=") {
			continue
		}
		if strings.HasPrefix(pair, "OPENCODE_TERMUX_AUTOUPDATE=") {
			hasTermuxAutoUpdate = true
		}
		if strings.HasPrefix(pair, "OPENCODE_DISABLE_AUTOUPDATE=") {
			hasDisableAutoUpdate = true
		}
		env = append(env, pair)
	}
	if !hasTermuxAutoUpdate {
		env = append(env, "OPENCODE_TERMUX_AUTOUPDATE=0")
	}
	if !hasDisableAutoUpdate {
		env = append(env, "OPENCODE_DISABLE_AUTOUPDATE=1")
	}
	if err := os.MkdirAll(a.workdir, 0o755); err != nil {
		return err
	}

	cmd := exec.Command(a.executable, "serve", "--hostname", a.hostname, "--port", strconv.Itoa(a.port))
	cmd.Dir = a.workdir
	cmd.Env = env
	cmd.Stdout = io.Discard
	cmd.Stderr = &a.stderr
	if err := cmd.Start(); err != nil {
		return err
	}

	done := make(chan struct{})
	a.mu.Lock()
	a.cmd = cmd
	a.done = done
	a.mu.Unlock()
	a.running.Store(true)
	go func() {
		_ = cmd.Wait()
		a.running.Store(false)
		close(done)
	}()

	deadline := time.Now().Add(healthTimeout)
	ready := false
	for time.Now().Before(deadline) {
		select {
		case <-done:
			return fmt.Errorf("opencode serve 退出（code=%d）：%s",
				cmd.ProcessState.ExitCode(), a.stderr.Tail(2000))
		default:
		}
		var health map[string]any
		if err := a.getJSON("/global/health", 3*time.Second, &health); err == nil {
			if healthy, _ := health["healthy"].(bool); healthy {
				ready = true
				break
			}
		}
		time.Sleep(time.Second)
	}
	if !ready {
		a.Stop()
		return fmt.Errorf("opencode /global/health 在 %d 秒内未就绪", int(healthTimeout/time.Second))
	}
	if err := a.verifyDoc(); err != nil {
		a.Stop()
		return err
	}

	ctx, cancel := context.WithCancel(context.Background())
	a.mu.Lock()
	a.eventCancel = cancel
	a.mu.Unlock()
	go a.eventLoop(ctx)
	return nil
}

func (a *OpencodeAdapter) Stop() {
	a.mu.Lock()
	cmd := a.cmd
	done := a.done
	cancel := a.eventCancel
	a.cmd = nil
	a.done = nil
	a.eventCancel = nil
	a.mu.Unlock()

	if cancel != nil {
		cancel()
	}
	if cmd == nil || cmd.Process == nil {
		return
	}
	if err := cmd.Process.Signal(syscall.SIGTERM); err != nil {
		_ = cmd.Process.Kill()
	}
	if done != nil {
		select {
		case <-done:
			return
		case <-time.After(5 * time.Second):
		}
	}
	_ = cmd.Process.Kill()
	if done != nil {
		select {
		case <-done:
		case <-time.After(5 * time.Second):
		}
	}
}

func (a *OpencodeAdapter) Health() bool {
	var health map[string]any
	if err := a.getJSON("/global/health", 3*time.Second, &health); err != nil {
		return false
	}
	healthy, _ := health["healthy"].(bool)
	return healthy
}

func (a *OpencodeAdapter) verifyDoc() error {
	var spec map[string]any
	if err := a.getJSON("/doc", 10*time.Second, &spec); err != nil {
		return err
	}
	paths, ok := spec["paths"].(map[string]any)
	if !ok {
		return errors.New("/doc 不是 OpenAPI spec")
	}
	var missing []string
	for _, path := range requiredPaths {
		if _, exists := paths[path]; !exists {
			missing = append(missing, path)
		}
	}
	if len(missing) > 0 {
		sample := make([]string, 0, len(paths))
		for path := range paths {
			sample = append(sample, path)
		}
		sort.Strings(sample)
		if len(sample) > 20 {
			sample = sample[:20]
		}
		return fmt.Errorf("opencode 接口与映射表不符，缺少 %v；实际 paths 样例：%v", missing, sample)
	}
	return nil
}

func (a *OpencodeAdapter) doJSON(method, path string, payload any, timeout time.Duration) (int, []byte, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	var body io.Reader
	if payload != nil {
		encoded, err := json.Marshal(payload)
		if err != nil {
			return 0, nil, err
		}
		body = bytes.NewReader(encoded)
	}
	request, err := http.NewRequestWithContext(ctx, method, a.base+path, body)
	if err != nil {
		return 0, nil, err
	}
	if payload != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	response, err := a.client.Do(request)
	if err != nil {
		return 0, nil, err
	}
	defer response.Body.Close()
	data, err := io.ReadAll(response.Body)
	return response.StatusCode, data, err
}

func (a *OpencodeAdapter) getJSON(path string, timeout time.Duration, out any) error {
	status, body, err := a.doJSON(http.MethodGet, path, nil, timeout)
	if err != nil {
		return err
	}
	if status < 200 || status >= 300 {
		return fmt.Errorf("GET %s HTTP %d", path, status)
	}
	if err := json.Unmarshal(body, out); err != nil {
		return err
	}
	return nil
}

func (a *OpencodeAdapter) statusCode(method, path string, payload any) int {
	status, _, err := a.doJSON(method, path, payload, 30*time.Second)
	if err != nil {
		return 0
	}
	return status
}

// ---- session mapping ----

func (a *OpencodeAdapter) loadSessionMap() {
	data, err := os.ReadFile(a.mapPath)
	if err != nil {
		return
	}
	var parsed map[string]string
	if err := json.Unmarshal(data, &parsed); err != nil {
		return
	}
	a.mu.Lock()
	a.sessions = parsed
	a.mu.Unlock()
}

func (a *OpencodeAdapter) saveSessionMap() {
	a.mu.Lock()
	snapshot := make(map[string]string, len(a.sessions))
	for key, value := range a.sessions {
		snapshot[key] = value
	}
	a.mu.Unlock()

	data, err := json.Marshal(snapshot)
	if err != nil {
		return
	}
	if err := os.MkdirAll(filepath.Dir(a.mapPath), 0o755); err != nil {
		return
	}
	_ = os.WriteFile(a.mapPath, data, 0o600)
}

func (a *OpencodeAdapter) sessionFor(conversationID string) (string, error) {
	a.mu.Lock()
	sessionID := a.sessions[conversationID]
	a.mu.Unlock()
	if sessionID != "" {
		return sessionID, nil
	}

	status, body, err := a.doJSON(http.MethodPost, "/session", map[string]any{}, 30*time.Second)
	if err != nil {
		return "", err
	}
	if status < 200 || status >= 300 {
		return "", fmt.Errorf("POST /session HTTP %d", status)
	}
	var created map[string]any
	if err := json.Unmarshal(body, &created); err != nil {
		return "", err
	}
	sessionID = stringValue(created["id"])
	if sessionID == "" {
		return "", fmt.Errorf("POST /session 未返回 id：%s", string(body))
	}
	a.mu.Lock()
	a.sessions[conversationID] = sessionID
	a.mu.Unlock()
	a.saveSessionMap()
	return sessionID, nil
}

func (a *OpencodeAdapter) knownSession(conversationID string) string {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.sessions[conversationID]
}

func (a *OpencodeAdapter) RenameConversation(conversationID, title string) bool {
	sessionID := a.knownSession(conversationID)
	if sessionID == "" {
		return false
	}
	status := a.statusCode(http.MethodPatch, "/session/"+sessionID, map[string]any{"title": title})
	return status == 200 || status == 204
}

func (a *OpencodeAdapter) Abort(conversationID string) bool {
	sessionID := a.knownSession(conversationID)
	if sessionID == "" {
		return false
	}
	status := a.statusCode(http.MethodPost, "/session/"+sessionID+"/abort", map[string]any{})
	return status == 200 || status == 204
}

func (a *OpencodeAdapter) DeleteConversation(conversationID string) bool {
	sessionID := a.knownSession(conversationID)
	if sessionID == "" {
		return false
	}
	status := a.statusCode(http.MethodDelete, "/session/"+sessionID, nil)
	if status != 200 && status != 204 && status != 404 {
		return false
	}
	a.mu.Lock()
	delete(a.sessions, conversationID)
	a.mu.Unlock()
	a.saveSessionMap()
	return true
}

// ---- one generation ----

func (a *OpencodeAdapter) Run(conversationID, message string, emit EmitFunc) error {
	sessionID, err := a.sessionFor(conversationID)
	if err != nil {
		return err
	}
	events := a.queueFor(sessionID)
	for {
		select {
		case <-events:
		default:
			goto drained
		}
	}
drained:

	status, _, err := a.doJSON(
		http.MethodPost,
		"/session/"+sessionID+"/prompt_async",
		map[string]any{"parts": []any{map[string]any{"type": "text", "text": message}}},
		30*time.Second,
	)
	if err != nil {
		return err
	}
	if status != 200 && status != 202 && status != 204 {
		emit("error", map[string]any{"message": fmt.Sprintf("prompt_async HTTP %d", status)})
		emit("done", doneStop())
		return nil
	}

	deadline := time.Now().Add(promptTimeout)
	partTypes := map[string]string{}
	toolsSeen := map[string]bool{}
	timeout := time.NewTimer(500 * time.Millisecond)
	defer timeout.Stop()

	for time.Now().Before(deadline) {
		select {
		case event := <-events:
			properties, _ := event["properties"].(map[string]any)
			if stringValue(properties["sessionID"]) != sessionID {
				continue
			}
			switch stringValue(event["type"]) {
			case "message.part.updated":
				part, _ := properties["part"].(map[string]any)
				if partID := stringValue(part["id"]); partID != "" {
					partTypes[partID] = stringValue(part["type"])
				}
				a.normalizePart(part, toolsSeen, emit)
			case "message.part.delta":
				if stringValue(properties["field"]) == "text" &&
					partTypes[stringValue(properties["partID"])] == "text" {
					if delta, ok := properties["delta"].(string); ok && delta != "" {
						emit("text", map[string]any{"delta": delta})
					}
				}
			case "session.idle":
				emit("done", doneStop())
				return nil
			case "session.error", "session.deleted":
				value := properties["error"]
				if value == nil {
					value = properties["message"]
				}
				if value == nil {
					value = event["type"]
				}
				emit("error", map[string]any{"message": fmt.Sprintf("%v", value)})
				emit("done", doneStop())
				return nil
			}
		case <-timeout.C:
			timeout.Reset(500 * time.Millisecond)
		}
	}

	emit("error", map[string]any{"message": fmt.Sprintf("opencode 生成超时（%d 秒）", int(promptTimeout/time.Second))})
	emit("done", doneStop())
	return nil
}

func (a *OpencodeAdapter) normalizePart(part map[string]any, toolsSeen map[string]bool, emit EmitFunc) {
	partType := stringValue(part["type"])
	partID := stringValue(part["id"])

	switch partType {
	case "file":
		path := stringValue(part["filename"])
		if path == "" {
			path = stringValue(part["url"])
		}
		mimeType := stringValue(part["mime"])
		if mimeType == "" {
			mimeType = "application/octet-stream"
		}
		size := numberValue(part["size"])
		emit("file", map[string]any{
			"path": path,
			"mime": mimeType,
			"size": size,
			"url":  stringValue(part["url"]),
		})
	case "tool":
		if partID == "" {
			return
		}
		state, _ := part["state"].(map[string]any)
		status := stringValue(state["status"])
		toolName := stringValue(part["tool"])
		if toolName == "" {
			toolName = stringValue(part["name"])
		}
		if toolName == "" {
			toolName = "tool"
		}
		if (status == "pending" || status == "running") && !toolsSeen[partID] {
			toolsSeen[partID] = true
			args := state["input"]
			if args == nil {
				args = map[string]any{}
			}
			emit("tool_call", map[string]any{"name": toolName, "args": args})
		}
		if (status == "completed" || status == "error") && toolsSeen[partID] {
			output := ""
			if text, ok := state["output"].(string); ok {
				output = text
			} else if state["output"] != nil {
				output = compactJSON(state["output"])
			}
			if runes := []rune(output); len(runes) > 4000 {
				output = string(runes[:4000])
			}
			emit("tool_result", map[string]any{"name": toolName, "output": output})
			delete(toolsSeen, partID)
		}
	}
}

// ---- history ----

func (a *OpencodeAdapter) ListConversations() []Conversation {
	var items []map[string]any
	if err := a.getJSON("/session", 10*time.Second, &items); err != nil {
		return []Conversation{}
	}
	conversations := make([]Conversation, 0, len(items))
	for _, item := range items {
		if stringValue(item["parentID"]) != "" {
			continue
		}
		stamp, _ := item["time"].(map[string]any)
		updated := numberValue(stamp["updated"])
		if updated == 0 {
			updated = numberValue(stamp["created"])
		}
		if updated > 1e11 {
			updated = updated / 1000
		}
		conversations = append(conversations, Conversation{
			ConversationID: a.conversationFor(stringValue(item["id"])),
			Title:          stringValue(item["title"]),
			UpdatedAt:      updated,
		})
	}
	sort.SliceStable(conversations, func(i, j int) bool {
		return conversations[i].UpdatedAt > conversations[j].UpdatedAt
	})
	return conversations
}

func (a *OpencodeAdapter) conversationFor(sessionID string) string {
	a.mu.Lock()
	defer a.mu.Unlock()
	for conversationID, known := range a.sessions {
		if known == sessionID {
			return conversationID
		}
	}
	return "oc-" + sessionID
}

func (a *OpencodeAdapter) ConversationMessages(conversationID string, limit int) []HistoryMessage {
	sessionID := a.knownSession(conversationID)
	if sessionID == "" && strings.HasPrefix(conversationID, "oc-") {
		sessionID = strings.TrimPrefix(conversationID, "oc-")
	}
	if sessionID == "" {
		return []HistoryMessage{}
	}
	var items []map[string]any
	if err := a.getJSON("/session/"+url.PathEscape(sessionID)+"/message", 10*time.Second, &items); err != nil {
		return []HistoryMessage{}
	}
	messages := make([]HistoryMessage, 0, len(items))
	for _, item := range items {
		info, _ := item["info"].(map[string]any)
		role := stringValue(info["role"])
		if role == "" {
			role = "assistant"
		}
		var texts []string
		if parts, ok := item["parts"].([]any); ok {
			for _, rawPart := range parts {
				part, _ := rawPart.(map[string]any)
				if stringValue(part["type"]) != "text" {
					continue
				}
				if text := stringValue(part["text"]); text != "" {
					texts = append(texts, text)
				}
			}
		}
		stamp, _ := info["time"].(map[string]any)
		created := numberValue(stamp["completed"])
		if created == 0 {
			created = numberValue(stamp["created"])
		}
		messages = append(messages, HistoryMessage{
			Role: role,
			Text: strings.Join(texts, "\n"),
			TS:   created / 1000,
		})
	}
	if limit > 0 && len(messages) > limit {
		messages = messages[len(messages)-limit:]
	}
	return messages
}

// ---- event bus ----

func (a *OpencodeAdapter) queueFor(sessionID string) chan map[string]any {
	a.mu.Lock()
	defer a.mu.Unlock()
	queue, ok := a.queues[sessionID]
	if !ok {
		queue = make(chan map[string]any, 1000)
		a.queues[sessionID] = queue
	}
	return queue
}

func (a *OpencodeAdapter) eventLoop(ctx context.Context) {
	for a.running.Load() {
		if err := a.consumeEventStream(ctx); err != nil {
			select {
			case <-ctx.Done():
				return
			case <-time.After(time.Second):
			}
			continue
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(200 * time.Millisecond):
		}
	}
}

func (a *OpencodeAdapter) consumeEventStream(ctx context.Context) error {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, a.base+"/event", nil)
	if err != nil {
		return err
	}
	response, err := a.client.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != 200 {
		return fmt.Errorf("/event HTTP %d", response.StatusCode)
	}

	buffer := make([]byte, 0, 8192)
	chunk := make([]byte, 4096)
	for {
		count, readErr := response.Body.Read(chunk)
		if count > 0 {
			buffer = append(buffer, chunk[:count]...)
			for {
				index := bytes.Index(buffer, []byte("\n\n"))
				if index < 0 {
					break
				}
				block := buffer[:index]
				buffer = append([]byte(nil), buffer[index+2:]...)
				if event := parseSSEBlock(block); event != nil {
					a.dispatch(event)
				}
			}
		}
		if readErr != nil {
			if errors.Is(readErr, io.EOF) {
				return nil
			}
			return readErr
		}
	}
}

func (a *OpencodeAdapter) dispatch(event map[string]any) {
	properties, _ := event["properties"].(map[string]any)
	sessionID := stringValue(properties["sessionID"])
	if sessionID == "" {
		if part, ok := properties["part"].(map[string]any); ok {
			sessionID = stringValue(part["sessionID"])
		}
	}
	if sessionID == "" {
		return
	}
	queue := a.queueFor(sessionID)
	select {
	case queue <- event:
	default:
	}
}

func parseSSEBlock(block []byte) map[string]any {
	var dataLines []string
	for _, line := range strings.Split(string(block), "\n") {
		if strings.HasPrefix(line, "data:") {
			dataLines = append(dataLines, strings.TrimSpace(line[5:]))
		}
	}
	if len(dataLines) == 0 {
		return nil
	}
	var value any
	if err := json.Unmarshal([]byte(strings.Join(dataLines, "\n")), &value); err != nil {
		return nil
	}
	object, ok := value.(map[string]any)
	if !ok {
		return nil
	}
	return object
}

func stringValue(value any) string {
	text, _ := value.(string)
	return text
}

func numberValue(value any) float64 {
	switch number := value.(type) {
	case float64:
		return number
	case float32:
		return float64(number)
	case int:
		return float64(number)
	case int64:
		return float64(number)
	case json.Number:
		parsed, _ := number.Float64()
		return parsed
	}
	return 0
}
