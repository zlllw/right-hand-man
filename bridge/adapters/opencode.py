"""opencode 适配器（契约 B 的 B6）——真机 Termux 内运行。

设计约束（B6）：

* ``start()`` spawn ``opencode serve --hostname 127.0.0.1 --port <P_oc>``，
  轮询 ``GET /global/health`` 直到 ``healthy: true``；``stop()`` 必须回收子进程（B2）。
* 启动后拉取运行时 ``/doc``（OpenAPI 3.1），**校验**本文件用到的每个路径确实存在；
  对不上就抛错退出，绝不带着猜测跑（B6「禁止凭经验硬编码」的落实方式：
  事件/路径映射写成显式表 + 启动期用真 spec 验证）。
* ``conversationId`` ↔ opencode ``sessionID`` 1:1，映射持久化到
  ``$HOME/.bridge/sessions.json``；未知 conversationId 视为新建（B5 第 7 条）。
* 判进程存活不依赖 pgrep（docs/install-notes.md 第 3 条）：只信自己 spawn 的句柄 + 端口探测。

在桌面/CI 上 ``start()`` 会因找不到可执行文件或健康检查失败而抛错——这是故意的（B10）。
"""

import json
import os
import queue
import subprocess
import threading
import time
import urllib.error
import urllib.request

from .base import AgentAdapter

HEALTH_TIMEOUT_SECONDS = 120
PROMPT_TIMEOUT_SECONDS = 600

# B6 显式映射表：本适配器依赖的上游路径，2026-11-21 以真机 opencode 1.19.3 的
# 运行时 /doc（OpenAPI 3.1，162 条路径）实测核对。start() 会再次用真 spec 校验。
REQUIRED_PATHS = (
    "/session",
    "/session/{sessionID}/message",
    "/session/{sessionID}/prompt_async",
    "/session/{sessionID}/abort",
    "/event",
    "/global/health",
)


