package com.zyyme.workdayalarmclock.xiaozhi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class XiaozhiWakeWordConfigTest {

    private fun loadRealTokens(): Set<String> {
        val candidates = listOf(
            File("src/main/assets/xiaozhi_kws/tokens.txt"),
            File("app/src/main/assets/xiaozhi_kws/tokens.txt")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("tokens.txt not found for test")
        return XiaozhiWakeWordConfig.parseTokenSet(file.readText(Charsets.UTF_8))
    }

    @Test
    fun presetsExpandAndValidateAgainstRealTokens() {
        val tokens = loadRealTokens()
        val res = XiaozhiWakeWordConfig.buildKeywordsText("你好小智, 小智同学, 你好小咩", tokens)
        assertTrue("unexpected warnings: ${res.warnings}", res.warnings.isEmpty())
        assertEquals(listOf("你好小智", "小智同学", "你好小咩"), res.activeLabels)
        assertTrue(res.keywordsText.contains("n ǐ h ǎo x iǎo zh ì @你好小智"))
        assertTrue(res.keywordsText.contains("n ǐ h ǎo x iǎo z ì @你好小智"))
        assertTrue(res.keywordsText.contains("x iǎo zh ì t óng x ué @小智同学"))
        assertTrue(res.keywordsText.contains("n ǐ h ǎo x iǎo m iē @你好小咩"))
    }

    @Test
    fun invalidCustomTokensAreRejectedWithFallback() {
        val tokens = loadRealTokens()
        val res = XiaozhiWakeWordConfig.buildKeywordsText("x bogus_tok @坏词", tokens)
        assertTrue(res.warnings.isNotEmpty())
        assertEquals(listOf("你好小智", "小智小智"), res.activeLabels)

        val validCustom = XiaozhiWakeWordConfig.buildKeywordsText("n ǐ h ǎo :1.2 #0.3 @你好", tokens)
        assertTrue(validCustom.warnings.isEmpty())
        assertEquals(listOf("你好"), validCustom.activeLabels)
    }

    @Test
    fun thresholdClampsWithinAllowedRange() {
        assertEquals(10, XiaozhiWakeWordConfig.clampThresholdPercent(1))
        assertEquals(90, XiaozhiWakeWordConfig.clampThresholdPercent(99))
        assertEquals(0.25f, XiaozhiWakeWordConfig.thresholdFloat(25), 0.0001f)
    }
}
