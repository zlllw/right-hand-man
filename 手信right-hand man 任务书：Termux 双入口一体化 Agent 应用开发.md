# 任务书：手信（right-hand man）—— Termux 双入口一体化 Agent 应用开发

> 本文件是交给执行 Agent 的唯一指令来源。所有决策已固化，不要重新评估架构方向；遇到本文未覆盖的问题，先停下汇报，不要自行发挥。
>
> **版本：v2（单一权威版本）**。v1 是「正文 + 末尾补丁（修改 1/2/3）」的形态，本版已把补丁合并进正文、修正交叉引用、统一措辞冲突。差异见文末「变更记录」。**执行时只读本文件与 `docs/` 下的两份契约，不要再去读 v1 的补丁段落。**

## 一、任务总述

在现有 Termux Android 应用基础上，**不重写、不删除**其终端功能，新增一个原生 Compose 聊天界面作为默认入口，通过底部导航栏和左右滑动与终端页切换。聊天界面对接一个运行在 Termux 环境内的 AI Agent（**默认 `opencode`**），Agent 可执行命令、读写文件。

**交付物**：一个可安装的 APK，安装后默认进入聊天界面，可切换终端界面，终端功能与原 Termux 完全一致。

## 二、硬约束（违反即返工）

1. **不得删除或重构**以下任何代码：`TerminalView`、`TerminalRenderer`、`TerminalEmulator`、`TerminalBuffer`、`TerminalViewClient`、`TerminalSession`、`TermuxSession`、`ShellManager`、`TermuxInstaller`、`termux.c`、`libtermux-bootstrap.so`、会话抽屉、键盘相关代码、终端布局文件与资源。这些代码原样保留，仅允许被 `AndroidView` 包裹。
2. **不得让 Android 侧直接读 Agent 进程的 stdout**。必须经过 Bridge 层，协议见第五节与 `docs/契约B-Bridge协议.md`。
3. **聊天界面必须可整体替换，不得把 UI 实现耦合进架构**。当前用原生 Compose，**不做 WebView**；但 `ChatRepository`、`ChatEvent`、Bridge 的 HTTP/SSE 协议**均不得依赖 Compose**，"对话" tab 的内容必须是一个可替换的 Composable（如 `ChatScreen()`），将来若开 WebView 分支只需替换这一个 Composable 为一个 `AndroidView`，其余代码零改动。Bridge 不感知前端是 Compose 还是 WebView。
4. **不得在 Compose 中重写 `TerminalView`**。`TerminalView` 只能通过 `AndroidView` 加载原有 XML 布局。
5. **不得修改 Agent 与终端共享的 Termux 运行时**。两者共享同一份 bootstrap、`$PREFIX`、`$HOME`，但作为独立子进程运行（详见 7.1）。
6. **不得跳过阶段 0 的验收**。阶段 0 失败，整个项目停止并汇报，不要进入阶段 1。
7. **不得自行更换 Agent 后端或协议格式**。默认后端是 `opencode`（通过其 server 接口适配）。用户若要换后端（例如改用 Claude CLI），由用户显式决定后由 Bridge 侧新增适配器支持，执行 Agent 不得自行替换。
8. **改包名必须整清单执行**：Java/资源侧的 6 处改动（见 `docs/契约A-阶段0-改包名与bootstrap.md` A1）缺一处即返工；只重写 bootstrap 而不改 Java 侧，会导致运行时前缀与 bootstrap 实际路径不一致。
9. **不得让自定义 bootstrap 被构建覆盖**：必须同步处理 `app/build.gradle` 的 bootstrap 下载校验（见契约 A 的 A4），否则本地定制 zip 会被删除并替换为上游版本。
10. **阶段 0 必须拆成 0a / 0b / 0c 三段**，按序执行，不得合并成一次性动作。

## 三、项目现状（2026-11-04 实测，引用格式 `路径:行号`）

