package com.termux.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.termux.app.EnvPreparer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 运行环境状态机（任务书阶段 1、契约 C 的 L3）。
 *
 * 和 [com.termux.app.chat.ChatViewModel] 一样只依赖非 UI 的 [EnvPreparer]，
 * 探测和安装都在 IO 线程，UI 只负责把 [UiState] 画出来。
 */
class EnvViewModel(private val preparer: EnvPreparer) : ViewModel() {

    enum class Phase {
        /** 还在探测 $PREFIX */
        CHECKING,

        /** python3 与 node 都能跑 */
        READY,

        /** 缺少运行时，等用户点「准备运行环境」 */
        MISSING,

        /** 脚本正在跑，[UiState.lines] 持续增长 */
        RUNNING,

        /** 上次准备失败，[UiState.detail] 是原因 */
        FAILED
    }

    data class UiState(
        val phase: Phase = Phase.CHECKING,
        val summary: String = "",
        val detail: String? = null,
        val lines: List<String> = emptyList()
    )

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    /** 重新探测。bootstrap 安装完成后由 MainActivity 触发一次。 */
    fun refresh() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(phase = Phase.CHECKING)
            val probe = withContext(Dispatchers.IO) {
                runCatching { preparer.probe() }.getOrNull()
            }
            mutableState.value = when {
                probe == null -> UiState(Phase.FAILED, detail = "无法探测运行环境")
                probe.needsSetup -> UiState(Phase.MISSING, summary = probe.summary)
                else -> UiState(Phase.READY, summary = probe.summary)
            }
        }
    }

    /** 开始准备。已经在跑就忽略重复点击。 */
    fun prepare() {
        if (mutableState.value.phase == Phase.RUNNING) return
        mutableState.value = UiState(Phase.RUNNING, summary = "正在准备运行环境")
        viewModelScope.launch {
            preparer.prepare().collect { event ->
                when (event) {
                    is EnvPreparer.Event.Line -> {
                        val lines = (mutableState.value.lines + event.text).takeLast(MAX_KEPT_LINES)
                        mutableState.value = mutableState.value.copy(lines = lines)
                    }

                    is EnvPreparer.Event.Finished -> {
                        val lines = mutableState.value.lines
                        mutableState.value = if (event.ok) {
                            UiState(Phase.READY, summary = event.detail, lines = lines)
                        } else {
                            UiState(
                                Phase.FAILED,
                                summary = "运行环境准备失败",
                                detail = event.detail,
                                lines = lines
                            )
                        }
                    }
                }
            }
        }
    }

    private companion object {
        /** 只留最后若干行：apt/dpkg 的日志可以很长，全留会撑爆内存 */
        const val MAX_KEPT_LINES = 300
    }
}