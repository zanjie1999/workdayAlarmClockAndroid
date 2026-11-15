package com.zyyme.workdayalarmclock.xiaozhi

/**
 * WebSocket 传输层接口，把 Java-WebSocket 隔在后面。
 * 将来若要换 OkHttp 或加代理，只改实现。
 */
interface XiaozhiTransport {

    interface Listener {
        fun onOpen()
        fun onText(text: String)
        fun onBinary(data: ByteArray)
        fun onClosed(reason: String, code: Int, remote: Boolean)
        /** 建连阶段失败（还没 open 过）；已 open 后出错也走这里，由上层决定是否重连 */
        fun onFailure(error: Throwable)
    }

    fun connect(headers: Map<String, String>, listener: Listener, timeoutMs: Int = 10_000)

    /** 返回是否发送成功（连接未就绪时返回 false，不抛） */
    fun sendText(text: String): Boolean

    fun sendBinary(data: ByteArray): Boolean

    fun close(reason: String = "")

    val isOpen: Boolean
}
