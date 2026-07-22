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
    const val KEY_LYRICS = "lyrics"
    const val KEY_LANDSCAPE = "landscape"
    const val KEY_VERTICAL = "vertical"
    const val KEY_ROUND = "round"
    const val KEY_AP = "ap"
    const val KEY_AUTO_BACK_CLOCK = "auto_back_clock"
    const val KEY_NOTIFICATION_FORWARD_URL = "notification_forward_url"

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context, key: String): Boolean {
        return preferences(context).getBoolean(key, false)
    }

    fun getEnabledKeys(context: Context, keys: Iterable<String>): Set<String> {
        val preferences = preferences(context)
        return keys.filterTo(mutableSetOf()) { key ->
            preferences.getBoolean(key, false)
        }
    }

    fun setEnabled(context: Context, key: String, enabled: Boolean) {
        preferences(context)
            .edit()
            .putBoolean(key, enabled)
            .apply()
    }

    fun getNotificationForwardUrl(context: Context): String {
        return preferences(context)
            .getString(KEY_NOTIFICATION_FORWARD_URL, "")
            .orEmpty()
    }

    fun setNotificationForwardUrl(context: Context, url: String) {
        val cleanUrl = url.trim()
        val editor = preferences(context).edit()
        if (cleanUrl.isEmpty()) {
            editor.remove(KEY_NOTIFICATION_FORWARD_URL)
        } else {
            editor.putString(KEY_NOTIFICATION_FORWARD_URL, cleanUrl)
        }
        editor.apply()
    }
}
