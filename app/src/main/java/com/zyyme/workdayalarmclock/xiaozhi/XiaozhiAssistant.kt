package com.zyyme.workdayalarmclock.xiaozhi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.zyyme.workdayalarmclock.MeSettings
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 小智AI 会话编排。
 *
 * 线程模型：
 * - 控制面：[handler]（HandlerThread "xiaozhi-ctrl"）上跑状态机、socket 收发、各种计时器；
 * - 采集与播放各有自己的线程，回调一律 post 回控制面；
 * - 宿主（MeService / Activity）的方法都在主线程调用。
 *
 * 手势 / 触屏 / 管道 / 将来的语音唤醒都只调 [start]，不再区分是谁唤起的。
 */
object XiaozhiAssistant {

    private const val HELLO_TIMEOUT_MS = 10_000L
    private const val MAX_RECONNECT = 5
    /** llm 单独发来的那行表情最多这么长，超过就当成正常正文，不当前缀 */
    private const val MAX_LEAD_LENGTH = 8
    private const val VOICE_RMS_THRESHOLD = 700.0
    private const val LEVEL_RESET_INTERVAL_MS = 800L
    private const val OTA_POLL_MAX_ATTEMPTS = 100
    private const val OTA_POLL_INTERVAL_MS = 3000L

    private var appContext: Context? = null
    private var host: XiaozhiHost? = null

    @Volatile
    private var config = XiaozhiConfig()

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var otaExecutor: ExecutorService? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private var stateMachine = XiaozhiStateMachine()

    @Volatile
    private var sessionActive = false

    private var transport: XiaozhiTransport? = null
    private var capture: XiaozhiAudioCapture? = null
    private var player: XiaozhiAudioPlayer? = null

    private var lastWsUrl = ""
    private var lastToken = ""
    private var reconnectCount = 0
    private var helloTimeout: Runnable? = null
    private var silenceTimeout: Runnable? = null
    /** 本轮 tts 之前单独发来的那行表情，挂在每段字幕前面 */
    private val turnLead = StringBuilder()

    @Volatile
    private var sendAudio = false

    @Volatile
    private var lastLevelResetAt = 0L

    // ---------------------------------------------------------------- 生命周期

