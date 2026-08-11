package com.zyyme.workdayalarmclock

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Camera
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.Charset
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("DEPRECATION")
internal class CameraHttpServer(
    context: Context,
    private val log: (String) -> Unit
) {
    companion object {
        const val PORT = 8880
        private val HTTP_CHARSET: Charset = Charset.forName("US-ASCII")
    }

    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val stateLock = Any()
    private val sessionChangeLock = Any()
    private val clients = LinkedHashMap<Socket, CameraStreamPipeline>()
    @Volatile private var password = ""
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var activePipeline: CameraStreamPipeline? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    fun start(cameraPassword: String): Boolean {
        val cleanPassword = normalizePassword(cameraPassword)
        if (running.get()) {
            updatePassword(cleanPassword)
            return true
        }
        password = cleanPassword
        if (!running.compareAndSet(false, true)) return true

        return try {
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
            serverSocket = socket
            acceptThread = Thread({ acceptLoop(socket) }, "camera-http-accept").apply { start() }
            log("摄像头HTTP服务已启动，端口$PORT")
            true
        } catch (e: Exception) {
            running.set(false)
            serverSocket = null
            log("摄像头HTTP服务启动失败：${e.message}")
            false
        }
    }

    fun updatePassword(cameraPassword: String) {
        val cleanPassword = normalizePassword(cameraPassword)
        if (password == cleanPassword) return
        password = cleanPassword
        closeActiveSession()
        log("摄像头密码已更新，现有视频流已断开")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        closeActiveSession()
        acceptThread?.interrupt()
        acceptThread = null
        log("摄像头HTTP服务已停止")
    }

    private fun normalizePassword(value: String): String {
        return value.trim().trim('/')
    }

    private fun acceptLoop(listener: ServerSocket) {
        while (running.get()) {
            try {
                val socket = listener.accept().apply {
                    tcpNoDelay = true
                    soTimeout = 5_000
                }
                Thread({ handleClient(socket) }, "camera-http-client").start()
            } catch (e: SocketException) {
                if (running.get()) log("摄像头HTTP监听失败：${e.message}")
            } catch (e: Exception) {
                if (running.get()) log("摄像头HTTP连接失败：${e.message}")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        var pipeline: CameraStreamPipeline? = null
        try {
            val route = readRoute(socket)
            if (route == null || !cameraExists(route)) {
                writeEmptyResponse(socket, 404, "Not Found")
                return
            }

            pipeline = acquirePipeline(route, socket)
            if (pipeline == null) {
                writeEmptyResponse(socket, 503, "Service Unavailable")
                return
            }
            socket.soTimeout = 0
            when (route.format) {
                CameraStreamFormat.MJPEG -> streamMjpeg(socket, pipeline)
                CameraStreamFormat.AVC -> streamAvc(socket, pipeline)
            }
        } catch (_: Exception) {
            // Client disconnects are expected while streams switch or viewers close.
        } finally {
            releaseClient(socket, pipeline)
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun readRoute(socket: Socket): CameraStreamKey? {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), HTTP_CHARSET))
        val requestLine = reader.readLine() ?: return null
        val parts = requestLine.split(' ')
        if (parts.size != 3 || parts[0] != "GET") return null

        var headerCount = 0
        while (true) {
            val line = reader.readLine() ?: return null
            if (line.isEmpty()) break
            headerCount++
            if (headerCount > 100) return null
        }

        val uri = try {
            Uri.parse(parts[1])
        } catch (_: Exception) {
            return null
        }
        if (uri.query != null || uri.fragment != null) return null
        val path = uri.path ?: return null
        val prefix = if (password.isEmpty()) "" else "/$password"

        val avcPrefix = "$prefix/avc/"
        if (path.startsWith(avcPrefix)) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
            val index = parseOneBasedIndex(path.substring(avcPrefix.length)) ?: return null
            return CameraStreamKey(CameraStreamFormat.AVC, index - 1)
        }

        val mjpegPrefix = "$prefix/"
        if (!path.startsWith(mjpegPrefix)) return null
        val index = parseOneBasedIndex(path.substring(mjpegPrefix.length)) ?: return null
        return CameraStreamKey(CameraStreamFormat.MJPEG, index - 1)
    }

    private fun parseOneBasedIndex(value: String): Int? {
        if (value.isEmpty() || value.any { it !in '0'..'9' }) return null
        val index = value.toIntOrNull() ?: return null
        return if (index > 0) index else null
    }

    private fun cameraExists(key: CameraStreamKey): Boolean {
        val count = try {
            when (key.format) {
                CameraStreamFormat.MJPEG -> Camera.getNumberOfCameras()
                CameraStreamFormat.AVC -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) 0
                    else AvcCameraPipeline.cameraCount(appContext)
                }
            }
        } catch (_: Exception) {
            0
        }
        return key.cameraIndex in 0 until count
    }

    private fun acquirePipeline(
        key: CameraStreamKey,
        socket: Socket
    ): CameraStreamPipeline? {
        synchronized(sessionChangeLock) {
            if (!running.get()) return null

            val current = synchronized(stateLock) { activePipeline }
            if (current != null && current.key == key) {
                synchronized(stateLock) { clients[socket] = current }
                return current
            }

            closeActiveSessionLocked()
            if (!running.get()) return null
            val newPipeline = createPipeline(key)
            if (!newPipeline.start()) {
                newPipeline.stop()
                return null
            }
            if (!running.get()) {
                newPipeline.stop()
                return null
            }
            synchronized(stateLock) {
                activePipeline = newPipeline
                clients[socket] = newPipeline
            }
            acquireStreamLocks()
            return newPipeline
        }
    }

    private fun createPipeline(key: CameraStreamKey): CameraStreamPipeline {
        return when (key.format) {
            CameraStreamFormat.MJPEG -> MjpegCameraPipeline(key, log)
            CameraStreamFormat.AVC -> AvcCameraPipeline(appContext, key, log)
        }
    }

    private fun releaseClient(socket: Socket, pipeline: CameraStreamPipeline?) {
        if (pipeline == null) return
        synchronized(sessionChangeLock) {
            var shouldStop = false
            synchronized(stateLock) {
                clients.remove(socket)
                if (activePipeline === pipeline && clients.values.none { it === pipeline }) {
                    activePipeline = null
                    shouldStop = true
                }
            }
            if (shouldStop) {
                pipeline.stop()
                releaseStreamLocks()
            }
        }
    }

    private fun closeActiveSession() {
        synchronized(sessionChangeLock) {
            closeActiveSessionLocked()
        }
    }

    private fun closeActiveSessionLocked() {
        val session = synchronized(stateLock) {
            val pipeline = activePipeline
            activePipeline = null
            val sockets = clients.keys.toList()
            clients.clear()
            Pair(pipeline, sockets)
        }
        session.second.forEach {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        session.first?.stop()
        releaseStreamLocks()
    }

    private fun streamMjpeg(socket: Socket, pipeline: CameraStreamPipeline) {
        val output = socket.getOutputStream()
        output.write(
            ("HTTP/1.0 200 OK\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-cache, no-store\r\n" +
                "Pragma: no-cache\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n")
                .toByteArray(HTTP_CHARSET)
        )
        output.flush()

        var sequence = 0L
        while (running.get() && !socket.isClosed) {
            val packet = pipeline.awaitPacket(sequence) ?: break
            sequence = packet.sequence
            output.write("--frame\r\n".toByteArray(HTTP_CHARSET))
            output.write("Content-Type: image/jpeg\r\n".toByteArray(HTTP_CHARSET))
            output.write("Content-Length: ${packet.data.size}\r\n\r\n".toByteArray(HTTP_CHARSET))
            output.write(packet.data)
            output.write("\r\n".toByteArray(HTTP_CHARSET))
            output.flush()
        }
    }

    private fun streamAvc(socket: Socket, pipeline: CameraStreamPipeline) {
        val output = socket.getOutputStream()
        output.write(
            ("HTTP/1.0 200 OK\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-cache, no-store\r\n" +
                "Pragma: no-cache\r\n" +
                "Content-Type: video/mp4\r\n\r\n")
                .toByteArray(HTTP_CHARSET)
        )
        output.flush()

        pipeline.requestKeyFrame()
        var sequence = 0L
        var started = false
        while (running.get() && !socket.isClosed) {
            val packet = pipeline.awaitPacket(sequence) ?: break
            sequence = packet.sequence
            if (!started) {
                if (!packet.keyFrame) continue
                val config = pipeline.codecConfig()
                if (config.isNotEmpty()) output.write(config)
                started = true
            }
            output.write(packet.data)
            output.flush()
        }
    }

    private fun writeEmptyResponse(socket: Socket, status: Int, reason: String) {
        val response = "HTTP/1.0 $status $reason\r\n" +
            "Connection: close\r\n" +
            "Content-Length: 0\r\n\r\n"
        socket.getOutputStream().write(response.toByteArray(HTTP_CHARSET))
        socket.getOutputStream().flush()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireStreamLocks() {
        if (wakeLock == null) {
            try {
                wakeLock = (appContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "workDayAlarmClock:CameraStream"
                    ).apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            } catch (e: Exception) {
                log("摄像头CPU唤醒锁获取失败：${e.message}")
            }
        }
        if (wifiLock == null) {
            try {
                wifiLock = (appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "workDayAlarmClock:CameraStream"
                    ).apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            } catch (e: Exception) {
                log("摄像头Wi-Fi锁获取失败：${e.message}")
            }
        }
    }

    private fun releaseStreamLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {
        }
        wifiLock = null
    }
}
