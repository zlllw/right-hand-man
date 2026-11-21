"""Bridge 与 Agent 之间的适配器接口（契约 B 的 B6）。

Bridge 是**唯一**懂 Agent 输出格式的组件；换 Agent 后端只换适配器，
App 侧的 HTTP/SSE 协议（契约 B 的 B3、B4）一行不改。
"""


class AgentAdapter(object):
    """适配器契约。

    * ``start()`` / ``stop()`` 负责上游生命周期（opencode 是 spawn ``opencode serve``）；
    * ``run()`` 同步执行一次生成，过程中通过 ``emit(name, data)`` 上报事件，
      且**必须**在结束前 ``emit("done", {...})``（Bridge 也会兜底补一条）。
    """

    name = "base"

    def start(self):
        return

    def stop(self):
        return

    def health(self):
        """上游是否健康。Bridge 的 ``/health`` 不依赖它，仅用于诊断。"""
        return True

    def run(self, conversation_id, message, emit):
        raise NotImplementedError

    def list_conversations(self):
        """B9 扩展：``[{conversationId, title, updatedAt}]``，按 updatedAt 降序。"""
        return []

    def conversation_messages(self, conversation_id, limit):
        """B9 扩展：归一化历史 ``[{role, text, ts}]``，最多 limit 条（取最新）。"""
        return []

    def rename_conversation(self, conversation_id, title):
        """B9 扩展：重命名会话；不支持或不存在时返回 False。"""
        return False

    def delete_conversation(self, conversation_id):
        """B9 扩展：删除会话；不支持或不存在时返回 False。"""
        return False

    def abort(self, conversation_id):
        """中断当前生成；不支持时返回 False。"""
        return False