"""B9 扩展（历史接口）的契约测试：GET /conversations、GET /conversations/{id}/messages。

fake 适配器在内存里记录历史，形状与真机 opencode 适配器归一化后的一致。
"""

import json
import os
import sys
import threading
import time
import unittest
import urllib.error
import urllib.request

BRIDGE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, BRIDGE_DIR)

from adapters.fake import FakeAgentAdapter  # noqa: E402
from server import build_server  # noqa: E402


def parse_sse_done(text):
    return "event: done" in text


class HistoryContractTest(unittest.TestCase):
    def setUp(self):
        self.adapter = FakeAgentAdapter()
        self.bridge, self.server = build_server(self.adapter)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.base = "http://127.0.0.1:{0}".format(self.server.server_address[1])

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def get_json(self, path):
        with urllib.request.urlopen(self.base + path, timeout=10) as response:
            return json.loads(response.read().decode("utf-8"))

    def chat(self, message, conversation_id):
        import urllib.request as request

        payload = json.dumps({"message": message, "conversationId": conversation_id}).encode()
        req = request.Request(self.base + "/chat", data=payload, headers={"Content-Type": "application/json"})
        with request.urlopen(req, timeout=10) as response:
            request_id = json.loads(response.read().decode())["requestId"]
        with request.urlopen("{0}/chat/{1}/stream".format(self.base, request_id), timeout=10) as response:
            self.assertTrue(parse_sse_done(response.read().decode()))

    def test_conversations_and_messages_shape(self):
        self.chat("first question", "c1")
        self.chat("second question", "c1")
        self.chat("other conversation", "c2")

        conversations = self.get_json("/conversations")
        self.assertEqual({c["conversationId"] for c in conversations}, {"c1", "c2"})
        for item in conversations:
            self.assertIn("title", item)
            self.assertIn("updatedAt", item)
        c1 = next(c for c in conversations if c["conversationId"] == "c1")
        self.assertEqual(c1["title"], "first question")

        messages = self.get_json("/conversations/c1/messages")
        self.assertEqual(
            [(m["role"], m["text"]) for m in messages],
            [
                ("user", "first question"),
                ("assistant", "echo: first question"),
                ("user", "second question"),
                ("assistant", "echo: second question"),
            ],
        )

    def test_limit_returns_latest(self):
        for index in range(5):
            self.chat("m{0}".format(index), "c3")
        messages = self.get_json("/conversations/c3/messages?limit=2")
        self.assertEqual(len(messages), 2)
        self.assertEqual(messages[-1]["text"], "echo: m4")

    def test_unknown_conversation_is_empty(self):
        self.assertEqual(self.get_json("/conversations/nope/messages"), [])

    def test_ordering_is_by_updated_at_desc(self):
        self.chat("old", "cold")
        time.sleep(0.01)
        self.chat("new", "cnew")
        conversations = self.get_json("/conversations")
        self.assertEqual([c["conversationId"] for c in conversations], ["cnew", "cold"])

    def request_status(self, method, path, payload=None):
        data = json.dumps(payload).encode() if payload is not None else None
        request = urllib.request.Request(
            self.base + path,
            data=data,
            method=method,
            headers={"Content-Type": "application/json"} if data else {},
        )
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                raw = response.read().decode("utf-8")
                return response.status, json.loads(raw) if raw.strip() else {}
        except urllib.error.HTTPError as error:
            return error.code, {}

    def test_rename_conversation(self):
        self.chat("hello", "cr")
        status, _ = self.request_status("PATCH", "/conversations/cr", {"title": "改过的标题"})
        self.assertEqual(status, 200)
        conversations = self.get_json("/conversations")
        item = next(c for c in conversations if c["conversationId"] == "cr")
        self.assertEqual(item["title"], "改过的标题")

    def test_delete_conversation(self):
        self.chat("hello", "cd")
        self.chat("keep", "ck")
        status, _ = self.request_status("DELETE", "/conversations/cd")
        self.assertEqual(status, 200)
        ids = [c["conversationId"] for c in self.get_json("/conversations")]
        self.assertNotIn("cd", ids)
        self.assertIn("ck", ids)
        self.assertEqual(self.get_json("/conversations/cd/messages"), [])

    def test_rename_and_delete_unknown(self):
        self.assertEqual(self.request_status("PATCH", "/conversations/none", {"title": "x"})[0], 404)
        self.assertEqual(self.request_status("DELETE", "/conversations/none")[0], 404)


if __name__ == "__main__":
    unittest.main()
