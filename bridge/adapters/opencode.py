"""opencode 适配器 —— 阶段 2 的**真机部分**，未在桌面上验证。

契约 B 的 B6 明确要求：事件映射必须**以运行时 ``/doc`` 暴露的 OpenAPI 3.1 spec 为准**，
禁止凭经验硬编码事件名。因此在没有真机与 opencode 实例的情况下，
这里**故意不实现**任何会凭猜测写死事件名的逻辑：

* 桌面与 CI 请用 ``--adapter fake``（契约 B 的 B10 第一段）；
* 真机上的实现步骤：``start()`` spawn ``opencode serve --hostname 127.0.0.1 --port <P_oc>``
  并轮询 ``GET /global/health`` 直到 ``healthy: true``，随后 Bridge 写端口文件（B2）；
  ``run()`` 用 ``POST /session`` + ``POST /session/:id/prompt_async`` 触发，
  订阅 ``GET /event`` 并按 ``sessionID`` 过滤后归一化为契约 B 的 B4 事件。

未经真机实测就填上映射表，等于把猜测写进契约，宁可不做。
"""

from .base import AgentAdapter


class AdapterNotAvailable(RuntimeError):
    """在缺少真机 / opencode 的环境里被调用。"""


_MESSAGE = (
    "opencode 适配器只能在真机 Termux 内运行：桌面与 CI 请用 --adapter fake。"
    "事件映射需先按 opencode 的 /doc（OpenAPI 3.1）核对后再实现，"
    "见 docs/契约B-Bridge协议.md 的 B6 与 B11、docs/install-notes.md。"
)


class OpencodeAgentAdapter(AgentAdapter):
    name = "opencode"

    def __init__(self, port, executable="opencode", hostname="127.0.0.1", workdir=None):
        self.port = port
        self.executable = executable
        self.hostname = hostname
        self.workdir = workdir
        self._process = None
        self._session_ids = {}

    def start(self):
        raise AdapterNotAvailable(_MESSAGE)

    def run(self, conversation_id, message, emit):
        raise AdapterNotAvailable(_MESSAGE)