- 代码库：`termux-app` 的 fork，已包含完整终端功能、bootstrap 安装逻辑、JNI、`termux.c`。仓库已托管为 `github.com/zlllw/right-hand-man`（私有）。
- **纯 Java 工程**：`app/src/main/java` 下 33 个 `.java`、**0 个 `.kt`**；所有 `.gradle` 文件中无任何 Kotlin 配置。→ **Kotlin 与 Compose 工具链是从零引入，属于阶段 1 的前置工程量。**
- 构建栈：AGP `8.13.2`（`build.gradle:7`）、Gradle `9.2.1`（`gradle/wrapper/gradle-wrapper.properties`）、`compileSdk 36` / `targetSdk 28` / `minSdk 21` / `ndkVersion 29.0.14206865`（`gradle.properties:17–21`）。
- **`app/build.gradle` 中没有 `applicationId` 声明**，AGP 会用 `namespace "com.termux"`；改包名必须显式新增。
- `TERMUX_PACKAGE_NAME = "com.termux"` 硬编码于 `termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java:352`，`$PREFIX` / `$HOME` 由它推导（`:581/:588/:595/:657`）。同文件 `:296–326` 是官方给出的改包名清单。
- 写死 `/data/data/com.termux` 的位置**只有** `TermuxConstants.java` 与 `termux-shared/src/main/res/values/strings.xml:12`。
- bootstrap 由 Gradle 从上游下载并按 hash 校验（`app/build.gradle:167–232`），本地 hash 不符会被删除重下；`clean` 会删除所有 `bootstrap-*.zip`（`:213–214`）。zip 通过 `.incbin` 编入 `libtermux-bootstrap.so`（`app/src/main/cpp/termux-bootstrap-zip.S`）。
- `TermuxInstaller` **不做任何包名替换**，只按 `SYMLINKS.txt` 建链接，缺该文件直接抛错（`TermuxInstaller.java:163`、`:208`）。
- Agent 后端：**默认 `opencode`**，运行在 Termux 内；`Claude Code` 与 `Hermes Agent` 作为可替换后端。**三者均可在 Termux 上安装运行**，各自的安装路径、已知坑与驱动接口见契约 B 的 B11。
- 用户已确认：开源发布，接受 GPLv3；仓库私有托管，后续公开。

## 四、总体架构

```
MainActivity (Compose)
├── ChatScreen (Compose) ──HTTP/SSE──> Bridge (Termux 内 Python 进程, $HOME/.bridge/server.py)
│                                      └── HTTP 适配 ──> opencode serve (Termux 内, 127.0.0.1:<P_oc>)
└── TerminalScreen (AndroidView) ──bind──> TermuxService (原有)
                                            └── PTY Shell (原有)
```

**三条隔离原则**：

1. Bridge 与 Agent 之间 Bridge 是唯一"懂"Agent 输出格式的组件；Agent 换版本、换后端只改 Bridge。
2. ChatScreen 只认 Bridge 的 HTTP/SSE 协议，不依赖 Agent 的任何代码。
3. Agent 与 PTY Shell 是同一 App 内独立子进程（Linux 层面兄弟进程），互不干扰，但共享文件系统与运行时。

## 五、Bridge 协议

**完整定义、语义边界、opencode 映射、鉴权决策与安全要求全部以 `docs/契约B-Bridge协议.md` 为准**，本节只保留接口摘要，避免两处描述漂移：

| 方法 | 路径 | 请求 | 响应 |
|---|---|---|---|
| POST | `/chat` | `{"message": "...", "conversationId": "..."}` | `{"requestId": "..."}` |
| GET | `/chat/{requestId}/stream?from=<eventId>` | — | `text/event-stream` |
| GET | `/files/{name}` | — | 文件字节流 |
| POST | `/files` | multipart | `{"path": "..."}` |
| GET | `/health` | — | `{"status": "ok"}` |

SSE 事件类型：`text` / `tool_call` / `tool_result` / `file` / `error` / `done`。

