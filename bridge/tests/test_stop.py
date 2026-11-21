"""中断生成（POST /abort）的契约测试。"""

import json
import os
import sys
import threading
import time
import unittest
import urllib.request

BRIDGE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, BRIDGE_DIR)

from adapters.fake import FakeAgentAdapter  # noqa: E402
from server import build_server  # noqa: E402


class StopTest(unittest.TestCase):
    def setUp(self):
        # 每步 0.4s，完整跑完约 1.2s；中断后应立即结束
        self.adapter = FakeAgentAdapter(step_delay=0.4)
        self.bridge, self.server = build_server(self.adapter)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.base = "http://127.0.0.1:{0}".format(self.server.server_address[1])

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def post(self, path, payload):
        request = urllib.request.Request(
            self.base + path,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                return response.status, json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            return error.code, {}

    def test_stop_ends_stream_without_waiting_upstream(self):
        status, body = self.post("/chat", {"message": "long task", "conversationId": "cs"})
        self.assertEqual(status, 200)
        request_id = body["requestId"]
        time.sleep(0.1)

        status, _ = self.post("/abort", {"requestId": request_id})
        self.assertEqual(status, 200)

        started = time.monotonic()
        with urllib.request.urlopen(
            "{0}/chat/{1}/stream".format(self.base, request_id), timeout=5
        ) as response:
            stream = response.read().decode("utf-8")
        self.assertLess(time.monotonic() - started, 1.0)
        self.assertIn("event: done", stream)

        deadline = time.monotonic() + 2
        while "cs" not in self.adapter.aborted and time.monotonic() < deadline:
            time.sleep(0.02)
        self.assertIn("cs", self.adapter.aborted)

    def test_abort_unknown_request(self):
        status, _ = self.post("/abort", {"requestId": "nope"})
        self.assertEqual(status, 404)


if __name__ == "__main__":
    unittest.main()
