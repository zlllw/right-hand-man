package com.termux.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.termux.BuildConfig
import com.termux.app.chat.ChatViewModel
import com.termux.app.chat.FakeChatRepository
import com.termux.app.chat.HttpChatRepository
import com.termux.app.ui.BridgeLocator
import com.termux.app.ui.ChatScreen

/**
 * 仅 DEBUG 构建可用的独立聊天页（契约 C 的 C7）。
 *
 * 用途：`adb reverse tcp:8765 tcp:8765` 之后直接把本页连到电脑上的 Bridge 调试，
 * 不必等终端页嵌入、也不必在真机上跑 Bridge。
 * release 构建进入即退出，避免把调试入口带到发布包。
 */
class DebugChatActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) {
            finish()
            return
        }
        val endpoint = BridgeLocator.baseUrlOrNull()
        val viewModel = ChatViewModel(
            repository = if (endpoint != null) {
                HttpChatRepository(endpoint)
            } else {
                FakeChatRepository()
            }
        )
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by viewModel.state.collectAsState()
                    ChatScreen(
                        state = state,
                        bridgeNotice = if (endpoint != null) {
                            null
                        } else {
                            "未连接 Bridge：用 `adb reverse tcp:8765 tcp:8765` 或先启动本机 Bridge"
                        },
                        onSend = viewModel::send,
                        onNewConversation = viewModel::newConversation,
                        onOpenConversation = viewModel::openConversation,
                        onRequestHistory = viewModel::loadConversations,
                        onUpload = viewModel::attach,
                        onRemoveAttachment = viewModel::removeAttachment,
                        onRenameConversation = viewModel::renameConversation,
                        onDeleteConversation = viewModel::deleteConversation,
                        onStop = viewModel::stop,
                        onRequestFiles = viewModel::loadFiles,
                        onDownloadFile = { file -> viewModel.saveToDownloads(this, file.name) },
                        onDeleteFile = viewModel::deleteFile,
                        onPreviewFile = viewModel::previewFile,
                        onClosePreview = viewModel::closePreview,
                        onAttachExisting = viewModel::attachExisting
                    )
                }
            }
        }
    }
}