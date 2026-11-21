package com.termux.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 运行环境探测的纯逻辑测试（契约 C 的 L2：不碰设备、不跑脚本）。
 *
 * 覆盖两件真实踩过的事：
 * 1. `python3 --version` 输出的是 `Python 3.14.10`，直接用会渲染成 `python Python 3.14.10`；
 * 2. npm 缺失时 `needsSetup` 必须为 true，否则 UI 显示就绪但 opencode 装不上。
 */
@RunWith(RobolectricTestRunner::class)
class EnvPreparerVersionTest {

    // 构造 EnvPreparer 只保存 Context 引用并按常量算出 prefix 路径，不会碰真实文件系统
    private val preparer = EnvPreparer(RuntimeEnvironment.getApplication())

    @Test
    fun pythonBannerIsNormalised() {
        assertEquals("3.14.10", preparer.versionNumber("Python 3.14.10"))
    }

    @Test
    fun nodeBannerIsNormalised() {
        assertEquals("26.13.1", preparer.versionNumber("v26.13.1"))
    }

    @Test
    fun npmBannerIsNormalised() {
        assertEquals("12.2.3", preparer.versionNumber("12.2.3"))
    }

    @Test
    fun unparsableOutputIsNull() {
        assertNull(preparer.versionNumber("command not found"))
        assertNull(preparer.versionNumber(null))
    }

    @Test
    fun summaryHasNoRepeatedWord() {
        val probe = EnvPreparer.Probe(
            pythonVersion = preparer.versionNumber("Python 3.14.10"),
            nodeVersion = preparer.versionNumber("v26.13.1"),
            npmVersion = preparer.versionNumber("12.2.3"),
            bash = true
        )
        assertEquals("python 3.14.10 · node 26.13.1", probe.summary)
        assertFalse(probe.needsSetup)
    }

    @Test
    fun missingNpmStillNeedsSetup() {
        val probe = EnvPreparer.Probe("3.14.10", "26.13.1", null, true)
        assertTrue(probe.needsSetup)
        assertEquals("运行环境未就绪", probe.summary)
    }

    @Test
    fun missingPythonNeedsSetup() {
        assertTrue(EnvPreparer.Probe(null, "26.13.1", "12.2.3", true).needsSetup)
    }
}