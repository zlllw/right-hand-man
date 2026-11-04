# 任务书：Termux 双入口一体化 Agent 应用开发

> 本文件是交给执行 Agent 的唯一指令来源。所有决策已固化，不要重新评估架构方向；遇到本文未覆盖的问题，先停下汇报，不要自行发挥。


## 一、任务总述

在现有 Termux Android 应用基础上，**不重写、不删除**其终端功能，新增一个原生 Compose 聊天界面作为默认入口，通过底部导航栏和左右滑动与终端页切换。聊天界面对接一个运行在 Termux 环境内的 AI Agent，Agent 可通过 Termux 执行命令、读写文件。用户首次启动时完成 bootstrap 初始化并配置 Agent（API Key 或本地 CLI）。

**交付物**：一个可安装的 APK，安装后默认进入聊天界面，可切换终端界面，终端功能与原 Termux 完全一致。


## 二、硬约束（违反即返工）

1. **不得删除或重构**以下任何代码：`TerminalView`、`TerminalRenderer`、`TerminalEmulator`、`TerminalBuffer`、`TerminalViewClient`、`TerminalSession`、`TermuxSession`、`ShellManager`、`TermuxInstaller`、`termux.c`、`libtermux-bootstrap.so`、会话抽屉、键盘相关代码、终端布局文件与资源。这些代码原样保留，仅允许被 `AndroidView` 包裹。
2. **不得让 Android 侧直接读 Agent 进程的 stdout**。必须经过 Bridge 层，协议见第五节。
3. **不得使用 WebView 做聊天界面**。聊天界面必须是原生 Compose。WebView 方案已评估并否决。
4. **不得在 Compose 中重写 `TerminalView`**。`TerminalView` 只能通过 `AndroidView` 加载原有 XML 布局。
5. **不得修改 Agent 与终端共享的 Termux 运行时**。两者共享同一份 bootstrap、`$PREFIX`、`$HOME`，但作为独立子进程运行（详见 6.3）。
6. **不得跳过阶段 0 的验收**。阶段 0 失败，整个项目停止并汇报，不要进入阶段 1。
7. **不得自行选择 Agent 类型或协议格式**。首次内置 Claude API 与 OpenAI 兼容接口两种，其他通过"自定义"选项留空给用户填。


## 三、项目现状

- 代码库：`termux-app` 的 fork，已包含完整终端功能、bootstrap 安装逻辑、JNI、`termux.c`。
- 已有 bootstrap zip：上游预构建的 aarch64 版本（无 Python）。
- 已有 Agent 后端：用户已有一个可用的 Python/Node Agent，运行在 Termux 内，原为网页版。**该 Agent 的代码不改**，仅通过 Bridge 适配。
- 用户已确认：开源发布，接受 GPLv3。


## 四、总体架构

```
MainActivity (Compose)
├── ChatScreen (Compose) ──HTTP/SSE──> Bridge (Termux 内进程)
│                                      └── spawn ──> Agent 进程 (Termux 内)
└── TerminalScreen (AndroidView) ──bind──> TermuxService (原有)
                                            └── PTY Shell (原有)
```

**三条隔离原则**：

1. Bridge 与 Agent 之间通过 stdin/stdout 通信，Bridge 是唯一"懂"Agent 输出格式的组件。
2. ChatScreen 只认 Bridge 的 HTTP/SSE 协议，不依赖 Agent 的任何代码。
3. Agent 与 PTY Shell 是同一 App 内通过 `fork()` 创建的两个独立子进程（Linux 层面的兄弟进程），互不干扰，但共享文件系统与运行时。


## 五、Bridge 协议（必须严格实现）

**位置**：Termux 内，`$HOME/.bridge/server.py`，监听 `127.0.0.1:<port>`，端口写入 `$HOME/.bridge/port`。

**接口**：

| 方法 | 路径 | 请求 | 响应 |
|---|---|---|---|
| POST | `/chat` | `{"message": "...", "conversationId": "..."}` | `{"requestId": "..."}` |
| GET | `/chat/{requestId}/stream?from=<eventId>` | — | `text/event-stream` |
| GET | `/files/{name}` | — | 文件字节流 |
| POST | `/files` | multipart | `{"path": "..."}` |
| GET | `/health` | — | `{"status": "ok"}` |

**SSE 事件类型**（`data` 为 JSON）：

```
event: text        data: {"delta": "..."}
event: tool_call   data: {"name": "...", "args": {...}}
event: tool_result data: {"name": "...", "output": "..."}
event: file        data: {"path": "...", "mime": "...", "size": N, "url": "/files/..."}
event: error       data: {"message": "..."}
event: done        data: {"finishReason": "stop"}
```

