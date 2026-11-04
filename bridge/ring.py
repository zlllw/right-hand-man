"""SSE 事件环形缓冲（契约 B 的 B5）。

这里落地四条语义：

* 事件 `id` 的作用域是**单个 requestId**，从 1 开始独立递增；
* 环形缓冲按 requestId 保留最近 ``RING_SIZE`` 个事件；
* ``from=<eventId>`` 的语义是「从该 id 之后（不含）继续推送」；
* ``from`` 指向已被淘汰的 id 时，调用方要先补发一条 ``history_truncated``。

只用标准库。Termux 上默认 ``python3`` 指向 Hermes 自带的 venv 且没有 pip 模块
（见 docs/install-notes.md），所以 Bridge 不引入任何第三方依赖。
"""

import json

RING_SIZE = 500
DONE_TTL_SECONDS = 60


class Event(object):
    """一条待推送的 SSE 事件。"""

    __slots__ = ("id", "name", "data")

    def __init__(self, event_id, name, data):
        self.id = event_id
        self.name = name
        self.data = data

    def to_sse(self):
        payload = json.dumps(self.data, ensure_ascii=False, separators=(",", ":"))
        return "id: {0}\nevent: {1}\ndata: {2}\n\n".format(self.id, self.name, payload)

    def __repr__(self):
        return "<Event id={0} name={1}>".format(self.id, self.name)


def sse_block(name, data, event_id=None):
    """构造不带环形缓冲 id 的 SSE 块（用于 TTL 超时后的收尾 done）。"""
    payload = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    prefix = "" if event_id is None else "id: {0}\n".format(event_id)
    return "{0}event: {1}\ndata: {2}\n\n".format(prefix, name, payload)


class EventLog(object):
    """单个 requestId 的事件日志。"""

    def __init__(self, capacity=RING_SIZE):
        self._capacity = capacity
        self._events = []
        self._next_id = 1

    def append(self, name, data):
        event = Event(self._next_id, name, data)
        self._next_id += 1
        self._events.append(event)
        overflow = len(self._events) - self._capacity
        if overflow > 0:
            del self._events[:overflow]
        return event

    @property
    def last_event(self):
        return self._events[-1] if self._events else None

    def earliest_id(self):
        return self._events[0].id if self._events else None

    def __len__(self):
        return len(self._events)

    def replay(self, after_id):
        """返回 ``(events, truncated)``。

        ``after_id`` 为 ``None`` 表示「从头开始」，返回全部；
        否则返回 id **严格大于** after_id 的事件。
        ``truncated`` 表示 after_id 之后、缓冲区最早事件之前存在已被丢弃的事件。
        """
        if not self._events:
            return [], False
        if after_id is None:
            return list(self._events), False
        earliest = self._events[0].id
        truncated = after_id < earliest - 1
        return [event for event in self._events if event.id > after_id], truncated