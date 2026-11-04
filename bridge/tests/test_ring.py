"""环形缓冲与 SSE 格式的单元测试（契约 B 的 B5；契约 C 的 L1）。

这个文件同时验证跨语言 fixture `tests/fixtures/sse/basic.jsonl` 的形状——
该 fixture 是唯一事实源，Kotlin 侧将来读同一份（契约 C 的 C2）。
"""

import json
import os
import sys
import unittest

BRIDGE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO_ROOT = os.path.dirname(BRIDGE_DIR)
sys.path.insert(0, BRIDGE_DIR)

from ring import DONE_TTL_SECONDS, EventLog  # noqa: E402

ALLOWED_EVENTS = {"text", "tool_call", "tool_result", "file", "error", "done"}


def load_fixture(name="basic.jsonl"):
    path = os.path.join(REPO_ROOT, "tests", "fixtures", "sse", name)
    with open(path, "r", encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


class EventLogTest(unittest.TestCase):
    def test_ids_start_at_one_and_increase(self):
        log = EventLog()
        log.append("text", {"delta": "a"})
        log.append("text", {"delta": "b"})
        events, truncated = log.replay(0)
        self.assertEqual([event.id for event in events], [1, 2])
        self.assertFalse(truncated)

    def test_replay_is_exclusive_on_from(self):
        log = EventLog()
        for index in range(4):
            log.append("text", {"delta": str(index)})
        events, truncated = log.replay(2)
        self.assertEqual([event.id for event in events], [3, 4])
        self.assertFalse(truncated)
        self.assertEqual(log.replay(4)[0], [])
        self.assertFalse(log.replay(4)[1])

    def test_none_after_id_returns_everything(self):
        log = EventLog()
        log.append("text", {"delta": "a"})
        events, truncated = log.replay(None)
        self.assertEqual([event.id for event in events], [1])
        self.assertFalse(truncated)

    def test_capacity_eviction_marks_truncated(self):
        log = EventLog(capacity=3)
        for index in range(5):
            log.append("text", {"delta": str(index)})
        events, truncated = log.replay(0)
        self.assertEqual([event.id for event in events], [3, 4, 5])
        self.assertTrue(truncated)
        events, truncated = log.replay(2)
        self.assertEqual([event.id for event in events], [3, 4, 5])
        self.assertFalse(truncated)
        events, truncated = log.replay(4)
        self.assertEqual([event.id for event in events], [5])
        self.assertFalse(truncated)

    def test_empty_log(self):
        log = EventLog()
        self.assertEqual(log.replay(None), ([], False))
        self.assertEqual(log.replay(7), ([], False))

    def test_sse_encoding(self):
        log = EventLog()
        event = log.append("text", {"delta": "hi"})
        self.assertEqual(event.to_sse(), 'id: 1\nevent: text\ndata: {"delta":"hi"}\n\n')

    def test_done_ttl_window_is_60_seconds(self):
        self.assertEqual(DONE_TTL_SECONDS, 60)


class FixtureTest(unittest.TestCase):
    def test_shape(self):
        events = load_fixture()
        self.assertTrue(events, "fixture 不能为空")
        self.assertEqual([event["id"] for event in events], list(range(1, len(events) + 1)))
        for event in events:
            self.assertIn(event["event"], ALLOWED_EVENTS)
            self.assertIsInstance(event["data"], dict)
        self.assertEqual(events[-1]["event"], "done")
        for event in events:
            if event["event"] == "done":
                self.assertIn(event["data"]["finishReason"], {"stop"})

    def test_replay_over_fixture(self):
        events = load_fixture()
        log = EventLog()
        for event in events:
            log.append(event["event"], event["data"])
        pending, truncated = log.replay(events[0]["id"])
        self.assertEqual([event.id for event in pending], [event["id"] for event in events[1:]])
        self.assertFalse(truncated)


if __name__ == "__main__":
    unittest.main()