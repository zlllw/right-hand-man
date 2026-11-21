package com.termux.app

import android.content.Context
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 运行环境准备（任务书阶段 1）。
 *
 * 职责只有三件：探测 `$PREFIX/bin/python3` 与 `node` 是否真的能跑、把
 * `assets/env-prep.sh` 落到应用私有目录、执行它并把每一行输出抛给 UI。
 *
 * 为什么不在 Kotlin 里做路径改写：deb 解包 / 重打包 / 等长字节替换都是
 * `dpkg-deb` 与 `sed` 的活，脚本比 Kotlin 短得多，且能在真机 adb 里单独复现
 * （见 `scripts/env-prep-survey.md` 记录的实测过程）。
 */
class EnvPreparer(private val context: Context) {

    sealed interface Event {
        /** 脚本的一行输出，原样展示给用户。 */
        data class Line(val text: String) : Event

        /** 流程结束；[detail] 是给人看的结论，[ok] 决定 UI 显示成功还是失败。 */
        data class Finished(val ok: Boolean, val detail: String) : Event
    }

    data class Probe(
        val pythonVersion: String?,
        val nodeVersion: String?,
        val npmVersion: String?,
        val bash: Boolean
    ) {
        /** npm 也算必备：opencode / Claude Code 都靠它装 */
        val needsSetup: Boolean
            get() = pythonVersion == null || nodeVersion == null || npmVersion == null

        val summary: String
            get() = when {
                needsSetup -> "运行环境未就绪"
                else -> "python $pythonVersion · node $nodeVersion"
            }
    }

    private val prefix = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH)

    fun probe(): Probe {
        val bash = File(prefix, "bin/bash")
        return Probe(
            pythonVersion = versionNumber(runOrNull(File(prefix, "bin/python3"), "--version")),
            nodeVersion = versionNumber(runOrNull(File(prefix, "bin/node"), "--version")),
            npmVersion = versionNumber(runOrNull(File(prefix, "bin/npm"), "--version")),
            bash = bash.canExecute()
        )
    }

    /** `"Python 3.14.10"` → `"3.14.10"`，避免 UI 出现 `python Python 3.14.10` 这种重复。 */
    internal fun versionNumber(raw: String?): String? =
        raw?.let { Regex("""\d+(?:\.\d+)+""").find(it)?.value }

    /**
     * 执行环境准备。产出大量 [Event.Line]，最后必有且只有一个 [Event.Finished]。
     * 失败不抛异常，只体现在 [Event.Finished.ok] 上——UI 不会因为异常悄悄停住。
     */
    fun prepare(): Flow<Event> = flow {
        val script = runCatching { installScript() }.getOrElse { error ->
            emit(Event.Finished(false, "无法写入安装脚本：${error.message}"))
            return@flow
        }
        val bash = File(prefix, "bin/bash")
        if (!bash.canExecute()) {
            emit(Event.Finished(false, "bash 不存在：基础环境尚未初始化"))
            return@flow
        }
        val process = runCatching {
            ProcessBuilder(bash.absolutePath, script.absolutePath)
                .redirectErrorStream(true)
                .apply { environment().putAll(childEnv()) }
                .start()
        }.getOrElse { error ->
            emit(Event.Finished(false, "无法启动 bash：${error.message}"))
            return@flow
        }

        val exitCode = coroutineScope {
            val watchdog = launch {
                delay(TIMEOUT_MINUTES * 60_000L)
                process.destroyForcibly()
            }
            try {
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        emit(Event.Line(line))
                    }
                }
                process.waitFor()
            } finally {
                watchdog.cancel()
            }
        }

        val after = probe()
        when {
            exitCode == 0 && !after.needsSetup ->
                emit(Event.Finished(true, after.summary))
            exitCode == 0 ->
                emit(Event.Finished(false, "脚本报成功，但 python3 / node 仍不可用"))
            else ->
                emit(Event.Finished(false, "环境准备失败（脚本退出码 $exitCode）"))
        }
    }.flowOn(Dispatchers.IO)

    private fun installScript(): File {
        val target = File(context.filesDir, "env-prep.sh")
        context.assets.open("env-prep.sh").use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        target.setExecutable(true, true)
        return target
    }

    private fun childEnv(): Map<String, String> = mapOf(
        "PREFIX" to prefix.absolutePath,
        "HOME" to File(prefix.parentFile, "home").absolutePath,
        "PATH" to "${prefix.absolutePath}/bin:/system/bin",
        "TMPDIR" to File(prefix, "tmp").absolutePath,
        "LANG" to "en_US.UTF-8",
        "LC_ALL" to "en_US.UTF-8",
        // 避免 dpkg 因为缺少 locale / 终端而提前失败
        "TERM" to "dumb"
    )

    private fun runOrNull(binary: File, argument: String): String? {
        if (!binary.canExecute()) return null
        return runCatching {
            val process = ProcessBuilder(binary.absolutePath, argument)
                .redirectErrorStream(true)
                .apply { environment().putAll(childEnv()) }
                .start()
            val text = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0) return null
            text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        }.getOrNull()
    }

    private companion object {
        const val TIMEOUT_MINUTES = 20L
    }
}