    @Synchronized
    fun attach(context: Context, host: XiaozhiHost) {
        appContext = context.applicationContext
        this.host = host
        if (handlerThread == null) {
            val thread = HandlerThread("xiaozhi-ctrl")
            thread.start()
            handlerThread = thread
            handler = Handler(thread.looper)
        }
        if (otaExecutor == null) {
            otaExecutor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "xiaozhi-ota").apply { isDaemon = true }
            }
        }
        reloadConfig()
    }

    @Synchronized
    fun shutdown() {
        stop("服务结束")
        handler?.post {
            handlerThread?.quitSafely()
        }
        handlerThread = null
        handler = null
        otaExecutor?.shutdownNow()
        otaExecutor = null
        appContext = null
        host = null
        stateMachine.reset()
        sessionActive = false
        XiaozhiCaption.clear()
    }

    fun reloadConfig() {
        val ctx = appContext ?: return
        val cfg = XiaozhiConfig(
            enabled = MeSettings.isEnabled(ctx, MeSettings.KEY_XIAOZHI),
            otaUrl = XiaozhiConfig.normalizeOtaUrl(MeSettings.getXiaozhiOtaUrl(ctx)),
            wsUrl = XiaozhiConfig.normalizeWsUrl(MeSettings.getXiaozhiWsUrl(ctx)),
            token = MeSettings.getXiaozhiToken(ctx),
            deviceMac = XiaozhiDeviceInfo.deviceMac(ctx),
            clientId = XiaozhiDeviceInfo.clientId(ctx),
            mode = XiaozhiConfig.normalizeMode(MeSettings.getXiaozhiMode(ctx)),
            autoContinue = MeSettings.isXiaozhiAutoContinue(ctx),
            silenceTimeoutMs = MeSettings.getXiaozhiSilenceTimeout(ctx) * 1000L
        )
        config = cfg
        stateMachine.autoContinue = cfg.autoContinue
        stateMachine.silenceTimeoutMs = cfg.silenceTimeoutMs
        if (!cfg.enabled && sessionActive) stop("模块已关闭")
    }

    fun isSessionActive(): Boolean = sessionActive

    fun isEnabled(): Boolean = config.enabled

    // ---------------------------------------------------------------- 对外入口

    /**
     * 唤醒 / 打断。返回 false 表示这次不该被消费（未启用 / 无权限），宿主落回原逻辑。
     *
     * 注意：这里不按系统版本做门槛。小智用到的音频接口都是 API 3 起就有的，
     * 唯一的版本差异是 16+ 才有 AcousticEchoCanceler，XiaozhiAudioCapture 内部已自行降级。
     *
     * @param hint 可选的补充文本，目前只有 Go 管道会传，预留给"AI 打开待办"这类带参数的唤起。
     */
    fun start(hint: String? = null): Boolean {
        if (!config.enabled) return false
        val ctx = appContext ?: return false
        if (!hasRecordPermission(ctx)) {
            status("需要麦克风权限")
            return false
        }
        post { onTrigger(hint) }
        return true
    }

    /** Go 侧将来的触发口：AI 或 AI 文本 */
    fun onPipeCommand(argument: String) {
        val hint = argument.trim()
        if (!start(hint.ifEmpty { null })) {
            log("收到管道唤醒但模块未就绪")
        }
    }

    fun stop(reason: String) {
        post { stopInternal(reason) }
    }

    /** 设置弹窗里激活按钮上的文案，激活流程随手更新，弹窗轮询读它 */
    @Volatile
    var activationLabel: String = "未激活（点击查询）"
        private set

    /** 设置里点「激活」：跑一次 OTA，把激活码/结果同时写到按钮和日志 */
    fun requestActivation() {
        val ctx = appContext ?: return
        val executor = otaExecutor ?: return
        if (config.token.isNotBlank()) {
            activationLabel = "已配置 Token，无需激活"
            status("已配置自定义 Token，无需激活")
            return
        }
        activationLabel = "查询中…"
        status("正在向小智服务端查询…")
        val otaUrl = config.otaUrl
        val mac = XiaozhiDeviceInfo.deviceMac(ctx)
        val clientId = XiaozhiDeviceInfo.clientId(ctx)
        val version = hostVersion()
        executor.execute {
            val client = XiaozhiOtaClient(otaUrl, mac, clientId) { log(it) }
            val body = XiaozhiDeviceInfo.otaRequestBody(ctx, version, "android")
            when (val result = client.fetch(body)) {
                is XiaozhiOtaClient.Result.Ok -> {
                    activationLabel = "已激活"
                    status("设备已绑定，可以直接对话")
                }
                is XiaozhiOtaClient.Result.NeedActivation -> {
                    if (result.code.isBlank()) {
                        activationLabel = "未绑定（点击重查）"
                        status("设备未绑定，请先在 xiaozhi.me 添加设备（MAC $mac）")
                    } else {
                        activationLabel = "激活码 ${result.code}（点击重查）"
                        status("激活码 ${result.code}，请到 xiaozhi.me 添加设备（设备 ${mac}）")
                        val polled = client.pollActivate(
                            result.code,
                            body,
                            OTA_POLL_MAX_ATTEMPTS,
                            OTA_POLL_INTERVAL_MS
                        ) { freshCode, _ ->
                            post { activationLabel = "激活码 $freshCode（点击重查）" }
                        }
                        when (polled) {
                            is XiaozhiOtaClient.Result.Ok -> {
                                activationLabel = "已激活"
                                status("设备已绑定，可以直接对话")
                            }
                            is XiaozhiOtaClient.Result.NeedActivation -> {
                                val latest = polled.code.ifBlank { result.code }
                                activationLabel = if (latest.isBlank()) {
                                    "未绑定（点击重查）"
                                } else {
                                    "激活码 $latest，等待绑定"
                                }
                                status("仍未绑定：${polled.message}")
                            }
                            is XiaozhiOtaClient.Result.Failed -> {
                                activationLabel = "激活码 ${result.code}（绑定失败，点击重试）"
                                status("激活失败：${polled.message}")
                            }
                        }
                    }
                }
                is XiaozhiOtaClient.Result.Failed -> {
                    activationLabel = "查询失败（点击重试）"
                    status("查询失败：${result.message}")
                }
            }
        }
    }

    // ---------------------------------------------------------------- 会话

    private fun onTrigger(hint: String?) {
        if (stateMachine.isActive) {
            interruptInternal()
        } else {
            beginSession(hint)
        }
    }

    private fun beginSession(hint: String?) {
        if (stateMachine.isActive) return
        val suffix = if (hint.isNullOrBlank()) "" else "：$hint"
        log("开始会话$suffix")
        sessionActive = true
        reconnectCount = 0
        turnLead.setLength(0)
        // 整个会话期间都把音乐压低（等同"丢掉焦点"），直到会话结束/断线才还原
        host?.setVoiceDuck(true)
        stateMachine.reset()
        dispatch(XiaozhiStateMachine.Event.Start)
        resolveEndpointAndConnect()
    }

    private fun interruptInternal() {
        if (!stateMachine.isActive) return
        if (stateMachine.state == XiaozhiStateMachine.State.CONNECTING ||
            stateMachine.state == XiaozhiStateMachine.State.HANDSHAKING
        ) {
            log("还在连接中，忽略这次打断")
            return
        }
        log("打断，重新聆听")
        turnLead.setLength(0)
        dispatch(XiaozhiStateMachine.Event.Interrupt)
    }

    private fun stopInternal(reason: String) {
        if (!sessionActive && stateMachine.state == XiaozhiStateMachine.State.IDLE) return
        log("结束会话：$reason")
        dispatch(XiaozhiStateMachine.Event.Stop(reason))
        finishSession()
    }

    private fun failSession(message: String) {
        log("失败：$message")
        status(message)
        dispatch(XiaozhiStateMachine.Event.FatalError(message))
        finishSession()
    }

    private fun finishSession() {
        sessionActive = false
        cancelTimers()
        stopCaptureInternal()
        player?.stop()
        player = null
        closeTransport()
        XiaozhiCaption.clear()
        host?.setVoiceDuck(false)
        host?.releaseMicrophone()
        sendAudio = false
    }

    // ---------------------------------------------------------------- 连接 / OTA

    private fun resolveEndpointAndConnect() {
        val ctx = appContext ?: return
        if (config.token.isNotBlank()) {
            connectWebSocket(config.wsUrl, config.token)
            return
        }
        setCaption("连接中…")
        val executor = otaExecutor
        if (executor == null) {
            failSession("内部错误：OTA 线程未就绪")
            return
        }
        val otaUrl = config.otaUrl
        val mac = XiaozhiDeviceInfo.deviceMac(ctx)
        val clientId = XiaozhiDeviceInfo.clientId(ctx)
        val version = hostVersion()
        executor.execute {
            val client = XiaozhiOtaClient(otaUrl, mac, clientId) { log(it) }
            val body = XiaozhiDeviceInfo.otaRequestBody(ctx, version, "android")
            when (val result = client.fetch(body)) {
                is XiaozhiOtaClient.Result.Ok -> post { connectWebSocket(result.wsUrl, result.token) }
                is XiaozhiOtaClient.Result.NeedActivation -> post { handleNeedActivation(client, result) }
                is XiaozhiOtaClient.Result.Failed -> post { failSession("OTA 失败：${result.message}") }
            }
        }
    }

    private fun handleNeedActivation(
        client: XiaozhiOtaClient,
        result: XiaozhiOtaClient.Result.NeedActivation
    ) {
        if (!sessionActive) return
        if (result.code.isBlank()) {
            failSession("设备未绑定，请到 xiaozhi.me 添加设备（MAC ${config.deviceMac}）")
            return
        }
        setCaption("激活码 ${result.code}")
        activationLabel = "激活码 ${result.code}（点击重查）"
        status("小智激活码 ${result.code}，请到 xiaozhi.me 添加设备（设备 ${config.deviceMac}）")
        log("等待绑定，激活码 ${result.code}，设备 ${config.deviceMac}")
        val ctx = appContext ?: return
        // 绑定生效后要再问一次 OTA 才拿得到连接配置
        val otaBody = XiaozhiDeviceInfo.otaRequestBody(ctx, hostVersion(), "android")
        val executor = otaExecutor ?: return
        executor.execute {
            val polled = client.pollActivate(
                result.code,
                otaBody,
                OTA_POLL_MAX_ATTEMPTS,
                OTA_POLL_INTERVAL_MS
            ) { freshCode, _ ->
                // 服务端换新码：屏上字幕和设置里的按钮都要跟着变
                post {
                    setCaption("激活码 $freshCode")
                    activationLabel = "激活码 $freshCode（点击重查）"
                }
            }
            post {
                when (polled) {
                    is XiaozhiOtaClient.Result.Ok -> {
                        activationLabel = "已激活"
                        connectWebSocket(polled.wsUrl, polled.token)
                    }
                    is XiaozhiOtaClient.Result.NeedActivation ->
                        failSession("等待绑定超时（${polled.message}），请重新唤醒")
                    is XiaozhiOtaClient.Result.Failed -> failSession("激活失败：${polled.message}")
                }
            }
        }
    }

    private fun connectWebSocket(wsUrl: String, token: String) {
        if (!sessionActive) return
        val ctx = appContext ?: return
        lastWsUrl = wsUrl
        lastToken = token
        closeTransport()
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "Protocol-Version" to XiaozhiProtocol.PROTOCOL_VERSION.toString(),
            "Device-Id" to XiaozhiDeviceInfo.deviceMac(ctx),
            "Client-Id" to XiaozhiDeviceInfo.clientId(ctx)
        )
        log("连接 $wsUrl")
        val transport = JavaWebSocketTransport(wsUrl) { log(it) }
        this.transport = transport
        // 建 TCP + TLS 握手是阻塞的（老机型上可能几秒），放到 IO 线程，别占住控制线程
        val executor = otaExecutor
        if (executor == null) {
            scheduleReconnect("内部错误：连接线程未就绪")
            return
        }
        executor.execute {
            try {
                transport.connect(headers, transportListener, HELLO_TIMEOUT_MS.toInt())
            } catch (e: Exception) {
                post { scheduleReconnect("连接失败：${e.message}") }
            }
        }
    }

    private fun closeTransport() {
        val current = transport
        transport = null
        try {
            current?.close("bye")
        } catch (ignored: Exception) {
        }
    }

    private val transportListener = object : XiaozhiTransport.Listener {
        override fun onOpen() = post { onTransportOpen() }
        override fun onText(text: String) = post { onServerText(text) }
        override fun onBinary(data: ByteArray) = post { player?.enqueue(data) }
        override fun onClosed(reason: String, code: Int, remote: Boolean) =
            post { onTransportClosed(reason, code, remote) }
        override fun onFailure(error: Throwable) = post {
            log("连接异常：${error.message}")
        }
    }

    private fun onTransportOpen() {
        if (!sessionActive) return
        dispatch(XiaozhiStateMachine.Event.TransportOpen)
        val transport = transport ?: return
        transport.sendText(XiaozhiProtocol.helloJson())
        scheduleHelloTimeout()
    }

    private fun scheduleHelloTimeout() {
        cancelHelloTimeout()
        val runnable = Runnable {
            if (!sessionActive) return@Runnable
            if (stateMachine.state == XiaozhiStateMachine.State.HANDSHAKING) {
                dispatch(XiaozhiStateMachine.Event.HelloTimeout)
                scheduleReconnect("握手超时")
            }
        }
        helloTimeout = runnable
        handler?.postDelayed(runnable, HELLO_TIMEOUT_MS)
    }

    private fun cancelHelloTimeout() {
        helloTimeout?.let { handler?.removeCallbacks(it) }
        helloTimeout = null
    }

    private fun onTransportClosed(reason: String, code: Int, remote: Boolean) {
        if (!sessionActive) return
        // remote=true 才是服务端关的；我们自己 close 时 remote=false，不用额外标志位
        // 服务端用 1000/1001 正常关闭 = 用户让 AI"退下"，连上就是赖着不走
        if (remote && (code == 1000 || code == 1001)) {
            endByServer("已退出对话")
            return
        }
        scheduleReconnect("连接断开：$reason")
    }

    /** 服务端主动结束会话：收尾并把音乐/麦克风让回去，不重连 */
    private fun endByServer(message: String) {
        log("服务端结束会话（$message）")
        stopInternal("服务端关闭")
        status(message)
    }

    private fun scheduleReconnect(reason: String) {
        log(reason)
        if (!sessionActive) return
        if (reconnectCount >= MAX_RECONNECT) {
            failSession("$reason（重试 $MAX_RECONNECT 次仍失败）")
            return
        }
        reconnectCount++
        val delay = (1000L shl (reconnectCount - 1)).coerceAtMost(16_000L)
        setCaption("重连中(${reconnectCount})…")
        dispatch(XiaozhiStateMachine.Event.TransportClosed(reason))
        closeTransport()
        handler?.postDelayed({
            if (!sessionActive) return@postDelayed
            stateMachine.reset()
            dispatch(XiaozhiStateMachine.Event.Start)
            if (lastToken.isNotBlank()) {
                connectWebSocket(lastWsUrl, lastToken)
            } else {
                resolveEndpointAndConnect()
            }
        }, delay)
    }

    // ---------------------------------------------------------------- 协议消息

    private fun onServerText(raw: String) {
        val event = XiaozhiProtocol.parse(raw) ?: run {
            log("无法解析的服务端消息：${raw.take(120)}")
            return
        }
        when (event) {
            is XiaozhiProtocol.ServerEvent.Hello -> {
                log("握手完成 session_id=${event.sessionId} 采样率=${event.sampleRate}")
                cancelHelloTimeout()
                createPlayer(event.sampleRate)
                dispatch(XiaozhiStateMachine.Event.HelloReceived(event.sampleRate))
            }

            is XiaozhiProtocol.ServerEvent.Stt -> {
                // 新的一轮，表情前缀清空
                turnLead.setLength(0)
                dispatch(XiaozhiStateMachine.Event.SttReceived(event.text))
                if (event.text.isNotBlank()) setCaption("你：${event.text}")
            }

            is XiaozhiProtocol.ServerEvent.Tts -> onTts(event.state, event.text)

            is XiaozhiProtocol.ServerEvent.Llm -> {
                // 服务端会先单独发一行表情，再发正文；先记下来，等正文来了拼在一起。
                // 只认很短的那种（一个表情几个字符），避免把整句正文当成前缀重复一遍
                val lead = event.text.trim()
                if (lead.isNotEmpty() && lead.length <= MAX_LEAD_LENGTH && turnLead.isEmpty()) {
                    turnLead.append(lead)
                    setCaption(lead)
                }
            }

            is XiaozhiProtocol.ServerEvent.Alert -> {
                val message = event.message.ifBlank { event.status }
                log("服务端提示：$message")
                status(message)
            }

            is XiaozhiProtocol.ServerEvent.Goodbye -> {
                // 用户说了"退下"之类，服务端主动收场，不再自动续听、不重连
                endByServer("已退出对话")
            }

            is XiaozhiProtocol.ServerEvent.Unknown -> {
                log("忽略消息类型 ${event.type}")
            }
        }
    }

    private fun onTts(state: String, text: String) {
        when (state) {
            "start" -> {
                dispatch(XiaozhiStateMachine.Event.TtsStart)
            }
            "sentence_start" -> {
                // 回复是一段段来的，字幕只显示当前这一段，不累积；
                // 开头那行表情固定挂在前面
                if (text.isNotBlank()) {
                    setCaption(if (turnLead.isEmpty()) text else turnLead.toString() + text)
                }
                dispatch(XiaozhiStateMachine.Event.TtsSentence(text))
            }
            "stop" -> {
                dispatch(XiaozhiStateMachine.Event.TtsStop)
                player?.markEndOfTurn()
            }
            "sentence_end" -> {
                // 不需要特殊处理，等 tts stop 再排空
            }
            else -> log("未知 tts 状态 $state")
        }
    }

    private fun createPlayer(sampleRate: Int) {
        player?.stop()
        val created = XiaozhiAudioPlayer(sampleRate) { log(it) }
        created.onDrained = { post { dispatch(XiaozhiStateMachine.Event.PlaybackDrained) } }
        created.start()
        player = created
    }

    // ---------------------------------------------------------------- 状态机副作用

    private fun dispatch(event: XiaozhiStateMachine.Event) {
        val transition = stateMachine.accept(event) ?: return
        log("状态 ${transition.from} -> ${transition.to}")
        applyEffects(transition.effects)
        updateCaptionForState()
    }

    private fun applyEffects(effects: List<XiaozhiStateMachine.SideEffect>) {
        for (effect in effects) {
            when (effect) {
                is XiaozhiStateMachine.SideEffect.StartCapture -> startCapture()
                is XiaozhiStateMachine.SideEffect.StopCapture -> stopCaptureInternal()
                is XiaozhiStateMachine.SideEffect.SendListenStart ->
                    transport?.sendText(XiaozhiProtocol.listenStartJson(config.mode))
                is XiaozhiStateMachine.SideEffect.SendListenStop ->
                    transport?.sendText(XiaozhiProtocol.listenStopJson())
                is XiaozhiStateMachine.SideEffect.SendAbort ->
                    transport?.sendText(XiaozhiProtocol.abortJson())
                is XiaozhiStateMachine.SideEffect.FlushPlayback -> player?.flushAndPause()
                is XiaozhiStateMachine.SideEffect.CancelSilence -> cancelSilenceTimer()
                is XiaozhiStateMachine.SideEffect.ScheduleSilence ->
                    scheduleSilenceTimer(effect.timeoutMs)
                is XiaozhiStateMachine.SideEffect.Log -> log(effect.message)
            }
        }
    }

    private fun updateCaptionForState() {
        when (stateMachine.state) {
            XiaozhiStateMachine.State.CONNECTING,
            XiaozhiStateMachine.State.HANDSHAKING -> setCaption("连接中…")
            XiaozhiStateMachine.State.LISTENING -> setCaption("聆听中…")
            XiaozhiStateMachine.State.THINKING -> setCaption("思考中…")
            XiaozhiStateMachine.State.SPEAKING -> Unit   // 由 sentence_start 负责
            XiaozhiStateMachine.State.IDLE,
            XiaozhiStateMachine.State.ERROR -> Unit
        }
    }

    // ---------------------------------------------------------------- 采集

    private fun startCapture() {
        if (capture != null) return
        host?.ensureForegroundMicType()
        host?.claimMicrophone()
        val created = XiaozhiAudioCapture(
            onPacket = { packet -> if (sendAudio) transport?.sendBinary(packet) },
            onLevel = { rms -> onVoiceLevel(rms) },
            onError = { message -> post { failSession(message) } },
            log = { message -> log(message) }
        )
        if (created.start()) {
            capture = created
            sendAudio = true
            log("开始录音")
        } else {
            capture = null
            sendAudio = false
            failSession("麦克风启动失败")
        }
    }

    private fun stopCaptureInternal() {
        sendAudio = false
        val current = capture
        capture = null
        if (current != null) {
            current.stop()
            log("停止录音")
        }
    }

    private fun onVoiceLevel(rms: Double) {
        if (rms < VOICE_RMS_THRESHOLD) return
        val now = System.currentTimeMillis()
        if (now - lastLevelResetAt < LEVEL_RESET_INTERVAL_MS) return
        lastLevelResetAt = now
        post { resetSilenceTimerIfListening() }
    }

    // ---------------------------------------------------------------- 计时器

    private fun scheduleSilenceTimer(timeoutMs: Long) {
        cancelSilenceTimer()
        val runnable = Runnable {
            if (stateMachine.state == XiaozhiStateMachine.State.LISTENING) {
                dispatch(XiaozhiStateMachine.Event.SilenceTimeout)
                if (stateMachine.state == XiaozhiStateMachine.State.IDLE) {
                    finishSession()
                }
            }
        }
        silenceTimeout = runnable
        handler?.postDelayed(runnable, timeoutMs)
    }

    private fun resetSilenceTimerIfListening() {
        if (stateMachine.state != XiaozhiStateMachine.State.LISTENING) return
        scheduleSilenceTimer(config.silenceTimeoutMs)
    }

    private fun cancelSilenceTimer() {
        silenceTimeout?.let { handler?.removeCallbacks(it) }
        silenceTimeout = null
    }

    private fun cancelTimers() {
        cancelHelloTimeout()
        cancelSilenceTimer()
    }

    // ---------------------------------------------------------------- 杂项

    private fun post(block: () -> Unit) {
        val target = handler
        if (target == null) return
        if (Looper.myLooper() == target.looper) {
            block()
        } else {
            target.post(block)
        }
    }

    private fun log(message: String) {
        mainHandler.post { host?.log("[小智] $message") }
    }

    private fun status(message: String) {
        mainHandler.post { host?.showStatus(message) }
    }

    private fun setCaption(text: String) {
        XiaozhiCaption.set(text)
    }

    private fun hostVersion(): String = host?.appVersion() ?: "1.0"

    private fun hasRecordPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }
}
