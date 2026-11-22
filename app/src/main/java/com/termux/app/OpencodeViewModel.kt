package com.termux.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 一键 opencode 安装的状态机。
 *
 * 安装脚本在 [EnvPreparer.installOpencode] 里跑；这里只保留最近一行进度和最终结果。
 */
class OpencodeViewModel(private val preparer: EnvPreparer) : ViewModel() {

    data class UiState(
        val installing: Boolean = false,
        val installed: Boolean = false,
        val message: String? = null,
        val error: String? = null
    )

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    fun install() {
        if (mutableState.value.installing) return
        mutableState.value = UiState(installing = true, message = "正在安装 opencode…")
        viewModelScope.launch {
            try {
                preparer.installOpencode().collect { event ->
                    when (event) {
                        is EnvPreparer.Event.Line -> {
                            val text = event.text.trim()
                            if (text.isNotEmpty()) {
                                mutableState.value = mutableState.value.copy(message = text)
                            }
                        }
                        is EnvPreparer.Event.Finished -> {
                            mutableState.value = if (event.ok) {
                                UiState(installed = true, message = event.detail)
                            } else {
                                UiState(message = event.detail, error = event.detail)
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                val message = "安装失败：${error.message}"
                mutableState.value = UiState(message = message, error = message)
            }
        }
    }

    /** 成功并重启 Bridge 后清空，避免提示条反复显示。 */
    fun reset() {
        mutableState.value = UiState()
    }
}