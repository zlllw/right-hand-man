package com.termux.app.ui

import android.graphics.BitmapFactory

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.termux.R
import com.termux.app.bridge.BridgeViewModel
import com.termux.app.chat.ChatViewModel
import com.termux.app.ui.theme.LocalRhmColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 双入口骨架：聊天 / 终端可左右滑动切换，底部是磨砂玻璃 dock
 * （对应 site/rhm-mobile-demo.html 的定稿设计）。
 *
 * 运行环境面板只在未就绪时出现；Bridge 未连上的原因仍由聊天页顶部横幅显示。
 */
@Composable
fun DualEntryScaffold(
    viewModel: ChatViewModel,
    envState: EnvViewModel.UiState,
    bridgeState: BridgeViewModel.UiState,
    onPrepareEnv: () -> Unit,
    onOpenTerminal: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val colors = LocalRhmColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var drawerOpen by remember { mutableStateOf(false) }

    // 探活通过（running）才算连上；否则把真实原因原样给用户，不含糊成"未连接"
    val bridgeNotice = if (bridgeState.running) null else bridgeState.message

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
    ) {
        HorizontalPager(
            state = pagerState,
            // 聊天页可左滑切终端；抽屉打开时整体禁用，避免抽屉里的手势漏到分页器
            userScrollEnabled = !drawerOpen,
            modifier = Modifier.weight(1f)
        ) { page ->
            when (page) {
                0 -> Column(modifier = Modifier.fillMaxSize()) {
                    if (envState.phase != EnvViewModel.Phase.READY) {
                        EnvPanel(state = envState, onPrepare = onPrepareEnv)
                    }
                    ChatScreen(
                        state = state,
                        bridgeNotice = bridgeNotice,
                        bridgeLoading = !bridgeState.running,
                        onSend = viewModel::send,
                        modifier = Modifier.weight(1f),
                        onNewConversation = viewModel::newConversation,
                        onOpenConversation = viewModel::openConversation,
                        onRequestHistory = viewModel::loadConversations,
                        onUpload = viewModel::attach,
                        onRemoveAttachment = viewModel::removeAttachment,
                        onRenameConversation = viewModel::renameConversation,
                        onDeleteConversation = viewModel::deleteConversation,
                        onStop = viewModel::stop,
                        onDrawerOpenChange = { drawerOpen = it },
                        onRequestFiles = viewModel::loadFiles,
                        onDownloadFile = { file -> viewModel.saveToDownloads(context, file.name) },
                        onDeleteFile = viewModel::deleteFile,
                        onPreviewFile = viewModel::previewFile,
                        onClosePreview = viewModel::closePreview,
                        onAttachExisting = viewModel::attachExisting
                    )
                }
                else -> TerminalScreen(onOpenTerminal = onOpenTerminal)
            }
        }
        RhmDock(
            selectedPage = pagerState.currentPage,
            onSelect = { scope.launch { pagerState.animateScrollToPage(it) } }
        )
    }
}

@Composable
private fun RhmDock(selectedPage: Int, onSelect: (Int) -> Unit) {
    val colors = LocalRhmColors.current
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .clip(shape)
                .background(colors.glassBg)
                .border(1.dp, colors.glassLine, shape)
                .padding(5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(modifier = Modifier.size(width = 54.dp, height = 44.dp), contentAlignment = Alignment.Center) {
                DockMark(
                    tint = colors.faint,
                    modifier = Modifier.width(38.dp).height(22.dp)
                )
            }
            Box(modifier = Modifier.width(1.dp).height(22.dp).background(colors.line))
            DockItem(RhmIcons.Chat, "对话", selectedPage == 0) { onSelect(0) }
            DockItem(RhmIcons.Terminal, "终端", selectedPage == 1) { onSelect(1) }
        }
    }
}

@Composable
private fun DockItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalRhmColors.current
    Box(
        modifier = Modifier
            .size(width = 54.dp, height = 44.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) colors.accentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) colors.accent else colors.muted,
            modifier = Modifier.size(20.dp)
        )
    }
}


/** dock 里的手信标记：静态淡灰，不参与任何动画（扫光彩蛋已按用户反馈移除）。 */
@Composable
private fun DockMark(tint: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val glyph = remember {
        BitmapFactory.decodeResource(context.resources, R.drawable.dock_mark).asImageBitmap()
    }
    Canvas(modifier = modifier) {
        drawImage(
            glyph,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            colorFilter = ColorFilter.tint(tint)
        )
    }
}

private const val PAGE_COUNT = 2
