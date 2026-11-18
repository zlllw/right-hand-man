package com.termux.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.termux.app.chat.ChatViewModel
import com.termux.app.chat.FakeChatRepository
import com.termux.app.chat.HttpChatRepository
import com.termux.app.ui.BridgeLocator
import com.termux.app.ui.DualEntryScaffold

/**
 * 手信的默认入口（任务书阶段 1b）：Compose 双 tab 宿主。
 *
 * 终端 tab 目前是占位——终端视图必须复用 [TermuxActivity] 的初始化，
 * 抽成可复用宿主后才能通过 `AndroidView` 嵌进来，见 `ui/TerminalScreen.kt` 的说明。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var chatViewModel: ChatViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val endpoint = BridgeLocator.baseUrlOrNull()
        chatViewModel = ChatViewModel(
            repository = if (endpoint != null) {
                HttpChatRepository(endpoint)
            } else {
                FakeChatRepository()
            }
        )
        if (endpoint != null) chatViewModel.refreshHealth()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DualEntryScaffold(
                        viewModel = chatViewModel,
                        bridgeConnected = endpoint != null,
                        onOpenTerminal = {
                            startActivity(Intent(this, TermuxActivity::class.java))
                        }
                    )
                }
            }
        }
    }
}