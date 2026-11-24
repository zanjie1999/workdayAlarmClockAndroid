package com.zyyme.workdayalarmclock

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate

/**
 * 设置
 * 代替之前的文件flag
 */
object MeSettings {
    private const val PREFS_NAME = "me_settings"

    const val KEY_DISABLE = "disable"
    const val KEY_NO_SCREEN = "no_screen"
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
    const val KEY_NOTIFICATION_FORWARD_SCREEN_OFF_ONLY = "notification_forward_screen_off_only"
    const val KEY_NOTIFICATION_FORWARD_BLACKLIST = "notification_forward_blacklist"
    const val KEY_TODO_URL = "todo_url"
    const val KEY_CAMERA_SERVER = "camera_server"
    const val KEY_COMPUTER_SPEAKER = "computer_speaker"
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
    const val KEY_CAMERA_FACE_WAKE = "camera_face_wake"
    const val KEY_DESK_MASK = "desk_mask"
    const val KEY_DESK_LIGHT_TEXT = "desk_light_text"
    const val KEY_DESK_KEEP_SCREEN_ON = "desk_keep_screen_on"
    const val KEY_DESK_SLOT_TOP_LEFT = "desk_slot_top_left"
    const val KEY_DESK_SLOT_TOP_RIGHT = "desk_slot_top_right"
    const val KEY_DESK_SLOT_BOTTOM_LEFT = "desk_slot_bottom_left"
    const val KEY_DESK_SLOT_BOTTOM_RIGHT = "desk_slot_bottom_right"
    const val KEY_DESK_LYRICS_POSITION = "desk_lyrics_position"

    // 小智AI 语音对话
    const val KEY_XIAOZHI = "xiaozhi"
    const val KEY_XIAOZHI_OTA_URL = "xiaozhi_ota_url"
    const val KEY_XIAOZHI_WS_URL = "xiaozhi_ws_url"
    const val KEY_XIAOZHI_TOKEN = "xiaozhi_token"
    const val KEY_XIAOZHI_DEVICE_MAC = "xiaozhi_device_mac"
    const val KEY_XIAOZHI_CLIENT_ID = "xiaozhi_client_id"
    const val KEY_XIAOZHI_MODE = "xiaozhi_mode"
    const val KEY_XIAOZHI_AUTO_CONTINUE = "xiaozhi_auto_continue"
    const val KEY_XIAOZHI_SILENCE_TIMEOUT = "xiaozhi_silence_timeout"
    const val KEY_XIAOZHI_PAUSE_MEDIA = "xiaozhi_pause_media"
    const val KEY_XIAOZHI_MIC_DENOISE = "xiaozhi_mic_denoise"
    const val KEY_XIAOZHI_ALLOW_PHOTO = "xiaozhi_allow_photo"
    const val KEY_XIAOZHI_VOICEWAKE = "xiaozhi_voicewake"
    const val KEY_XIAOZHI_TALK_INTERRUPT = "xiaozhi_talk_interrupt"
    const val KEY_XIAOZHI_WAKE_KEYWORDS = "xiaozhi_wake_keywords"
    const val KEY_XIAOZHI_WAKE_THRESHOLD = "xiaozhi_wake_threshold"
    const val KEY_XIAOZHI_WAKE_DENOISE = "xiaozhi_wake_denoise"

    const val DEFAULT_XIAOZHI_OTA_URL = "https://api.tenclass.net/xiaozhi/ota/"
    const val DEFAULT_XIAOZHI_WS_URL = "wss://api.tenclass.net/xiaozhi/v1/"
    const val DEFAULT_XIAOZHI_MODE = "auto"
    const val DEFAULT_XIAOZHI_SILENCE_TIMEOUT = 8

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

