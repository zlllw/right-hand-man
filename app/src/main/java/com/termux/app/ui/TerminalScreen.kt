package com.termux.app.ui

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 终端页：直接复用 [com.termux.app.TermuxTerminalHostActivity] 抽出的根视图。
 *
 * 根视图由宿主 Activity 创建并持有（服务绑定、会话、键盘、会话抽屉都在里面），
 * 这里只负责把它交给 Compose 显示；切换 tab 时拆下但不销毁，回到终端页再挂回去。
 */
@Composable
fun TerminalScreen(terminalRoot: View?, modifier: Modifier = Modifier) {
    if (terminalRoot == null) {
        Box(
            modifier = modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "终端尚未初始化",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(android.graphics.Color.BLACK)
                (terminalRoot.parent as? ViewGroup)?.removeView(terminalRoot)
                addView(
                    terminalRoot,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            }
        },
        onRelease = { container -> container.removeAllViews() }
    )
}

@Preview(showBackground = true)
@Composable
private fun PreviewTerminalScreen() {
    MaterialTheme {
        TerminalScreen(terminalRoot = null)
    }
}