**位置**：`$HOME/.bridge/server.py`，监听 `127.0.0.1:<P_app>`，端口写入 `$HOME/.bridge/port`。

> **协议变更必须汇报**：`docs/契约B` 的 B9 里那两条历史接口（`GET /conversations`、`GET /conversations/{id}/messages`）属于对本协议的扩展，**需用户确认后**才能实现。

## 六、分阶段任务

**推荐执行顺序：0 → 2 → 3（Debug 页）→ 1 → 4 → 5 → 6 → 7**，理由：阶段 1 依赖真机且 `TerminalView` 集成风险最高，而阶段 2/3 可以在电脑上闭环验证。阶段 1 与阶段 2/3 之间无代码依赖，也可并行。

---

### 阶段 0：Bootstrap 定制（Go/No-Go，拆 0a/0b/0c）

**全部细则见 `docs/契约A-阶段0-改包名与bootstrap.md`**。要点：

- **0a**：Java/资源侧 6 处改动（`applicationId`、`manifestPlaceholders`、`TermuxConstants`、两处 `strings.xml`、`shortcuts.xml` 的 `targetPackage`）；同时跑 A3 的一致性断言。
- **0b**：生成含 `python3` + `python-pip` 及依赖闭包的 aarch64 bootstrap，三层路径重写（文本 ~215 个 / patchelf ~307 个 / `SYMLINKS.txt`），产物精确落在 `app/src/main/cpp/bootstrap-aarch64.zip`，并同步修改 `app/build.gradle` 的下载校验任务，禁止其他架构静默沿用未定制 bootstrap。
- **0c**：分两段——**0c-静态**（桌面只读断言：无 `com.termux` 残留、`SYMLINKS.txt` 合法性、依赖闭包齐全、`readelf` 记录 interpreter 与 `DT_RUNPATH`；可进 CI）+ **0c-动态**（**必须真机**：装 APK 后 `$PREFIX`、`python3 --version`、`import ssl`、`pip list`）。桌面无法执行 aarch64 的 Android/bionic 二进制，**不得用桌面脚本替代动态验收**（论证见契约 C 的 C5）。

**禁止事项**：`0c` 未通过不得进入阶段 1；阶段 0 不得改动硬约束名单中的终端代码；不得改 `namespace` 与 `packageVariant`。

---

### 阶段 1：Kotlin/Compose 工具链 + MainActivity + TerminalScreen

**1a 工具链前置（本阶段第一步，不可省）**

- 引入 Kotlin 与 Compose：`org.jetbrains.kotlin.android` + Compose 编译器插件（`org.jetbrains.kotlin.plugin.compose`）+ Compose BOM + JVM 17 toolchain，版本需与 AGP `8.13.2` 匹配。
- 处理与现有纯 Java / AppCompat / XML 主题 / `viewpager` / `drawerlayout` 的共存；不得为了让 Compose 编译通过而改动硬约束名单中的代码。

**1b 界面与入口**

- `MainActivity`（Compose）：`Scaffold` + `NavigationBar`（对话 / 终端两个 tab）+ `HorizontalPager`。
- `TerminalScreen`（Compose）：`AndroidView` inflate 原 `TermuxActivity` 的布局。
- **推荐落点**：`TermuxActivity` **不在硬约束禁改名单内**。与其把 `onCreate` 的初始化逻辑复制进 `AndroidView.factory`（易漏），不如把它的根视图（`TermuxActivityRootView`）抽成一个可复用宿主，供 `MainActivity` 的终端页与原 `TermuxActivity` 共用；会话抽屉与键盘相关代码必须原样保留。
- `AndroidManifest.xml`：launcher 改为 `MainActivity`，`TermuxActivity` 保留但不再作为入口；`shortcuts.xml` 里指向的 Activity 名需同步确认。

**交互约束**：

- 默认选中"对话" tab。
- 终端页 `TerminalView` 获得焦点时 `HorizontalPager.userScrollEnabled = false`，失焦恢复。
- `HorizontalPager` 的预加载策略需显式设置（避免终端页在后台被反复创建）。
- 键盘弹出时终端页正确 `adjustResize`。

