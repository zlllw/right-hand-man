"""Bridge 的 HTTP/SSE 契约测试（契约 B 的 B3/B4/B5、B10 第一段）。

跑在桌面与 CI 上，用 fake 适配器，**不需要 opencode，也不需要 Android**。
真机上的 opencode 端到端验收是另一段（B10 第二段）。
"""

import json
import os
import sys
import threading
import unittest
import urllib.error
import urllib.request

BRIDGE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, BRIDGE_DIR)

from adapters.fake import FakeAgentAdapter  # noqa: E402
from server import build_server  # noqa: E402


def parse_sse(text):
    blocks = []
    for raw in text.split("\n\n"):
        if not raw.strip():
            continue
        event = {"id": None, "event": None, "data": None}
        for line in raw.split("\n"):
            if line.startswith("id: "):
                event["id"] = int(line[4:])
            elif line.startswith("event: "):
                event["event"] = line[7:]
            elif line.startswith("data: "):
                event["data"] = json.loads(line[6:])
        blocks.append(event)
    return blocks


class BridgeTestCase(unittest.TestCase):
    adapter = None

    def setUp(self):
        self.adapter = self.make_adapter()
        self.bridge, self.server = build_server(self.adapter)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.base = "http://127.0.0.1:{0}".format(self.server.server_address[1])

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def make_adapter(self):
        return FakeAgentAdapter()

    def post_chat(self, message, conversation_id):
        payload = json.dumps({"message": message, "conversationId": conversation_id}).encode("utf-8")
        request = urllib.request.Request(
            self.base + "/chat", data=payload, headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(request, timeout=10) as response:
            return json.loads(response.read().decode("utf-8"))["requestId"]

    def stream(self, request_id, after_id=None):
        url = "{0}/chat/{1}/stream".format(self.base, request_id)
        if after_id is not None:
            url += "?from={0}".format(after_id)
        with urllib.request.urlopen(url, timeout=10) as response:
            return parse_sse(response.read().decode("utf-8"))

    def get_json(self, path):
        with urllib.request.urlopen(self.base + path, timeout=10) as response:
            return json.loads(response.read().decode("utf-8"))


class BasicContractTest(BridgeTestCase):
    def test_health(self):
        self.assertEqual(self.get_json("/health"), {"status": "ok"})

    def test_chat_then_stream_produces_text_then_done(self):
        request_id = self.post_chat("hello", "c1")
        events = self.stream(request_id)
        self.assertEqual([event["event"] for event in events], ["text", "text", "done"])
        self.assertEqual([event["id"] for event in events], [1, 2, 3])
        joined = events[0]["data"]["delta"] + events[1]["data"]["delta"]
        self.assertIn("hello", joined)
        self.assertEqual(events[-1]["data"]["finishReason"], "stop")

    def test_reconnect_with_from_does_not_replay(self):
        request_id = self.post_chat("hello", "c2")
        self.stream(request_id)
        events = self.stream(request_id, after_id=2)
        self.assertEqual([event["id"] for event in events], [3])
        self.assertEqual(events[0]["event"], "done")

    def test_stream_from_zero_returns_full_sequence_within_ttl(self):
        request_id = self.post_chat("hello", "c3")
        self.stream(request_id)
        events = self.stream(request_id, after_id=0)
        self.assertEqual([event["id"] for event in events], [1, 2, 3])

    def test_unknown_request_is_404(self):
        with self.assertRaises(urllib.error.HTTPError) as caught:
            self.stream("does-not-exist")
        self.assertEqual(caught.exception.code, 404)

    def test_invalid_from_is_400(self):
        request_id = self.post_chat("hello", "c4")
        with self.assertRaises(urllib.error.HTTPError) as caught:
            self.stream(request_id, after_id="nope")
        self.assertEqual(caught.exception.code, 400)

    def test_files_endpoint_is_not_implemented_in_stage_2(self):
        with self.assertRaises(urllib.error.HTTPError) as caught:
            self.get_json("/files/anything")
        self.assertEqual(caught.exception.code, 501)


class ErrorNormalisationTest(BridgeTestCase):
    def make_adapter(self):
        return FakeAgentAdapter(fail_with="adapter exploded")

    def test_adapter_exception_becomes_error_then_done(self):
        request_id = self.post_chat("boom", "c-err")
        events = self.stream(request_id)
        self.assertEqual([event["event"] for event in events], ["error", "done"])
        self.assertIn("adapter exploded", events[0]["data"]["message"])


class SerialisationTest(BridgeTestCase):
    def test_same_conversation_is_serialised_in_submission_order(self):
        self.adapter.step_delay = 0.05
        first = self.post_chat("one", "same")
        second = self.post_chat("two", "same")
        self.stream(first)
        self.stream(second)
        self.assertFalse(self.adapter.overlaps("same"), "同一 conversationId 不得并发")
        self.assertEqual([call[3] for call in self.adapter.spans("same")], ["one", "two"])

    def test_different_conversations_run_concurrently(self):
        self.adapter.step_delay = 0.05
        first = self.post_chat("one", "conv-a")
        second = self.post_chat("two", "conv-b")
        self.stream(first)
        self.stream(second)
        # calls 每项为 (conversationId, 开始, 结束, message)
        spans = sorted(self.adapter.calls, key=lambda call: call[1])
        self.assertEqual(len(spans), 2)
        self.assertLess(spans[1][1], spans[0][2], "不同 conversationId 之间应当并发")


if __name__ == "__main__":
    unittest.main()