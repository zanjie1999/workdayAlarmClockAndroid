package com.zyyme.workdayalarmclock.xiaozhi

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaozhiProtocolTest {

    @Test
    fun outboundMessagesCarrySessionIdAndFields() {
        val startRealtime = JSONObject(XiaozhiProtocol.listenStartJson("realtime", "sess-42"))
        assertEquals("sess-42", startRealtime.getString("session_id"))
        assertEquals("listen", startRealtime.getString("type"))
        assertEquals("start", startRealtime.getString("state"))
        assertEquals("realtime", startRealtime.getString("mode"))

        val stop = JSONObject(XiaozhiProtocol.listenStopJson("sess-42"))
        assertEquals("sess-42", stop.getString("session_id"))
        assertEquals("stop", stop.getString("state"))

        val detect = JSONObject(XiaozhiProtocol.listenDetectJson("你好小智", "sess-42"))
        assertEquals("sess-42", detect.getString("session_id"))
        assertEquals("detect", detect.getString("state"))
        assertEquals("你好小智", detect.getString("text"))

        val abort = JSONObject(XiaozhiProtocol.abortJson(XiaozhiProtocol.ABORT_REASON_WAKE_WORD, "sess-42"))
        assertEquals("sess-42", abort.getString("session_id"))
        assertEquals("abort", abort.getString("type"))
        assertEquals(XiaozhiProtocol.ABORT_REASON_WAKE_WORD, abort.getString("reason"))
    }

    @Test
    fun parseServerEventsCorrectly() {
        val hello = XiaozhiProtocol.parse(
            """{"type":"hello","transport":"websocket","session_id":"abc","audio_params":{"sample_rate":24000,"frame_duration":60}}"""
        ) as? XiaozhiProtocol.ServerEvent.Hello
        assertNotNull(hello)
        assertEquals("abc", hello?.sessionId)
        assertEquals(24000, hello?.sampleRate)

        val stt = XiaozhiProtocol.parse("""{"type":"stt","text":"暂停音乐"}""")
            as? XiaozhiProtocol.ServerEvent.Stt
        assertEquals("暂停音乐", stt?.text)

        val tts = XiaozhiProtocol.parse("""{"type":"tts","state":"sentence_start","text":"好的"}""")
            as? XiaozhiProtocol.ServerEvent.Tts
        assertEquals("sentence_start", tts?.state)
        assertEquals("好的", tts?.text)

        val goodbye = XiaozhiProtocol.parse("""{"type":"goodbye"}""")
        assertTrue(goodbye is XiaozhiProtocol.ServerEvent.Goodbye)

        val mcp = XiaozhiProtocol.parse(
            """{"type":"mcp","session_id":"abc","payload":{"jsonrpc":"2.0","id":"req-1","method":"tools/list","params":{}}}"""
        ) as? XiaozhiProtocol.ServerEvent.Mcp
        assertEquals("req-1", mcp?.id)
        assertEquals("tools/list", mcp?.method)
    }
}