**重连**：每个事件带单调递增 `id`。Bridge 在内存中维护最近 500 个事件的环形缓冲区。客户端带 `from=<lastEventId>` 重连时，Bridge 从该 id 之后继续推送。

**Agent 崩溃**：Bridge 检测子进程退出，指数退避重启（最多 3 次），期间通过 `event: error` 通知客户端。


## 六、分阶段任务

### 阶段 0：Bootstrap 定制（Go/No-Go）

**输入**：上游 aarch64 bootstrap `.deb` 包清单（含 `bash`、`dpkg`、`apt`、`python3`、`python-pip`）。

**产出**：`bootstrap-aarch64.zip`，包名替换为目标包名，可直接嵌入 APK 的 `libtermux-bootstrap.so`。

**必须做的路径重写**（三层，缺一不可）：

1. **文本文件**：`sed` 替换 `/data/data/com.termux/` → `/data/data/<目标包名>/`。约 215 个文件。
2. **ELF 动态链接器**：`patchelf --set-rpath` 重写 `DT_RUNPATH`。约 307 个二进制。**只做 sed 不做 patchelf 会导致 Python 无法启动。**
3. **符号链接**：重写绝对路径目标，写入 `SYMLINKS.txt`。

**运行时环境变量**（在 `TerminalSession` spawn 时注入）：`PREFIX`、`HOME`、`TERMUX_APP_PACKAGE`、`GIT_EXEC_PATH`、`SSL_CERT_FILE`、`TERMINFO`、`LOCPATH`。

**验收命令**（在解压后的目录上执行，不需要装 APK）：

```bash
export PREFIX=/tmp/test-prefix
export PATH=$PREFIX/bin:$PATH
export SSL_CERT_FILE=$PREFIX/etc/tls/cert.pem
python3 --version
python3 -c "import ssl; print(ssl.get_default_verify_paths())"
pip list
dpkg -l
```

**失败排查顺序**：`patchelf` 的 `DT_RUNPATH` → `SSL_CERT_FILE` / `LOCPATH` → `$PREFIX`。全部失败则回退到 `termux-generator` 源码构建路线，并汇报。

**禁止事项**：不要在阶段 0 未通过时进入阶段 1。不要在阶段 0 中改动 Termux 原有 Java 代码。

---

### 阶段 1：MainActivity + TerminalScreen

**产出**：

- `MainActivity`（Compose）：`Scaffold` + `NavigationBar`（两个 tab：对话 / 终端）+ `HorizontalPager`。
- `TerminalScreen`（Compose）：`AndroidView` inflate 原 `TermuxActivity` 的布局，原 `TermuxActivity.onCreate` 的初始化逻辑搬入 `TerminalScreen` 的 `factory` 回调。
- `AndroidManifest.xml`：launcher 改为 `MainActivity`，`TermuxActivity` 保留但不再作为入口。

**交互约束**：

- 默认选中"对话"tab。
- 终端页 `TerminalView` 获得焦点时，`HorizontalPager` 的 `userScrollEnabled = false`，失焦时恢复。
- 键盘弹出时终端页正确 `adjustResize`，聊天页暂为占位（阶段 3 实现）。

**验收**（真机增量安装，见第八节测试策略）：

- 终端功能与原生 Termux 完全一致：键盘、复制粘贴、会话抽屉、旋转屏幕、多会话切换。
- 底部导航切换正常，左右滑动切换正常（非终端焦点时）。
- 冷启动默认进入对话 tab（此时对话页是占位文案）。

**禁止事项**：不要在 `TerminalScreen` 中修改 `TerminalView` 的任何实现。所有适配通过 `AndroidView` 的 `factory` / `update` 和 `ViewCompat` 完成。

---

### 阶段 2：Bridge 最小版本

**产出**：

- `$HOME/.bridge/server.py`：实现 `POST /chat`、`GET /chat/{id}/stream`、`GET /health`。
- 支持一种 Agent 适配器（先实现 API 直连模式，Claude API）。
- `TermuxService` 修改：bootstrap 就绪后 spawn Bridge，端口写 `$HOME/.bridge/port`，维护 `AgentServerState`（`Stopped` / `Starting` / `Running(port)` / `Crashed(msg)`），通过 Binder 暴露给 UI。

**验收**（在电脑上执行，不需要 Android）：

```bash
# 启动 Bridge
python3 server.py --port 8765

# 发消息
REQ_ID=$(curl -s -X POST localhost:8765/chat -d '{"message":"echo hello"}' | jq -r .requestId)

# 收流
curl -N localhost:8765/chat/$REQ_ID/stream
# 期望看到 event: text / event: done，内容包含 "hello"

# 重连
curl -N "localhost:8765/chat/$REQ_ID/stream?from=2"
# 期望从第 2 个事件后继续，不重放
```

