package com.termux.app.ui

import com.termux.shared.termux.TermuxConstants
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

/**
 * 找到 Bridge 的地址（契约 B 的 B2/B3：Bridge 把端口写进 `$HOME/.bridge/port`）。
 *
 * 注意：Debug 构建下允许用 `bridge.url` 覆盖（契约 C 的 C7），
 * 方便 `adb reverse tcp:8765 tcp:8765` 直连电脑上的 Bridge。
 */
object BridgeLocator {

    private const val OVERRIDE_PROPERTY = "bridge.url"

    fun baseUrlOrNull(override: String? = null): HttpUrl? {
        val explicit = override ?: System.getProperty(OVERRIDE_PROPERTY)
        if (!explicit.isNullOrBlank()) {
            return explicit.toHttpUrlOrNull()
        }
        val port = readPort() ?: return null
        return "http://127.0.0.1:$port".toHttpUrlOrNull()
    }

    /**
     * 不能用 `System.getenv("HOME")`：Android 应用进程的环境变量里没有 HOME，
     * 那样永远读不到端口文件。必须按包名算出 Termux 的 `$HOME`。
     */
    fun readPort(): Int? {
        val portFile = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".bridge/port")
        if (!portFile.isFile) return null
        val port = runCatching { portFile.readText().trim().toInt() }.getOrNull() ?: return null
        return port.takeIf { it in 1..65535 }
    }
}