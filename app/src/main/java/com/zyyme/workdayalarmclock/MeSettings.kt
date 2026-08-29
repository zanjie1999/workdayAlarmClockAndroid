package com.zyyme.workdayalarmclock

import android.content.Context
import android.content.Intent

/**
 * 设置
 * 代替之前的文件flag
 */
object MeSettings {
    private const val PREFS_NAME = "me_settings"

    const val KEY_DISABLE = "disable"
    const val KEY_CLOCK = "clock"
    const val KEY_DESK_CLOCK = "desk_clock"
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
    const val KEY_CAMERA_SERVER = "camera_server"
    const val KEY_CAMERA_PASSWORD = "camera_password"
    const val KEY_CAMERA_AUTO_BRIGHTNESS = "camera_auto_brightness"
    const val KEY_CAMERA_BRIGHTNESS_0 = "camera_brightness_0"
    const val KEY_CAMERA_BRIGHTNESS_1 = "camera_brightness_1"
    const val KEY_CAMERA_BRIGHTNESS_2 = "camera_brightness_2"
    const val KEY_CAMERA_BRIGHTNESS_3 = "camera_brightness_3"
    const val KEY_CAMERA_BRIGHTNESS_4 = "camera_brightness_4"
    const val KEY_CAMERA_CLOSE_SCREEN = "camera_close_screen"
    const val KEY_CAMERA_CLOSE_SCREEN_LEVEL_1 = "camera_close_screen_level_1"
    const val KEY_CAMERA_CLOSE_SCREEN_KEEP_SCREEN_ON = "camera_close_screen_keep_screen_on"
    const val KEY_CAMERA_AUTO_WAKE_LEVEL = "camera_auto_wake_level"
    const val KEY_CAMERA_BRIGHTNESS_INTERVAL = "camera_brightness_interval"
    const val KEY_DESK_MASK = "desk_mask"
    const val KEY_DESK_LIGHT_TEXT = "desk_light_text"
    const val KEY_DESK_KEEP_SCREEN_ON = "desk_keep_screen_on"
    const val KEY_DESK_SLOT_TOP_LEFT = "desk_slot_top_left"
    const val KEY_DESK_SLOT_TOP_RIGHT = "desk_slot_top_right"
    const val KEY_DESK_SLOT_BOTTOM_LEFT = "desk_slot_bottom_left"
    const val KEY_DESK_SLOT_BOTTOM_RIGHT = "desk_slot_bottom_right"

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context, key: String, defaultValue: Boolean = false): Boolean {
        return preferences(context).getBoolean(key, defaultValue)
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

    fun getInt(context: Context, key: String, defaultValue: Int): Int {
        return preferences(context).getInt(key, defaultValue)
    }

    fun setInt(context: Context, key: String, value: Int) {
        preferences(context)
            .edit()
            .putInt(key, value)
            .apply()
    }

    fun getString(context: Context, key: String, defaultValue: String): String {
        return preferences(context).getString(key, defaultValue).orEmpty()
    }

    fun setString(context: Context, key: String, value: String) {
        preferences(context).edit().putString(key, value).apply()
    }

    fun createClockIntent(context: Context): Intent {
        val activityClass = if (isEnabled(context, KEY_DESK_CLOCK)) {
            DeskActivity::class.java
        } else {
            ClockActivity::class.java
        }
        return Intent(context, activityClass)
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

    fun getCameraPassword(context: Context): String {
        return preferences(context)
            .getString(KEY_CAMERA_PASSWORD, "")
            .orEmpty()
    }

    fun setCameraPassword(context: Context, password: String) {
        val cleanPassword = password.trim().trim('/')
        val editor = preferences(context).edit()
        if (cleanPassword.isEmpty()) {
            editor.remove(KEY_CAMERA_PASSWORD)
        } else {
            editor.putString(KEY_CAMERA_PASSWORD, cleanPassword)
        }
        editor.apply()
    }
}