**禁止事项**：不要在 Bridge 中硬编码任何 Android 路径。Bridge 只认 Termux 文件系统路径（`$HOME`、`$PREFIX`）。

---

### 阶段 3：ChatScreen

**产出**：

- `ChatViewModel` + `ChatRepository` 接口 + `HttpChatRepository`（OkHttp + `okhttp-sse`）。
- `ChatScreen`：消息列表（`LazyColumn`）、输入栏、流式渲染、加载中/失败状态。
- `MessageBubble`：文本消息渲染。

**验收**（真机 + 电脑 Bridge 或真机 Bridge）：

- 发送消息，收到流式回复，逐字显示。
- 断网后重连，消息不重复、不丢失（依靠 `lastEventId` 重连）。
- Bridge 未就绪时显示"正在连接…"，就绪后自动加载。

**禁止事项**：不要在 `ChatViewModel` 中直接依赖 `TermuxService` 或 Bridge 的进程句柄。一切通过 `ChatRepository` 接口。

---

### 阶段 4：工具调用与文件

**产出**：

- Bridge 增加 `tool_call` / `tool_result` / `file` 事件。
- `ChatScreen` 增加 `ToolCallCard`、`FileCard`、上传按钮。
- Bridge 实现 `GET /files/{name}`、`POST /files`。
- Android 侧实现文件下载（OkHttp → 缓存目录）和上传（multipart）。

**验收**：

- 用户发送"列出家目录"，Agent 调用工具执行 `ls ~`，`ToolCallCard` 显示命令与输出。
- Agent 生成一张图片，`FileCard` 显示缩略图，点击可预览、可分享。
- 用户上传一个文件，Agent 能通过 Termux 路径读取到。

---

### 阶段 5：OnboardingActivity

**产出**：

- `OnboardingActivity`（Compose）：三步（选 Agent → 填参数 → 测试连接）。
- `AgentConfigStore`：写 `~/.agent/config.json` + `EncryptedSharedPreferences`。
- 首次启动检测：无 `~/.agent/config.json` 时跳转 `OnboardingActivity`。

**验收**：

- 全新安装（`adb shell pm clear <pkg>` 后重装），30 秒内完成引导，能正常对话。
- 填错 API Key 时，测试连接给出明确错误，可跳过。
- 再次启动不重复出现引导。

---

### 阶段 6：本地 CLI 模式（可选，视工期）

**产出**：Bridge 增加 `CliAgentAdapter`，通过 `spawn` 启动用户已安装的 CLI（如 `claude`、`codex`），接管 stdin/stdout。

**验收**：用户在终端里 `npm install -g` 某个 CLI，在引导页选择"本地 CLI"并填启动命令，ChatScreen 可正常对话。

---

### 阶段 7：开源合规与发布

**产出**：

- `LICENSE`（GPLv3）、`NOTICE`、各模块许可证标注。
- `README`：从源码复现 bootstrap zip 与 APK 的完整步骤。
- 固定上游 `.deb` 快照版本。

**验收**：第三方按 README 可从零构建出 APK。


## 七、关键技术细节

### 7.1 进程模型（回应常见误解）

Termux 是单一 Android 应用，但会在其内部通过 `fork()` + `exec()` 创建多个独立子进程：

- 每个 `TerminalSession` 对应一个独立的 `bash` 子进程。
- Agent 是另一个独立的子进程，与 PTY Shell 平级。

"隔离"指的就是这个 Linux 层面的子进程隔离，不是 Android 应用沙箱隔离。共享文件系统、共享 `$PREFIX`，但 PID、内存、信号互不干扰。

> ### 7.2 当前选 Compose，WebView 保留为未来分支
>
> **当前阶段用 Compose**，理由：键盘遮挡、与终端手势冲突、与 Compose 导航栏的整合成本，在原生方案里更可控；用户当前明确要求原生安卓。
>
> **WebView 不作为否决项**。用户可能在后期开独立分支做 WebView 版本（用于复用现有网页版 UI、加快迭代、或作为 Compose 遇到不可逾越障碍时的后备）。因此：
> - 不得让 Bridge 协议、`ChatRepository`、`ChatEvent` 混入任何 Compose 类型。它们是纯 Kotlin / JSON，任何前端都能消费。
> - "对话"tab 的入口保持为一个 Composable 函数（如 `ChatScreen()`），替换实现时只动这一个文件。
> - Bridge 的 `/chat`、`/chat/{id}/stream`、`/files` 接口和 SSE 事件格式，**在 WebView 分支中必须原样可用**，不允许为 Compose 做特殊优化（例如依赖 Binder、依赖 Android 生命周期）。
> - 如果 Compose 路线在后期遇到阻塞（如某类工具调用卡片无法表达、流式渲染性能问题），**停下汇报**，由用户决定是否切换分支，**不要自行改为 WebView 实现**。


