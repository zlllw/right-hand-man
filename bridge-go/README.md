# 手信 Bridge（Go 版）

与 `bridge/`（Python）协议等价的 Go 实现。只用标准库，目标是编译成静态二进制后在 Android/Termux 里替代 Python 进程。

## 状态

- `POST /chat`、`GET /chat/{id}/stream`、`/health`、`/abort`、`/files*`、`/conversations*` 全部实现，状态码与 JSON 形状对齐 Python 版。
- SSE 事件 id、500 条环形缓冲、`from=` 重放、`history_truncated`、done 60s 保留窗口一致。
- fake 与 opencode 适配器均已移植：opencode 的 `/doc` 校验、`sessions.json` 映射、SSE 归一化（text delta / reasoning 过滤 / tool 折叠 / file / idle）、abort 都在。
- `go test ./...` 覆盖以上契约；Python 原套件 38 项仍全绿，作为基线。
- **尚未接入 App**：APK 目前仍打包 `bridge/` 的 Python 实现；本目录先在分支上独立验证。

## 构建与运行

```bash
go test ./...                     # 契约测试（fake 适配器，无需 opencode）
go run . --port 8765 --adapter fake
go run . --adapter opencode --oc-port 4096 --port-file ~/.bridge/port
```

参数与 Python 版一致：

| 参数 | 说明 |
|---|---|
| `--port` | 0 = 自动选空闲端口 |
| `--adapter` | `fake` / `opencode` |
| `--oc-port` | opencode serve 的端口，默认 4096 |
| `--port-file` | 实际端口写入该文件（0600） |
| `--workspace` | 文件工作区根目录，默认 `$HOME/rhmdir`；`none` 关闭 `/files` |

启动输出与 Python 版一致：`bridge listening on 127.0.0.1:<port> (adapter=<name>)`。

## Android 交叉编译

```bash
./build-android.sh
```

产物在 `dist/`：

| 文件 | ABI |
|---|---|
| `bridge-arm64-v8a` | arm64-v8a |
| `bridge-armeabi-v7a` | armeabi-v7a |
| `bridge-x86` | x86 |
| `bridge-x86_64` | x86_64 |

使用 `GOOS=linux CGO_ENABLED=0` 的纯静态二进制，不依赖 NDK 和 libc。四个 ABI 产物约 6.6–7.1MB。

**真机运行验证待补**：本轮交叉编译时手机 adb 掉线，arm64 二进制尚未在小米 12 上实际执行。接上设备后的验证步骤：

```bash
adb push dist/bridge-arm64-v8a /data/local/tmp/rhm-bridge-go
adb shell chmod 755 /data/local/tmp/rhm-bridge-go
adb shell "nohup /data/local/tmp/rhm-bridge-go --port 18800 --adapter fake --workspace none >/data/local/tmp/bridge.log 2>&1 &"
adb forward tcp:18800 tcp:18800
curl http://127.0.0.1:18800/health
```

若某些 ROM 的 seccomp 限制静态 linux 二进制，可改用 `GOOS=android` + NDK 目标（需要 cgo 与 NDK 链接器配置）；本仓库暂不启用。

## 与 Python 版的已知差异

- 当前 Windows 环境没有 cgo 编译器，`go test -race` 跑不了；建议在 Linux CI 补跑一次。
- 不支持的 HTTP 方法返回 404 JSON（Python 的 `BaseHTTPRequestHandler` 返回 501 HTML），不影响 App 侧。
- MIME 用固定扩展名表 + 系统表兜底，避免 Windows/Android 注册表差异。

## 结构对照

| Python | Go | 职责 |
|---|---|---|
| `server.py` | `main.go` + `http.go` + `bridge.go` | HTTP 路由、SSE、每会话 FIFO |
| `ring.py` | `ring.go` | 事件环形缓冲与 SSE 编码 |
| `files.py` | `files.go` | rhmdir 工作区、multipart、路径校验 |
| `adapters/fake.py` | `fake.go` | 桌面/CI 假适配器 |
| `adapters/opencode.py` | `opencode.go` | opencode 进程、事件总线、历史映射 |

## 接入 App 的下一步

1. Gradle 增加 `copyBridgeAssets` 的 Go 版：把 `dist/bridge-<abi>` 放进 assets（按 split ABI 选择）。
2. `BridgeProcess` 解包到 `filesDir`，`chmod 700` 后 exec，命令行参数保持不变。
3. 若确认不再需要 Python（opencode 用 Node），可从 `env-prep.sh` 去掉 python 安装，首次自举更快、前缀更小。
4. 真机 instrumented 回归（`ChatEndToEndTest` + Bridge 契约）。