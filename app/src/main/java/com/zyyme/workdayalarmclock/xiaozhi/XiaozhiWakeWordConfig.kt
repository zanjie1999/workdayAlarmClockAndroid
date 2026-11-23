package com.zyyme.workdayalarmclock.xiaozhi

/**
 * 离线语音唤醒词配置与校验（不依赖 Android，方便 JVM 单测）。
 *
 * 注意：sherpa-onnx C++ 层在遇到未知 token 或空行语法错时会直接 exit(-1)，
 * 所以所有用户输入必须在这里按 `tokens.txt` 严格校验，并兜底回退到默认唤醒词。
 */
object XiaozhiWakeWordConfig {

    const val DEFAULT_WAKE_WORDS = "你好小智,小智小智"
    const val DEFAULT_THRESHOLD_PERCENT = 25
    const val MIN_THRESHOLD_PERCENT = 10
    const val MAX_THRESHOLD_PERCENT = 90

    data class BuildResult(
        val keywordsText: String,
        val activeLabels: List<String>,
        val warnings: List<String>
    )

    private data class Preset(
        val label: String,
        val lines: List<String>
    )

    private val PRESETS: Map<String, Preset> = mapOf(
        "你好小智" to Preset(
            label = "你好小智",
            lines = listOf(
                "n ǐ h ǎo x iǎo zh ì @你好小智",
                "n ǐ h ǎo x iǎo zh @你好小智",
                "n ǐ h ǎo x iǎo z ì @你好小智",
                "n ǐ h ǎo x iǎo z @你好小智"
            )
        ),
        "小智小智" to Preset(
            label = "小智小智",
            lines = listOf(
                "x iǎo zh ì x iǎo zh ì @小智小智",
                "x iǎo zh x iǎo zh @小智小智",
                "x iǎo z ì x iǎo z ì @小智小智",
                "x iǎo z x iǎo z @小智小智"
            )
        ),
        "小智同学" to Preset(
            label = "小智同学",
            lines = listOf(
                "x iǎo zh ì t óng x ué @小智同学",
                "x iǎo zh t óng x ué @小智同学",
                "x iǎo z ì t óng x ué @小智同学"
            )
        ),
        "你好小咩" to Preset(
            label = "你好小咩",
            lines = listOf(
                "n ǐ h ǎo x iǎo m iē @你好小咩"
            )
        )
    )

    fun clampThresholdPercent(raw: Int): Int =
        raw.coerceIn(MIN_THRESHOLD_PERCENT, MAX_THRESHOLD_PERCENT)

    fun thresholdFloat(percent: Int): Float = clampThresholdPercent(percent) / 100f

    fun parseTokenSet(tokensTxt: String): Set<String> {
        if (tokensTxt.isBlank()) return emptySet()
        val set = HashSet<String>(256)
        tokensTxt.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val firstSpace = trimmed.indexOfFirst { it.isWhitespace() }
            val token = if (firstSpace > 0) trimmed.substring(0, firstSpace) else trimmed
            if (token.isNotEmpty()) set.add(token)
        }
        return set
    }

    fun buildKeywordsText(rawInput: String, validTokens: Set<String>? = null): BuildResult {
        val lines = LinkedHashSet<String>()
        val labels = LinkedHashSet<String>()
        val warnings = ArrayList<String>()

        val entries = rawInput.split('\n', ',', '，', ';', '；')
        for (rawEntry in entries) {
            val entry = rawEntry.trim()
            if (entry.isEmpty()) continue

            val presetKey = normalizePresetKey(entry)
            val preset = PRESETS[presetKey]
            if (preset != null) {
                val validLines = if (validTokens == null) {
                    preset.lines
                } else {
                    preset.lines.filter { validateCustomLine(it, validTokens) == null }
                }
                if (validLines.isNotEmpty()) {
                    lines.addAll(validLines)
                    labels.add(preset.label)
                } else {
                    warnings.add("内置唤醒词「${preset.label}」与当前 tokens.txt 不匹配，已跳过")
                }
                continue
            }

            if (entry.any { it.isWhitespace() }) {
                val error = validateCustomLine(entry, validTokens)
                if (error == null) {
                    val normalized = entry.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
                    lines.add(normalized)
                    labels.add(extractLabel(normalized))
                } else {
                    warnings.add("忽略无效唤醒词「$entry」：$error")
                }
            } else {
                warnings.add(
                    "唤醒词「$entry」不是内置预设（你好小智/小智小智/小智同学/你好小咩），自定义词请用空格分隔声母韵母拼音（如 n ǐ h ǎo x iǎo zh ì @$entry）"
                )
            }
        }

        if (lines.isEmpty()) {
            val fallback = listOf("你好小智", "小智小智")
            for (key in fallback) {
                val preset = PRESETS[key] ?: continue
                lines.addAll(preset.lines)
                labels.add(preset.label)
            }
            if (rawInput.isNotBlank()) {
                warnings.add("未保留任何有效唤醒词，已自动回退到默认：你好小智,小智小智")
            }
        }

        return BuildResult(
            keywordsText = lines.joinToString("\n", postfix = "\n"),
            activeLabels = labels.toList(),
            warnings = warnings
        )
    }

    private fun normalizePresetKey(entry: String): String {
        val sb = StringBuilder(entry.length)
        for (ch in entry) {
            if (ch.isWhitespace() || ch in charArrayOf(',', '，', '.', '。', '!', '！', '?', '？', '、', ':', '：')) {
                continue
            }
            sb.append(ch)
        }
        return sb.toString()
    }

    private fun validateCustomLine(line: String, validTokens: Set<String>?): String? {
        val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "空行"
        var tokenCount = 0
        for (part in parts) {
            when {
                part.startsWith("@") -> {
                    if (part.length <= 1) return "@标签不能为空"
                }
                part.startsWith(":") -> {
                    val score = part.substring(1).toFloatOrNull()
                    if (score == null) return "无效加权分 $part"
                }
                part.startsWith("#") -> {
                    val threshold = part.substring(1).toFloatOrNull()
                    if (threshold == null || threshold <= 0f || threshold >= 1f) {
                        return "无效阈值 $part"
                    }
                }
                else -> {
                    if (validTokens != null && part !in validTokens) {
                        return "未知音素「$part」"
                    }
                    tokenCount++
                }
            }
        }
        if (tokenCount < 2) return "音素序列过短（至少需要 2 个音素）"
        return null
    }

    private fun extractLabel(line: String): String {
        val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val atPart = parts.firstOrNull { it.startsWith("@") && it.length > 1 }
        if (atPart != null) return atPart.substring(1)
        return parts.filter { !it.startsWith(":") && !it.startsWith("#") }.joinToString("")
    }
}