### 7.3 `TerminalView` 集成要点

- `TerminalView` 是重量级自定义 View，依赖 `WindowInsets`、焦点、生命周期。
- 通过 `AndroidView` 的 `factory` inflate 原布局，在 `factory` 内通过 `ViewCompat.setOnApplyWindowInsetsListener` 处理键盘高度。
- `HorizontalPager` 与 `TerminalView` 手势冲突：`TerminalView` 获得焦点时禁用 pager 滑动。

### 7.4 端口发现

Bridge 启动后把实际端口写入 `$HOME/.bridge/port`。`ChatRepository` 初始化时读该文件；若读不到，观察 `AgentServerState` 直到 `Running`。


## 八、测试策略（严格执行，避免每次装完整包）

| 层 | 测试方式 | 频率 |
|---|---|---|
| Bridge | 电脑上 `python3 server.py` + `curl` + `pytest` | 每次 commit |
| Compose UI | `FakeChatRepository` + `@Preview` + Compose UI Test | 每次 commit |
| `HttpChatRepository` | `MockWebServer` 或本地 Bridge | 每次 commit |
| Bootstrap zip | 电脑上解压 + 执行验收命令（阶段 0 命令） | 每次改 bootstrap |
| Android 集成 | 真机 `adb install -r` 增量包 | 改 TerminalView/Service/导航时 |
| 完整流程 | `adb shell pm clear` + 重装 | 发版前 |

**关键**：`BuildConfig.DEBUG` 下加调试开关，跳过 bootstrap 初始化，手动 `adb reverse tcp:8765 tcp:8765` 连电脑上的 Bridge。这样 UI 调试不需要真机跑 Bridge。


## 九、风险与回退

| 风险 | 回退方案 |
|---|---|
| 阶段 0 Python 无法启动 | 按 6.1 排查顺序；全失败则回退 `termux-generator` 源码构建并汇报 |
| `TerminalView` 在 `AndroidView` 中键盘异常 | 用 1px 隐藏 `EditText` 兜底；仍不行则汇报，不要自行重构终端 |
| SSE 断线丢消息 | 检查 Bridge 环形缓冲区与 `from` 参数；不要在客户端做"去重"启发式 |
| Agent 崩溃未被察觉 | 检查 Bridge 的子进程 `waitpid` 监听；不要在 Android 侧轮询进程 |
| APK 体积过大 | 仅 arm64；不要为减体积删功能，先汇报 |

## 十、汇报要求

每个阶段完成后汇报：**产出文件清单、验收命令输出、未解决问题**。遇到以下情况必须停下汇报，不得自行决定：

- 阶段 0 验收失败且排查无果。
- 需要修改"硬约束"中列出的任何代码。
- 需要变更 Bridge 协议。
- 需要引入新的第三方依赖（除 OkHttp、`okhttp-sse`、Compose 官方库外）。




> 3. **当前阶段用原生 Compose 实现聊天界面，不做 WebView**。但架构上必须保证聊天界面**可整体替换**：
>    - `ChatRepository` 接口、`ChatEvent` 数据模型、Bridge 的 HTTP/SSE 协议，**均不得依赖 Compose**。
>    - `MainActivity` 的"对话"tab 内容必须是一个可替换的 Composable（如 `ChatScreen()`），将来 WebView 分支只需替换这一个 Composable 为一个 `AndroidView` 加载 `http://127.0.0.1:<port>`，**其余代码零改动**。
>    - Bridge 协议（第五节）必须与 UI 实现完全无关。WebView 版本直接消费同一协议，Bridge 不感知前端是 Compose 还是 WebView。

## 修改 2：第七节 7.2



## 修改 3：第八节测试策略末尾补一段

在"关键：`BuildConfig.DEBUG` 下加调试开关…"这一段之后追加：

> **WebView 分支预留的测试保证**：Bridge 的验收命令（阶段 2 的 `curl` 测试）本身就是一个纯 HTTP 客户端视角，等价于 WebView 将来会做的事。只要 Bridge 的 `curl` 验收全绿，就意味着"任意 HTTP/SSE 客户端"都能对接，包括 WebView 分支。这条验收同时也是 WebView 分支的回归测试。

## 第十节汇报要求追加一条

在"遇到以下情况必须停下汇报"列表中增加：

> - Compose 路线遇到无法用原生组件合理表达的需求，且用户可能需要切换到 WebView 分支时。

---

其余内容（阶段划分、Bridge 协议、进程模型、bootstrap 验收、`TerminalView` 集成要点）**全部不变**。执行 Agent 仍按原任务书推进，只是"对话"tab 的那一个 Composable 被视为可替换插槽。