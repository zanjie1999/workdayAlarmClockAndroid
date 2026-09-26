package com.zyyme.workdayalarmclock.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Camera
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import com.zyyme.workdayalarmclock.MeService
import java.io.ByteArrayOutputStream
import java.io.InputStream
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
    private val brightness: AmbientBrightnessController,
    private val log: (String) -> Unit,
    private val cameraEnabled: () -> Boolean,
    private val speakerEnabled: () -> Boolean
) {
    companion object {
        const val PORT = 8880
        private val HTTP_CHARSET: Charset = Charset.forName("US-ASCII")
        private val HTML_CHARSET: Charset = Charset.forName("UTF-8")
    }

    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val stateLock = Any()
    private val sessionChangeLock = Any()
    private val audioSessionChangeLock = Any()
    private val streamLockState = Any()
    private val clients = LinkedHashMap<Socket, CameraStreamPipeline>()
    private val audioClients = LinkedHashSet<Socket>()
    @Volatile private var password = ""
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var activePipeline: CameraStreamPipeline? = null
    private var activeAudioPipeline: AacAudioPipeline? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var streamLockUsers = 0

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
        closeActiveAudioSession()
        log("摄像头密码已更新，现有媒体流已断开")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        closeActiveSession()
        closeActiveAudioSession()
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
        var audioPipeline: AacAudioPipeline? = null
        try {
            val request = readRequest(socket) ?: return
            val path = request.path
            if ((request.method == "POST" || request.method == "PUT") && path == "/aplay") {
                if (!speakerEnabled()) {
                    writeEmptyResponse(socket, 404, "Not Found")
                    return
                }
                if (request.expectContinue) {
                    socket.getOutputStream().write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(HTTP_CHARSET))
                    socket.getOutputStream().flush()
                }
                socket.soTimeout = 0
                streamPcm(socket, request)
                return
            }
            if (!cameraEnabled()) {
                writeEmptyResponse(socket, 404, "Not Found")
                return
            }
            if (path == "/" && request.method == "GET") {
                writePlayerPage(socket)
                return
            }
            if (path?.let { isAvcCapabilityRoute(it) } == true) {
                writeAvcCapability(socket)
                return
            }
            if (path?.let { isBrightnessRoute(it) } == true) {
                writeBrightness(socket)
                return
            }
            if (path?.let { isAacRoute(it) } == true) {
                if (!canStreamMicrophone()) {
                    writeEmptyResponse(socket, 204, "No Content")
                    return
                }
                audioPipeline = acquireAudioPipeline(socket)
                if (audioPipeline == null) {
                    writeEmptyResponse(socket, 503, "Service Unavailable")
                    return
                }
                socket.soTimeout = 0
                streamAac(socket, audioPipeline)
                return
            }
            val route = path?.let { parseRoute(it) }
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
                CameraStreamFormat.AVC -> {
                    val config = pipeline.awaitAvcConfig(10_000L)
                    if (config == null) {
                        writeEmptyResponse(socket, 503, "Service Unavailable")
                    } else {
                        streamAvc(socket, pipeline, config)
                    }
                }
            }
        } catch (e: Exception) {
            print2LogView("HTTP客户端处理失败：${e.javaClass.simpleName}: ${e.message ?: "无错误信息"}")
        } finally {
            releaseClient(socket, pipeline)
            releaseAudioClient(socket, audioPipeline)
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val uri: Uri,
        val body: InputStream,
        val expectContinue: Boolean
    )

    private fun readRequest(socket: Socket): HttpRequest? {
        val input = socket.getInputStream()
        val requestLine = readHttpLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size != 3 || parts[0] !in setOf("GET", "POST", "PUT")) return null

        var headerCount = 0
        var chunked = false
        var expectContinue = false
        while (true) {
            val line = readHttpLine(input) ?: return null
            if (line.isEmpty()) break
            headerCount++
            if (headerCount > 100) return null
            if (line.substringBefore(':').trim().equals("Transfer-Encoding", ignoreCase = true)) {
                chunked = line.substringAfter(':').trim().split(',')
                    .any { it.trim().equals("chunked", ignoreCase = true) }
            }
            if (line.substringBefore(':').trim().equals("Expect", ignoreCase = true)) {
                expectContinue = line.substringAfter(':').trim()
                    .equals("100-continue", ignoreCase = true)
            }
        }

        val uri = try {
            Uri.parse(parts[1])
        } catch (_: Exception) {
            return null
        }
        if (uri.fragment != null) return null
        val body = if (chunked) ChunkedInputStream(input) else input
        return HttpRequest(parts[0], uri.path.toString(), uri, body, expectContinue)
    }

    private class ChunkedInputStream(private val input: InputStream) : InputStream() {
        private var remaining = 0
        private var finished = false

        override fun read(): Int {
            if (!ensureChunk()) return -1
            val value = input.read()
            if (value < 0) throw java.io.EOFException("truncated chunk")
            remaining--
            if (remaining == 0) consumeCrlf()
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (!ensureChunk()) return -1
            val count = input.read(buffer, offset, minOf(length, remaining))
            if (count < 0) throw java.io.EOFException("truncated chunk")
            if (count == 0) return 0
            remaining -= count
            if (remaining == 0) consumeCrlf()
            return count
        }

        private fun ensureChunk(): Boolean {
            if (finished) return false
            if (remaining > 0) return true
            val line = readLine() ?: throw java.io.EOFException("missing chunk header")
            val sizeText = line.substringBefore(';').trim()
            remaining = sizeText.toIntOrNull(16)
                ?: throw IllegalArgumentException("invalid chunk size: $line")
            if (remaining == 0) {
                while (true) {
                    val trailer = readLine() ?: throw java.io.EOFException("missing chunk trailer")
                    if (trailer.isEmpty()) break
                }
                finished = true
                return false
            }
            return true
        }

        private fun consumeCrlf() {
            if (input.read() != '\r'.code || input.read() != '\n'.code) {
                throw IllegalArgumentException("invalid chunk terminator")
            }
        }

        private fun readLine(): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                if (value < 0) return null
                if (value == '\n'.code) return bytes.toString(HTTP_CHARSET.name()).trimEnd('\r')
                if (bytes.size() >= 8192) throw IllegalArgumentException("chunk line too long")
                bytes.write(value)
            }
        }
    }

    private fun readHttpLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return if (bytes.size() == 0) null else bytes.toString(HTTP_CHARSET.name())
            if (value == '\n'.code) return bytes.toString(HTTP_CHARSET.name()).trimEnd('\r')
            if (bytes.size() >= 8192) return null
            bytes.write(value)
        }
    }

    private fun parseRoute(path: String): CameraStreamKey? {
        val prefix = if (password.isEmpty()) "" else "/$password"

        val avcPrefix = "$prefix/avc/"
        if (path.startsWith(avcPrefix)) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
            val indices = parseCameraIndices(path.substring(avcPrefix.length)) ?: return null
            return CameraStreamKey(CameraStreamFormat.AVC, indices.first, indices.second)
        }

        val mjpegPrefix = "$prefix/"
        if (!path.startsWith(mjpegPrefix)) return null
        val indices = parseCameraIndices(path.substring(mjpegPrefix.length)) ?: return null
        return CameraStreamKey(CameraStreamFormat.MJPEG, indices.first, indices.second)
    }

    private fun isAvcCapabilityRoute(path: String): Boolean {
        val prefix = if (password.isEmpty()) "" else "/$password"
        return path == "$prefix/avc/capability"
    }

    private fun isBrightnessRoute(path: String): Boolean {
        val prefix = if (password.isEmpty()) "" else "/$password"
        return path == "$prefix/brightness"
    }

    private fun isAacRoute(path: String): Boolean {
        val prefix = if (password.isEmpty()) "" else "/$password"
        return path == "$prefix/aac"
    }

    private fun print2LogView(s: String) {
        MeService.me?.print2LogView("CameraHttp: $s")
    }

    private fun releasePcmTrack(track: AudioTrack?) {
        if (track == null) return

        // AudioTrack 初始化失败时 state == STATE_UNINITIALIZED，
        // 此时不能调用 stop()/flush()，否则会抛：
        // "stop() called on uninitialized AudioTrack"。
        if (track.state == AudioTrack.STATE_INITIALIZED) {
            try {
                track.stop()
            } catch (_: Exception) {
            }
            try {
                // 丢掉尚未播放的数据，断开连接时不要留下播放尾巴。
                track.flush()
            } catch (_: Exception) {
            }
        }

        try {
            track.release()
        } catch (_: Exception) {
        }
    }

    private fun streamPcm(socket: Socket, request: HttpRequest) {
        var track: AudioTrack? = null
        try {
            val rate = request.uri.getQueryParameter("rate")?.toIntOrNull() ?: 44100
            val channels = request.uri.getQueryParameter("channels")?.toIntOrNull() ?: 2
            if (rate !in 8000..192000 || channels !in 1..2) {
                print2LogView("电脑音箱参数不支持：rate=$rate channels=$channels")
                writeEmptyResponse(socket, 400, "Unsupported")
                return
            }

            val channelMask = if (channels == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }
            val minBufferFallback = AudioTrack.getMinBufferSize(
                rate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val minBuffer = minBufferFallback / 2
            if (minBuffer <= 0) {
                print2LogView("电脑音箱无法获取缓冲区：rate=$rate channels=$channels result=$minBuffer")
                writeEmptyResponse(socket, 415, "Unsupported Media Type")
                return
            } else {
                print2LogView("电脑音箱连接 缓冲：$minBuffer")
            }

            track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(channelMask)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuffer)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                val legacyTrack = try {
                    AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        rate,
                        channelMask,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBuffer,
                        AudioTrack.MODE_STREAM
                    )
                } catch (e: IllegalArgumentException) {
                    print2LogView("小缓冲区不可用：$minBuffer，回退到 $minBufferFallback：${e.message}")
                    AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        rate,
                        channelMask,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBufferFallback,
                        AudioTrack.MODE_STREAM
                    )
                }
                legacyTrack
            }

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                print2LogView("电脑音箱初始化失败：rate=$rate channels=$channels state=${track.state}")
                releasePcmTrack(track)
                track = null
                writeEmptyResponse(socket, 415, "Unsupported Media Type")
                return
            }
            socket.getOutputStream().write(
                "HTTP/1.0 200 OK\r\nConnection: close\r\n\r\n".toByteArray(HTTP_CHARSET)
            )
            socket.getOutputStream().flush()
            track.play()
            val frameBytes = channels * 2
            val buffer = ByteArray(minBuffer.coerceAtLeast(2048) + frameBytes)
            var pending = 0
            while (true) {
                val count = request.body.read(buffer, pending, buffer.size - pending)
                if (count < 0) break
                if (count == 0) continue
                val total = pending + count
                val writable = total - (total % frameBytes)
                var offset = 0
                while (offset < writable) {
                    val length = writable - offset
                    val written = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        track.write(buffer, offset, length, AudioTrack.WRITE_BLOCKING)
                    } else {
                        @Suppress("DEPRECATION")
                        track.write(buffer, offset, length)
                    }
                    if (written < 0) {
                        throw IllegalStateException("AudioTrack.write failed: $written")
                    }
                    if (written == 0) {
                        Thread.sleep(1L)
                    } else {
                        offset += written
                    }
                }
                pending = total - writable
                if (pending > 0) {
                    System.arraycopy(buffer, writable, buffer, 0, pending)
                }
            }
        } catch (e: Exception) {
            print2LogView("电脑音箱播放失败：${e.javaClass.simpleName}: ${e.message ?: "无错误信息"}")
            throw e
        } finally {
            // 每个 Socket 都只回收自己的 AudioTrack。
            // 因此多个设备可以同时播放；旧连接即使晚于新连接退出，
            // 也只会释放旧连接自己的 Track，不会碰新连接。
            releasePcmTrack(track)
            track = null
        }
    }

    private fun canStreamMicrophone(): Boolean {
        if (!AacAudioPipeline.isPlatformSupported()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun writeBrightness(socket: Socket) {
        val body = brightness.level.toString().toByteArray(HTTP_CHARSET)
        val header = "HTTP/1.0 200 OK\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-cache, no-store\r\n" +
            "Content-Type: text/plain; charset=us-ascii\r\n" +
            "Content-Length: ${body.size}\r\n\r\n"
        val output = socket.getOutputStream()
        output.write(header.toByteArray(HTTP_CHARSET))
        output.write(body)
        output.flush()
    }

    private fun writeAvcCapability(socket: Socket) {
        val supported = isAvcEncoderUsable()
        val body = "{\"supported\":$supported}"
            .toByteArray(HTTP_CHARSET)
        val header = "HTTP/1.0 200 OK\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-cache, no-store\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n\r\n"
        val output = socket.getOutputStream()
        output.write(header.toByteArray(HTTP_CHARSET))
        output.write(body)
        output.flush()
    }

    private fun isAvcEncoderUsable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false

        var encoder: MediaCodec? = null
        var inputSurface: Surface? = null
        var started = false
        return try {
            val testEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder = testEncoder
            val capabilities = testEncoder.codecInfo.getCapabilitiesForType(
                MediaFormat.MIMETYPE_VIDEO_AVC
            )
            if (!capabilities.colorFormats.contains(
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
            ) return false
            val videoCapabilities = capabilities.videoCapabilities ?: return false
            val testSize = listOf(
                Pair(640, 480),
                Pair(1280, 720),
                Pair(320, 240),
                Pair(1920, 1080),
                Pair(176, 144)
            ).firstOrNull { (width, height) ->
                try {
                    videoCapabilities.areSizeAndRateSupported(width, height, 15.0)
                } catch (_: Exception) {
                    false
                }
            } ?: return false
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                testSize.first,
                testSize.second
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, 512_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 15)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            testEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = testEncoder.createInputSurface()
            testEncoder.start()
            started = true
            true
        } catch (_: Exception) {
            false
        } finally {
            if (started) {
                try {
                    encoder?.stop()
                } catch (_: Exception) {
                }
            }
            try {
                inputSurface?.release()
            } catch (_: Exception) {
            }
            try {
                encoder?.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun parseCameraIndices(value: String): Pair<Int, Int?>? {
        val parts = value.split('/')
        if (parts.size !in 1..2) return null
        val cameraIndex = parseOneBasedIndex(parts[0]) ?: return null
        val resolutionIndex = if (parts.size == 2) {
            parseOneBasedIndex(parts[1]) ?: return null
        } else {
            null
        }
        return Pair(cameraIndex - 1, resolutionIndex?.minus(1))
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
            brightness.onIpCameraStarting()
            if (!running.get()) return null
            val newPipeline = createPipeline(key)
            if (!newPipeline.start()) {
                newPipeline.stop()
                brightness.onIpCameraStopped()
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
            CameraStreamFormat.MJPEG -> {
                brightness.beginCameraWarmup()
                MjpegCameraPipeline(key, log, brightness::updateLuma)
            }
            CameraStreamFormat.AVC -> AvcCameraPipeline(appContext, key, log)
        }
    }

    private fun acquireAudioPipeline(socket: Socket): AacAudioPipeline? {
        synchronized(audioSessionChangeLock) {
            if (!running.get() || !canStreamMicrophone()) return null
            synchronized(stateLock) {
                activeAudioPipeline?.let { pipeline ->
                    audioClients.add(socket)
                    return pipeline
                }
            }

            val pipeline = AacAudioPipeline(log)
            if (!pipeline.start()) {
                pipeline.stop()
                return null
            }
            if (!running.get()) {
                pipeline.stop()
                return null
            }
            synchronized(stateLock) {
                activeAudioPipeline = pipeline
                audioClients.add(socket)
            }
            acquireStreamLocks()
            return pipeline
        }
    }

    private fun releaseAudioClient(socket: Socket, pipeline: AacAudioPipeline?) {
        if (pipeline == null) return
        synchronized(audioSessionChangeLock) {
            var shouldStop = false
            synchronized(stateLock) {
                audioClients.remove(socket)
                if (activeAudioPipeline === pipeline && audioClients.isEmpty()) {
                    activeAudioPipeline = null
                    shouldStop = true
                }
            }
            if (shouldStop) {
                pipeline.stop()
                releaseStreamLocks()
            }
        }
    }

    private fun closeActiveAudioSession() {
        synchronized(audioSessionChangeLock) {
            val session = synchronized(stateLock) {
                val pipeline = activeAudioPipeline
                activeAudioPipeline = null
                val sockets = audioClients.toList()
                audioClients.clear()
                Pair(pipeline, sockets)
            }
            session.second.forEach { socket ->
                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }
            session.first?.let { pipeline ->
                pipeline.stop()
                releaseStreamLocks()
            }
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
                brightness.onIpCameraStopped()
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
        val pipeline = session.first
        if (pipeline != null) {
            pipeline.stop()
            brightness.onIpCameraStopped()
            releaseStreamLocks()
        }
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

    private fun streamAvc(
        socket: Socket,
        pipeline: CameraStreamPipeline,
        config: AvcStreamConfig
    ) {
        val muxer = FragmentedMp4Muxer(config)
        val output = socket.getOutputStream()
        output.write(
            ("HTTP/1.0 200 OK\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-cache, no-store\r\n" +
                "Pragma: no-cache\r\n" +
                "Content-Type: video/mp4\r\n" +
                "X-Video-Codec: ${config.codecString}\r\n\r\n")
                .toByteArray(HTTP_CHARSET)
        )
        output.write(muxer.initializationSegment())
        output.flush()

        pipeline.requestKeyFrame()
        var sequence = 0L
        var started = false
        while (running.get() && !socket.isClosed) {
            val packet = pipeline.awaitPacket(sequence) ?: break
            sequence = packet.sequence
            if (!started) {
                if (!packet.keyFrame) continue
                started = true
            }
            val fragment = muxer.mediaFragment(packet) ?: continue
            output.write(fragment)
            output.flush()
        }
    }

    private fun streamAac(socket: Socket, pipeline: AacAudioPipeline) {
        val config = pipeline.config
        val muxer = FragmentedAacMp4Muxer(config)
        val output = socket.getOutputStream()
        output.write(
            ("HTTP/1.0 200 OK\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-cache, no-store\r\n" +
                "Pragma: no-cache\r\n" +
                "Content-Type: audio/mp4\r\n" +
                "X-Audio-Codec: ${config.codecString}\r\n\r\n")
                .toByteArray(HTTP_CHARSET)
        )
        output.write(muxer.initializationSegment())
        output.flush()

        var sequence = 0L
        while (running.get() && !socket.isClosed) {
            val packet = pipeline.awaitPacket(sequence) ?: break
            sequence = packet.sequence
            output.write(muxer.mediaFragment(packet))
            output.flush()
        }
    }

    private fun writePlayerPage(socket: Socket) {
        val body = PLAYER_PAGE.toByteArray(HTML_CHARSET)
        val header = "HTTP/1.0 200 OK\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-cache, no-store\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n\r\n"
        val output = socket.getOutputStream()
        output.write(header.toByteArray(HTTP_CHARSET))
        output.write(body)
        output.flush()
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
        synchronized(streamLockState) {
            streamLockUsers++
            if (streamLockUsers > 1) return
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
    }

    private fun releaseStreamLocks() {
        synchronized(streamLockState) {
            if (streamLockUsers == 0) return
            streamLockUsers--
            if (streamLockUsers > 0) return
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

    private val PLAYER_PAGE = """
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>咩IP摄像头</title>
<style>
*{box-sizing:border-box}body{font-family:sans-serif;max-width:960px;margin:0 auto;padding:16px;background:#000;color:#eee}
#controls{display:flex;flex-wrap:wrap;gap:8px;align-items:end}.field{display:flex;flex:1 1 180px;min-width:0;flex-direction:column;gap:4px;font-size:14px;color:#bbb}
input,button{width:100%;min-height:42px;font:inherit;font-size:16px;padding:8px 10px;border:1px solid #555;border-radius:4px}input{background:#222;color:#eee}
button{flex:0 1 110px;cursor:pointer;background:#333;color:#eee}button[type=submit]{background:#1769aa;border-color:#278bd2}
#modes{display:flex;gap:14px;align-items:center;min-height:42px;padding:0 8px}.mode{display:flex;gap:7px;align-items:center;color:#eee;font-size:16px}.mode input{width:20px;min-height:20px;margin:0;padding:0}
#videoFrame{width:100%;max-width:100vw;max-height:100vh;aspect-ratio:16/9;margin:16px auto 0;display:flex;align-items:center;justify-content:center;overflow:hidden;background:#000}
video,#mjpeg{display:block;width:100%;height:100%;object-fit:contain;transform-origin:center center}audio{display:block;width:100%;margin-top:12px}
.status{color:#aaa;margin-top:8px;min-height:1.4em}[hidden]{display:none!important}
@media (max-width:520px){body{padding:10px}#controls{display:grid;grid-template-columns:1fr 1fr;gap:8px}.field,#modes{grid-column:span 2}button{flex:auto;width:auto}#videoFrame{margin-top:10px}}
</style>
</head>
<body>
<form id="controls">
<label class="field">密码 <input id="password" type="text" autocomplete="off"></label>
<label class="field">摄像头 <input id="camera" type="number" min="1" value="1"></label>
<label class="field">分辨率档位 <input id="resolution" type="number" min="1" placeholder="自动"></label>
<div id="modes">
<label class="mode"><input id="playVideo" type="checkbox" checked>视频</label>
<label class="mode"><input id="playAudio" type="checkbox" checked>声音</label>
</div>
<button id="playStop" type="submit">播放</button>
<button id="rotate" type="button" aria-label="旋转画面">旋转 0°</button>
</form>
<div id="videoFrame"><video id="video" controls autoplay muted playsinline></video></div>
<div id="videoStatus" class="status"></div>
<audio id="audio" controls autoplay></audio>
<div id="audioStatus" class="status"></div>
<script>
const video=document.getElementById('video'),audio=document.getElementById('audio');
const videoFrame=document.getElementById('videoFrame'),form=document.getElementById('controls');
const playStopButton=document.getElementById('playStop'),rotateButton=document.getElementById('rotate');
const videoStatus=document.getElementById('videoStatus'),audioStatus=document.getElementById('audioStatus');
const playVideo=document.getElementById('playVideo'),playAudio=document.getElementById('playAudio');
let runId=0,rotation=0,playbackActive=false,mjpegImage=null;
let videoAbort=null,videoRetry=null,videoUrl=null,audioAbort=null,audioRetry=null,audioUrl=null;
function setVideoStatus(value){videoStatus.textContent=value;}
function setAudioStatus(value){audioStatus.textContent=value;}
function setPlaybackState(active){playbackActive=active;playStopButton.textContent=active?'停止':'播放';}
function waitEvent(target,event){return new Promise(resolve=>target.addEventListener(event,resolve,{once:true}));}
async function appendChunk(media,sourceBuffer,data){
  if(!data||!data.byteLength)return;
  while(sourceBuffer.updating)await waitEvent(sourceBuffer,'updateend');
  sourceBuffer.appendBuffer(data);
  await waitEvent(sourceBuffer,'updateend');
  if(media.buffered.length&&media.currentTime>10&&!sourceBuffer.updating){
    const removeEnd=media.currentTime-10;
    if(removeEnd>media.buffered.start(0)){sourceBuffer.remove(0,removeEnd);await waitEvent(sourceBuffer,'updateend');}
  }
}
function clearVideo(){
  if(videoRetry){clearTimeout(videoRetry);videoRetry=null;}
  if(videoAbort){videoAbort.abort();videoAbort=null;}
  if(mjpegImage){mjpegImage.remove();mjpegImage=null;}
  video.onerror=null;video.removeAttribute('src');video.load();video.style.display='block';
  if(videoUrl){URL.revokeObjectURL(videoUrl);videoUrl=null;}
}
function clearAudio(){
  if(audioRetry){clearTimeout(audioRetry);audioRetry=null;}
  if(audioAbort){audioAbort.abort();audioAbort=null;}
  audio.onerror=null;audio.removeAttribute('src');audio.load();
  if(audioUrl){URL.revokeObjectURL(audioUrl);audioUrl=null;}
}
function stopPlayback(showStatus){
  runId++;setPlaybackState(false);clearVideo();clearAudio();
  if(showStatus){setVideoStatus(playVideo.checked?'已停止':'');setAudioStatus(playAudio.checked?'已停止':'');}
}
function updateVideoShape(){
  const width=video.videoWidth||16,height=video.videoHeight||9,rotated=rotation%180!==0;
  videoFrame.style.aspectRatio=(rotated?height/width:width/height)+' / 1';
  video.style.width=rotated?(width/height*100)+'%':'100%';
  video.style.height=rotated?(height/width*100)+'%':'100%';
  video.style.transform='rotate('+rotation+'deg)';rotateButton.textContent='旋转 '+rotation+'°';
}
function showMjpegFallback(id,password,camera,resolution){
  if(id!==runId)return;clearVideo();video.style.display='none';
  const prefix=password?encodeURIComponent(password)+'/':'',resolutionPath=resolution===null?'':'/'+encodeURIComponent(resolution);
  mjpegImage=document.createElement('img');mjpegImage.id='mjpeg';mjpegImage.alt='MJPEG摄像头画面';
  mjpegImage.src='/'+prefix+encodeURIComponent(camera)+resolutionPath;
  mjpegImage.onload=()=>setVideoStatus('正在使用MJPEG播放摄像头 '+camera+(resolution===null?' 自动':' 档位'+resolution));
  mjpegImage.onerror=()=>setVideoStatus('MJPEG连接失败');videoFrame.appendChild(mjpegImage);
  setVideoStatus('正在切换到MJPEG ...');
}
async function getAvcCapability(password){
  const prefix=password?encodeURIComponent(password)+'/':'';
  try{
    const response=await fetch('/'+prefix+'avc/capability',{cache:'no-store'});
    if(response.status===404)return 'password-error';if(!response.ok)return 'unsupported';
    const result=await response.json();return result&&result.supported===true?'supported':'unsupported';
  }catch(error){return 'unsupported';}
}
async function connectVideo(id,password,camera,resolution){
  if(id!==runId)return;
  const capability=await getAvcCapability(password);if(id!==runId)return;
  if(capability==='password-error'){setVideoStatus('密码错误');return;}
  if(capability!=='supported'){showMjpegFallback(id,password,camera,resolution);return;}
  const prefix=password?encodeURIComponent(password)+'/':'',resolutionPath=resolution===null?'':'/'+encodeURIComponent(resolution);
  const path='/'+prefix+'avc/'+encodeURIComponent(camera)+resolutionPath;
  if(!window.MediaSource){
    video.onerror=()=>showMjpegFallback(id,password,camera,resolution);video.src=path;video.load();
    setVideoStatus('正在播放摄像头 '+camera+' ...');video.play().catch(()=>{});return;
  }
  clearVideo();const mediaSource=new MediaSource();videoUrl=URL.createObjectURL(mediaSource);video.src=videoUrl;video.play().catch(()=>{});
  try{
    await waitEvent(mediaSource,'sourceopen');if(id!==runId)return;
    videoAbort=new AbortController();const response=await fetch(path,{cache:'no-store',signal:videoAbort.signal});
    if(!response.ok){const error=new Error('HTTP '+response.status);error.status=response.status;throw error;}
    const codec=response.headers.get('X-Video-Codec'),mime='video/mp4; codecs="'+codec+'"';
    if(!codec||!MediaSource.isTypeSupported(mime))throw new Error('浏览器不支持视频编码');
    const sourceBuffer=mediaSource.addSourceBuffer(mime),reader=response.body.getReader();
    setVideoStatus('正在播放摄像头 '+camera+(resolution===null?' 自动':' 档位'+resolution)+' ...');
    while(id===runId){const item=await reader.read();if(item.done)throw new Error('视频连接已结束');await appendChunk(video,sourceBuffer,item.value);if(video.paused)video.play().catch(()=>{});}
  }catch(error){
    if(id!==runId||error.name==='AbortError')return;
    if(error.status===404||error.status===503||String(error.message||'').indexOf('不支持视频编码')>=0){showMjpegFallback(id,password,camera,resolution);return;}
    setVideoStatus('视频连接失败，2秒后重试：'+error.message);videoRetry=setTimeout(()=>connectVideo(id,password,camera,resolution),2000);
  }
}
async function connectAudio(id,password){
  if(id!==runId)return;
  const prefix=password?encodeURIComponent(password)+'/':'',path='/'+prefix+'aac';
  if(!window.MediaSource){audio.src=path;audio.load();audio.play().catch(()=>{});setAudioStatus('正在连接麦克风 ...');return;}
  clearAudio();const mediaSource=new MediaSource();audioUrl=URL.createObjectURL(mediaSource);audio.src=audioUrl;audio.play().catch(()=>{});
  try{
    await waitEvent(mediaSource,'sourceopen');if(id!==runId)return;
    audioAbort=new AbortController();const response=await fetch(path,{cache:'no-store',signal:audioAbort.signal});
    if(response.status===204){clearAudio();setAudioStatus('麦克风未授权，当前无声音');return;}
    if(!response.ok){const error=new Error('HTTP '+response.status);error.status=response.status;throw error;}
    const codec=response.headers.get('X-Audio-Codec')||'mp4a.40.2',mime='audio/mp4; codecs="'+codec+'"';
    if(!MediaSource.isTypeSupported(mime))throw new Error('浏览器不支持 '+mime);
    const sourceBuffer=mediaSource.addSourceBuffer(mime),reader=response.body.getReader();setAudioStatus('正在播放麦克风声音 ...');
    while(id===runId){const item=await reader.read();if(item.done)throw new Error('音频连接已结束');await appendChunk(audio,sourceBuffer,item.value);if(audio.paused)audio.play().catch(()=>{});}
  }catch(error){
    if(id!==runId||error.name==='AbortError')return;
    if(error.status===404){setAudioStatus('密码错误');return;}
    if(error.status===503){setAudioStatus('麦克风暂时不可用');return;}
    setAudioStatus('音频连接失败，2秒后重试：'+error.message);audioRetry=setTimeout(()=>connectAudio(id,password),2000);
  }
}
form.addEventListener('submit',event=>{
  event.preventDefault();if(playbackActive){stopPlayback(true);return;}
  const password=document.getElementById('password').value.trim().replace(/^\/+|\/+$/g,'');
  const camera=parseInt(document.getElementById('camera').value,10),resolutionText=document.getElementById('resolution').value.trim();
  const resolution=resolutionText===''?null:Number(resolutionText),withVideo=playVideo.checked,withAudio=playAudio.checked;
  if(!withVideo&&!withAudio){setVideoStatus('请至少选择视频或声音');setAudioStatus('');return;}
  if(withVideo&&(!Number.isInteger(camera)||camera<1)){setVideoStatus('摄像头编号无效');return;}
  if(withVideo&&resolution!==null&&(!Number.isInteger(resolution)||resolution<1)){setVideoStatus('分辨率档位无效');return;}
  try{
    localStorage.setItem('cameraPassword',password);localStorage.setItem('cameraNumber',String(camera));
    localStorage.setItem('cameraResolution',resolution===null?'':String(resolution));
    localStorage.setItem('cameraPlayVideo',String(withVideo));localStorage.setItem('cameraPlayAudio',String(withAudio));
  }catch(error){}
  videoFrame.hidden=!withVideo;videoStatus.hidden=!withVideo;rotateButton.disabled=!withVideo;
  audio.hidden=!withAudio;audioStatus.hidden=!withAudio;setVideoStatus('');setAudioStatus('');setPlaybackState(true);
  const id=runId;if(withVideo)connectVideo(id,password,camera,resolution);if(withAudio)connectAudio(id,password);
});
rotateButton.addEventListener('click',()=>{rotation=(rotation+90)%360;updateVideoShape();});video.addEventListener('loadedmetadata',updateVideoShape);
try{
  const savedPassword=localStorage.getItem('cameraPassword'),savedCamera=localStorage.getItem('cameraNumber'),savedResolution=localStorage.getItem('cameraResolution');
  const savedVideo=localStorage.getItem('cameraPlayVideo'),savedAudio=localStorage.getItem('cameraPlayAudio');
  if(savedPassword!==null)document.getElementById('password').value=savedPassword;
  if(savedCamera!==null&&/^[1-9][0-9]*$/.test(savedCamera))document.getElementById('camera').value=savedCamera;
  if(savedResolution!==null&&/^[1-9][0-9]*$/.test(savedResolution))document.getElementById('resolution').value=savedResolution;
  if(savedVideo!==null)playVideo.checked=savedVideo==='true';if(savedAudio!==null)playAudio.checked=savedAudio==='true';
}catch(error){}
updateVideoShape();
</script>
</body>
</html>
"""
}
