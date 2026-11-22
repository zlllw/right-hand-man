package com.termux.app

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.termux.R
import com.termux.app.terminal.TermuxActivityRootView
import com.termux.app.bridge.BridgeProcess
import com.termux.app.bridge.BridgeViewModel
import com.termux.app.chat.ChatViewModel
import com.termux.app.chat.FakeChatRepository
import com.termux.app.chat.HttpChatRepository
import com.termux.app.ui.BridgeLocator
import com.termux.app.ui.DualEntryScaffold
import com.termux.app.ui.EnvViewModel
import com.termux.app.ui.theme.AppearanceMode
import com.termux.app.ui.theme.LocalRhmColors
import com.termux.app.ui.theme.RhmAppearance
import com.termux.app.ui.theme.RhmTheme
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 手信的默认入口（任务书阶段 1b / 1c）：Compose 双 tab 宿主。
 *
 * 进入时的三条前置链：
 * 1. bootstrap：`TermuxInstaller.setupBootstrapIfNeeded` 在 $PREFIX 已存在时同步回调，
 *    否则弹安装进度并在装完后回调，回调里探测 python/node/npm；
 * 2. 运行环境：只有 READY 之后才拉起 Bridge（[BridgeProcess] 需要 `$PREFIX/bin/python3`）；
 * 3. Bridge：拉起结果以 `/health` 探活为准；一旦端口可用就把聊天页的 Repository
 *    从 [FakeChatRepository] 换成 [HttpChatRepository]，此后每条消息都真的走 Bridge。
 *
 * 终端 tab 直接复用 [TermuxTerminalHostActivity] 抽出的终端根视图：
 * 与独立 TermuxActivity 共享同一套服务绑定、会话、键盘和会话抽屉，
 * 只是把它嵌入 Compose 的 `AndroidView` 而不是铺满整个 Activity。
 */
class MainActivity : TermuxTerminalHostActivity() {

    private lateinit var chatViewModel: ChatViewModel
    private lateinit var envViewModel: EnvViewModel
    private lateinit var bridgeViewModel: BridgeViewModel
    private lateinit var terminalRoot: TermuxActivityRootView

    private var terminalFocused by mutableStateOf(false)
    private var terminalPageSelected by mutableStateOf(false)
    private val terminalFocusListener =
        ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
            terminalFocused = newFocus?.id == R.id.terminal_view
        }

    override fun isTerminalRootEmbedded(): Boolean = true

    override fun onBootstrapReady() {
        if (::envViewModel.isInitialized) envViewModel.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RhmAppearance.init(applicationContext)

        terminalRoot = getTermuxActivityRootView()
        terminalRoot.viewTreeObserver.addOnGlobalFocusChangeListener(terminalFocusListener)

        // 冷启动时端口文件可能已经存在（Bridge 没随 app 退出），先用 HTTP 起步
        val endpoint = BridgeLocator.baseUrlOrNull()
        chatViewModel = ChatViewModel(
            repository = if (endpoint != null) {
                HttpChatRepository(endpoint)
            } else {
                FakeChatRepository()
            }
        )
        if (endpoint != null) chatViewModel.refreshHealth()

        envViewModel = EnvViewModel(EnvPreparer(applicationContext))
        bridgeViewModel = BridgeViewModel(BridgeProcess(applicationContext))

        setContent {
            val mode by RhmAppearance.mode.collectAsState()
            val preset by RhmAppearance.theme.collectAsState()
            val dark = when (mode) {
                AppearanceMode.SYSTEM -> isSystemInDarkTheme()
                AppearanceMode.LIGHT -> false
                AppearanceMode.DARK -> true
            }
            // 系统栏图标明暗跟随应用主题；终端页背景恒黑，因此图标恒浅色
            LaunchedEffect(dark, terminalPageSelected) {
                val lightSystemBars = dark || terminalPageSelected
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !lightSystemBars
                    isAppearanceLightNavigationBars = !lightSystemBars
                }
            }
            RhmTheme(mode = mode, preset = preset) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = LocalRhmColors.current.background
                ) {
                    val envState by envViewModel.state.collectAsState()
                    val bridgeState by bridgeViewModel.state.collectAsState()

                    // 环境就绪后自动拉起 Bridge；失败不自动重试，避免在坏环境里反复起进程
                    LaunchedEffect(envState.phase) {
                        if (envState.phase == EnvViewModel.Phase.READY) {
                            bridgeViewModel.ensureStarted()
                        }
                    }

                    // 探活通过才把聊天页接到真实 Bridge；端口变化（Bridge 重生）时同样重接
                    LaunchedEffect(bridgeState.running, bridgeState.port) {
                        val port = bridgeState.port
                        if (bridgeState.running && port != null) {
                            val url = "http://127.0.0.1:$port".toHttpUrlOrNull()
                            if (url != null) {
                                chatViewModel.useRepository(HttpChatRepository(url))
                                chatViewModel.refreshHealth()
                            }
                        }
                    }

                    DualEntryScaffold(
                        viewModel = chatViewModel,
                        envState = envState,
                        bridgeState = bridgeState,
                        onPrepareEnv = envViewModel::prepare,
                        terminalRoot = terminalRoot,
                        terminalFocused = terminalFocused,
                        onTerminalPageChanged = ::onTerminalPageChanged
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        if (::terminalRoot.isInitialized) {
            terminalRoot.viewTreeObserver.removeOnGlobalFocusChangeListener(terminalFocusListener)
        }
        super.onDestroy()
    }

    /** 终端页整页恒黑；离开时收起键盘并清焦点，避免回到对话页后终端继续吃输入。 */
    private fun onTerminalPageChanged(visible: Boolean) {
        terminalPageSelected = visible
        if (visible) return
        val terminalView: View = terminalRoot.findViewById(R.id.terminal_view) ?: return
        terminalView.clearFocus()
        val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(terminalView.windowToken, 0)
    }
}