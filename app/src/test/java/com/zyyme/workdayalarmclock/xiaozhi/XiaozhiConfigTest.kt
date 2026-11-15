package com.zyyme.workdayalarmclock.xiaozhi

import org.junit.Assert.assertEquals
import org.junit.Test

class XiaozhiConfigTest {

    @Test
    fun wsUrl_httpBecomesWs() {
        assertEquals("ws://192.168.1.2:8003/xiaozhi/v1/",
            XiaozhiConfig.normalizeWsUrl("http://192.168.1.2:8003/xiaozhi/v1/"))
        assertEquals("wss://api.tenclass.net/xiaozhi/v1/",
            XiaozhiConfig.normalizeWsUrl("https://api.tenclass.net/xiaozhi/v1/"))
    }

    @Test
    fun wsUrl_withoutSchemeDefaultsToWss() {
        assertEquals("wss://api.tenclass.net/xiaozhi/v1/",
            XiaozhiConfig.normalizeWsUrl("api.tenclass.net/xiaozhi/v1/"))
    }

    @Test
    fun wsUrl_addsTrailingSlashOnlyWhenNoPath() {
        assertEquals("wss://example.com/", XiaozhiConfig.normalizeWsUrl("wss://example.com"))
        assertEquals("wss://example.com/v1", XiaozhiConfig.normalizeWsUrl("wss://example.com/v1"))
    }

    @Test
    fun wsUrl_blankFallsBackToDefault() {
        assertEquals(XiaozhiConfig.DEFAULT_WS_URL, XiaozhiConfig.normalizeWsUrl("   "))
    }

    @Test
    fun otaUrl_addsHttps() {
        assertEquals("https://api.tenclass.net/xiaozhi/ota/",
            XiaozhiConfig.normalizeOtaUrl("api.tenclass.net/xiaozhi/ota/"))
        assertEquals("http://192.168.1.2:8003/xiaozhi/ota/",
            XiaozhiConfig.normalizeOtaUrl("http://192.168.1.2:8003/xiaozhi/ota/"))
    }

    @Test
    fun otaUrl_blankFallsBackToDefault() {
        assertEquals(XiaozhiConfig.DEFAULT_OTA_URL, XiaozhiConfig.normalizeOtaUrl(""))
    }

    @Test
    fun modeNormalization() {
        assertEquals("auto", XiaozhiConfig.normalizeMode(""))
        assertEquals("auto", XiaozhiConfig.normalizeMode("bogus"))
        assertEquals("manual", XiaozhiConfig.normalizeMode("MANUAL"))
        assertEquals("realtime", XiaozhiConfig.normalizeMode(" realtime "))
    }
}
