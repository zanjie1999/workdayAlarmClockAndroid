package com.zyyme.workdayalarmclock.xiaozhi

/**
 * 允许 AI 通过 MCP 工具下发给 Go 的命令白名单。
 *
 * **必须白名单**：`toGo()` 写进去的是 Go 的命令管道（`STOP` / `YSLED` / `WAKELOCK` 这些都能收），
 * 放任模型随便拼字符串等于把整台设备的控制权交出去。这里只认下面这些，其余一律拒绝：
 *
 * - `1key` 播放默认歌单
 * - `playmusic {歌曲id}`
 * - `playlist {歌单id}`
 * - `play {音频url}`
 * - `pause` / `resume` / `stop`
 * - `next` / `prev`
 * - `stop {分钟}`
 * - `volp` / `volm` / `vol {1-100}`
 * - `alarmlist` 查闹钟
 * - `alarmadd {HHmm} {类型,类型}` / `alarmdel {HHmm} {类型,类型}`
 */
object XiaozhiPlayerCommands {

    /** 定时停止最多 24 小时，再长没意义 */
    private const val MAX_STOP_MINUTES = 1440

    private const val MAX_ID_LENGTH = 64
    private const val MAX_URL_LENGTH = 2048
    private const val MAX_ALARM_TYPES_LENGTH = 64

    /** 校验并归一化；返回 null 表示不认识，不下发 */    fun parse(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parts = text.split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val keyword = parts[0].lowercase()
        return when (keyword) {
            "1key", "pause", "resume", "volp", "volm", "next", "prev", "alarmlist" ->
                if (parts.size == 1) keyword else null

            "stop" -> when (parts.size) {
                1 -> "stop"
                2 -> {
                    val minutes = parts[1].toIntOrNull() ?: return null
                    if (minutes in 1..MAX_STOP_MINUTES) "stop $minutes" else null
                }
                else -> null
            }

            "vol" -> {
                if (parts.size != 2) return null
                val value = parts[1].toIntOrNull() ?: return null
                if (value in 1..100) "vol $value" else null
            }

            "playmusic", "playlist" -> {
                if (parts.size != 2) return null
                val id = parts[1]
                if (!isSafeId(id)) null else "$keyword $id"
            }

            "play" -> {
                if (parts.size != 2) return null
                val url = parts[1]
                if (!isHttpUrl(url)) null else "play $url"
            }

            "alarmadd", "alarmdel" -> {
                if (parts.size != 3) return null
                val hhmm = normalizeAlarmTime(parts[1]) ?: return null
                val types = normalizeAlarmTypes(parts[2]) ?: return null
                "$keyword $hhmm $types"
            }

            else -> null
        }
    }

    /**
     * 这条命令执行完，这轮对话就该收场、不再续听。
     *
     * 只有"开始/切换播放"这一类算：点歌、点歌单、放链接、恢复播放、上下一首、默认歌单。
     * `pause`/`stop`/音量/闹钟都是可能连着操作的，留着麦克风继续听。
     */
    fun endsConversation(command: String): Boolean {
        return when (command.substringBefore(' ').lowercase()) {
            "1key", "playmusic", "playlist", "play", "resume", "next", "prev" -> true
            else -> false
        }
    }

    /** 闹钟时间归一化成 4 位 HHmm，顺手吃掉 07:30 这种写法 */
    private fun normalizeAlarmTime(s: String): String? {
        val digits = s.replace(":", "").replace("：", "")
        if (digits.length != 4) return null
        for (c in digits) {
            if (c !in '0'..'9') return null
        }
        return digits
    }

    /** 类型列表只查字符，具体取值（1-11 / 月日）交给 Go 判断 */
    private fun normalizeAlarmTypes(s: String): String? {
        if (s.isEmpty() || s.length > MAX_ALARM_TYPES_LENGTH) return null
        for (c in s) {
            val ok = (c in '0'..'9') || c == ','
            if (!ok) return null
        }
        return s
    }

    /** 歌曲/歌单 id：只允许字母数字和 `_ -`，挡住换行、空格、分号这类能拼出第二条命令的东西 */
    private fun isSafeId(id: String): Boolean {
        if (id.isEmpty() || id.length > MAX_ID_LENGTH) return false
        for (c in id) {
            val ok = (c in '0'..'9') || (c in 'a'..'z') || (c in 'A'..'Z') || c == '_' || c == '-'
            if (!ok) return false
        }
        return true
    }

    private fun isHttpUrl(url: String): Boolean {
        if (url.length > MAX_URL_LENGTH) return false
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        // 命令是按行写给 Go 的，URL 里出现空白就等于截断
        for (c in url) {
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') return false
        }
        return true
    }
}
