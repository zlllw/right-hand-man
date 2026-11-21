package com.termux.app.bridge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bridge 状态机（契约 B 的 B2/B3、契约 C 的 L3）。
 *
 * 拉起是阻塞活，全部丢到 IO；UI 只看 [UiState]。
 * 只有 [UiState.running] 为 true 时才算「Bridge 可用」——那是 `/health` 探活的结果，
 * 不是"进程起过"。
 */
class BridgeViewModel(private val process: BridgeProcess) : ViewModel() {

    data class UiState(
        val starting: Boolean = false,
        val running: Boolean = false,
        val port: Int? = null,
        val message: String = "Bridge 未启动"
    )

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    fun ensureStarted() {
        if (mutableState.value.starting || mutableState.value.running) return
        mutableState.value = mutableState.value.copy(starting = true, message = "正在启动 Bridge…")
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) {
                runCatching { process.ensureStarted() }.getOrElse { error ->
                    BridgeProcess.Status(false, null, "Bridge 启动异常：${error.message}")
                }
            }
            mutableState.value = UiState(
                starting = false,
                running = status.running,
                port = status.port,
                message = status.message
            )
        }
    }

    fun stop() {
        mutableState.value = UiState(message = "Bridge 已停止")
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { process.stop() } }
        }
    }
}