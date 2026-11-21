package com.termux.app.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.app.chat.ChatViewModel
import com.termux.app.chat.ChatViewModel.Role
import com.termux.app.chat.ConversationSummary
import com.termux.app.chat.WorkspaceFile
import com.termux.app.ui.theme.LocalRhmColors
import com.termux.app.ui.theme.rhmFadeTop
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 对话页（契约 C 的 L4：无状态、可预览）。
 *
 * 只吃 [ChatViewModel.UiState] 与回调，不持有 ViewModel，
 * 因此 `@Preview` 与 UI 测试都能直接喂数据。
 * 协议/网络相关的一切都在 Repository 与 ViewModel 里，这里不碰。
 *
 * 布局对应手机原型（site/rhm-mobile-demo.html）：
 * 顶栏左侧 ☰ 拉出历史抽屉，右侧 + 新建对话；输入栏左侧 📎 上传附件到 ~/rhmdir。
 *
 * [bridgeNotice] 为 null 表示 Bridge 正常；非 null 时按原样显示成一条提示横幅。
 */
@Composable
fun ChatScreen(
    state: ChatViewModel.UiState,
    bridgeNotice: String?,
    bridgeLoading: Boolean = false,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    onNewConversation: () -> Unit = {},
    onOpenConversation: (String) -> Unit = {},
    onRequestHistory: () -> Unit = {},
    onUpload: (String, ByteArray) -> Unit = { _, _ -> },
    onRemoveAttachment: (Int) -> Unit = {},
    onRenameConversation: (String, String) -> Unit = { _, _ -> },
    onDeleteConversation: (String) -> Unit = {},
    onStop: () -> Unit = {},
    onDrawerOpenChange: (Boolean) -> Unit = {},
    onRequestFiles: () -> Unit = {},
    onDownloadFile: (WorkspaceFile) -> Unit = {},
    onDeleteFile: (String) -> Unit = {},
    onPreviewFile: (String) -> Unit = {},
    onClosePreview: () -> Unit = {},
    onAttachExisting: (WorkspaceFile) -> Unit = {}
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val colors = LocalRhmColors.current
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showFiles by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ConversationSummary?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ConversationSummary?>(null) }
    var fileDeleteTarget by remember { mutableStateOf<WorkspaceFile?>(null) }
    val drawerOpen = drawerState.currentValue != DrawerValue.Closed

    LaunchedEffect(drawerOpen) { onDrawerOpenChange(drawerOpen) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = queryDisplayName(context, uri) ?: "upload.bin"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes != null) onUpload(name, bytes)
    }

    // 有新消息就滚到底（契约 C 的 C4：用状态而不是轮询）
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = {
            Row(modifier = Modifier.fillMaxSize()) {
                ModalDrawerSheet(
                    drawerContainerColor = colors.surface,
                    drawerContentColor = colors.ink,
                    modifier = Modifier.fillMaxWidth(0.86f)
                ) {
                    HistoryDrawer(
                        conversations = state.conversations,
                        currentId = state.conversationId,
                        onOpen = { id ->
                            onOpenConversation(id)
                            scope.launch { drawerState.close() }
                        },
                        onClose = { scope.launch { drawerState.close() } },
                        onOpenFiles = {
                            onRequestFiles()
                            scope.launch { drawerState.close() }
                            showFiles = true
                        },
                        onOpenSettings = { showSettings = true },
                        onRename = { target ->
                            renameTarget = target
                            renameDraft = target.title
                        },
                        onDelete = { target -> deleteTarget = target }
                    )
                }
                // 右侧空白处点击关闭（位于抽屉层，scrim 之上）
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { scope.launch { drawerState.close() } }
                        .semantics { contentDescription = "关闭抽屉区域" }
                )
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .imePadding()
                    .background(colors.background)
                    // 聊天页右滑拉出历史抽屉；左滑交给分页器切到终端（抽屉打开时整体不响应）
                    .pointerInput(drawerOpen) {
                        if (drawerOpen) return@pointerInput
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var totalX = 0f
                            var locked = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUpIgnoreConsumed()) break
                                val dx = change.positionChange().x
                                if (!locked) {
                                    totalX += dx
                                    if (kotlin.math.abs(totalX) > slop) locked = true
                                } else {
                                    if (totalX > 0f) change.consume()
                                    totalX += dx
                                }
                            }
                            if (totalX > 64.dp.toPx()) {
                                onRequestHistory()
                                scope.launch { drawerState.open() }
                            }
                        }
                    }
            ) {
            val title = state.conversations
                .firstOrNull { it.conversationId == state.conversationId }
                ?.title?.ifEmpty { null }
                ?: state.conversationId
            ChatHeader(
                title = title,
                onHistory = {
                    onRequestHistory()
                    scope.launch { drawerState.open() }
                },
                onNew = onNewConversation
            )

            bridgeNotice?.let { notice ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surface2)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = notice,
                        fontSize = 12.sp,
                        color = colors.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (bridgeLoading) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = colors.accent,
                            trackColor = colors.line
                        )
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().rhmFadeTop(28.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.messages.isEmpty()) {
                    item {
                        Text(
                            text = "说点什么，Agent 会在终端里执行\n📎 上传的文件会放进 ~/rhmdir，与终端共享",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 48.dp)
                        )
                    }
                }
                items(state.messages) { message ->
                    MessageBubble(message)
                }
            }

            state.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            if (state.pendingAttachments.isNotEmpty() || state.uploading) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                    if (state.uploading) {
                        Text("上传中…", fontSize = 9.sp, color = colors.muted, modifier = Modifier.padding(2.dp))
                    }
                    state.pendingAttachments.forEachIndexed { index, attachment ->
                        Row(
                            modifier = Modifier
                                .padding(vertical = 2.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.surface2)
                                .border(1.dp, colors.line, RoundedCornerShape(8.dp))
                                .padding(start = 8.dp, end = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "📎 ${attachment.name}（${attachment.size / 1024}KB）",
                                fontSize = 12.sp,
                                color = colors.ink2,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onRemoveAttachment(index) }
                                    .semantics { contentDescription = "移除附件" },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("×", fontSize = 16.sp, color = colors.muted)
                            }
                        }
                    }
                }
            }

            val canSend = !state.streaming && (draft.isNotBlank() || state.pendingAttachments.isNotEmpty())
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.field)
                        .border(1.dp, colors.line, RoundedCornerShape(16.dp))
                        .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 8.dp)
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        textStyle = TextStyle(fontSize = 15.sp, color = colors.ink, lineHeight = 23.sp),
                        cursorBrush = SolidColor(colors.accent),
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "消息输入" },
                        decorationBox = { inner ->
                            Box {
                                if (draft.isEmpty()) {
                                    Text("发消息…", fontSize = 15.sp, color = colors.faint)
                                }
                                inner()
                            }
                        }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = RhmIcons.Folder,
                            contentDescription = null,
                            tint = colors.muted,
                            modifier = Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    onRequestFiles()
                                    showFiles = true
                                }
                                .semantics { contentDescription = "打开文件库" }
                        )
                        Spacer(Modifier.width(10.dp))
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(colors.accent))
                        Spacer(Modifier.width(6.dp))
                        Text("opencode", fontSize = 11.sp, color = colors.muted)
                        Spacer(Modifier.weight(1f))
                        val streaming = state.streaming
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (streaming || canSend) colors.accent else colors.line)
                                .clickable(enabled = streaming || canSend) {
                                    if (streaming) {
                                        onStop()
                                    } else {
                                        val text = draft.trim()
                                        if (text.isNotEmpty() || state.pendingAttachments.isNotEmpty()) {
                                            onSend(text)
                                            draft = ""
                                        }
                                    }
                                }
                                .semantics { contentDescription = if (streaming) "停止生成" else "发送消息" },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (streaming) RhmIcons.Stop else RhmIcons.Send,
                                contentDescription = null,
                                tint = if (streaming || canSend) colors.accentInk else colors.faint,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }
            }
        }
    }
    if (showSettings) SettingsSheet(onDismiss = { showSettings = false })
    if (showFiles) {
        FilesSheet(
            files = state.workspaceFiles,
            preview = state.preview,
            onDismiss = {
                showFiles = false
                onClosePreview()
            },
            onPreview = onPreviewFile,
            onClosePreview = onClosePreview,
            onAttach = { file ->
                onAttachExisting(file)
                showFiles = false
            },
            onDownload = onDownloadFile,
            onDelete = { file -> fileDeleteTarget = file },
            onUpload = { picker.launch(arrayOf("*/*")) }
        )
    }
    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = colors.surface,
            title = { Text("重命名对话", fontSize = 18.sp, color = colors.ink) },
            text = {
                BasicTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    textStyle = TextStyle(fontSize = 15.sp, color = colors.ink),
                    cursorBrush = SolidColor(colors.accent),
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.field)
                        .border(1.dp, colors.line, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                        .semantics { contentDescription = "对话标题" }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val title = renameDraft.trim()
                    if (title.isNotEmpty()) {
                        onRenameConversation(target.conversationId, title)
                        renameTarget = null
                    }
                }) { Text("保存", color = colors.accent) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消", color = colors.muted) }
            }
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = colors.surface,
            title = { Text("删除这段对话？", fontSize = 18.sp, color = colors.ink) },
            text = {
                Text(
                    "对话将从列表中移除，~/rhmdir 里的文件不受影响。",
                    fontSize = 14.sp,
                    color = colors.muted,
                    lineHeight = 22.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteConversation(target.conversationId)
                    deleteTarget = null
                }) { Text("删除", color = colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消", color = colors.muted) }
            }
        )
    }
    fileDeleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { fileDeleteTarget = null },
            containerColor = colors.surface,
            title = { Text("删除这个文件？", fontSize = 18.sp, color = colors.ink) },
            text = {
                Text(
                    "${target.name} 将从 ~/rhmdir 中删除，此操作不可撤销。",
                    fontSize = 14.sp,
                    color = colors.muted,
                    lineHeight = 22.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteFile(target.name)
                    fileDeleteTarget = null
                }) { Text("确认删除", color = colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { fileDeleteTarget = null }) { Text("取消", color = colors.muted) }
            }
        )
    }
}