**验收**（真机增量安装）：

- 终端功能与原生 Termux 完全一致：键盘、复制粘贴、会话抽屉、旋转屏幕、多会话切换。
- 底部导航与左右滑动切换正常（非终端焦点时）。
- 冷启动默认进入对话 tab（此时对话页可为占位文案）。

**禁止事项**：不要在 `TerminalScreen` 中修改 `TerminalView` 的任何实现；所有适配通过 `AndroidView` 的 `factory` / `update` 和 `ViewCompat` 完成。

---

### 阶段 2：Bridge 最小版本 + opencode 适配

**先决条件**：在真机上装好默认后端 `opencode`（安装路径与已知坑见契约 B 的 B11），并确认三件事——能稳定启动（含**第二次**启动）、存在可被 Bridge 驱动的非交互接口、首次交互式步骤已被阶段 5 的 Onboarding 覆盖。验收命令：`opencode serve --hostname 127.0.0.1 --port <free>` 且 `curl /global/health` 返回 `healthy: true`。**若某个后端在你的设备上确实装不上，换用其余两个之一并汇报，不得自行改架构方向。**

**产出**：

- `$HOME/.bridge/server.py`：实现 `POST /chat`、`GET /chat/{id}/stream`、`GET /health`；按契约 B 的 B2 管理 opencode 子进程与端口。
- `OpencodeAgentAdapter`：`conversationId` ↔ opencode `sessionID` 映射、`POST /session`、`POST /session/:id/prompt_async`、订阅 `GET /event` 并按 sessionID 过滤归一化；映射表以运行时拉取的 `/doc`（OpenAPI 3.1）为准。
- `TermuxService` 修改：bootstrap 就绪后 spawn Bridge，维护 `AgentServerState`（`Stopped` / `Starting` / `Running(port)` / `Crashed(msg)`），通过 Binder 暴露给 UI。
- **`DebugChatActivity`（仅 `BuildConfig.DEBUG`，本阶段一并搭好）**：一个承载 `ChatScreen` 的独立 Activity，用于 `adb reverse` 连电脑 Bridge，使阶段 3 不必等阶段 1 完成。

**验收（电脑上，不需要 Android）**：见契约 B 的 B10 命令组，必须逐条通过（含 `from=` 重连不重放）。

**禁止事项**：不要在 Bridge 中硬编码任何 Android 路径。Bridge 只认 Termux 文件系统路径（`$HOME`、`$PREFIX`）。

---

### 阶段 3：ChatScreen

**产出**：

- `ChatViewModel` + `ChatRepository` 接口 + `HttpChatRepository`（OkHttp + `okhttp-sse`）。
- `ChatScreen`：消息列表（`LazyColumn`）、输入栏、流式渲染、加载中 / 失败状态。
- `MessageBubble`：文本消息渲染。

**验收**（`DebugChatActivity` + 电脑 Bridge，或真机 Bridge）：

- 发送消息，收到流式回复，逐字显示。
- 断网后重连，消息不重复、不丢失（依靠 `lastEventId` 重连语义，见契约 B 的 B5）。
- Bridge 未就绪时显示"正在连接…"，就绪后自动加载。

**禁止事项**：不要在 `ChatViewModel` 中直接依赖 `TermuxService` 或 Bridge 的进程句柄。一切通过 `ChatRepository` 接口。

---

### 阶段 4：工具调用与文件

**产出**：

- Bridge 增加 `tool_call` / `tool_result` / `file` 事件的归一化，并实现 `GET /files/{name}`、`POST /files`（含契约 B 的 B8 安全要求）。
- `ChatScreen` 增加 `ToolCallCard`、`FileCard`、上传按钮。
- Android 侧实现文件下载（OkHttp → 缓存目录）和上传（multipart）。

**验收**：

