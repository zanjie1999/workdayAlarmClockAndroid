package com.zyyme.workdayalarmclock.xiaozhi

import org.json.JSONObject

/**
 * 小智官方 WebSocket 协议（Protocol-Version: 1）。
 *
 * 只做 JSON 的构建与解析，不碰网络与线程，方便 JVM 单测。
 * 一切解析都用 opt* 并包在 try 里，服务端改字段时只丢一条消息，不崩。
 */
object XiaozhiProtocol {

    const val PROTOCOL_VERSION = 1

    const val UPLOAD_SAMPLE_RATE = 16000
    const val UPLOAD_CHANNELS = 1
    const val FRAME_DURATION_MS = 60
    const val UPLOAD_FRAME_SAMPLES = UPLOAD_SAMPLE_RATE * FRAME_DURATION_MS / 1000 // 960

    const val DEFAULT_DOWNLOAD_SAMPLE_RATE = 24000

    const val ABORT_REASON_WAKE_WORD = "wake_word_detected"

    /**
     * 设备语言，等价于官方固件里的 `Lang::CODE`。
     *
     * WebSocket 协议本身没有语言字段，服务端只从 **OTA 请求**（body 的 `language`
     * 和请求头 `Accept-Language`）得知这台设备说什么语言，再据此决定系统播报
     * （配对验证码、"没有找到该设备的版本信息"）用中文还是英文。
     */
    const val DEVICE_LANGUAGE = "zh-CN"

    // ---------------- 客户端 -> 服务端 ----------------

    fun helloJson(features: Map<String, Boolean> = emptyMap()): String {
        val audioParams = JSONObject()
            .put("format", "opus")
            .put("sample_rate", UPLOAD_SAMPLE_RATE)
            .put("channels", UPLOAD_CHANNELS)
            .put("frame_duration", FRAME_DURATION_MS)
        val root = JSONObject()
            .put("type", "hello")
            .put("version", PROTOCOL_VERSION)
            .put("transport", "websocket")
            .put("audio_params", audioParams)
        if (features.isNotEmpty()) {
            val json = JSONObject()
            for ((key, value) in features) json.put(key, value)
            root.put("features", json)
        }
        return root.toString()
    }

    fun listenStartJson(mode: String = "auto"): String = JSONObject()
        .put("session_id", "")
        .put("type", "listen")
        .put("state", "start")
        .put("mode", mode)
        .toString()

    fun listenStopJson(): String = JSONObject()
        .put("session_id", "")
        .put("type", "listen")
        .put("state", "stop")
        .toString()

    /** 唤醒词检测到之后，把命中文本告诉服务端（v1 暂未启用，留给语音唤醒） */
    fun listenDetectJson(text: String): String = JSONObject()
        .put("session_id", "")
        .put("type", "listen")
        .put("state", "detect")
        .put("text", text)
        .toString()

    fun abortJson(reason: String = ABORT_REASON_WAKE_WORD): String = JSONObject()
        .put("type", "abort")
        .put("reason", reason)
        .toString()

    // ---------------- 服务端 -> 客户端 ----------------

    sealed class ServerEvent {
        data class Hello(
            val sessionId: String,
            val transport: String,
            val sampleRate: Int,
            val frameDurationMs: Int
        ) : ServerEvent()

        data class Stt(val text: String) : ServerEvent()
        data class Tts(val state: String, val text: String) : ServerEvent()
        data class Llm(val text: String, val emotion: String) : ServerEvent()
        data class Alert(val status: String, val message: String) : ServerEvent()
        object Goodbye : ServerEvent()

        /**
         * 设备侧 MCP（JSON-RPC 2.0 塞在 payload 里）。
         * [id] 原样保留：可能是整数也可能是字符串，回包必须一字不差地带回去，否则服务端不认。
         */
        data class Mcp(
            val id: Any?,
            val method: String,
            val params: JSONObject?,
            val sessionId: String
        ) : ServerEvent()

        data class Unknown(val type: String) : ServerEvent()
    }

