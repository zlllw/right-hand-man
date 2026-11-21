package com.termux.app.ui.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppearanceMode { SYSTEM, LIGHT, DARK }

enum class ThemePreset(val label: String) {
    BLUE("科技蓝"),
    TEAL("松绿"),
    CLAY("赭陶"),
    GRAPHITE("石墨")
}

/**
 * 外观设置（模式 + 主题色）的单一来源：设置面板读改写这里，MainActivity 收集后驱动 [RhmTheme]。
 * SharedPreferences 持久化；与测试同进程，也方便后续被 instrumented 测试直接改写。
 */
object RhmAppearance {

    private const val PREFS_NAME = "rhm_appearance"
    private const val KEY_MODE = "mode"
    private const val KEY_THEME = "theme"

    private var prefs: SharedPreferences? = null

    private val _mode = MutableStateFlow(AppearanceMode.SYSTEM)
    val mode: StateFlow<AppearanceMode> = _mode.asStateFlow()

    private val _theme = MutableStateFlow(ThemePreset.GRAPHITE)
    val theme: StateFlow<ThemePreset> = _theme.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = store
        _mode.value = runCatching {
            AppearanceMode.valueOf(store.getString(KEY_MODE, AppearanceMode.SYSTEM.name)!!)
        }.getOrDefault(AppearanceMode.SYSTEM)
        _theme.value = runCatching {
            ThemePreset.valueOf(store.getString(KEY_THEME, ThemePreset.GRAPHITE.name)!!)
        }.getOrDefault(ThemePreset.GRAPHITE)
    }

    fun setMode(mode: AppearanceMode) {
        _mode.value = mode
        prefs?.edit()?.putString(KEY_MODE, mode.name)?.apply()
    }

    fun setTheme(theme: ThemePreset) {
        _theme.value = theme
        prefs?.edit()?.putString(KEY_THEME, theme.name)?.apply()
    }
}
