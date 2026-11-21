"""Bridge 服务（契约 B 的 B2/B3/B4/B5）。

用法::

    python3 bridge/server.py --port 8765 --adapter fake            # 桌面 / CI
    python3 bridge/server.py --adapter opencode --oc-port 4096     # 真机 Termux

设计要点：

* **只用 Python 标准库**。Termux 上默认 ``python3`` 指向 Hermes 自带的 venv 且没有
  pip 模块（docs/install-notes.md），所以 Bridge 不能依赖任何第三方包。
* **按 conversationId 串行**：每个 conversationId 一个 FIFO 工作线程，
  保证「先到的请求跑完再出下一个」，不同 conversationId 之间天然并发（B5 第 6 条）。
* **Bridge 不懂 Agent 的格式**：Agent 侧一律通过 adapters/ 下的适配器接入（B6）。
"""

import argparse
import json
import os
import queue
import stat
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from adapters.fake import FakeAgentAdapter  # noqa: E402
from adapters.opencode import OpencodeAgentAdapter  # noqa: E402
from files import (  # noqa: E402
    BadUpload,
    FileTooLarge,
    UnsafePath,
    Workspace,
    parse_multipart_upload,
)
from ring import DONE_TTL_SECONDS, EventLog, sse_block  # noqa: E402


class RequestSession(object):
    """一次 ``POST /chat`` 对应的事件日志与状态。"""

    def __init__(self, request_id, conversation_id):
        self.request_id = request_id
        self.conversation_id = conversation_id
        self.log = EventLog()
        self.condition = threading.Condition()
        self.completed = False
        self.completed_at = None


class ConversationWorker(object):
    """单个 conversationId 的 FIFO 串行执行器。"""

    def __init__(self, bridge, conversation_id):
        self._bridge = bridge
        self.conversation_id = conversation_id
        self._queue = queue.Queue()
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()

    def submit(self, session, message):
        self._queue.put((session, message))

    def _loop(self):
        while True:
            session, message = self._queue.get()
            if session is None:
                return
            self._bridge.execute(session, message)


class Bridge(object):
    def __init__(self, adapter, workspace=None):
        self.adapter = adapter
        self.workspace = workspace
        self._sessions = {}
        self._sessions_lock = threading.Lock()
        self._workers = {}
        self._workers_lock = threading.Lock()

    def _worker(self, conversation_id):
        with self._workers_lock:
            worker = self._workers.get(conversation_id)
            if worker is None:
                worker = ConversationWorker(self, conversation_id)
                self._workers[conversation_id] = worker
            return worker

    def submit(self, conversation_id, message):
        request_id = uuid.uuid4().hex
        session = RequestSession(request_id, conversation_id)
        with self._sessions_lock:
            self._sessions[request_id] = session
        self._worker(conversation_id).submit(session, message)
        return request_id

    def session(self, request_id):
        with self._sessions_lock:
            return self._sessions.get(request_id)

    def stop(self, request_id):
        """中断一次生成：先给 SSE 补一条 done（客户端立刻收尾），再通知上游 abort。"""
        session = self.session(request_id)
        if session is None:
            return False
        with session.condition:
            if not session.completed:
                last = session.log.last_event
                if last is None or last.name != "done":
                    session.log.append("done", {"finishReason": "stop"})
                session.completed = True
                session.completed_at = time.monotonic()
                session.condition.notify_all()
        threading.Thread(
            target=self.adapter.abort, args=(session.conversation_id,), daemon=True
        ).start()
        return True

    def execute(self, session, message):
        """运行一次适配器调用，把事件写进会话日志（B4/B5）。"""

        def emit(name, data):
            with session.condition:
                session.log.append(name, data)
                session.condition.notify_all()

        try:
            self.adapter.run(session.conversation_id, message, emit)
        except Exception as exc:  # 任何适配器异常都归一化为 error 事件
            emit("error", {"message": str(exc)})
        with session.condition:
            last = session.log.last_event
            if last is None or last.name != "done":
                session.log.append("done", {"finishReason": "stop"})
            session.completed = True
            session.completed_at = time.monotonic()
            session.condition.notify_all()

    def stream(self, session, after_id):
        """产出 SSE 文本块，直到 ``done`` 推完为止。"""
        if session.completed and time.monotonic() - session.completed_at > DONE_TTL_SECONDS:
            # B5 第 4/5 条：超出保留窗口的重连只补一个 done
            yield sse_block("done", {"finishReason": "stop"})
            return

        last_sent = after_id if after_id is not None else 0
        warned = False
        while True:
            with session.condition:
                pending, truncated = session.log.replay(last_sent)
                completed = session.completed
            if truncated and not warned:
                warned = True
                yield sse_block("error", {"message": "history_truncated"})
            for event in pending:
                last_sent = event.id
                yield event.to_sse()
            if completed:
                return
            with session.condition:
                session.condition.wait(timeout=0.5)


class BridgeHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    bridge = None

    def log_message(self, fmt, *args):
        sys.stderr.write("[bridge] {0}\n".format(fmt % args))

    def _json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return None
        try:
            return json.loads(self.rfile.read(length).decode("utf-8"))
        except ValueError:
            return None

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        if path == "/health":
            return self._json(200, {"status": "ok"})
        if path.startswith("/chat/") and path.endswith("/stream"):
            request_id = path[len("/chat/"):-len("/stream")]
            after_id = None
            raw_from = parse_qs(parsed.query).get("from")
            if raw_from:
                try:
                    after_id = int(raw_from[0])
                except ValueError:
                    return self._json(400, {"error": "invalid_from"})
            session = self.bridge.session(request_id)
            if session is None:
                return self._json(404, {"error": "unknown_request", "requestId": request_id})
            return self._stream(session, after_id)
        if path == "/conversations":
            return self._json(200, self.bridge.adapter.list_conversations())
        if path.startswith("/conversations/") and path.endswith("/messages"):
            conversation_id = unquote(path[len("/conversations/"):-len("/messages")])
            raw_limit = parse_qs(parsed.query).get("limit")
            limit = 50
            if raw_limit:
                try:
                    limit = max(1, min(500, int(raw_limit[0])))
                except ValueError:
                    return self._json(400, {"error": "invalid_limit"})
            return self._json(200, self.bridge.adapter.conversation_messages(conversation_id, limit))
        if path == "/files":
            workspace = self.bridge.workspace
            if workspace is None:
                return self._json(501, {"error": "no_workspace"})
            return self._json(200, {"root": workspace.display_root(), "files": workspace.list_files()})
        if path.startswith("/files/"):
            workspace = self.bridge.workspace
            if workspace is None:
                return self._json(501, {"error": "no_workspace"})
            name = unquote(path[len("/files/"):])
            try:
                target = workspace.resolve(name)
            except UnsafePath:
                return self._json(403, {"error": "unsafe_path"})
            if not os.path.isfile(target):
                return self._json(404, {"error": "not_found", "name": name})
            return self._send_file(target)
        return self._json(404, {"error": "not_found", "path": path})

    def do_POST(self):
        parsed = urlparse(self.path)
        if parsed.path == "/chat":
            body = self._read_json()
            if body is None:
                return self._json(400, {"error": "invalid_json"})
            message = body.get("message")
            conversation_id = body.get("conversationId")
            if not isinstance(message, str) or not isinstance(conversation_id, str):
                return self._json(400, {"error": "missing_fields"})
            return self._json(200, {"requestId": self.bridge.submit(conversation_id, message)})
        if parsed.path == "/abort":
            body = self._read_json()
            if body is None:
                return self._json(400, {"error": "invalid_json"})
            request_id = body.get("requestId")
            if not isinstance(request_id, str):
                return self._json(400, {"error": "missing_fields"})
            if not self.bridge.stop(request_id):
                return self._json(404, {"error": "unknown_request", "requestId": request_id})
            return self._json(200, {"ok": True})
        if parsed.path == "/files":
            workspace = self.bridge.workspace
            if workspace is None:
                return self._json(501, {"error": "no_workspace"})
            content_type = self.headers.get("Content-Type") or ""
            if "multipart/form-data" not in content_type:
                return self._json(400, {"error": "multipart_required"})
            declared = int(self.headers.get("Content-Length") or 0)
            if declared > workspace.max_bytes or declared <= 0:
                # B8：超限返回明确错误而不是截断；顺带把请求体排空以保持连接可复用
                self._drain(declared)
                return self._json(413, {"error": "file_too_large", "maxBytes": workspace.max_bytes})
            body = self.rfile.read(declared)
            try:
                filename, data = parse_multipart_upload(content_type, body, workspace.max_bytes)
                saved = workspace.save_upload(filename, data)
            except FileTooLarge:
                return self._json(413, {"error": "file_too_large", "maxBytes": workspace.max_bytes})
            except (BadUpload, ValueError) as exc:
                return self._json(400, {"error": "bad_upload", "detail": str(exc)})
            return self._json(200, {"path": saved["displayPath"], "name": saved["name"], "size": saved["size"]})
        return self._json(404, {"error": "not_found", "path": parsed.path})

    def do_PATCH(self):
        parsed = urlparse(self.path)
        path = parsed.path
        if path.startswith("/conversations/"):
            conversation_id = unquote(path[len("/conversations/"):])
            if not conversation_id or "/" in conversation_id:
                return self._json(404, {"error": "not_found", "path": path})
            body = self._read_json()
            if body is None:
                return self._json(400, {"error": "invalid_json"})
            title = body.get("title")
            if not isinstance(title, str) or not title.strip():
                return self._json(400, {"error": "missing_title"})
            if not self.bridge.adapter.rename_conversation(conversation_id, title.strip()):
                return self._json(404, {"error": "unknown_conversation"})
            return self._json(200, {"ok": True})
        return self._json(404, {"error": "not_found", "path": path})

    def do_DELETE(self):
        parsed = urlparse(self.path)
        path = parsed.path
        if path.startswith("/files/"):
            workspace = self.bridge.workspace
            if workspace is None:
                return self._json(501, {"error": "no_workspace"})
            name = unquote(path[len("/files/"):])
            try:
                removed = workspace.delete(name)
            except UnsafePath:
                return self._json(403, {"error": "unsafe_path"})
            if not removed:
                return self._json(404, {"error": "not_found", "name": name})
            return self._json(200, {"ok": True})
        if path.startswith("/conversations/"):
            conversation_id = unquote(path[len("/conversations/"):])
            if not conversation_id or "/" in conversation_id:
                return self._json(404, {"error": "not_found", "path": path})
            if not self.bridge.adapter.delete_conversation(conversation_id):
                return self._json(404, {"error": "unknown_conversation"})
            return self._json(200, {"ok": True})
        return self._json(404, {"error": "not_found", "path": path})

    def _drain(self, declared):
        remaining = declared
        while remaining > 0:
            chunk = self.rfile.read(min(remaining, 65536))
            if not chunk:
                return
            remaining -= len(chunk)

    def _send_file(self, target):
        import mimetypes

        mime = mimetypes.guess_type(target)[0] or "application/octet-stream"
        size = os.path.getsize(target)
        self.send_response(200)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(size))
        self.end_headers()
        with open(target, "rb") as handle:
            while True:
                chunk = handle.read(65536)
                if not chunk:
                    break
                self.wfile.write(chunk)

    def _stream(self, session, after_id):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        try:
            for chunk in self.bridge.stream(session, after_id):
                self.wfile.write(chunk.encode("utf-8"))
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            return