    fun applyClockTheme(context: Context) {
        val nightMode = if (isEnabled(context, KEY_WHITE)) {
            AppCompatDelegate.MODE_NIGHT_NO
        } else {
            AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
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

    fun getNotificationForwardBlacklist(context: Context): Set<String> {
        return preferences(context)
            .getStringSet(KEY_NOTIFICATION_FORWARD_BLACKLIST, emptySet())
            ?.toSet()
            .orEmpty()
    }

    fun setNotificationForwardBlacklist(context: Context, packageNames: Set<String>) {
        val cleanPackageNames = packageNames.filterTo(mutableSetOf()) { it.isNotBlank() }
        val editor = preferences(context).edit()
        if (cleanPackageNames.isEmpty()) {
            editor.remove(KEY_NOTIFICATION_FORWARD_BLACKLIST)
        } else {
            editor.putStringSet(KEY_NOTIFICATION_FORWARD_BLACKLIST, cleanPackageNames)
        }
        editor.apply()
    }

    fun getTodoUrl(context: Context): String {
        return preferences(context).getString(KEY_TODO_URL, "").orEmpty()
    }

    fun setTodoUrl(context: Context, url: String) {
        val cleanUrl = url.trim()
        val editor = preferences(context).edit()
        if (cleanUrl.isEmpty()) {
            editor.remove(KEY_TODO_URL)
        } else {
            editor.putString(KEY_TODO_URL, cleanUrl)
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

    // ---- 小智AI ----

    private fun getTrimmed(context: Context, key: String, def: String = ""): String {
        return preferences(context).getString(key, def).orEmpty().trim()
    }

    private fun putTrimmed(context: Context, key: String, value: String) {
        val cleanValue = value.trim()
        val editor = preferences(context).edit()
        if (cleanValue.isEmpty()) {
            editor.remove(key)
        } else {
            editor.putString(key, cleanValue)
        }
        editor.apply()
    }

    fun getXiaozhiOtaUrl(context: Context): String =
        getTrimmed(context, KEY_XIAOZHI_OTA_URL, DEFAULT_XIAOZHI_OTA_URL)
            .ifEmpty { DEFAULT_XIAOZHI_OTA_URL }

    fun setXiaozhiOtaUrl(context: Context, url: String) = putTrimmed(context, KEY_XIAOZHI_OTA_URL, url)

    fun getXiaozhiWsUrl(context: Context): String =
        getTrimmed(context, KEY_XIAOZHI_WS_URL, DEFAULT_XIAOZHI_WS_URL)
            .ifEmpty { DEFAULT_XIAOZHI_WS_URL }

    fun setXiaozhiWsUrl(context: Context, url: String) = putTrimmed(context, KEY_XIAOZHI_WS_URL, url)

    fun getXiaozhiToken(context: Context): String = getTrimmed(context, KEY_XIAOZHI_TOKEN)

    fun setXiaozhiToken(context: Context, token: String) = putTrimmed(context, KEY_XIAOZHI_TOKEN, token)

    fun getXiaozhiDeviceMac(context: Context): String = getTrimmed(context, KEY_XIAOZHI_DEVICE_MAC)

    fun setXiaozhiDeviceMac(context: Context, mac: String) = putTrimmed(context, KEY_XIAOZHI_DEVICE_MAC, mac)

    fun getXiaozhiClientId(context: Context): String = getTrimmed(context, KEY_XIAOZHI_CLIENT_ID)

    fun setXiaozhiClientId(context: Context, clientId: String) =
        putTrimmed(context, KEY_XIAOZHI_CLIENT_ID, clientId)

    fun getXiaozhiMode(context: Context): String =
        getTrimmed(context, KEY_XIAOZHI_MODE, DEFAULT_XIAOZHI_MODE).ifEmpty { DEFAULT_XIAOZHI_MODE }

    fun setXiaozhiMode(context: Context, mode: String) = putTrimmed(context, KEY_XIAOZHI_MODE, mode)

    fun isXiaozhiAutoContinue(context: Context): Boolean =
        preferences(context).getBoolean(KEY_XIAOZHI_AUTO_CONTINUE, true)

    fun setXiaozhiAutoContinue(context: Context, enabled: Boolean) =
        setEnabled(context, KEY_XIAOZHI_AUTO_CONTINUE, enabled)

    fun getXiaozhiSilenceTimeout(context: Context): Int =
        preferences(context).getInt(KEY_XIAOZHI_SILENCE_TIMEOUT, DEFAULT_XIAOZHI_SILENCE_TIMEOUT)

    fun setXiaozhiSilenceTimeout(context: Context, seconds: Int) =
        setInt(context, KEY_XIAOZHI_SILENCE_TIMEOUT, seconds.coerceIn(2, 60))

    /** 会话期间暂停音乐（外放会糊麦克风，默认打开） */
    fun isXiaozhiPauseMedia(context: Context): Boolean =
        preferences(context).getBoolean(KEY_XIAOZHI_PAUSE_MEDIA, true)

    fun setXiaozhiPauseMedia(context: Context, enabled: Boolean) =
        setEnabled(context, KEY_XIAOZHI_PAUSE_MEDIA, enabled)

    /** 麦克风降噪：开=语音通话链路（HAL 的 AEC/NS/AGC），关=MIC 裸麦（默认关） */
    fun isXiaozhiMicDenoise(context: Context): Boolean =
        preferences(context).getBoolean(KEY_XIAOZHI_MIC_DENOISE, false)

    fun setXiaozhiMicDenoise(context: Context, enabled: Boolean) =
        setEnabled(context, KEY_XIAOZHI_MIC_DENOISE, enabled)

    /** 允许 AI 拍照：开了小智的工具列表里才会出现拍照工具（默认关） */
    fun isXiaozhiAllowPhoto(context: Context): Boolean =
        preferences(context).getBoolean(KEY_XIAOZHI_ALLOW_PHOTO, false)

    fun setXiaozhiAllowPhoto(context: Context, enabled: Boolean) =
        setEnabled(context, KEY_XIAOZHI_ALLOW_PHOTO, enabled)

    /**
     * 语音唤醒：开了才常驻监听「你好小智」（默认关）。
     * 需要先装「工作咩闹钟语音唤醒插件」，没装时开关会打不开。
     */
    fun isXiaozhiVoiceWake(context: Context): Boolean =
        preferences(context).getBoolean(KEY_XIAOZHI_VOICEWAKE, false)

    fun setXiaozhiVoiceWake(context: Context, enabled: Boolean) =
        setEnabled(context, KEY_XIAOZHI_VOICEWAKE, enabled)

    /**
     * 说话打断：播报中音量突然变高就当成人在说话，打断播报（默认关）。
     * 依赖 HAL 的 AEC 压 TTS 回声，降噪关掉时基本失效。
     */
    fun isXiaozhiTalkInterrupt(context: Context): Boolean =
        preferences(context).getBoolean(KEY_XIAOZHI_TALK_INTERRUPT, false)

    fun setXiaozhiTalkInterrupt(context: Context, enabled: Boolean) =
        setEnabled(context, KEY_XIAOZHI_TALK_INTERRUPT, enabled)

    /** 语音唤醒词的原始文本（一行一个，sherpa 拼音 token 格式），在插件的设置界面里改 */
    fun getXiaozhiWakeKeywords(context: Context): String =
        preferences(context).getString(KEY_XIAOZHI_WAKE_KEYWORDS, null)
            ?: com.zyyme.workdayalarmclock.wake.WakeWordDefaults.KEYWORDS

    fun setXiaozhiWakeKeywords(context: Context, keywords: String) =
        preferences(context).edit()
            .putString(KEY_XIAOZHI_WAKE_KEYWORDS, keywords)
            .apply()

    /** 语音唤醒触发阈值，越小越灵敏（sherpa 默认 0.25） */
    fun getXiaozhiWakeThreshold(context: Context): Float =
        preferences(context).getFloat(
            KEY_XIAOZHI_WAKE_THRESHOLD,
            com.zyyme.workdayalarmclock.wake.WakeWordDefaults.THRESHOLD
        )

    fun setXiaozhiWakeThreshold(context: Context, threshold: Float) =
        preferences(context).edit()
            .putFloat(KEY_XIAOZHI_WAKE_THRESHOLD, threshold)
            .apply()

    /**
     * 待机监听唤醒词时的麦克风降噪：开=VOICE_COMMUNICATION（HAL 的 AEC），
     * 能把外放的音乐/视频当回声消掉，避免放着音乐被误唤醒；关=裸麦（默认关）。
     */
    fun isXiaozhiWakeDenoise(context: Context): Boolean =
        preferences(context).getBoolean(KEY_XIAOZHI_WAKE_DENOISE, false)

    fun setXiaozhiWakeDenoise(context: Context, enabled: Boolean) =
        setEnabled(context, KEY_XIAOZHI_WAKE_DENOISE, enabled)
}
