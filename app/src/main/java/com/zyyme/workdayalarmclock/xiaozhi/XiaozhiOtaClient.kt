package com.zyyme.workdayalarmclock.xiaozhi

import com.zyyme.workdayalarmclock.UnsafeHttps
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 官方 OTA 接口：拿到 ws 地址 + token，或者拿到 6 位激活码让用户去 xiaozhi.me 绑定。
 *
 * 全部是阻塞调用，必须在后台线程跑（XiaozhiAssistant 的 OTA 线程）。
 */
class XiaozhiOtaClient(
    private val otaUrl: String,
    private val deviceMac: String,
    private val clientId: String,
    private val log: (String) -> Unit = {}
) {

    sealed class Result {
        data class Ok(val wsUrl: String, val token: String) : Result()
        data class NeedActivation(val code: String, val message: String, val challenge: String) : Result()
        data class Failed(val message: String) : Result()
    }

    private fun newConnection(target: String): HttpURLConnection {
        val connection = URL(target).openConnection() as HttpURLConnection
        UnsafeHttps.configure(connection)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Device-Id", deviceMac)
        connection.setRequestProperty("Client-Id", clientId)
        connection.setRequestProperty("Activation-Version", "1")
        connection.setRequestProperty("User-Agent", "workday-alarm-clock-android")
        return connection
    }

    private fun post(target: String, body: String): Pair<Int, String> {
        val connection = newConnection(target)
        try {
            OutputStreamWriter(connection.outputStream, "UTF-8").use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            connection.disconnect()
        }
    }

    /** 正常请求 OTA：有 websocket 就直接连，没绑定就给激活码 */
    fun fetch(bodyJson: String): Result {
        return try {
            val (code, text) = post(otaUrl, bodyJson)
            log("OTA 响应 HTTP $code ${text.take(600)}")
            interpret(code, text)
        } catch (e: Exception) {
            log("OTA 请求失败：${e.message}")
            Result.Failed(e.message ?: "OTA 请求失败")
        }
    }

    /**
     * 未绑定时等待用户在 xiaozhi.me 输入激活码。
     *
     * 对齐官方 ota.cc（[Ota::Activate]）：
     * - v1（没有 eFuse 序列号的设备）激活请求体就是 `{}`，不带 code、不带 hmac；
     *   code 由人在网页端输入，设备只负责显示。
     * - HTTP 202 = 还在等用户输入；HTTP 200 = 服务器已认这台设备。
     * - 激活请求里**绝不能带 code**，带了服务器会无条件回 200（假成功），
     *   于是"一直激活成功但一直连不上"。
     *
     * 真正的"能不能连"以 OTA 响应里的 websocket token 为准（test-token 表示未绑定），
     * 所以每轮都先问 OTA，再问 activate。
     *
     * @param otaBody 原始 OTA 请求体，每轮用它确认配置是否已下发
     * @param onUpdate (当前激活码, 提示文案)，激活码被服务端换新时回调，便于界面同步
     */
    fun pollActivate(
        code: String,
        otaBody: String,
        maxAttempts: Int = 60,
        intervalMs: Long = 3000L,
        onUpdate: (String, String) -> Unit = { _, _ -> }
    ): Result {
        val target = activateUrl()
        log("开始等待设备绑定：$code")
        var currentCode = code
        var lastMessage = ""
        for (attempt in 1..maxAttempts) {
            // 1) 先问 OTA：绑定生效后这里会直接给出可用的 token
            val ota = fetch(otaBody)
            if (ota is Result.Ok) {
                log("设备已绑定，拿到连接地址")
                return ota
            }
            if (ota is Result.NeedActivation) {
                if (ota.code.isNotBlank() && ota.code != currentCode) {
                    // 服务端换新码了，界面要跟着变，否则用户输入的是废码
                    currentCode = ota.code
                    log("激活码更新为 $currentCode")
                    onUpdate(currentCode, ota.message)
                }
                if (ota.message.isNotBlank()) lastMessage = ota.message
            }

            // 2) 再问激活状态：202 等用户，200 表示服务器已认
            try {
                val (httpCode, text) = post(target, "{}")
                when {
                    httpCode == 200 -> {
                        lastMessage = "已激活，等待服务端下发配置"
                        log("激活已通过，等待配置下发")
                    }
                    httpCode == 202 -> {
                        lastMessage = "等待在 xiaozhi.me 输入激活码"
                    }
                    else -> {
                        lastMessage = serverMessage(text) ?: "HTTP $httpCode"
                        log("激活轮询 HTTP $httpCode $lastMessage")
                    }
                }
            } catch (e: Exception) {
                lastMessage = e.message ?: "网络异常"
            }
            try {
                Thread.sleep(intervalMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return Result.Failed("已取消")
            }
        }
        return Result.NeedActivation(
            currentCode,
            lastMessage.ifEmpty { "还没有在 xiaozhi.me 添加设备" },
            ""
        )
    }

    private fun activateUrl(): String {
        val base = otaUrl.trimEnd('/')
        return "$base/activate"
    }

    /** 服务端的错误文案，比 "HTTP 404" 有用得多 */
    private fun serverMessage(text: String): String? {
        return try {
            val json = JSONObject(text)
            json.optString("error").ifEmpty { json.optString("message") }.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun interpret(httpCode: Int, text: String): Result {
        val parsed = XiaozhiProtocol.parseOta(text)
        val ws = parsed?.websocket
        val activation = parsed?.activation
        // 官方固件只按"有没有 activation 段"判断是否要激活；
        // token 是服务端给什么用什么（哪怕是 test-token），不自己加规则。
        if (activation != null) {
            return Result.NeedActivation(activation.code, activation.message, activation.challenge)
        }
        if (ws != null) {
            log("websocket token=${ws.token.ifEmpty { "(空)" }}")
            return Result.Ok(ws.url, ws.token)
        }
        return if (httpCode in 200..299) {
            Result.Failed(serverMessage(text) ?: "OTA 响应里没有 websocket 字段")
        } else {
            Result.Failed(serverMessage(text) ?: "HTTP $httpCode")
        }
    }
}
