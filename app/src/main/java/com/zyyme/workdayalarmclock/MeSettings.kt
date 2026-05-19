package com.zyyme.workdayalarmclock

import android.content.Context

/**
 * 设置
 * 代替之前的文件flag
 */
object MeSettings {
    private const val PREFS_NAME = "me_settings"

    const val KEY_DISABLE = "disable"
    const val KEY_CLOCK = "clock"
    const val KEY_TSS = "tss"
    const val KEY_T24 = "t24"
    const val KEY_WHITE = "white"
    const val KEY_LANDSCAPE = "landscape"
    const val KEY_VERTICAL = "vertical"
    const val KEY_ROUND = "round"
    const val KEY_AP = "ap"
    const val KEY_AUTO_BACK_CLOCK = "auto_back_clock"

    fun isEnabled(context: Context, key: String): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(key, false)
    }

    fun setEnabled(context: Context, key: String, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key, enabled)
            .apply()
    }
}
