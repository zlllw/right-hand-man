package com.termux.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.termux.app.chat.ChatViewModel
import kotlinx.coroutines.launch

/**
 * 双入口骨架：底部导航 + 左右滑动（任务书阶段 1b）。
 *
 * 「对话」tab 的内容是一个可整体替换的 Composable（`ChatScreen()`），
 * 将来若开 WebView 分支，只需把它换成 `AndroidView` 加载
 * `http://127.0.0.1:<P_app>`，其余代码零改动（任务书 7.2、契约 B 的 7.2）。
 */
@Composable
fun DualEntryScaffold(
    viewModel: ChatViewModel,
    bridgeConnected: Boolean,
    onOpenTerminal: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    icon = { Text("C") },
                    label = { Text("对话") }
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    icon = { Text(">_") },
                    label = { Text("终端") }
                )
            }
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.padding(innerPadding)
        ) { page ->
            when (page) {
                0 -> ChatScreen(
                    state = state,
                    bridgeConnected = bridgeConnected,
                    onSend = viewModel::send
                )
                else -> TerminalScreen(onOpenTerminal = onOpenTerminal)
            }
        }
    }
}

private const val PAGE_COUNT = 2