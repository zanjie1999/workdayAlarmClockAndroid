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
        private val HTML_CHARSET: Charset = Charset.forName("UTF-8")
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
            val path = readRequestPath(socket)
            if (path == "/") {
                writePlayerPage(socket)
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

    private fun readRequestPath(socket: Socket): String? {
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
        return uri.path
    }

    private fun parseRoute(path: String): CameraStreamKey? {
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

    private val PLAYER_PAGE = """
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>摄像头</title>
<style>
body{font-family:sans-serif;max-width:960px;margin:24px auto;padding:0 16px;background:#111;color:#eee}
label{display:inline-block;margin:4px 12px 4px 0}input,button{font-size:16px;padding:6px}input{width:180px}
button{cursor:pointer;margin:4px}video{display:block;width:100%;max-height:80vh;background:#000;margin-top:16px}
#status{color:#aaa;margin-top:8px;min-height:1.4em}
</style>
</head>
<body>
<form id="controls">
<label>密码 <input id="password" type="text" autocomplete="off"></label>
<label>摄像头 <input id="camera" type="number" min="1" value="1"></label>
<button type="submit">播放</button>
<button id="stop" type="button">停止</button>
</form>
<video id="video" controls autoplay muted playsinline></video>
<div id="status"></div>
<script>
const video=document.getElementById('video');
const form=document.getElementById('controls');
const stopButton=document.getElementById('stop');
const statusView=document.getElementById('status');
let runId=0,abortController=null,retryTimer=null,objectUrl=null;
function setStatus(value){statusView.textContent=value;}
function waitEvent(target,event){return new Promise(resolve=>target.addEventListener(event,resolve,{once:true}));}
async function appendChunk(sourceBuffer,data){
  if(!data||!data.byteLength)return;
  while(sourceBuffer.updating)await waitEvent(sourceBuffer,'updateend');
  sourceBuffer.appendBuffer(data);
  await waitEvent(sourceBuffer,'updateend');
  if(video.buffered.length&&video.currentTime>10&&!sourceBuffer.updating){
    const removeEnd=video.currentTime-10;
    if(removeEnd>video.buffered.start(0)){sourceBuffer.remove(0,removeEnd);await waitEvent(sourceBuffer,'updateend');}
  }
}
function stopPlayback(showStatus){
  runId++;
  if(retryTimer){clearTimeout(retryTimer);retryTimer=null;}
  if(abortController){abortController.abort();abortController=null;}
  if(objectUrl){URL.revokeObjectURL(objectUrl);objectUrl=null;}
  video.removeAttribute('src');video.load();
  if(showStatus)setStatus('已停止');
}
async function connect(id,password,camera){
  if(id!==runId)return;
  if(!window.MediaSource){setStatus('当前浏览器不支持 MediaSource');return;}
  const mediaSource=new MediaSource();
  if(objectUrl)URL.revokeObjectURL(objectUrl);
  objectUrl=URL.createObjectURL(mediaSource);video.src=objectUrl;
  try{
    await waitEvent(mediaSource,'sourceopen');
    if(id!==runId)return;
    abortController=new AbortController();
    const prefix=password?encodeURIComponent(password)+'/':'';
    const response=await fetch('/'+prefix+'avc/'+encodeURIComponent(camera),{cache:'no-store',signal:abortController.signal});
    if(!response.ok)throw new Error('HTTP '+response.status);
    const codec=response.headers.get('X-Video-Codec');
    if(!codec)throw new Error('没有收到视频编码信息');
    const mime='video/mp4; codecs="'+codec+'"';
    if(!MediaSource.isTypeSupported(mime))throw new Error('浏览器不支持 '+mime);
    const sourceBuffer=mediaSource.addSourceBuffer(mime);
    const reader=response.body.getReader();
    setStatus('正在连接摄像头 '+camera+' ...');
    while(id===runId){
      const item=await reader.read();
      if(item.done)throw new Error('视频连接已结束');
      await appendChunk(sourceBuffer,item.value);
      if(video.paused)video.play().catch(()=>{});
    }
  }catch(error){
    if(id!==runId||error.name==='AbortError')return;
    setStatus('连接失败，2秒后重试：'+error.message);
    retryTimer=setTimeout(()=>connect(id,password,camera),2000);
  }
}
form.addEventListener('submit',event=>{
  event.preventDefault();
  stopPlayback(false);
  const id=runId;
  const password=document.getElementById('password').value.trim().replace(/^\/+|\/+$/g,'');
  const camera=parseInt(document.getElementById('camera').value,10);
  if(!Number.isInteger(camera)||camera<1){setStatus('摄像头编号无效');return;}
  connect(id,password,camera);
});
stopButton.addEventListener('click',()=>stopPlayback(true));
</script>
</body>
</html>
"""
}