def build_server(adapter, port=0, host="127.0.0.1", workspace=None):
    """构造 (bridge, server)。端口传 0 表示由系统选空闲端口。"""
    bridge = Bridge(adapter, workspace=workspace)
    handler = type("BridgeHandlerWithAdapter", (BridgeHandler,), {"bridge": bridge})
    return bridge, ThreadingHTTPServer((host, port), handler)


def write_port_file(path, port):
    """B2：把实际端口写入端口文件，权限 0600。"""
    directory = os.path.dirname(os.path.abspath(path))
    if directory:
        os.makedirs(directory, exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write("{0}\n".format(port))
    try:
        os.chmod(path, stat.S_IRUSR | stat.S_IWUSR)
    except OSError:
        pass


def main(argv=None):
    parser = argparse.ArgumentParser(description="手信 Bridge（契约 B）")
    parser.add_argument("--port", type=int, default=0, help="0 = 自动选空闲端口")
    parser.add_argument("--adapter", choices=("fake", "opencode"), default="fake")
    parser.add_argument("--oc-port", type=int, default=4096, help="opencode serve 的端口")
    parser.add_argument("--port-file", default=None, help="端口文件路径（真机为 $HOME/.bridge/port）")
    parser.add_argument(
        "--workspace",
        default=None,
        help="文件工作区根目录（默认 $HOME/rhmdir；传 none 可关闭 /files）",
    )
    args = parser.parse_args(argv)

    if args.adapter == "fake":
        adapter = FakeAgentAdapter()
    else:
        adapter = OpencodeAgentAdapter(port=args.oc_port)

    workspace = None
    if args.workspace != "none":
        root = args.workspace or os.path.join(os.path.expanduser("~"), "rhmdir")
        workspace = Workspace(root)

    bridge, server = build_server(adapter, port=args.port, workspace=workspace)
    port = server.server_address[1]
    try:
        adapter.start()
    except Exception as exc:  # 真机上 opencode 起不来时，这里必须看得见原因
        server.server_close()
        print("Bridge 启动失败：{0}".format(exc), file=sys.stderr)
        return 2
    if args.port_file:
        write_port_file(args.port_file, port)
    print(
        "bridge listening on 127.0.0.1:{0} (adapter={1})".format(port, adapter.name),
        flush=True,
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        adapter.stop()
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())