package com.zyyme.workdayalarmclock.xiaozhi

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaozhiProtocolTest {

    @Test
    fun helloJson_hasExpectedShape() {
        val json = JSONObject(XiaozhiProtocol.helloJson())
        assertEquals("hello", json.getString("type"))
        assertEquals(1, json.getInt("version"))
        assertEquals("websocket", json.getString("transport"))
        val audio = json.getJSONObject("audio_params")
        assertEquals("opus", audio.getString("format"))
        assertEquals(16000, audio.getInt("sample_rate"))
        assertEquals(1, audio.getInt("channels"))
        assertEquals(60, audio.getInt("frame_duration"))
    }

    @Test
    fun helloJson_withFeatures() {
        val json = JSONObject(XiaozhiProtocol.helloJson(mapOf("mcp" to true)))
        assertTrue(json.getJSONObject("features").getBoolean("mcp"))
    }

    @Test
    fun controlMessages() {
        val start = JSONObject(XiaozhiProtocol.listenStartJson("auto"))
        assertEquals("listen", start.getString("type"))
        assertEquals("start", start.getString("state"))
        assertEquals("auto", start.getString("mode"))
        assertEquals("", start.getString("session_id"))

        val stop = JSONObject(XiaozhiProtocol.listenStopJson())
        assertEquals("stop", stop.getString("state"))

        val abort = JSONObject(XiaozhiProtocol.abortJson())
        assertEquals("abort", abort.getString("type"))
        assertEquals("wake_word_detected", abort.getString("reason"))

        val detect = JSONObject(XiaozhiProtocol.listenDetectJson("你好"))
        assertEquals("detect", detect.getString("state"))
        assertEquals("你好", detect.getString("text"))
    }

    @Test
    fun parseHello_usesNegotiatedSampleRate() {
        val raw = """{"type":"hello","transport":"websocket","session_id":"abc","audio_params":{"format":"opus","sample_rate":24000,"channels":1,"frame_duration":60}}"""
        val event = XiaozhiProtocol.parse(raw) as XiaozhiProtocol.ServerEvent.Hello
        assertEquals("abc", event.sessionId)
        assertEquals(24000, event.sampleRate)
        assertEquals(60, event.frameDurationMs)
    }

    @Test
    fun parseHello_missingAudioParamsFallsBackToDefault() {
        val event = XiaozhiProtocol.parse("""{"type":"hello","session_id":"x"}""")
                as XiaozhiProtocol.ServerEvent.Hello
        assertEquals(XiaozhiProtocol.DEFAULT_DOWNLOAD_SAMPLE_RATE, event.sampleRate)
    }

    @Test
    fun parseSttAndTts() {
        val stt = XiaozhiProtocol.parse("""{"type":"stt","text":"今天天气"}""")
        assertEquals("今天天气", (stt as XiaozhiProtocol.ServerEvent.Stt).text)

        val tts = XiaozhiProtocol.parse("""{"type":"tts","state":"sentence_start","text":"好的"}""")
                as XiaozhiProtocol.ServerEvent.Tts
        assertEquals("sentence_start", tts.state)
        assertEquals("好的", tts.text)
    }

    @Test
    fun parseAlertAndUnknown() {
        val alert = XiaozhiProtocol.parse("""{"type":"alert","status":"alert","message":"没找着设备"}""")
                as XiaozhiProtocol.ServerEvent.Alert
        assertEquals("没找着设备", alert.message)

        val unknown = XiaozhiProtocol.parse("""{"type":"mcp","payload":{}}""")
        assertEquals("mcp", (unknown as XiaozhiProtocol.ServerEvent.Unknown).type)
    }

    @Test
    fun parseGarbageReturnsNull() {
        assertNull(XiaozhiProtocol.parse("not json"))
        assertNull(XiaozhiProtocol.parse(""))
        assertNull(XiaozhiProtocol.parse(null))
    }

    @Test
    fun parseOta_boundDevice() {
        val raw = """{"websocket":{"url":"wss://api.tenclass.net/xiaozhi/v1/","token":"abc","version":1},"firmware":{"version":"0.9.1"}}"""
        val ota = XiaozhiProtocol.parseOta(raw)
        assertNotNull(ota)
        assertEquals("wss://api.tenclass.net/xiaozhi/v1/", ota!!.websocket!!.url)
        assertEquals("abc", ota.websocket!!.token)
        assertEquals("0.9.1", ota.firmwareVersion)
        assertNull(ota.activation)
    }

    @Test
    fun parseOta_needsActivation() {
        val raw = """{"activation":{"code":"123456","message":"xiaozhi.me","challenge":"c"},"websocket":{"url":"wss://x/","token":"test-token","version":1}}"""
        val ota = XiaozhiProtocol.parseOta(raw)!!
        assertEquals("123456", ota.activation!!.code)
        assertEquals("xiaozhi.me", ota.activation!!.message)
        assertEquals("test-token", ota.websocket!!.token)
    }

    @Test
    fun parseOta_garbageReturnsNull() {
        assertNull(XiaozhiProtocol.parseOta("<html>"))
        assertNull(XiaozhiProtocol.parseOta(""))
    }
}
