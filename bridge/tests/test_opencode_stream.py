"""opencode 事件流的编码回归测试。

真机 bug：_consume_event_stream 曾逐字节 read(1) 后单独 decode("utf-8")，
UTF-8 多字节字符被切碎成 U+FFFD，导致中文回复全部乱码。
"""

import json
import os
import sys
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

BRIDGE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, BRIDGE_DIR)

from adapters.opencode import OpencodeAgentAdapter  # noqa: E402

CHINESE_TEXT = "你好，我是手信里运行的开源助手。"


class _EventStreamHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        part = {
            "properties": {
                "sessionID": "s1",
                "part": {"id": "p1", "type": "text", "text": CHINESE_TEXT},
            }
        }
        block = b"data: " + json.dumps(part, ensure_ascii=False).encode("utf-8") + b"\n\n"
        # 故意用 3 字节小块发送，跨越 UTF-8 字符边界
        for index in range(0, len(block), 3):
            self.wfile.write(block[index:index + 3])
            self.wfile.flush()
            time.sleep(0.001)

    def log_message(self, *args):
        pass


class ChineseStreamTest(unittest.TestCase):
    def test_multibyte_chinese_survives_stream(self):
        server = HTTPServer(("127.0.0.1", 0), _EventStreamHandler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        adapter = OpencodeAgentAdapter(port=server.server_address[1])
        queue = adapter._queue_for("s1")

        threading.Thread(target=adapter._consume_event_stream, daemon=True).start()
        try:
            event = queue.get(timeout=10)
        finally:
            server.shutdown()
            server.server_close()

        text = event.get("properties", {}).get("part", {}).get("text", "")
        self.assertEqual(text, CHINESE_TEXT)
        self.assertNotIn("\ufffd", json.dumps(event, ensure_ascii=False))


if __name__ == "__main__":
    unittest.main()