@Composable
private fun ChatHeader(
    title: String,
    onHistory: () -> Unit,
    onNew: () -> Unit
) {
    val colors = LocalRhmColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(colors.glassBg)
            .border(1.dp, colors.glassLine, RoundedCornerShape(18.dp))
            .padding(horizontal = 8.dp)
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeaderIcon(RhmIcons.Sidebar, "打开历史侧边栏", colors.ink2, onHistory)
        Text(
            text = title,
            fontSize = 13.sp,
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
        )
        HeaderIcon(RhmIcons.Edit, "新建对话", colors.accent, onNew)
    }
}

@Composable
private fun HeaderIcon(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    val colors = LocalRhmColors.current
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface2)
            .border(1.dp, colors.line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

private val SwipeRenameColor = Color(0xFF2F78F0)
private val SwipePinColor = Color(0xFFE89A3C)
private val SwipeDeleteColor = Color(0xFFD9534F)

private data class DrawerEntry(
    val header: String? = null,
    val conversation: ConversationSummary? = null
)

private fun dateGroupLabel(updatedAtSeconds: Long): String {
    val now = System.currentTimeMillis()
    val timestamp = updatedAtSeconds * 1000L
    val target = Calendar.getInstance().apply { timeInMillis = timestamp }
    val today = Calendar.getInstance()
    fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (sameDay(target, today)) return "今天"
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (sameDay(target, yesterday)) return "昨天"
    if (now - timestamp <= 7L * 24 * 3600 * 1000) return "过去 7 天"
    return "更早"
}

@Composable
private fun HistoryDrawer(
    conversations: List<ConversationSummary>,
    currentId: String,
    onOpen: (String) -> Unit,
    onClose: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenSettings: () -> Unit,
    onRename: (ConversationSummary) -> Unit,
    onDelete: (ConversationSummary) -> Unit
) {
    val colors = LocalRhmColors.current
    var query by rememberSaveable { mutableStateOf("") }
    var pinned by rememberSaveable { mutableStateOf(ArrayList<String>()) }

    val filtered = conversations.filter {
        query.isBlank() || it.title.contains(query, true) || it.conversationId.contains(query, true)
    }
    val pinnedList = filtered.filter { it.conversationId in pinned }
    val rest = filtered.filterNot { it.conversationId in pinned }
    val entries = ArrayList<DrawerEntry>()
    if (pinnedList.isNotEmpty()) {
        entries.add(DrawerEntry(header = "置顶"))
        pinnedList.forEach { entries.add(DrawerEntry(conversation = it)) }
    }
    var lastGroup: String? = null
    rest.forEach { item ->
        val group = dateGroupLabel(item.updatedAt)
        if (group != lastGroup) {
            entries.add(DrawerEntry(header = group))
            lastGroup = group
        }
        entries.add(DrawerEntry(conversation = item))
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "对话历史",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink,
                modifier = Modifier.weight(1f).padding(start = 8.dp, top = 24.dp, bottom = 12.dp)
            )
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surface2)
                    .border(1.dp, colors.line, RoundedCornerShape(10.dp))
                    .clickable(onClick = onClose)
                    .semantics { contentDescription = "关闭历史侧边栏" },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = RhmIcons.Close,
                    contentDescription = null,
                    tint = colors.ink2,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        SearchField(query = query, onQueryChange = { query = it })
        if (entries.isEmpty()) {
            Text(
                text = if (query.isBlank()) {
                    "暂无历史会话\n（历史保存在 opencode，聊过一次就会出现）"
                } else {
                    "没有匹配的对话"
                },
                fontSize = 12.sp,
                color = colors.muted,
                lineHeight = 20.sp,
                modifier = Modifier.padding(16.dp)
            )
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(
                items = entries,
                key = { entry -> entry.conversation?.conversationId ?: "h-${entry.header}" }
            ) { entry ->
                val header = entry.header
                if (header != null) {
                    Text(
                        text = header,
                        fontSize = 11.sp,
                        color = colors.faint,
                        modifier = Modifier.padding(start = 9.dp, top = 14.dp, bottom = 6.dp)
                    )
                } else {
                    val conversation = entry.conversation!!
                    ConversationRow(
                        conversation = conversation,
                        selected = conversation.conversationId == currentId,
                        pinned = conversation.conversationId in pinned,
                        onOpen = { onOpen(conversation.conversationId) },
                        onTogglePin = {
                            val next = ArrayList(pinned)
                            if (!next.remove(conversation.conversationId)) {
                                next.add(conversation.conversationId)
                            }
                            pinned = next
                        },
                        onRename = { onRename(conversation) },
                        onDelete = { onDelete(conversation) }
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface2)
                    .clickable(onClick = onOpenFiles)
                    .semantics { contentDescription = "文件工作区" },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = RhmIcons.Folder,
                    contentDescription = null,
                    tint = colors.ink2,
                    modifier = Modifier.size(21.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface2)
                    .clickable(onClick = onOpenSettings)
                    .semantics { contentDescription = "设置" },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = RhmIcons.Settings,
                    contentDescription = null,
                    tint = colors.ink2,
                    modifier = Modifier.size(21.dp)
                )
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val colors = LocalRhmColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.field)
            .border(1.dp, colors.line, RoundedCornerShape(10.dp))
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = RhmIcons.Search,
            contentDescription = null,
            tint = colors.faint,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            textStyle = TextStyle(fontSize = 14.sp, color = colors.ink),
            cursorBrush = SolidColor(colors.accent),
            maxLines = 1,
            modifier = Modifier.weight(1f).semantics { contentDescription = "搜索对话" },
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text("搜索对话", fontSize = 14.sp, color = colors.faint)
                    }
                    inner()
                }
            }
        )
    }
}

