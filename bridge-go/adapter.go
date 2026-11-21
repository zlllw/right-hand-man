package main

// EmitFunc reports one normalized event to the Bridge.
type EmitFunc func(name string, data any)

type Conversation struct {
	ConversationID string  `json:"conversationId"`
	Title          string  `json:"title"`
	UpdatedAt      float64 `json:"updatedAt"`
}

type HistoryMessage struct {
	Role string  `json:"role"`
	Text string  `json:"text"`
	TS   float64 `json:"ts"`
}

// Adapter is the only layer that knows an agent backend's wire format.
type Adapter interface {
	Name() string
	Start() error
	Stop()
	Health() bool
	Run(conversationID, message string, emit EmitFunc) error
	ListConversations() []Conversation
	ConversationMessages(conversationID string, limit int) []HistoryMessage
	RenameConversation(conversationID, title string) bool
	DeleteConversation(conversationID string) bool
	Abort(conversationID string) bool
}

func doneStop() map[string]any {
	return map[string]any{"finishReason": "stop"}
}
