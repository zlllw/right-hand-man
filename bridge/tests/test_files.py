"""/files 接口的契约测试（B3 文件行 + B8 安全要求）。

桌面 / CI 跑，零依赖（unittest + 标准库），与 test_http_contract.py 同一套路。
"""

import io
import json
import os
import shutil
import sys
import tempfile
import threading
import unittest
import urllib.parse
import urllib.error
import urllib.request
import uuid

BRIDGE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, BRIDGE_DIR)

from adapters.fake import FakeAgentAdapter  # noqa: E402
from files import Workspace  # noqa: E402
from server import build_server  # noqa: E402


def multipart_body(filename, data):
    boundary = "----bridge-test-" + uuid.uuid4().hex
    buffer = io.BytesIO()
    buffer.write(("--" + boundary + "\r\n").encode())
    buffer.write(
        (
            'Content-Disposition: form-data; name="file"; filename="{0}"\r\n'
            "Content-Type: application/octet-stream\r\n\r\n"
        ).format(filename).encode("utf-8")
    )
    buffer.write(data)
    buffer.write(("\r\n--" + boundary + "--\r\n").encode())
    return boundary, buffer.getvalue()


class FilesContractTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="rhmdir-test-")
        self.workspace = Workspace(self.root)
        self.bridge, self.server = build_server(FakeAgentAdapter(), workspace=self.workspace)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.base = "http://127.0.0.1:{0}".format(self.server.server_address[1])

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        shutil.rmtree(self.root, ignore_errors=True)

    def upload(self, filename, data):
        boundary, body = multipart_body(filename, data)
        request = urllib.request.Request(
            self.base + "/files",
            data=body,
            headers={"Content-Type": "multipart/form-data; boundary=" + boundary},
        )
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, json.loads(response.read().decode("utf-8"))

    def test_upload_list_download_roundtrip(self):
        status, saved = self.upload("hello.txt", b"bridge-content")
        self.assertEqual(status, 200)
        self.assertEqual(saved["name"], "hello.txt")
        self.assertEqual(saved["size"], len(b"bridge-content"))

        listing = json.loads(
            urllib.request.urlopen(self.base + "/files", timeout=10).read().decode("utf-8")
        )
        self.assertEqual([item["name"] for item in listing["files"]], ["hello.txt"])

        with urllib.request.urlopen(self.base + "/files/hello.txt", timeout=10) as response:
            self.assertEqual(response.read(), b"bridge-content")

    def test_duplicate_name_is_renamed_not_overwritten(self):
        self.upload("note.md", b"first")
        _, second = self.upload("note.md", b"second")
        self.assertEqual(second["name"], "note (2).md")
        with open(os.path.join(self.root, "note.md"), "rb") as handle:
            self.assertEqual(handle.read(), b"first")

    def test_filename_with_path_is_sanitized(self):
        _, saved = self.upload("../../etc/passwd", b"x")
        self.assertEqual(saved["name"], "passwd")
        self.assertTrue(os.path.isfile(os.path.join(self.root, "passwd")))

    def test_oversize_upload_returns_413_without_truncating(self):
        workspace = Workspace(os.path.join(self.root, "small"), max_bytes=16)
        bridge, server = build_server(FakeAgentAdapter(), workspace=workspace)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            base = "http://127.0.0.1:{0}".format(server.server_address[1])
            boundary, body = multipart_body("big.bin", b"x" * 64)
            request = urllib.request.Request(
                base + "/files",
                data=body,
                headers={"Content-Type": "multipart/form-data; boundary=" + boundary},
            )
            with self.assertRaises(urllib.error.HTTPError) as caught:
                urllib.request.urlopen(request, timeout=10)
            self.assertEqual(caught.exception.code, 413)
            self.assertEqual(os.listdir(os.path.join(self.root, "small")), [])
        finally:
            server.shutdown()
            server.server_close()

    def test_download_rejects_escape(self):
        self.upload("keep.txt", b"safe")
        for bad in ("../server.py", "%2e%2e%2fserver.py", "/etc/hosts"):
            url = self.base + "/files/" + bad
            try:
                urllib.request.urlopen(url, timeout=10)
                self.fail("expected rejection for " + bad)
            except urllib.error.HTTPError as error:
                self.assertIn(error.code, (400, 403, 404), bad)

    def delete(self, name):
        request = urllib.request.Request(self.base + "/files/" + urllib.parse.quote(name), method="DELETE")
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                return response.status
        except urllib.error.HTTPError as error:
            return error.code

    def test_delete_file_removes_from_workspace(self):
        self.upload("gone.txt", b"bye")
        self.assertEqual(self.delete("gone.txt"), 200)
        with urllib.request.urlopen(self.base + "/files", timeout=10) as response:
            files = json.loads(response.read().decode("utf-8"))["files"]
        self.assertNotIn("gone.txt", [item["name"] for item in files])
        self.assertEqual(self.delete("gone.txt"), 404)

    def test_delete_rejects_escape(self):
        self.upload("keep.txt", b"safe")
        self.assertIn(self.delete("../server.py"), (403, 404))
        self.assertIn(self.delete("%2e%2e%2fserver.py"), (403, 404))

    def test_download_missing_file_is_404(self):
        try:
            urllib.request.urlopen(self.base + "/files/nope.txt", timeout=10)
            self.fail("expected 404")
        except urllib.error.HTTPError as error:
            self.assertEqual(error.code, 404)

    def test_non_multipart_post_is_400(self):
        request = urllib.request.Request(
            self.base + "/files", data=b"raw", headers={"Content-Type": "application/octet-stream"}
        )
        try:
            urllib.request.urlopen(request, timeout=10)
            self.fail("expected 400")
        except urllib.error.HTTPError as error:
            self.assertEqual(error.code, 400)


if __name__ == "__main__":
    unittest.main()