@Composable
private fun ConversationRow(
    conversation: ConversationSummary,
    selected: Boolean,
    pinned: Boolean,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = LocalRhmColors.current
    val scope = rememberCoroutineScope()
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val maxOffset = with(LocalDensity.current) { 186.dp.toPx() }
    val offsetX = remember { Animatable(0f) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
    ) {
        Row(modifier = Modifier.matchParentSize(), horizontalArrangement = Arrangement.End) {
            SwipeAction("重命名", RhmIcons.Edit, SwipeRenameColor) {
                scope.launch { offsetX.animateTo(0f) }
                onRename()
            }
            SwipeAction(if (pinned) "取消置顶" else "置顶", RhmIcons.Pin, SwipePinColor) {
                onTogglePin()
                scope.launch { offsetX.animateTo(0f) }
            }
            SwipeAction("删除", RhmIcons.Trash, SwipeDeleteColor) {
                scope.launch { offsetX.animateTo(0f) }
                onDelete()
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .background(if (selected) colors.accentSoft else colors.surface)
                .pointerInput(conversation.conversationId) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch {
                                offsetX.snapTo((offsetX.value + dragAmount).coerceIn(-maxOffset, 0f))
                            }
                        },
                        onDragEnd = {
                            scope.launch {
                                offsetX.animateTo(if (offsetX.value < -maxOffset / 3f) -maxOffset else 0f)
                            }
                        }
                    )
                }
                .pointerInput(conversation.conversationId) {
                    detectTapGestures(
                        onTap = {
                            if (offsetX.value < -1f) {
                                scope.launch { offsetX.animateTo(0f) }
                            } else {
                                onOpen()
                            }
                        },
                        onLongPress = {
                            scope.launch { offsetX.animateTo(-maxOffset) }
                        }
                    )
                }
                .padding(horizontal = 11.dp, vertical = 11.dp)
                .semantics { contentDescription = "会话 ${conversation.title}" },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (pinned) RhmIcons.Pin else RhmIcons.Chat,
                contentDescription = null,
                tint = if (selected) colors.accentSoftInk else colors.faint,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.title.ifEmpty { conversation.conversationId },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (selected) colors.accentSoftInk else colors.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = timeFormat.format(Date(conversation.updatedAt * 1000L)),
                    fontSize = 11.sp,
                    color = colors.muted,
                    modifier = Modifier.padding(top = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun RowScope.SwipeAction(
    label: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(62.dp)
            .fillMaxHeight()
            .background(color)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, color = Color.White)
    }
}