- 用户发送"列出家目录"，Agent 调用工具执行 `ls ~`，`ToolCallCard` 显示命令与输出。
- Agent 生成一张图片，`FileCard` 显示缩略图，点击可预览、可分享。
- 用户上传一个文件，Agent 能通过 Termux 路径读取到。

---

### 阶段 5：OnboardingActivity（首次启动就绪检查）

**产出**：

- `OnboardingActivity`（Compose）：三步 —— ①环境就绪检查（bootstrap / `$PREFIX` / `python3` / opencode 可执行）→ ②Agent 配置（模型与 provider；opencode 侧的配置入口，例如引导用户完成 provider 授权，或写入 `opencode.json`）→ ③连通性测试（`/health` + 一次最小 prompt）。
- `AgentConfigStore`：写 opencode 配置与 App 侧偏好；敏感项（如 provider key）落 `EncryptedSharedPreferences`，文件权限 `600`。
- 首次启动检测：环境未就绪或 Agent 未配置时进入 `OnboardingActivity`。
- **换后端不在本阶段实现**：用户若想换成 Claude CLI 等，走阶段 6。

**验收**：

- 全新安装（`adb shell pm clear <pkg>` 后重装），30 秒内完成引导，能正常对话。
- 配置错误时测试连接给出明确错误（区分"网络不可达"与"凭据错误"），可跳过。
- 再次启动不重复出现引导。

---

### 阶段 6：切换到其他后端（Claude Code / Hermes）

**产出**：Bridge 增加 `CliAgentAdapter`（`spawn` CLI 并接管 stdin/stdout，或走非交互调用），至少打通 `claude -p "<prompt>"` 与 `hermes` 两条路径；后端选择在配置里显式声明。**Bridge 对 App 的协议完全不变**（契约 B）。

**验收**：用户在引导页显式选择后端（`opencode` / `claude` / `hermes` / 自定义命令），ChatScreen 均可正常对话；切回 `opencode` 无需改 App 代码。

**依据**：三种后端的安装路径与驱动接口见契约 B 的 B11。

---

### 阶段 7：开源合规与发布

**产出**：

- `LICENSE`（GPLv3）、`NOTICE`、各模块许可证标注。
- `README`：从源码复现 bootstrap zip 与 APK 的完整步骤。
- 固定上游 `.deb` 快照版本。

**验收**：第三方按 README 可从零构建出 APK。

## 七、关键技术细节

### 7.1 进程模型

Termux 是单一 Android 应用，但会在其内部通过 `fork()` + `exec()` 创建多个独立子进程：

- 每个 `TerminalSession` 对应一个独立的 `bash` 子进程。
- Agent（opencode）是另一个独立子进程，与 PTY Shell 平级；Bridge 又是它前面的一个独立子进程。

"隔离"指的就是这个 Linux 层面的子进程隔离，不是 Android 应用沙箱隔离。共享文件系统、共享 `$PREFIX`，但 PID、内存、信号互不干扰。

### 7.2 前端实现路线：Compose 为主，界面可整体替换

**当前阶段用原生 Compose**，理由：键盘遮挡、与终端手势冲突、与 Compose 导航栏的整合成本，在原生方案里更可控；用户当前明确要求原生安卓。

**WebView 不是否决项，只是当前不做**。用户可能在后期开独立分支做 WebView 版本（复用现有网页版 UI、加快迭代，或作为 Compose 遇到不可逾越障碍时的后备）。因此：

- `ChatRepository` 接口、`ChatEvent` 数据模型、Bridge 的 HTTP/SSE 协议，**均不得依赖 Compose**；它们是纯 Kotlin / JSON，任何前端都能消费。
- `MainActivity` 的"对话" tab 内容必须是一个可替换的 Composable（如 `ChatScreen()`）；将来 WebView 分支只需把它替换为一个 `AndroidView` 加载 `http://127.0.0.1:<P_app>`，**其余代码零改动**。
- Bridge 协议必须与 UI 实现完全无关：不允许为 Compose 做特殊优化（例如依赖 Binder、依赖 Android 生命周期）。
- 如果 Compose 路线遇到阻塞（如某类工具调用卡片无法表达、流式渲染性能问题），**停下汇报**，由用户决定是否切换分支，**不要自行改为 WebView 实现**。

