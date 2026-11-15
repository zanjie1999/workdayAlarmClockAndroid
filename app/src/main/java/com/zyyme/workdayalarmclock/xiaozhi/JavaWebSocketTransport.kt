package com.zyyme.workdayalarmclock.xiaozhi

import com.zyyme.workdayalarmclock.UnsafeHttps
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ServerHandshake
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import javax.net.ssl.SSLSocket

/**
 * 基于 Java-WebSocket 1.4.1 的传输实现。
 *
 * wss 不走它自带的 TLS（老系统上平台 JSSE 只有 CBC 套件，tenclass 只收 AEAD 会握手失败），
 * 而是自己用 [UnsafeHttps] 的 socket 工厂建好 TCP + TLS，再交给 WebSocketClient 复用，
 * 这样从 Android 4.0 到最新系统都能连上。
 */
class JavaWebSocketTransport(
    private val url: String,
    private val log: (String) -> Unit = {}
) : XiaozhiTransport {

    @Volatile
    private var client: WebSocketClient? = null

    @Volatile
    private var opened = false

    @Volatile
    private var closedByUs = false

    override val isOpen: Boolean
        get() = opened && client?.isOpen == true

    override fun connect(
        headers: Map<String, String>,
        listener: XiaozhiTransport.Listener,
        timeoutMs: Int
    ) {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase() ?: "wss"
        // 1.4.1 没有 setConnectTimeout()，超时只能通过构造器给
        val ws = object : WebSocketClient(uri, Draft_6455(), headers, timeoutMs) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                opened = true
                log("WebSocket 已连接 $url")
                listener.onOpen()
            }

            override fun onMessage(message: String?) {
                if (message != null) listener.onText(message)
            }

            override fun onMessage(bytes: ByteBuffer?) {
                if (bytes != null) {
                    val data = ByteArray(bytes.remaining())
                    bytes.get(data)
                    listener.onBinary(data)
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                opened = false
                val text = reason.orEmpty()
                log("WebSocket 关闭 code=$code reason=$text remote=$remote")
                listener.onClosed(if (text.isEmpty()) "code=$code" else text, code, remote)
            }

            override fun onError(ex: Exception?) {
                log("WebSocket 错误：${ex?.message}")
                listener.onFailure(ex ?: RuntimeException("unknown websocket error"))
            }
        }

        ws.setConnectionLostTimeout(15)

        if (scheme == "wss") {
            val socket = openTlsSocket(uri, timeoutMs)
            @Suppress("DEPRECATION")
            ws.setSocket(socket)
        }

        client = ws
        closedByUs = false
        ws.connect()
    }

    private fun openTlsSocket(uri: URI, timeoutMs: Int): SSLSocket {
        val host = uri.host
        val port = if (uri.port > 0) uri.port else 443
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress(host, port), timeoutMs)
        val factory = UnsafeHttps.socketFactory()
        val ssl = factory.createSocket(raw, host, port, true) as SSLSocket
        try {
            ssl.startHandshake()
        } catch (e: Exception) {
            try {
                ssl.close()
            } catch (ignored: Exception) {
            }
            throw e
        }
        return ssl
    }

    override fun sendText(text: String): Boolean = try {
        val ws = client
        if (ws != null && ws.isOpen) {
            ws.send(text)
            true
        } else {
            false
        }
    } catch (e: Exception) {
        log("发送文本失败：${e.message}")
        false
    }

    override fun sendBinary(data: ByteArray): Boolean = try {
        val ws = client
        if (ws != null && ws.isOpen) {
            ws.send(data)
            true
        } else {
            false
        }
    } catch (e: Exception) {
        log("发送音频失败：${e.message}")
        false
    }

    override fun close(reason: String) {
        closedByUs = true
        opened = false
        val ws = client
        client = null
        try {
            ws?.close(1000, reason)
        } catch (e: Exception) {
            log("关闭 WebSocket 失败：${e.message}")
        }
    }
}
