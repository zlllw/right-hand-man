"""桌面与 CI 用的假适配器：不依赖 opencode、不依赖 Android。

它让契约 B 的协议语义可以在电脑上完整验证（契约 C 的 C2/C3）：
协议层与 SSE 语义在桌面测得，真机上只补 opencode 端到端那一段。
"""

import threading
import time

from .base import AgentAdapter

DEFAULT_SCRIPT = (
    ("text", {"delta": "echo: "}),
    ("text", {"delta": "{message}"}),
    ("done", {"finishReason": "stop"}),
)


class FakeAgentAdapter(AgentAdapter):
    name = "fake"

    def __init__(self, script=None, step_delay=0.0, fail_with=None):
        self.script = tuple(script if script is not None else DEFAULT_SCRIPT)
        self.step_delay = step_delay
        self.fail_with = fail_with
        self.calls = []
        self._calls_lock = threading.Lock()
        self._history = {}
        self._titles = {}
        self.aborted = set()
        self._history_lock = threading.Lock()

    def run(self, conversation_id, message, emit):
        started = time.monotonic()
        reply_parts = []
        try:
            if self.fail_with:
                raise RuntimeError(self.fail_with)
            for name, data in self.script:
                if self.step_delay:
                    time.sleep(self.step_delay)
                payload = {}
                for key, value in data.items():
                    if isinstance(value, str):
                        payload[key] = value.format(message=message)
                    else:
                        payload[key] = value
                if name == "text":
                    reply_parts.append(payload.get("delta", ""))
                emit(name, payload)
        finally:
            now = time.time()
            with self._calls_lock:
                # (conversationId, 开始, 结束, message)：前两项用于并发断言，末项用于顺序断言
                self.calls.append((conversation_id, started, time.monotonic(), message))
            with self._history_lock:
                entries = self._history.setdefault(conversation_id, [])
                entries.append({"role": "user", "text": message, "ts": now})
                entries.append({"role": "assistant", "text": "".join(reply_parts), "ts": now})

    def list_conversations(self):
        with self._history_lock:
            snapshot = {cid: list(entries) for cid, entries in self._history.items()}
            titles = dict(self._titles)
        conversations = []
        for conversation_id, entries in snapshot.items():
            first_user = next((e["text"] for e in entries if e["role"] == "user"), "")
            conversations.append({
                "conversationId": conversation_id,
                "title": titles.get(conversation_id) or first_user[:40] or conversation_id,
                "updatedAt": entries[-1]["ts"],
            })
        conversations.sort(key=lambda item: item["updatedAt"], reverse=True)
        return conversations

    def rename_conversation(self, conversation_id, title):
        with self._history_lock:
            if conversation_id not in self._history:
                return False
            self._titles[conversation_id] = title
        return True

    def abort(self, conversation_id):
        with self._history_lock:
            self.aborted.add(conversation_id)
        return True

    def delete_conversation(self, conversation_id):
        with self._history_lock:
            if conversation_id not in self._history:
                return False
            self._history.pop(conversation_id, None)
            self._titles.pop(conversation_id, None)
        return True

    def conversation_messages(self, conversation_id, limit):
        with self._history_lock:
            entries = list(self._history.get(conversation_id, []))
        return entries[-limit:] if limit and limit > 0 else entries

    def spans(self, conversation_id):
        with self._calls_lock:
            spans = [call for call in self.calls if call[0] == conversation_id]
        return sorted(spans, key=lambda span: span[1])

    def overlaps(self, conversation_id):
        """同一 conversationId 的两次 run 是否发生过时间重叠。

        契约 B 的 B5 第 6 条要求「同一 conversationId 串行」，
        因此这里期望为 ``False``；不同 conversationId 之间期望为 ``True``。
        """
        spans = self.spans(conversation_id)
        for previous, current in zip(spans, spans[1:]):
            if current[1] < previous[2]:
                return True
        return False