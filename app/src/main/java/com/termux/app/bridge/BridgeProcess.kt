package com.termux.app.bridge

import android.content.Context
import com.termux.shared.termux.TermuxConstants
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Bridge 进程管理（任务书阶段 1c / 契约 B 的 B2、B3）。
 *
 * 三件事：把 APK 里 `assets/bridge` 解到 `$HOME/.bridge/app`、用 `$PREFIX/bin/python3`
 * 以 `--port 0 --port-file $HOME/.bridge/port` 拉起 `server.py`、再读端口文件并用
 * `/health` 确认真的活着（只看进程存在不算，必须探活）。
 *
 * 注意：探活走 OkHttp，受 Android 网络安全策略约束——targetSdk 28 起默认禁明文，
 * 所以 `res/xml/network_security_config.xml` 里只放行 127.0.0.1 / localhost，
 * 否则这里会稳定报「/health 不可用」。
 *
 * 全程在调用方的 IO 线程执行：这里会阻塞等待端口文件与 HTTP 响应。
 */
class BridgeProcess(private val context: Context) {

    data class Status(
        val running: Boolean,
        val port: Int?,
        val message: String,
        val adapter: String? = null
    )

    private val prefix = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH)
    private val home = File(TermuxConstants.TERMUX_HOME_DIR_PATH)
    private val bridgeDir = File(home, ".bridge")
    private val appDir = File(bridgeDir, "app")
    private val portFile = File(bridgeDir, "port")
    private val logFile = File(bridgeDir, "bridge.log")

    private val http = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var currentAdapter: String? = null

    /** 只读状态，不会拉起进程。 */
    fun status(): Status {
        val adapter = currentAdapter ?: adapterFromLog()
        val port = readPort() ?: return Status(false, null, "Bridge 未启动", adapter)
        val failure = healthFailure(port)
        return if (failure == null) {
            Status(true, port, "Bridge 就绪：127.0.0.1:$port", adapter)
        } else {
            Status(false, port, "Bridge 端口 $port 无响应：$failure", adapter)
        }
    }

    /**
     * 保证 Bridge 在运行：已在跑就直接复用（多入口、进程重启都安全），
     * 否则解包并拉起。返回的 [Status] 已包含探活结果，不是"启动命令执行成功"。
     */
    @Synchronized
    fun ensureStarted(adapter: String = defaultAdapter()): Status {
        status().takeIf { it.running }?.let { return it }
        currentAdapter = adapter

        val python = File(prefix, "bin/python3")
        if (!python.canExecute()) {
            return Status(false, null, "运行环境未就绪：缺少 python3", adapter)
        }
        if (!deployBridge()) {
            return Status(false, null, "无法解出 Bridge 脚本到 $appDir", adapter)
        }

        portFile.delete()
        val command = listOf(
            python.absolutePath,
            File(appDir, "server.py").absolutePath,
            "--port", "0",
            "--adapter", adapter,
            "--port-file", portFile.absolutePath
        )
        return try {
            process = ProcessBuilder(command)
                .directory(appDir)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .apply { environment().putAll(childEnv()) }
                .start()
            val port = awaitPort(PORT_WAIT_MS)
            if (port == null) {
                Status(false, null, "Bridge 启动超时，详见 ${logFile.name}", adapter)
            } else {
                val failure = healthFailure(port)
                if (failure == null) {
                    Status(true, port, "Bridge 就绪：127.0.0.1:$port", adapter)
                } else {
                    Status(false, port, "Bridge 已启动但 /health 不可用：$failure", adapter)
                }
            }
        } catch (error: Exception) {
            Status(false, null, "Bridge 启动失败：${error.message}", adapter)
        }
    }

    fun stop() {
        process?.let {
            it.destroy()
            runCatching { it.waitFor(3, TimeUnit.SECONDS) }
        }
        process = null
        currentAdapter = null
        portFile.delete()
    }

    /** 冷启动复用已有 Bridge 时，从日志恢复适配器名，供 UI 判断是否为演示回声。 */
    private fun adapterFromLog(): String? = runCatching {
        logFile.takeIf { it.isFile }?.readLines()
            ?.lastOrNull { it.contains("(adapter=") }
            ?.let { Regex("\\(adapter=([A-Za-z0-9_-]+)\\)").find(it)?.groupValues?.get(1) }
    }.getOrNull()

    private fun deployBridge(): Boolean = runCatching {
        appDir.mkdirs()
        copyAssetDir(ASSET_ROOT, appDir)
        true
    }.getOrDefault(false)

    /** assets 里没有目录/文件的可靠区分，用 `list()` 非空且以 `.py` 结尾两条规则判断。 */
    private fun copyAssetDir(assetPath: String, target: File) {
        val children = context.assets.list(assetPath) ?: return
        target.mkdirs()
        for (name in children) {
            val childAsset = "$assetPath/$name"
            val childTarget = File(target, name)
            val isDirectory = context.assets.list(childAsset)?.isNotEmpty() == true
            if (isDirectory) {
                copyAssetDir(childAsset, childTarget)
            } else if (name.endsWith(".py")) {
                context.assets.open(childAsset).use { input ->
                    childTarget.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun awaitPort(timeoutMs: Long): Int? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            readPort()?.let { return it }
            Thread.sleep(100)
        }
        return null
    }

    private fun readPort(): Int? {
        if (!portFile.isFile) return null
        val port = runCatching { portFile.readText().trim().toInt() }.getOrNull() ?: return null
        return port.takeIf { it in 1..65535 }
    }

    /** null = 健康；否则是失败原因（异常原文或 HTTP 状态码），要能显示到 UI 上。 */
    private fun healthFailure(port: Int): String? = try {
        val request = Request.Builder().url("http://127.0.0.1:$port/health").build()
        http.newCall(request).execute().use { response ->
            if (response.isSuccessful) null else "HTTP ${response.code}"
        }
    } catch (error: Exception) {
        error.message ?: error.javaClass.simpleName
    }

    private fun childEnv(): Map<String, String> = mapOf(
        "PREFIX" to prefix.absolutePath,
        "HOME" to home.absolutePath,
        "PATH" to "${prefix.absolutePath}/bin:/system/bin",
        "TMPDIR" to File(prefix, "tmp").absolutePath,
        "LANG" to "en_US.UTF-8",
        // 不缓冲：日志要能实时 tail 到 bridge.log
        "PYTHONUNBUFFERED" to "1"
    )

    companion object {
        private const val ASSET_ROOT = "bridge"
        private const val PORT_WAIT_MS = 30_000L

        /**
         * 仅 instrumented 测试使用：在 Activity 启动前强制指定适配器。
         * 测试与 App 同进程，静态覆盖能保证 E2E 固定走 fake 的确定性回声。
         */
        @Volatile
        var adapterOverride: String? = null

        /**
         * 优先接真 Agent：装了 opencode 就用它，否则回落 fake。
         * 桌面调试仍可用 `-Dbridge.adapter=...` 覆盖。
         */
        fun defaultAdapter(): String =
            adapterOverride
                ?: System.getProperty("bridge.adapter")
                ?: if (opencodeInstalled()) "opencode" else "fake"

        private fun opencodeInstalled(): Boolean =
            File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "bin/opencode").canExecute()
    }
}