### 7.3 `TerminalView` 集成要点

- `TerminalView` 是重量级自定义 View，依赖 `WindowInsets`、焦点、生命周期。
- 通过 `AndroidView` 的 `factory` inflate 原布局，在 `factory` 内通过 `ViewCompat.setOnApplyWindowInsetsListener` 处理键盘高度。
- `HorizontalPager` 与 `TerminalView` 手势冲突：`TerminalView` 获得焦点时禁用 pager 滑动。
- 优先复用 `TermuxActivity` 的根视图宿主（见阶段 1 的推荐落点），不要手工复制其初始化逻辑。

### 7.4 端口发现

Bridge 启动后把自己的端口写入 `$HOME/.bridge/port`。`ChatRepository` 初始化时读该文件；若读不到，观察 `AgentServerState` 直到 `Running`。**opencode 的端口不出现在 App 侧**，由 Bridge 独占管理。

### 7.5 opencode 适配

以运行时 `/doc` 暴露的 OpenAPI 3.1 spec 为准生成映射表，禁止凭经验硬编码事件名。默认端口 `4096`、默认 host `127.0.0.1`；由 Bridge 显式传入 `--port`。opencode 的会话由它自己持久化（SQLite），Bridge 不另做落盘。

## 八、测试策略与 CI

**完整测试方案（分层、工具选型与依赖预算、Fake 与契约测试、跨语言 fixture 共享、各阶段放行条件、真机手工对拍清单）见 `docs/契约C-测试与CI.md`。** 本节只保留三条不可让步的原则与放行条件摘要。

1. **协议与逻辑层必须能在桌面 JVM 上完整验证**；UI 测试（Compose，或将来的 WebView）**不得成为协议正确性的唯一保障**。协议的最终裁判是"任意 HTTP/SSE 客户端都能跑通"。
2. **不为测试修改硬约束名单中的终端代码**；终端行为只测包裹层，其余进真机手工对拍清单（契约 C 的 C6），汇报时如实标注，不得声称已自动化覆盖。
3. **bootstrap 的动态验收只能在 Android 上做**：bootstrap 是 aarch64 的 Android/bionic 产物，桌面无法加载（`qemu` 也不适用）。桌面侧只做**静态断言**并进 CI，动态部分必须真机。本文件 v2 早期把 0c 写成"电脑上解压后执行 `python3`"，该说法**不成立**，已在契约 A 的 A5 修正。

| 阶段 | 必须通过（详见契约 C 的 C9） |
|---|---|
| 0 | 0c-静态（CI）+ 0c-动态（真机）+ 包名残留断言 |
| 1 | L1 / L3 单测、L5 导航与 Pager 联动 + 终端对拍 |
| 2 | Bridge 契约测试（契约 B 的 B10 全绿）+ 协议单测 |
| 3 | L2 / L3 / L4（流式、错误、重连） |
| 4 | L2 文件接口（含安全边界）+ L4 工具卡 / 文件卡 |
| 5 | L3 / L4 引导流程 + 真机重装 |
| 6 | L2 / L3 适配器切换 |
| 7 | README 从零构建（第三方复现性） |

**关键**：`BuildConfig.DEBUG` 下提供"Bridge 地址可覆盖"的调试开关，配合 `adb reverse tcp:8765 tcp:8765` 连电脑上的 Bridge，UI 调试不需要真机跑 Bridge；该开关必须被 `BuildConfig.DEBUG` 包裹，不得泄漏到 release。

**CI（仓库已托管在 GitHub，可直接启用）**：job 结构见契约 C 的 C8。**每个 job 只调用 `scripts/` 下的脚本**，保证"CI 上跑的命令 = 本地能复现的命令"。Bridge 的 `curl` 验收是纯 HTTP 客户端视角，等价于 WebView 分支将来会做的事——只要它全绿，就意味着"任意 HTTP/SSE 客户端"都能对接。

