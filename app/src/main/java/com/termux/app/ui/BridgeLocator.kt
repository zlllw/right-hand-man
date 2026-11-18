package com.termux.app.ui

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
        val home = System.getenv("HOME") ?: return null
        val portFile = File(home, ".bridge/port")
        if (!portFile.isFile) return null
        val port = portFile.readText().trim().toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return "http://127.0.0.1:$port".toHttpUrlOrNull()
    }
}