private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
    }
    return uri.lastPathSegment
}

@Composable
private fun MessageBubble(message: ChatViewModel.Message) {
    val colors = LocalRhmColors.current
    val clipboard = LocalClipboardManager.current
    if (message.role == Role.USER) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Box(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .clip(RoundedCornerShape(16.dp, 16.dp, 6.dp, 16.dp))
                    .background(colors.accentSoft)
                    .padding(horizontal = 15.dp, vertical = 12.dp)
            ) {
                Text(message.text, fontSize = 15.sp, color = colors.accentSoftInk, lineHeight = 24.sp)
            }
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(colors.accentSoft),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        ">_",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = colors.accentSoftInk
                    )
                }
                Spacer(Modifier.width(7.dp))
                Text("opencode", fontSize = 12.sp, color = colors.muted)
            }
            Spacer(Modifier.height(8.dp))
            if (message.streaming && message.text.isEmpty() && message.tools.isEmpty()) {
                Row(
                    modifier = Modifier.semantics { contentDescription = "正在思考" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TypingDots(colors.accent)
                    Text("正在思考…", fontSize = 12.sp, color = colors.muted)
                }
            } else {
                Text(
                    text = message.text + if (message.streaming) " ▍" else "",
                    fontSize = 15.sp,
                    color = colors.ink,
                    lineHeight = 24.sp
                )
            }
            message.tools.forEach { tool ->
                ToolCard(tool)
            }
            if (!message.streaming && message.text.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .size(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { clipboard.setText(AnnotatedString(message.text)) }
                        .semantics { contentDescription = "复制回复" },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = RhmIcons.Copy,
                        contentDescription = null,
                        tint = colors.faint,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolCard(tool: ChatViewModel.ToolEntry) {
    val colors = LocalRhmColors.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface2)
            .border(1.dp, colors.line, RoundedCornerShape(10.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .semantics { contentDescription = "工具 ${tool.name}" }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                tool.name,
                fontSize = 11.sp,
                color = colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (tool.running) "运行中…" else "已完成",
                fontSize = 10.sp,
                color = if (tool.running) colors.accent else colors.ok
            )
        }
        if (expanded && tool.output != null) {
            Text(
                tool.output.take(4000),
                fontSize = 11.sp,
                lineHeight = 18.sp,
                color = colors.muted,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
            )
        }
    }
}

@Composable
private fun TypingDots(color: Color) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 520, delayMillis = index * 130),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$index"
            )
            Box(Modifier.size(5.dp).clip(CircleShape).background(color.copy(alpha = alpha)))
        }
    }
}

