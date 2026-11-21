package com.termux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.app.chat.ChatViewModel
import com.termux.app.chat.WorkspaceFile
import com.termux.app.ui.theme.LocalRhmColors
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private fun humanSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/** 工作区面板：浏览 ~/rhmdir、文本预览、引用已有文件到当前对话。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesSheet(
    files: List<WorkspaceFile>,
    preview: ChatViewModel.WorkspacePreview?,
    onDismiss: () -> Unit,
    onPreview: (String) -> Unit,
    onClosePreview: () -> Unit,
    onAttach: (WorkspaceFile) -> Unit,
    onDownload: (WorkspaceFile) -> Unit,
    onDelete: (WorkspaceFile) -> Unit,
    onUpload: () -> Unit
) {
    val colors = LocalRhmColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        contentColor = colors.ink,
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.line) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.64f)
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "工作区文件",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.ink,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surface2)
                        .border(1.dp, colors.line, RoundedCornerShape(10.dp))
                        .clickable(onClick = onUpload)
                        .semantics { contentDescription = "上传文件" },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = RhmIcons.Plus,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Text(
                "~/rhmdir",
                fontSize = 12.sp,
                color = colors.muted,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
            )
            HorizontalDivider(color = colors.line)
            if (preview != null) {
                PreviewContent(preview = preview, onClose = onClosePreview, onAttach = onAttach)
            } else {
                if (files.isEmpty()) {
                    Text(
                        "文件夹还是空的\n把资料上传到对话，或直接在 ~/rhmdir 里放文件。",
                        fontSize = 13.sp,
                        lineHeight = 21.sp,
                        color = colors.muted,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(files, key = { it.name }) { file ->
                        FileRow(
                            file = file,
                            onPreview = onPreview,
                            onAttach = onAttach,
                            onDownload = onDownload,
                            onDelete = onDelete
                        )
                    }
                }
            }
        }
    }
}

private val FileDownloadColor = Color(0xFF2F78F0)
private val FileDeleteColor = Color(0xFFD9534F)

@Composable
private fun FileRow(
    file: WorkspaceFile,
    onPreview: (String) -> Unit,
    onAttach: (WorkspaceFile) -> Unit,
    onDownload: (WorkspaceFile) -> Unit,
    onDelete: (WorkspaceFile) -> Unit
) {
    val colors = LocalRhmColors.current
    val scope = rememberCoroutineScope()
    val maxOffset = with(LocalDensity.current) { 124.dp.toPx() }
    val offsetX = remember(file.name) { Animatable(0f) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
    ) {
        Row(
            modifier = Modifier.matchParentSize(),
            horizontalArrangement = Arrangement.End
        ) {
            FileAction("下载", RhmIcons.Download, FileDownloadColor, "下载 ${file.name}") {
                onDownload(file)
                scope.launch { offsetX.animateTo(0f) }
            }
            FileAction("删除", RhmIcons.Trash, FileDeleteColor, "删除 ${file.name}") {
                onDelete(file)
                scope.launch { offsetX.animateTo(0f) }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .background(colors.surface2)
                .pointerInput(file.name) {
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
                .clickable {
                    if (offsetX.value < -1f) {
                        scope.launch { offsetX.animateTo(0f) }
                    } else {
                        onPreview(file.name)
                    }
                }
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .semantics { contentDescription = "文件 ${file.name}" },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    file.name,
                    fontSize = 14.sp,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    humanSize(file.size),
                    fontSize = 11.sp,
                    color = colors.muted,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onAttach(file) }
                    .semantics { contentDescription = "引用 ${file.name}" },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = RhmIcons.Paperclip,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun FileAction(
    label: String,
    icon: ImageVector,
    color: Color,
    description: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(62.dp)
            .fillMaxHeight()
            .background(color)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, color = Color.White)
    }
}

@Composable
private fun ColumnScope.PreviewContent(
    preview: ChatViewModel.WorkspacePreview,
    onClose: () -> Unit,
    onAttach: (WorkspaceFile) -> Unit
) {
    val colors = LocalRhmColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            preview.name,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            "返回",
            fontSize = 12.sp,
            color = colors.accent,
            modifier = Modifier.clickable(onClick = onClose).padding(6.dp)
        )
    }
    Text(
        "~/rhmdir/${preview.name} · ${humanSize(preview.size)}",
        fontSize = 11.sp,
        color = colors.muted,
        modifier = Modifier.padding(bottom = 10.dp)
    )
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.field)
            .border(1.dp, colors.line, RoundedCornerShape(10.dp))
    ) {
        if (preview.text != null) {
            Text(
                preview.text,
                fontSize = 13.sp,
                lineHeight = 21.sp,
                color = colors.ink2,
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp)
            )
        } else {
            Text(
                "这个格式暂不预览，可以直接附到对话。",
                fontSize = 13.sp,
                color = colors.muted,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.accent)
            .clickable { onAttach(WorkspaceFile(preview.name, preview.size, "")) }
            .semantics { contentDescription = "附到当前对话" }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("附到当前对话", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.accentInk)
    }
}
