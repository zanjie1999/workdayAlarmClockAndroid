package com.zyyme.workdayalarmclock.xiaozhi

/**
 * 小智模块的运行配置快照，从 MeSettings 读出来后交给 XiaozhiAssistant。
 * 该类只做数据与 URL 归一化，不依赖 Android。
 */
data class XiaozhiConfig(
    val enabled: Boolean = false,
    val otaUrl: String = DEFAULT_OTA_URL,
    val wsUrl: String = DEFAULT_WS_URL,
    val token: String = "",
    val deviceMac: String = "",
    val clientId: String = "",
    val mode: String = MODE_AUTO,
    val autoContinue: Boolean = true,
    val silenceTimeoutMs: Long = 8000L,
    /** 会话期间暂停音乐，会话结束恢复（外放音乐会把麦克风糊住，默认开） */
    val pauseMedia: Boolean = true,
    /** 麦克风降噪：开=VOICE_COMMUNICATION + 通话模式（HAL 处理），关=MIC 裸麦（默认关） */
    val micDenoise: Boolean = false,
    /** 允许 AI 拍照：开了 tools/list 里才会出现拍照工具 */
    val allowPhoto: Boolean = false,
    /**
     * 说话打断：播报期间麦克风音量突然变高（有人在说话）就打断播报。
     * 录音在 THINKING/SPEAKING 期间保持常开，只是不发上行帧；靠 HAL 的 AEC 压掉 TTS 回声，
     * 所以实际只在「麦克风降噪」打开时才有效，但不限制。
     */
    val talkInterrupt: Boolean = false
) {
    companion object {
        const val DEFAULT_OTA_URL = "https://api.tenclass.net/xiaozhi/ota/"
        const val DEFAULT_WS_URL = "wss://api.tenclass.net/xiaozhi/v1/"

        const val MODE_AUTO = "auto"
        const val MODE_MANUAL = "manual"
        const val MODE_REALTIME = "realtime"

        val MODES = arrayOf(MODE_AUTO, MODE_MANUAL, MODE_REALTIME)

        /** http(s) 会被换成 ws(s)；没写协议头时按 wss 处理；主机后没有路径时补一个斜杠。 */
        fun normalizeWsUrl(raw: String): String {
            var url = raw.trim()
            if (url.isEmpty()) return DEFAULT_WS_URL
            url = when {
                url.startsWith("https://", ignoreCase = true) ->
                    "wss://" + url.substring("https://".length)
                url.startsWith("http://", ignoreCase = true) ->
                    "ws://" + url.substring("http://".length)
                url.startsWith("wss://", ignoreCase = true) || url.startsWith("ws://", ignoreCase = true) -> url
                else -> "wss://$url"
            }
            val schemeEnd = url.indexOf("://")
            if (schemeEnd >= 0) {
                val pathStart = url.indexOf('/', schemeEnd + 3)
                if (pathStart < 0) url = "$url/"
            }
            return url
        }

        fun normalizeOtaUrl(raw: String): String {
            var url = raw.trim()
            if (url.isEmpty()) return DEFAULT_OTA_URL
            if (!url.startsWith("http://", ignoreCase = true) &&
                !url.startsWith("https://", ignoreCase = true)
            ) {
                url = "https://$url"
            }
            return url
        }

        fun normalizeMode(raw: String): String {
            val mode = raw.trim().lowercase()
            return if (mode in MODES) mode else MODE_AUTO
        }
    }
}