    fun parse(raw: String?): ServerEvent? {
        if (raw.isNullOrBlank()) return null
        return try {
            val root = JSONObject(raw)
            when (val type = root.optString("type")) {
                "hello" -> {
                    val audio = root.optJSONObject("audio_params")
                    ServerEvent.Hello(
                        sessionId = root.optString("session_id"),
                        transport = root.optString("transport", "websocket"),
                        sampleRate = audio?.optInt("sample_rate", DEFAULT_DOWNLOAD_SAMPLE_RATE)
                            ?: DEFAULT_DOWNLOAD_SAMPLE_RATE,
                        frameDurationMs = audio?.optInt("frame_duration", FRAME_DURATION_MS)
                            ?: FRAME_DURATION_MS
                    )
                }
                "stt" -> ServerEvent.Stt(root.optString("text"))
                "tts" -> ServerEvent.Tts(
                    state = root.optString("state", "sentence_start"),
                    text = root.optString("text")
                )
                "llm" -> ServerEvent.Llm(
                    text = root.optString("text"),
                    emotion = root.optString("emotion")
                )
                "alert" -> ServerEvent.Alert(
                    status = root.optString("status"),
                    message = root.optString("message")
                )
                // 用户让 AI"退下"时服务端会发 goodbye 并关掉连接，这种情况不该重连
                "goodbye" -> ServerEvent.Goodbye
                // 设备侧 MCP：payload 里是 JSON-RPC，method 为空说明是坏包，当未知类型忽略
                "mcp" -> {
                    val payload = root.optJSONObject("payload")
                    val method = payload?.optString("method").orEmpty()
                    if (payload == null || method.isEmpty()) {
                        ServerEvent.Unknown(type)
                    } else {
                        ServerEvent.Mcp(
                            id = payload.opt("id"),
                            method = method,
                            params = payload.optJSONObject("params"),
                            sessionId = root.optString("session_id")
                        )
                    }
                }
                else -> ServerEvent.Unknown(type)
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- MCP 回包 ----------------

    /**
     * MCP 回复信封：`{"session_id":..,"type":"mcp","payload":{"jsonrpc":"2.0","id":..,"result":..}}`
     *
     * 只实现 legacy（2024-11-05）形状：不带 resultType / ttlMs / _meta。
     * 新网关会先发 server/discover 探测，我们回 -32601，它就退回 legacy 流程。
     */
    fun mcpResultJson(id: Any?, result: JSONObject, sessionId: String = ""): String {
        val root = mcpEnvelope(id, sessionId)
        root.getJSONObject("payload").put("result", result)
        return root.toString()
    }

    fun mcpErrorJson(id: Any?, code: Int, message: String, sessionId: String = ""): String {
        val error = JSONObject().put("code", code).put("message", message)
        val root = mcpEnvelope(id, sessionId)
        root.getJSONObject("payload").put("error", error)
        return root.toString()
    }

    private fun mcpEnvelope(id: Any?, sessionId: String): JSONObject {
        val payload = JSONObject().put("jsonrpc", "2.0")
        // id 必须是原样带回：字符串就是字符串，整数就是整数
        payload.put("id", id ?: JSONObject.NULL)
        return JSONObject()
            .put("session_id", sessionId)
            .put("type", "mcp")
            .put("payload", payload)
    }

    // ---------------- OTA ----------------

    data class OtaWebsocket(val url: String, val token: String, val version: Int)

    data class OtaActivation(val code: String, val message: String, val challenge: String)

    data class OtaResponse(
        val websocket: OtaWebsocket?,
        val activation: OtaActivation?,
        val firmwareVersion: String?
    )

    /** 解析 OTA 响应。字段缺失不抛异常，返回对应 null。 */
    fun parseOta(raw: String?): OtaResponse? {
        if (raw.isNullOrBlank()) return null
        return try {
            val root = JSONObject(raw)
            val wsJson = root.optJSONObject("websocket")
            val websocket = wsJson?.let {
                val url = it.optString("url")
                if (url.isBlank()) null else OtaWebsocket(
                    url = url,
                    token = it.optString("token"),
                    version = it.optInt("version", PROTOCOL_VERSION)
                )
            }
            val actJson = root.optJSONObject("activation")
            val activation = actJson?.let {
                val code = it.optString("code")
                if (code.isBlank()) null else OtaActivation(
                    code = code,
                    message = it.optString("message"),
                    challenge = it.optString("challenge")
                )
            }
            val firmware = root.optJSONObject("firmware")?.optString("version")
            OtaResponse(websocket, activation, firmware)
        } catch (e: Exception) {
            null
        }
    }
}
