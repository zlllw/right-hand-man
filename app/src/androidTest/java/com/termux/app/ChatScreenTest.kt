package com.termux.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.termux.app.chat.ChatViewModel
import com.termux.app.chat.ChatViewModel.Role
import com.termux.app.chat.ConversationSummary
import com.termux.app.chat.UploadedFile
import com.termux.app.chat.WorkspaceFile
import com.termux.app.ui.ChatScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * ChatScreen 的 Compose UI 测试（契约 C 的 L4：无状态、可预览、可测）。
 *
 * 全部用语义节点定位（`onNodeWithText` / `hasSetTextAction`），不碰坐标、
 * 不依赖分辨率与状态栏高度——这也正是换掉 uiautomator 坐标点击的原因。
 */
class ChatScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptyStateShowsHintAndSendDisabled() {
        compose.setContent {
            MaterialTheme {
                ChatScreen(state = ChatViewModel.UiState(), bridgeNotice = null, onSend = {})
            }
        }

        // 空态两行文案（第二行提示 ~/rhmdir 共享目录），用 substring 避免绑定换行符
        compose.onNodeWithText("说点什么，Agent 会在终端里执行", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("发送消息").assertIsNotEnabled()
    }

    @Test
    fun typingThenSendingHandsTextToCallback() {
        val sent = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatViewModel.UiState(),
                    bridgeNotice = null,
                    onSend = { sent += it }
                )
            }
        }

        compose.onNodeWithContentDescription("消息输入").performClick()
        compose.onNodeWithContentDescription("消息输入").performTextInput("hello")
        compose.onNodeWithContentDescription("发送消息").assertIsEnabled().performClick()

        assertEquals(listOf("hello"), sent)
    }

    @Test
    fun streamingShowsStopButtonThatStops() {
        var stopped = false
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatViewModel.UiState(
                        messages = listOf(
                            ChatViewModel.Message(Role.USER, "列出家目录"),
                            ChatViewModel.Message(Role.ASSISTANT, "正在执行 ls ~", streaming = true)
                        ),
                        streaming = true
                    ),
                    bridgeNotice = null,
                    onSend = {},
                    onStop = { stopped = true }
                )
            }
        }

        compose.onNodeWithText("列出家目录").assertIsDisplayed()
        compose.onNodeWithText("正在执行 ls ~", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("停止生成").assertIsEnabled().performClick()
        assert(stopped)
    }

    @Test
    fun toolCardExpandsToShowOutput() {
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatViewModel.UiState(
                        messages = listOf(
                            ChatViewModel.Message(
                                role = Role.ASSISTANT,
                                text = "",
                                tools = listOf(ChatViewModel.ToolEntry("ls", "a.txt\nb.txt", false))
                            )
                        )
                    ),
                    bridgeNotice = null,
                    onSend = {}
                )
            }
        }

        compose.onNodeWithContentDescription("工具 ls").performClick()
        compose.onNodeWithText("a.txt", substring = true).assertIsDisplayed()
    }

    @Test
    fun bridgeNoticeIsRenderedWhenNotConnected() {
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatViewModel.UiState(),
                    bridgeNotice = "Bridge 未启动：聊天内容来自本地演示适配器",
                    bridgeLoading = true,
                    onSend = {}
                )
            }
        }

        compose.onNodeWithText("Bridge 未启动：聊天内容来自本地演示适配器").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertIsDisplayed()
    }

    @Test
    fun errorLineIsRendered() {
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatViewModel.UiState(error = "连接中断：Connection refused"),
                    bridgeNotice = null,
                    onSend = {}
                )
            }
        }

        compose.onNodeWithText("连接中断：Connection refused").assertIsDisplayed()
    }

    @Test
    fun attachmentChipAloneEnablesSend() {
        val sent = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatViewModel.UiState(
                        pendingAttachments = listOf(
                            UploadedFile("a.txt", "~/rhmdir/a.txt", 2048)
                        )
                    ),
                    bridgeNotice = null,
                    onSend = { sent += it }
                )
            }
        }

        compose.onNodeWithText("📎 a.txt（2KB）").assertIsDisplayed()
        // 只有附件没有文字也允许发送（附件路径清单会附在消息尾）
        compose.onNodeWithContentDescription("发送消息").assertIsEnabled().performClick()
        assertEquals(listOf(""), sent)
    }

    @Test
    fun uploadingShowsChipAndLibraryEntry() {
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatViewModel.UiState(uploading = true),
                    bridgeNotice = null,
                    onSend = {}
                )
            }
        }

        compose.onNodeWithText("上传中…").assertIsDisplayed()
        compose.onNodeWithContentDescription("打开文件库").assertIsEnabled()
    }

    @Test
    fun historyDrawerListsConversationsAndOpensOnTap() {
        val opened = mutableListOf<String>()
        var historyRequested = false
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatViewModel.UiState(
                        conversationId = "c2",
                        conversations = listOf(
                            ConversationSummary("c1", "旧会话", 1_795_193_436L)
                        )
                    ),
                    bridgeNotice = null,
                    onSend = {},
                    onRequestHistory = { historyRequested = true },
                    onOpenConversation = { opened += it }
                )
            }
        }

        compose.onNodeWithContentDescription("打开历史侧边栏").performClick()
        assert(historyRequested)

        compose.onNodeWithText("对话历史").assertIsDisplayed()
        compose.onNodeWithText("旧会话").assertIsDisplayed().performClick()
        assertEquals(listOf("c1"), opened)

        // 关闭按钮：抽屉标题右侧 ×
        compose.onNodeWithContentDescription("打开历史侧边栏").performClick()
        compose.onNodeWithContentDescription("关闭历史侧边栏").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("对话历史").assertIsNotDisplayed()
    }

    @Test
    fun swipeRightOnChatOpensHistoryDrawer() {
        compose.setContent {
            MaterialTheme {
                ChatScreen(state = ChatViewModel.UiState(), bridgeNotice = null, onSend = {})
            }
        }

        compose.onNodeWithText("说点什么，Agent 会在终端里执行", substring = true)
            .performTouchInput { swipeRight() }
        compose.onNodeWithText("对话历史").assertIsDisplayed()
    }

    @Test
    fun tappingBlankAreaClosesDrawer() {
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatViewModel.UiState(
                        conversationId = "c2",
                        conversations = listOf(
                            ConversationSummary("c1", "旧会话", 1_795_193_436L)
                        )
                    ),
                    bridgeNotice = null,
                    onSend = {}
                )
            }
        }

        compose.onNodeWithContentDescription("打开历史侧边栏").performClick()
        compose.onNodeWithText("对话历史").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭抽屉区域").performTouchInput {
            click(position = Offset(width * 0.92f, height * 0.3f))
        }
        compose.waitForIdle()
        compose.onNodeWithText("对话历史").assertIsNotDisplayed()
    }

    @Test
    fun filesSheetReferencesExistingFile() {
        val attached = mutableListOf<WorkspaceFile>()
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatViewModel.UiState(
                        workspaceFiles = listOf(
                            WorkspaceFile("notes.md", 2048, "text/markdown")
                        )
                    ),
                    bridgeNotice = null,
                    onSend = {},
                    onAttachExisting = { attached += it }
                )
            }
        }

        compose.onNodeWithContentDescription("打开历史侧边栏").performClick()
        compose.onNodeWithContentDescription("文件工作区").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("notes.md").assertIsDisplayed()
        compose.onNodeWithContentDescription("引用 notes.md").performClick()
        compose.waitForIdle()
        assertEquals("notes.md", attached.single().name)
    }

    @Test
    fun fileRowSwipeDeleteHandsNameToCallback() {
        val deleted = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatViewModel.UiState(
                        workspaceFiles = listOf(
                            WorkspaceFile("notes.md", 2048, "text/markdown")
                        )
                    ),
                    bridgeNotice = null,
                    onSend = {},
                    onDeleteFile = { deleted += it }
                )
            }
        }

        compose.onNodeWithContentDescription("打开历史侧边栏").performClick()
        compose.onNodeWithContentDescription("文件工作区").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("文件 notes.md").performTouchInput { swipeLeft() }
        compose.onNodeWithContentDescription("删除 notes.md").performClick()
        compose.onNodeWithText("确认删除").performClick()
        compose.waitForIdle()
        assertEquals(listOf("notes.md"), deleted)
    }

    @Test
    fun typingIndicatorShowsWhileWaitingFirstToken() {
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatViewModel.UiState(
                        messages = listOf(
                            ChatViewModel.Message(Role.ASSISTANT, "", streaming = true)
                        ),
                        streaming = true
                    ),
                    bridgeNotice = null,
                    onSend = {}
                )
            }
        }

        compose.onNodeWithContentDescription("正在思考").assertIsDisplayed()
    }

    @Test
    fun longPressRevealsActionsAndRenameHandsTitleToCallback() {
        val renames = mutableListOf<Pair<String, String>>()
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    state = ChatViewModel.UiState(
                        conversationId = "c2",
                        conversations = listOf(
                            ConversationSummary("c1", "旧会话", 1_795_193_436L)
                        )
                    ),
                    bridgeNotice = null,
                    onSend = {},
                    onRenameConversation = { id, title -> renames += id to title }
                )
            }
        }

        compose.onNodeWithContentDescription("打开历史侧边栏").performClick()
        compose.onNodeWithContentDescription("会话 旧会话").performTouchInput { longClick() }
        compose.onNodeWithText("重命名").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("对话标题").performTextReplacement("新标题")
        compose.onNodeWithText("保存").performClick()
        assertEquals(listOf("c1" to "新标题"), renames)
    }
}