class OpencodeAgentAdapter(AgentAdapter):
    name = "opencode"

    def __init__(self, port, executable="opencode", hostname="127.0.0.1", workdir=None):
        self.port = port
        self.executable = executable
        self.hostname = hostname
        self.workdir = workdir or os.path.expanduser("~/rhmdir")
        self.base = "http://{0}:{1}".format(hostname, port)
        self._process = None
        self._events = None  # 后台 SSE 读线程
        self._queues = {}  # sessionID -> queue.Queue
        self._queues_lock = threading.Lock()
        self._sessions = {}
        self._map_path = os.path.join(os.path.expanduser("~"), ".bridge", "sessions.json")
        self._load_session_map()

    # ---- 生命周期（B2） ----

    def start(self):
        env = dict(os.environ)
        env.setdefault("OPENCODE_TERMUX_AUTOUPDATE", "0")
        env.setdefault("OPENCODE_DISABLE_AUTOUPDATE", "1")
        env.pop("LD_PRELOAD", None)
        os.makedirs(self.workdir, exist_ok=True)
        self._process = subprocess.Popen(
            [
                self.executable,
                "serve",
                "--hostname",
                self.hostname,
                "--port",
                str(self.port),
            ],
            cwd=self.workdir,
            env=env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
        )
        deadline = time.monotonic() + HEALTH_TIMEOUT_SECONDS
        while time.monotonic() < deadline:
            if self._process.poll() is not None:
                stderr = self._process.stderr.read().decode("utf-8", "replace") if self._process.stderr else ""
                raise RuntimeError(
                    "opencode serve 退出（code={0}）：{1}".format(self._process.returncode, stderr[-2000:])
                )
            body = None
            try:
                body = self._get_json("/global/health", timeout=3)
            except (urllib.error.URLError, OSError, ValueError):
                body = None  # serve 还没监听，继续等
            if isinstance(body, dict) and body.get("healthy") is True:
                break
            time.sleep(1)
        else:
            self.stop()
            raise RuntimeError("opencode /global/health 在 {0}s 内未就绪".format(HEALTH_TIMEOUT_SECONDS))

        self._verify_doc()
        self._events = threading.Thread(target=self._event_loop, daemon=True)
        self._events.start()

    def stop(self):
        process, self._process = self._process, None
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

    def health(self):
        body = self._get_json("/global/health", timeout=3)
        return isinstance(body, dict) and body.get("healthy") is True

    # ---- B6：运行时 /doc 校验 ----

    def _verify_doc(self):
        spec = self._get_json("/doc", timeout=10)
        if not isinstance(spec, dict) or "paths" not in spec:
            raise RuntimeError("/doc 不是 OpenAPI spec")
        paths = set(spec["paths"].keys())
        missing = [p for p in REQUIRED_PATHS if p not in paths]
        if missing:
            raise RuntimeError(
                "opencode 接口与映射表不符，缺少 {0}；实际 paths 样例：{1}".format(
                    missing, sorted(paths)[:20]
                )
            )
        self.doc_info = spec.get("info", {})

    # ---- 会话映射（B5 第 7 条 / B6） ----

    def _load_session_map(self):
        try:
            with open(self._map_path, "r", encoding="utf-8") as handle:
                data = json.load(handle)
            if isinstance(data, dict):
                self._sessions = {str(k): str(v) for k, v in data.items()}
        except (OSError, ValueError):
            self._sessions = {}

    def _save_session_map(self):
        try:
            os.makedirs(os.path.dirname(self._map_path), exist_ok=True)
            with open(self._map_path, "w", encoding="utf-8") as handle:
                json.dump(self._sessions, handle, ensure_ascii=False)
        except OSError:
            pass

    def _session_for(self, conversation_id):
        with self._queues_lock:
            session_id = self._sessions.get(conversation_id)
        if session_id:
            return session_id
        # POST /session 的 schema 是 additionalProperties:false，只认 title 等字段；
        # 工作目录由 `opencode serve` 的 cwd 决定（start() 里 cwd=rhmdir）。
        # 不传 title：让 opencode 依据首条消息自动生成标题（真机实测传了就不再生成）。
        created = self._post_json("/session", {})
        session_id = created.get("id") if isinstance(created, dict) else None
        if not session_id:
            raise RuntimeError("POST /session 未返回 id：{0!r}".format(created))
        with self._queues_lock:
            self._sessions[conversation_id] = session_id
        self._save_session_map()
        return session_id

    def rename_conversation(self, conversation_id, title):
        with self._queues_lock:
            session_id = self._sessions.get(conversation_id)
        if not session_id:
            return False
        return self._http_status("PATCH", "/session/{0}".format(session_id), {"title": title}) in (200, 204)

    def abort(self, conversation_id):
        with self._queues_lock:
            session_id = self._sessions.get(conversation_id)
        if not session_id:
            return False
        return self._http_status("POST", "/session/{0}/abort".format(session_id), {}) in (200, 204)

    def delete_conversation(self, conversation_id):
        with self._queues_lock:
            session_id = self._sessions.get(conversation_id)
        if not session_id:
            return False
        status = self._http_status("DELETE", "/session/{0}".format(session_id))
        if status in (200, 204, 404):
            with self._queues_lock:
                self._sessions.pop(conversation_id, None)
            self._save_session_map()
            return True
        return False

    # ---- 一次生成（B4 事件归一化） ----

    def run(self, conversation_id, message, emit):
        session_id = self._session_for(conversation_id)
        events = self._queue_for(session_id)
        while True:  # 清掉订阅前的陈旧事件
            try:
                events.get_nowait()
            except queue.Empty:
                break

        status = self._post_status(
            "/session/{0}/prompt_async".format(session_id),
            {"parts": [{"type": "text", "text": message}]},
        )
        if status not in (200, 202, 204):
            emit("error", {"message": "prompt_async HTTP {0}".format(status)})
            emit("done", {"finishReason": "stop"})
            return

        deadline = time.monotonic() + PROMPT_TIMEOUT_SECONDS
        part_types = {}  # partID -> part 类型（delta 事件只带 partID，类型要靠 updated 事件登记）
        tools_seen = set()
        finished = False
        while not finished and time.monotonic() < deadline:
            try:
                event = events.get(timeout=0.5)
            except queue.Empty:
                continue
            kind = event.get("type")
            properties = event.get("properties") or {}
            if properties.get("sessionID") != session_id:
                continue
            if kind == "message.part.updated":
                part = properties.get("part") or {}
                if part.get("id"):
                    part_types[part["id"]] = part.get("type")
                self._normalize_part(part, tools_seen, emit)
            elif kind == "message.part.delta":
                # schema: {sessionID, messageID, partID, field, delta}
                # 只放行「text 类型 part 的 text 字段」：reasoning part 的同名 delta 是思考过程，不上屏；
                # part.updated 也会带全量 text，但用它会产生重复（真机实测），所以文本只走 delta。
                if properties.get("field") == "text" and part_types.get(properties.get("partID")) == "text":
                    delta = properties.get("delta")
                    if isinstance(delta, str) and delta:
                        emit("text", {"delta": delta})
            elif kind == "session.idle":
                emit("done", {"finishReason": "stop"})
                finished = True
            elif kind in ("session.error", "session.deleted"):
                error = properties.get("error") or properties.get("message") or kind
                emit("error", {"message": str(error)})
                emit("done", {"finishReason": "stop"})
                finished = True
        if not finished:
            emit("error", {"message": "opencode 生成超时（{0}s）".format(PROMPT_TIMEOUT_SECONDS)})
            emit("done", {"finishReason": "stop"})

    def _normalize_part(self, part, tools_seen, emit):
        part_type = part.get("type")
        part_id = part.get("id")
        # 文本不走这里：message.part.updated 带全量文本，与 message.part.delta 重复（真机实测）。
        if part_type == "file":
            emit("file", {
                "path": part.get("filename") or part.get("url") or "",
                "mime": part.get("mime") or "application/octet-stream",
                "size": part.get("size") or 0,
                "url": part.get("url") or "",
            })
        elif part_type == "tool" and part_id:
            state = part.get("state") or {}
            status = state.get("status")
            tool_name = part.get("tool") or part.get("name") or "tool"
            if status in ("pending", "running") and part_id not in tools_seen:
                tools_seen.add(part_id)
                emit("tool_call", {"name": tool_name, "args": state.get("input") or {}})
            if status in ("completed", "error") and part_id in tools_seen:
                output = state.get("output")
                if not isinstance(output, str):
                    output = json.dumps(output, ensure_ascii=False) if output is not None else ""
                emit("tool_result", {"name": tool_name, "output": output[:4000]})
                tools_seen.discard(part_id)

    # ---- 历史（B9 扩展）：真源是 opencode 的存储，Bridge 不落盘 ----

    def list_conversations(self):
        body = self._get_json("/session", timeout=10)
        if not isinstance(body, list):
            return []
        conversations = []
        for item in body:
            if not isinstance(item, dict):
                continue
            if item.get("parentID"):  # 子 agent 会话不进侧栏（挂在父会话下）
                continue
            stamp = item.get("time") or {}
            updated = stamp.get("updated") or stamp.get("created") or 0
            conversations.append({
                "conversationId": self._conversation_for(item.get("id")),
                "title": item.get("title") or "",
                # opencode 的 time 是毫秒；契约 B9 与 fake 适配器统一用秒
                "updatedAt": updated // 1000 if updated > 10**11 else updated,
            })
        conversations.sort(key=lambda entry: entry["updatedAt"], reverse=True)
        return conversations

    def _conversation_for(self, session_id):
        with self._queues_lock:
            for conversation_id, known in self._sessions.items():
                if known == session_id:
                    return conversation_id
        return "oc-{0}".format(session_id)

    def conversation_messages(self, conversation_id, limit):
        with self._queues_lock:
            session_id = self._sessions.get(conversation_id)
        if not session_id and conversation_id.startswith("oc-"):
            session_id = conversation_id[len("oc-"):]
        if not session_id:
            return []
        body = self._get_json("/session/{0}/message".format(session_id), timeout=10)
        if not isinstance(body, list):
            return []
        messages = []
        for item in body:
            if not isinstance(item, dict):
                continue
            info = item.get("info") or {}
            role = info.get("role")
            parts = item.get("parts") or []
            texts = []
            for part in parts:
                if isinstance(part, dict) and part.get("type") == "text":
                    texts.append(part.get("text") or "")
            stamp = info.get("time") or {}
            messages.append({
                "role": role or "assistant",
                "text": "\n".join(t for t in texts if t),
                "ts": (stamp.get("completed") or stamp.get("created") or 0) // 1000,
            })
        return messages[-limit:] if limit and limit > 0 else messages

    # ---- 全局事件总线（B6：订阅 /event，按 sessionID 过滤） ----

    def _queue_for(self, session_id):
        with self._queues_lock:
            event_queue = self._queues.get(session_id)
            if event_queue is None:
                event_queue = queue.Queue(maxsize=1000)
                self._queues[session_id] = event_queue
            return event_queue

    def _event_loop(self):
        while self._process is not None and self._process.poll() is None:
            try:
                self._consume_event_stream()
            except Exception:
                time.sleep(1)  # 断线重连；上游挂了下一轮 poll 会退出

    def _consume_event_stream(self):
        request = urllib.request.Request(self.base + "/event")
        with urllib.request.urlopen(request, timeout=None) as response:
            # 必须按字节缓冲、整块解码：逐字节 decode 会把 UTF-8 多字节字符切碎成 �（真机实测中文乱码）
            buffer = b""
            while True:
                chunk = response.read(4096)
                if not chunk:
                    return
                buffer += chunk
                while b"\n\n" in buffer:
                    block, buffer = buffer.split(b"\n\n", 1)
                    event = self._parse_sse_block(block.decode("utf-8", "replace"))
                    if event is not None:
                        self._dispatch(event)

    def _dispatch(self, event):
        properties = event.get("properties") or {}
        session_id = properties.get("sessionID")
        part = properties.get("part") or {}
        session_id = session_id or part.get("sessionID")
        if not session_id:
            return  # server.connected 等连接期事件直接丢弃（B6 映射表）
        target = self._queue_for(session_id)
        try:
            target.put_nowait(event)
        except queue.Full:
            pass

    @staticmethod
    def _parse_sse_block(block):
        data_lines = []
        for line in block.split("\n"):
            if line.startswith("data:"):
                data_lines.append(line[5:].strip())
        if not data_lines:
            return None
        try:
            parsed = json.loads("\n".join(data_lines))
        except ValueError:
            return None
        return parsed if isinstance(parsed, dict) else None

    # ---- HTTP 小工具（标准库） ----

    def _get_json(self, path, timeout):
        request = urllib.request.Request(self.base + path)
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))

    def _post_json(self, path, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            self.base + path, data=body, headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read().decode("utf-8")
            return json.loads(raw) if raw.strip() else {}

    def _post_status(self, path, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            self.base + path, data=body, headers={"Content-Type": "application/json"}
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                response.read()
                return response.status
        except urllib.error.HTTPError as error:
            return error.code

    def _http_status(self, method, path, payload=None):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
        request = urllib.request.Request(
            self.base + path,
            data=body,
            method=method,
            headers={"Content-Type": "application/json"} if body else {},
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                response.read()
                return response.status
        except urllib.error.HTTPError as error:
            return error.code