// ---- Preview：每种状态都要有，喂假数据、不联网（契约 C 的 C4） ----

@Preview(name = "空态", showBackground = true)
@Composable
private fun PreviewEmpty() {
    MaterialTheme {
        ChatScreen(state = ChatViewModel.UiState(), bridgeNotice = null, onSend = {})
    }
}

@Preview(name = "流式中", showBackground = true)
@Composable
private fun PreviewStreaming() {
    MaterialTheme {
        ChatScreen(
            state = ChatViewModel.UiState(
                messages = listOf(
                    ChatViewModel.Message(Role.USER, "列一下家目录"),
                    ChatViewModel.Message(Role.ASSISTANT, "正在执行 ls ~", streaming = true)
                ),
                streaming = true
            ),
            bridgeNotice = null,
            onSend = {}
        )
    }
}

@Preview(name = "未连接 Bridge", showBackground = true)
@Composable
private fun PreviewDisconnected() {
    MaterialTheme {
        ChatScreen(
            state = ChatViewModel.UiState(
                messages = listOf(ChatViewModel.Message(Role.ASSISTANT, "echo: 演示模式")),
                error = "连接中断：Connection refused"
            ),
            bridgeNotice = "Bridge 未启动：聊天内容来自本地演示适配器，不会真的调用 Agent",
            onSend = {}
        )
    }
}

@Preview(name = "带附件", showBackground = true)
@Composable
private fun PreviewAttachments() {
    MaterialTheme {
        ChatScreen(
            state = ChatViewModel.UiState(
                pendingAttachments = listOf(
                    com.termux.app.chat.UploadedFile("report.pdf", "~/rhmdir/report.pdf", 20480)
                )
            ),
            bridgeNotice = null,
            onSend = {}
        )
    }
}