**每阶段的验收命令落成 `scripts/verify-stageN.sh` 并纳入 CI**，汇报时直接贴 CI 输出。

## 九、风险与回退

| 风险 | 回退方案 |
|---|---|
| **某后端在你的设备上装不通或不稳定** | 见契约 B 的 B11；三种后端（opencode / Claude Code / Hermes）均可安装，换用其余之一并汇报，不得自行改架构方向 |
| 阶段 0 `python3` 无法启动 | 按 A4→A5 顺序排查：`patchelf` 的 `DT_RUNPATH` → `SSL_CERT_FILE` / `LOCPATH` → `$PREFIX`；全失败则改用 `termux-packages` 自身构建流程重出 bootstrap 并汇报 |
| 自定义 bootstrap 被构建覆盖 | 检查 `app/build.gradle` 的下载校验与 clean 任务（契约 A 的 A4） |
| `TerminalView` 在 `AndroidView` 中键盘异常 | 用 1px 隐藏 `EditText` 兜底；仍不行则汇报，不要自行重构终端 |
| SSE 断线丢消息 | 检查 Bridge 环形缓冲区与 `from` 参数（契约 B 的 B5）；不要在客户端做"去重"启发式 |
| Agent 崩溃未被察觉 | 检查 Bridge 的子进程 `waitpid` 监听；不要在 Android 侧轮询进程 |
| 进程被系统回收导致对话中断 | Agent 侧历史由 opencode 持久化；App 重启后按 B9 的待确认接口恢复，未确认前不承诺历史可见 |
| 改包名后插件生态失效 | 已决策接受（Termux:API/Boot/Styling/Widget/Float/Tasker、第三方 `RUN_COMMAND` 均失效） |
| LLM API 在国网络不可达 | 引导页区分"网络不可达"与"凭据错误"；直连不通时由用户在 opencode 侧配置代理 |
| APK 体积过大 | 仅 arm64；不要为减体积删功能，先汇报 |
| targetSdk 升级带来的限制 | 当前 `targetSdk 28` 豁免；若将来升级，需补 `foregroundServiceType` 与 `127.0.0.1` 明文放行 |

## 十、汇报要求

每个阶段完成后汇报：**产出文件清单、验收命令输出、未解决问题**。遇到以下情况必须停下汇报，不得自行决定：

- 阶段 0 验收失败且排查无果。
- 契约 B 的 B11 三条路径全部失败（opencode 无法运行）。
- 需要修改"硬约束"中列出的任何代码。
- 需要变更 Bridge 协议（含 B9 的两条历史接口）。
- 需要引入新的第三方依赖（除 OkHttp、`okhttp-sse`、Compose 官方库外）。
- Compose 路线遇到无法用原生组件合理表达的需求，且用户可能需要切换到 WebView 分支时。

## 附录：变更记录（相对 v1）

