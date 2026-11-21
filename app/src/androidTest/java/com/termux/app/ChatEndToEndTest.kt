package com.termux.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.termux.app.bridge.BridgeProcess
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 端到端 instrumented 测试（任务书阶段 1d）：真实 Activity → 真实 Bridge → 真实 SSE。
 *
 * 全程走 Compose 语义树，不做任何坐标计算：
 * 1. `waitUntil` 等 EnvPanel 出现「运行环境：」——说明 python/node/npm 探测通过；
 * 2. `waitUntil` 等含「Bridge」的提示消失——那行只在 Bridge 未探活成功时渲染；
 * 3. 输入 `hello` 点发送，等 `echo: hello` 出现——fake adapter 的固定回声，
 *    这条气泡是经过 Bridge 的 `/chat` + SSE 才回来的，能证明整条链路通了。
 *
 * 依赖真机环境：bootstrap 已解压、python/node/npm 已装（首次跑需点过「准备运行环境」）。
 */
@RunWith(AndroidJUnit4::class)
class ChatEndToEndTest {

    companion object {
        /**
         * App 现在默认会接 opencode（真 AI、回复不确定），本测试断言固定回声
         * 「echo: hello」，所以在 Activity 启动前把适配器钉回 fake。
         * @BeforeClass 保证早于任何 Rule 实例化与 Activity 启动。
         */
        @JvmStatic
        @BeforeClass
        fun forceFakeAdapter() {
            BridgeProcess.adapterOverride = "fake"
        }
    }

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun messageTravelsThroughBridgeAndStreamsBack() {
        // 运行环境就绪后 Bridge 才会被拉起；「Bridge」横幅消失即代表探活通过
        compose.waitUntil("Bridge 应探活通过（提示横幅消失）", timeoutMillis = 120_000) {
            !textExists("Bridge", substring = true)
        }

        compose.onNodeWithContentDescription("消息输入").performClick()
        compose.onNodeWithContentDescription("消息输入").performTextInput("hello")
        compose.onNodeWithContentDescription("发送消息").performClick()

        compose.waitUntil("应收到 Bridge 流式回声", timeoutMillis = 30_000) {
            textExists("echo: hello", substring = true)
        }
    }

    private fun textExists(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
}