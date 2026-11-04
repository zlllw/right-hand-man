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

    def run(self, conversation_id, message, emit):
        started = time.monotonic()
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
                emit(name, payload)
        finally:
            with self._calls_lock:
                # (conversationId, 开始, 结束, message)：前两项用于并发断言，末项用于顺序断言
                self.calls.append((conversation_id, started, time.monotonic(), message))

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