1. **合并补丁**：v1 末尾的「修改 1/2/3」与「第十节追加一条」已并入正文；删除重复出现的 7.2 段落，硬约束第 3 条与 7.2 的措辞冲突统一为"当前不做 WebView，但界面必须可整体替换"。
2. **修正交叉引用**：原硬约束第 5 条的"（详见 6.3）" → "（详见 7.1）"；原第九节风险表的"按 6.1 排查顺序" → 指向阶段 0 的排查顺序；全文统一用"阶段 N / §7.x"引用，不再使用"6.x"这类小节编号。
3. **Agent 后端变更**：从"用户已有的 Python/Node Agent + Claude API 直连"改为"默认 `opencode` + Bridge 协议适配"；阶段 2 随之重写，阶段 5 改为"环境就绪检查 + Agent 配置"，阶段 6 改为"替换后端"。
4. **阶段 2 先决条件（已修订，见第 13 条）**：原为"opencode 在 Termux/aarch64 的可运行性 Go/No-Go"，该闸门已作废，改为"在真机装好默认后端，并确认可被非交互驱动"（契约 B 的 B11）。
5. **阶段 0 拆分**：拆为 0a/0b/0c，并补齐 Java/资源侧改包名清单与 bootstrap 构建覆盖问题（契约 A）。
6. **阶段 1 补前置**：新增"1a Kotlin/Compose 工具链"，并给出 `TermuxActivity` 根视图复用的推荐落点。
7. **新增决策落字**：Bridge 与 opencode 均不加鉴权（含残余风险与可选缓解）；历史由 opencode 持久化；Debug 聊天页先行以解耦阶段 1 与 2/3。
8. **协议语义钉死**：事件 id 作用域、环形缓冲淘汰、`from` 语义、`done` 后重连窗口、并发串行规则、`finishReason` 取值白名单（契约 B 的 B5）。
9. **删除不实引用**：v1 第 107 行的"`termux-generator`"未能核实为官方工具，统一改为引用 `termux-packages` 及其 `Building-packages` wiki。
10. **未决项**：目标包名待确认（建议 `com.zlllw.righthandman`）；`nodejs-lts` 主版本选定（原"是否预装 node"已确定必须预装；`glibc` 已不再是待定项，见第 14 条）；B9 两条历史接口待确认。
11. **新增测试与 CI 契约（契约 C）**：确立测试分层 L1–L8、测试依赖预算（新增第三方测试依赖须汇报）、Fake 与契约测试（同一套 `ChatRepositoryContractTest` 同时跑 `FakeChatRepository` 与 `HttpChatRepository`）、Kotlin 与 Python 共用同一批 SSE fixture、Compose 测试规范（Preview 必配、语义节点断言、禁止 `Thread.sleep`）、CI job 结构、各阶段放行条件，以及明确"不可自动化、必须真机手工对拍"的终端行为清单。
12. **修正 0c 验收**：原"电脑上解压后执行 `python3`"不成立——bootstrap 是 aarch64 的 Android/bionic 产物，桌面与 `qemu` 均无法执行；改为「桌面静态断言（进 CI）+ Android 动态验收（真机，不可替代）」。契约 A 的 A5 与本任务书阶段 0 已同步修正。
13. **更正 Agent 后端"跑不起来"的错误结论（重要）**：v2 早期依据**一条聚合搜索摘要**断言"opencode 二进制在 Termux/aarch64 上无法运行"，并据此设了 Go/No-Go 闸门。该结论**错误**——`opencode`、`Claude Code`、`Hermes Agent` 三者均可在 Termux 上安装运行。已删除该断言，契约 B 的 B11 改写为三种后端的安装路径、已知坑与驱动接口；阶段 2 先决条件从"能不能跑"改为"装好并确认可被非交互驱动"；阶段 6 从"可选替换后端"升级为"打通 Claude Code / Hermes 两条路径"；§三、§九与契约 A 的 A4 / A7（`nodejs-lts` 必须预装）同步更新。**教训：外部搜索的聚合摘要不得作为架构级结论的唯一依据，架构级判断必须有官方文档或真机实测支撑。**
14. **更正"需要虚拟环境"的说法**：三种后端（`opencode` / `Claude Code` / `Hermes Agent`）都能在 Termux 原生环境**直接安装**，**不需要 `proot-distro`、不需要额外容器、不需要 Python `venv`**。契约 B 的 B11 已按此改写：opencode 走 `npm install -g opencode-ai` 直装、Claude Code 走 npm 直装、Hermes 走 `pip install hermes-agent` 直装（官方 install 脚本会自建 venv，标注为非必需）。原先作为"候选路径"的 glibc 层与第三方移植降级为**未实测的备选线索**；契约 A 的 A7 同步去掉"是否打入 glibc"的待定项。**连带结论：阶段 5 的 Onboarding 不需要任何"创建环境"步骤。**