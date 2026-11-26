package com.zyyme.workdayalarmclock.xiaozhi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.zyyme.workdayalarmclock.MeService
import com.zyyme.workdayalarmclock.MeSettings
import com.zyyme.workdayalarmclock.MeYsLed
import com.zyyme.workdayalarmclock.xiaozhi.wake.VoiceWakeBridge
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

    // ---- 说话打断：播报期间靠音量突增判「有人在说话」 ----
    /** 判定窗口（帧），每帧 = 一次 read = 20ms */
    private const val INTERRUPT_WINDOW_FRAMES = 12
    /** 窗口内至少这么多帧超阈才判命中 */
    private const val INTERRUPT_HIT_FRAMES = 9
    /**
     * 绝对阈值 自适应底噪烂完了
     */
    private const val INTERRUPT_MIN_RMS = 300.0
    /** 起播后这段时间不判：TTS 开头有淡入/爆音 */
    private const val INTERRUPT_GUARD_MS = 300L
    /**
     * 播放器 flush/pause 之后这段时间不判。
     * 那一刻 AEC 的回声参考突然消失，自适应滤波器要 100~300ms 重新收敛，
     * 期间残留会短暂冲到 80~200 —— 实测误打断全部落在这一段里。
     */
    private const val INTERRUPT_FLUSH_GUARD_MS = 400L
    /** 打断后冷却，免得同一句话连环触发 */
    private const val INTERRUPT_COOLDOWN_MS = 1500L
    /** 临时诊断：播报期间每秒把 rms/底噪/阈值打到日志，调参用，调好记得关掉 */
    private const val INTERRUPT_DEBUG = true

    private var appContext: Context? = null
    private var host: XiaozhiHost? = null
    /** 上一次打过日志的视觉地址，避免每条 MCP 消息都刷一遍 */
    private var lastVisionUrl = ""

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
    /** 待机监听唤醒词专用的采集实例，只在「语音唤醒」开关打开且没有会话时存在 */
    private var idleCapture: XiaozhiAudioCapture? = null
    /** 引擎当前用的「阈值|唤醒词」，变了就要重建引擎 */
    private var idleSignature: String? = null
    /** 待机监听这次是不是自己切了通话音频模式（降噪开时） */
    private var idleVoiceMode = false
    /** 语音唤醒是否启用（模块开 + 开关开）。会话期间录音要不要留着也看它 */
    @Volatile
    private var wakeEnabled = false
    private var player: XiaozhiAudioPlayer? = null

    // ---- 说话打断 ----
    /** 开关快照，采集线程每 20ms 读一次，必须是 volatile */
    @Volatile
    private var talkInterruptOn = false
    /** 状态机快照：采集线程不能直接读 stateMachine（那是控制线程的东西） */
    @Volatile
    private var stateSnapshot = XiaozhiStateMachine.State.IDLE
    /** 打断前那几帧预滚，采集线程填、控制线程取 */
    @Volatile
    private var pendingInterruptAudio: ShortArray? = null
    /** 最近 12 帧的 RMS 环形记录，只在采集线程里动 */
    private val interruptRms = DoubleArray(INTERRUPT_WINDOW_FRAMES)
    private var interruptRmsIdx = 0
    private var interruptRmsCount = 0
    /** 本轮播报内见过的最大 rms（诊断用，看插话到底能到多少） */
    private var speakingPeak = 0.0
    /** 采集线程用它发现「这是新一轮播报」，从而清掉本地判定状态 */
    private var detectorForSinceAt = 0L
    /** 进入播报的时刻，由控制线程写、采集线程读 */
    @Volatile
    private var speakingSinceAt = 0L
    /** 最近一次播放器 flush/pause 的时刻（AEC 参考信号突变点），控制线程写、采集线程读 */
    @Volatile
    private var lastPlaybackFlushAt = 0L
    private var lastInterruptAt = 0L
    private var lastInterruptDebugAt = 0L

    private var lastWsUrl = ""
    private var lastToken = ""
    /**
     * 缓存这份连接配置时用的设备 MAC。
     * 重新生成设备ID 之后就是另一台设备了，旧 token 一律不作数。
     */
    private var lastEndpointMac = ""
    /**
     * 这次会话是直接拿缓存的地址/token 连的。
     * 连不上就说明缓存过期（服务端换了 token），要把缓存丢掉重新问 OTA。
     */
    private var reusedCachedEndpoint = false
    private var reconnectCount = 0
    private var helloTimeout: Runnable? = null
    private var silenceTimeout: Runnable? = null
    /** 本轮 tts 之前单独发来的那行表情，挂在每段字幕前面 */
    private val turnLead = StringBuilder()

    @Volatile
    private var sendAudio = false

    /** 本次会话是否由我们暂停了音乐，只有 true 才在结束时恢复 */
    private var mediaPausedByUs = false

    /**
     * 录音期间被我们改掉的原始音频模式，`MODE_INVALID` 表示当前不归我们管。
     * 只有它 != INVALID 时才会去还原，避免踩到别人（比如系统通话）已经设好的模式。
     */
    private var previousAudioMode = AudioManager.MODE_INVALID

    /** 蓝牙 SCO 是我们请求的就负责停掉；只有降噪开（走通话链路）时才会为 true */
    private var bluetoothScoOwned = false

    /**
     * 本轮说完就收场、不再续听。
     * 播放控制类命令（点歌/切歌/恢复播放）执行完，这件事就办完了，
     * 没必要再把麦克风打开等着听下一句。
     */
    private var stopAfterTurn = false

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
            stopIdleListening(releaseEngine = true)
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
        reusedCachedEndpoint = false
        invalidateCachedEndpoint("模块已关闭")
        XiaozhiCaption.clear()
    }

    fun reloadConfig() {
        val ctx = appContext ?: return
        val previous = config
        val cfg = XiaozhiConfig(
            enabled = MeSettings.isEnabled(ctx, MeSettings.KEY_XIAOZHI),
            otaUrl = XiaozhiConfig.normalizeOtaUrl(MeSettings.getXiaozhiOtaUrl(ctx)),
            wsUrl = XiaozhiConfig.normalizeWsUrl(MeSettings.getXiaozhiWsUrl(ctx)),
            token = MeSettings.getXiaozhiToken(ctx),
            deviceMac = XiaozhiDeviceInfo.deviceMac(ctx),
            clientId = XiaozhiDeviceInfo.clientId(ctx),
            mode = XiaozhiConfig.normalizeMode(MeSettings.getXiaozhiMode(ctx)),
            autoContinue = MeSettings.isXiaozhiAutoContinue(ctx),
            silenceTimeoutMs = MeSettings.getXiaozhiSilenceTimeout(ctx) * 1000L,
            pauseMedia = MeSettings.isXiaozhiPauseMedia(ctx),
            micDenoise = MeSettings.isXiaozhiMicDenoise(ctx),
            allowPhoto = MeSettings.isXiaozhiAllowPhoto(ctx),
            talkInterrupt = MeSettings.isXiaozhiTalkInterrupt(ctx)
        )
        config = cfg
        talkInterruptOn = cfg.talkInterrupt
        wakeEnabled = cfg.enabled && MeSettings.isXiaozhiVoiceWake(ctx)
        // 地址/token/设备ID 变过，缓存的连接配置就不认了（只改自动续听之类不动它）
        if (previous.otaUrl != cfg.otaUrl || previous.wsUrl != cfg.wsUrl ||
            previous.token != cfg.token || previous.deviceMac != cfg.deviceMac
        ) {
            invalidateCachedEndpoint("配置变更")
        }
        stateMachine.autoContinue = cfg.autoContinue
        stateMachine.silenceTimeoutMs = cfg.silenceTimeoutMs
        if (!cfg.enabled && sessionActive) stop("模块已关闭")
        // 开关或唤醒词改过都要重新同步待机监听
        syncIdleListening()
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
    var activationLabel: String = "查询激活状态"
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
            val client = XiaozhiOtaClient(otaUrl, mac, clientId, version) { log(it) }
            val body = XiaozhiDeviceInfo.otaRequestBody(ctx, version)
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
                        activationLabel = "激活码 ${result.code}（到xiaozhi.me绑定）"
                        status("激活码 ${result.code}，请到 xiaozhi.me 添加设备（设备 ${mac}）")
                        val polled = client.pollActivate(
                            result.code,
                            body,
                            OTA_POLL_MAX_ATTEMPTS,
                            OTA_POLL_INTERVAL_MS
                        ) { freshCode, _ ->
                            post { activationLabel = "激活码 $freshCode（到xiaozhi.me绑定）" }
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
        reusedCachedEndpoint = false
        turnLead.setLength(0)
        stopAfterTurn = false
        // 先停音乐再压低音量：外放的音乐会糊住麦克风，识别会缺字
        mediaPausedByUs = config.pauseMedia && host?.pauseMediaForVoice() == true
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
        // 用户主动打断就是想接着说，别再收场
        stopAfterTurn = false
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
        stopAfterTurn = false
        cancelTimers()
        // 会话彻底结束：不管「说话打断」开没开，录音一定要真的关掉
        stopCaptureInternal(force = true)
        pendingInterruptAudio = null
        player?.stop()
        player = null
        closeTransport()
        XiaozhiCaption.clear()
        // 顺序：先把音量还原，再恢复播放，避免恢复后第一下还是 duck 音量
        host?.setVoiceDuck(false)
        if (mediaPausedByUs) {
            mediaPausedByUs = false
            host?.resumeMediaAfterVoice()
        }
        host?.releaseMicrophone()
        sendAudio = false
        // 会话结束：语音唤醒开着就回到待机监听
        syncIdleListening()
    }

    // ---------------------------------------------------------------- 连接 / OTA

    private fun resolveEndpointAndConnect() {
        val ctx = appContext ?: return
        if (config.token.isNotBlank()) {
            connectWebSocket(config.wsUrl, config.token)
            return
        }
        // 官方固件把 OTA 下发的地址和 token 存进 NVS，之后每次唤醒直接用，不再问 OTA。
        // 这里等价地在进程内缓存一份：正常唤醒零 HTTP，只有连不上/配置变了才重新获取。
        if (hasCachedEndpoint(ctx)) {
            log("复用上次的 OTA 配置，跳过 OTA")
            reusedCachedEndpoint = true
            connectWebSocket(lastWsUrl, lastToken)
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
            val client = XiaozhiOtaClient(otaUrl, mac, clientId, version) { log(it) }
            val body = XiaozhiDeviceInfo.otaRequestBody(ctx, version)
            when (val result = client.fetch(body)) {
                is XiaozhiOtaClient.Result.Ok -> post { connectWebSocket(result.wsUrl, result.token) }
                is XiaozhiOtaClient.Result.NeedActivation -> post { handleNeedActivation(client, result) }
                is XiaozhiOtaClient.Result.Failed -> post { failSession("OTA 失败：${result.message}") }
            }
        }
    }

    /** 缓存的连接配置还能不能用：地址/token 都在，且还是同一台设备（MAC 没换） */
    private fun hasCachedEndpoint(context: Context): Boolean =
        lastWsUrl.isNotBlank() && lastToken.isNotBlank() &&
                lastEndpointMac == XiaozhiDeviceInfo.deviceMac(context)

    private fun invalidateCachedEndpoint(reason: String) {
        if (lastWsUrl.isEmpty() && lastToken.isEmpty() && lastEndpointMac.isEmpty()) return
        log("丢弃缓存的小智连接配置：$reason")
        lastWsUrl = ""
        lastToken = ""
        lastEndpointMac = ""
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
        val otaBody = XiaozhiDeviceInfo.otaRequestBody(ctx, hostVersion())
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
        lastEndpointMac = XiaozhiDeviceInfo.deviceMac(ctx)
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
        // 声明支持 MCP，服务端才会来问 tools/list（不声明就完全不会发 initialize）
        // mcp_20260728：告诉网关我们支持新版，它就不用先发失败一次的 server/discover 探测
        transport.sendText(
            XiaozhiProtocol.helloJson(mapOf("mcp" to true, "mcp_20260728" to true))
        )
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
        // 拿缓存配置压根没连上：多半是服务端把 token 换了，丢掉缓存，这次重连就重新问 OTA
        if (reusedCachedEndpoint) {
            reusedCachedEndpoint = false
            invalidateCachedEndpoint("缓存配置连不上")
        }
        if (reconnectCount >= MAX_RECONNECT) {
            // 重试用尽多半是配置真过期了，下次唤醒从头来（重新 OTA）
            invalidateCachedEndpoint("重试次数用尽")
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
                // 缓存配置连通了，这次会话后面再断线重连也不用再问 OTA
                reusedCachedEndpoint = false
                cancelHelloTimeout()
                createPlayer(event.sampleRate)
                dispatch(XiaozhiStateMachine.Event.HelloReceived(event.sampleRate))
            }

            is XiaozhiProtocol.ServerEvent.Stt -> {
                // 新的一轮，表情前缀清空
                turnLead.setLength(0)
                dispatch(XiaozhiStateMachine.Event.SttReceived(event.text))
                if (event.text.isNotBlank()) {
                    setCaption("你：${event.text}")
                    log("你：${event.text}")
                }
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

            is XiaozhiProtocol.ServerEvent.Mcp -> onMcp(event)
        }
    }

    /**
     * 设备侧 MCP：服务端来问工具清单 / 调工具。
     * 都是控制线程上的同步小活（拼 JSON + 往 Go 管道写一行），不用另开线程。
     */
    private fun onMcp(request: XiaozhiProtocol.ServerEvent.Mcp) {
        // 握手类消息按条记，工具调用在下面单独记命令原文
        if (request.method != "notifications/initialized" && request.method != "tools/call") {
            log("MCP ${request.method}")
        }
        val reply = XiaozhiMcp.handle(
            request = request,
            serverVersion = hostVersion(),
            allowPhoto = config.allowPhoto,
            runCommand = { command ->
                log("执行命令 $command")
                if (XiaozhiPlayerCommands.endsConversation(command)) stopAfterTurn = true
                host?.runPlayerCommand(command) ?: "设备未就绪"
            },
            deviceStatus = { host?.deviceStatusJson() ?: "设备未就绪" },
            capturePhoto = { question, cameraIndex, visionUrl, visionToken ->
                host?.capturePhoto(question, cameraIndex, visionUrl, visionToken)
            }
        )
        // 网关只在设备声明了 me.xiaozhi/vision 后才下发地址，打出来方便排查拍照为什么不可用
        if (request.method == "initialize" || request.method == "tools/call") {
            val vision = XiaozhiMcp.visionUrl()
            if (vision != lastVisionUrl) {
                lastVisionUrl = vision
                log("MCP 视觉地址：${vision.ifEmpty { "未下发" }}")
            }
        }
        if (reply != null) transport?.sendText(reply)
    }

    /** 一轮 TTS 播完：平时自动续听，执行过播放控制命令的话这轮就收场 */
    private fun onPlaybackDrained() {
        if (stopAfterTurn) {
            stopAfterTurn = false
            // 走 Stop 而不是 PlaybackDrained：状态机直接进 IDLE，一路 StartCapture 都不会被触发
            stopInternal("播放命令已执行")
            return
        }
        dispatch(XiaozhiStateMachine.Event.PlaybackDrained)
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
                    val line = if (turnLead.isEmpty()) text else turnLead.toString() + text
                    setCaption(line)
                    // 字幕一条条刷，日志也一条条记，方便事后翻对话
                    log("小智：$line")
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
        created.onDrained = { post { onPlaybackDrained() } }
        created.start()
        player = created
    }

    // ---------------------------------------------------------------- 状态机副作用

    private fun dispatch(event: XiaozhiStateMachine.Event) {
        val transition = stateMachine.accept(event) ?: return
        log("状态 ${transition.from} -> ${transition.to}")
        applyEffects(transition.effects)
        updateCaptionForState()
        onStateChanged(transition.to)
    }

    /**
     * 状态迁移完再更新快照：副作用里的 stopCaptureInternal 要按**新**状态决定
     * 是「摘编码管线」还是「真的停录音」。
     *
     * 这里只负责把「进入播报的时刻」发布给采集线程；判定器自己的环形缓冲和底噪
     * 都归采集线程所有，它发现 sinceAt 变了会自己清零，避免跨线程写。
     */
    private fun onStateChanged(state: XiaozhiStateMachine.State) {
        if (state == XiaozhiStateMachine.State.SPEAKING) {
            if (stateSnapshot != XiaozhiStateMachine.State.SPEAKING) {
                speakingSinceAt = android.os.SystemClock.elapsedRealtime()
            }
        } else {
            speakingSinceAt = 0L
        }
        stateSnapshot = state
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
                is XiaozhiStateMachine.SideEffect.FlushPlayback -> {
                    lastPlaybackFlushAt = android.os.SystemClock.elapsedRealtime()
                    player?.flushAndPause()
                }
                is XiaozhiStateMachine.SideEffect.CancelSilence -> cancelSilenceTimer()
                is XiaozhiStateMachine.SideEffect.ScheduleSilence ->
                    scheduleSilenceTimer(effect.timeoutMs)
                is XiaozhiStateMachine.SideEffect.Log -> log(effect.message)
            }
        }
    }

    /**
     * 状态更新  顺便更新一说宝宝1s的灯板
     */
    private fun updateCaptionForState() {
        when (stateMachine.state) {
            XiaozhiStateMachine.State.CONNECTING,
            XiaozhiStateMachine.State.HANDSHAKING -> {
                setCaption("连接中…")
                MeService.me?.ysSetLedsValue(MeYsLed.CONNECT_1, MeYsLed.CONNECT_3)
            }
            XiaozhiStateMachine.State.LISTENING -> {
                setCaption("聆听中…")
                MeService.me?.ysSetLedsValue(MeYsLed.TALKING_1, MeYsLed.TALKING_6)
            }
            XiaozhiStateMachine.State.THINKING -> setCaption("思考中…")
            XiaozhiStateMachine.State.SPEAKING -> {
                // 文字更新由 sentence_start 负责
//                MeService.me?.ysSetLedsValue(MeYsLed.VIOLENCE_1, MeYsLed.VIOLENCE_4)
                MeService.me?.ysSetLedsValue(MeYsLed.HAPPY_1, MeYsLed.HAPPY_2)
            }
            XiaozhiStateMachine.State.IDLE,
            XiaozhiStateMachine.State.ERROR -> {
                MeService.me?.ysSetLedsValue(MeYsLed.EMPTY)
            }
        }
    }

    // ---------------------------------------------------------------- 采集

    /**
     * 待机常驻录音只在「语音唤醒」开关打开时才跑；模块关掉或者正在会话时一律不跑。
     * 关掉时连同插件的引擎一起释放，不占内存。
     *
     * reloadConfig 是主线程调的，开麦克风不能在那上面做，统一丢给控制面。
     */
    private fun syncIdleListening() {
        val h = handler
        if (h == null) {
            applyIdleListening()
            return
        }
        h.post { applyIdleListening() }
    }

    private fun applyIdleListening() {
        val ctx = appContext
        if (ctx == null) {
            stopIdleListening(releaseEngine = true)
            return
        }
        val wanted = config.enabled && MeSettings.isXiaozhiVoiceWake(ctx)
        if (wanted && !sessionActive) {
            startIdleListening()
        } else {
            stopIdleListening(releaseEngine = !wanted)
        }
    }

    private fun startIdleListening() {
        val ctx = appContext ?: return
        if (!hasRecordPermission(ctx)) {
            log("语音唤醒：没有麦克风权限")
            // 待机起不来就没有接手的人了，会话留下的通话链路不能一直挂着
            restoreAudioMode()
            return
        }
        if (!VoiceWakeBridge.isPluginInstalled(ctx)) {
            log("语音唤醒：插件未安装")
            restoreAudioMode()
            return
        }
        val keywords = MeSettings.getXiaozhiWakeKeywords(ctx)
        val threshold = MeSettings.getXiaozhiWakeThreshold(ctx)
        val signature = "$threshold|$keywords"
        if (idleCapture != null) {
            if (signature == idleSignature) return
            // 唤醒词或灵敏度改过：引擎要按新参数重建
            stopIdleListening(releaseEngine = true)
        }
        val started = VoiceWakeBridge.start(
            ctx,
            keywords,
            threshold,
            { keyword -> onWakeDetected(keyword) },
            { message -> log("唤醒插件：$message") }
        )
        if (!started) {
            log("语音唤醒：引擎加载失败 ${VoiceWakeBridge.errorMessage()}")
            restoreAudioMode()
            return
        }
        host?.ensureForegroundMicType()
        // 待机降噪开关：开了走 VOICE_COMMUNICATION（AEC 能把外放音乐当回声消掉），
        // 这个音源必须先切到通话模式才生效
        val idleDenoise = MeSettings.isXiaozhiWakeDenoise(ctx)
        if (idleDenoise) enterVoiceAudioMode(useBluetoothMic = true)
        // 只要现在确实处在通话模式，退出时就必须负责还原——哪怕进来之前就已经是了。
        // 不这么做的话：上一次没还原干净 → 这次 enterVoiceAudioMode 看到已是通话模式就
        // 直接 return、previousAudioMode 保持 INVALID → 再也没有机会还原，
        // 系统级 MODE_IN_COMMUNICATION 会一直卡着，媒体就放不出声。
        if (idleDenoise && audioManager()?.mode == AudioManager.MODE_IN_COMMUNICATION) {
            if (previousAudioMode == AudioManager.MODE_INVALID) {
                previousAudioMode = AudioManager.MODE_NORMAL
            }
            idleVoiceMode = true
        } else {
            idleVoiceMode = false
        }
        val created = XiaozhiAudioCapture(
            denoise = idleDenoise,
            onPacket = {},
            onLevel = {},
            onError = { message ->
                log("语音唤醒：录音出错 $message")
                post { stopIdleListening(releaseEngine = false) }
            },
            log = { message -> log(message) },
            onPcm = { samples, length -> VoiceWakeBridge.acceptPcm(samples, length) }
        )
        if (created.start(encode = false)) {
            idleCapture = created
            idleSignature = signature
            log("语音唤醒：开始待机监听")
        } else {
            log("语音唤醒：麦克风打不开")
            if (idleVoiceMode) {
                idleVoiceMode = false
                restoreAudioMode()
            }
            VoiceWakeBridge.stop()
        }
    }

    private fun stopIdleListening(releaseEngine: Boolean) {
        val current = idleCapture
        idleCapture = null
        if (current != null) {
            current.stop()
            log("语音唤醒：停止待机监听")
        }
        // 不管待机实例还在不在，只要这次是我们切了通话模式就必须还原。
        // 早先写在 if (current != null) 里面，实例提前变 null 时模式就永远卡住了。
        if (idleVoiceMode) {
            idleVoiceMode = false
            // 会话马上要接手（它的降噪也开着）就先别还：还了 SCO 得重新协商，
            // 200~800ms 里上行还是手机麦，唤醒后第一句开头会缺一截。
            // 必须确认链路真的还在手上，否则这句日志会和后面的重新进入自相矛盾
            if (sessionActive && config.micDenoise &&
                previousAudioMode != AudioManager.MODE_INVALID
            ) {
                log("通话链路交给会话")
            } else {
                restoreAudioMode()
            }
        }
        if (releaseEngine) {
            idleSignature = null
            VoiceWakeBridge.release()
        } else {
            VoiceWakeBridge.stop()
        }
    }

    /** 引擎线程回调，切回控制线程再动状态 */
    private fun onWakeDetected(keyword: String) {
        log("语音唤醒：$keyword")
        post {
            XiaozhiChime.play(appContext) { message -> log(message) }
            if (sessionActive) {
                // 会话中（含播报中）等同双击打断：丢掉还没播的、重新聆听
                dispatch(XiaozhiStateMachine.Event.Interrupt)
            } else {
                // 先停引擎，免得同一句话反复触发；startCapture 里会重新接上
                VoiceWakeBridge.stop()
                start()
            }
        }
    }

    /** 会话期间把引擎重新挂起来（待机实例让出麦克风时引擎被 stop 过） */
    private fun ensureWakeEngine() {
        if (!wakeEnabled) return
        val ctx = appContext ?: return
        val keywords = MeSettings.getXiaozhiWakeKeywords(ctx)
        val threshold = MeSettings.getXiaozhiWakeThreshold(ctx)
        val signature = "$threshold|$keywords"
        if (signature != idleSignature) {
            // 唤醒词或灵敏度改过，旧引擎不能用了
            VoiceWakeBridge.release()
            idleSignature = null
        }
        val started = VoiceWakeBridge.start(
            ctx,
            keywords,
            threshold,
            { keyword -> onWakeDetected(keyword) },
            { message -> log("唤醒插件：$message") }
        )
        if (!started) {
            log("语音唤醒：引擎加载失败 ${VoiceWakeBridge.errorMessage()}")
            return
        }
        idleSignature = signature
    }

    private fun startCapture() {
        // 麦克风只有一个：会话要录音，先把待机监听的实例让出来（引擎留着，会话结束还能复用）
        if (idleCapture != null) stopIdleListening(releaseEngine = false)
        val existing = capture
        if (existing != null) {
            // 录音还在（THINKING/SPEAKING 期间只摘了编码管线）：挂回去就行，别重开 AudioRecord，
            // 一重开预滚缓冲就空了，打断前那段声音也就没了。
            host?.ensureForegroundMicType()
            existing.attachEncoder { packet -> if (sendAudio) transport?.sendBinary(packet) }
            sendAudio = true
            flushPendingPreroll(existing)
            log("恢复上行")
            return
        }
        host?.ensureForegroundMicType()
        host?.claimMicrophone()
        // 降噪开关决定走哪条链路：开=语音通话（必须先切 mode 才生效），关=录像链路不碰 mode
        if (config.micDenoise) enterVoiceAudioMode(useBluetoothMic = true)
        // 语音唤醒开着：会话期间也要把 PCM 扇给引擎，这样播报中喊唤醒词能打断
        if (wakeEnabled) ensureWakeEngine()
        val wakeSink: ((ShortArray, Int) -> Unit)? = if (wakeEnabled) {
            { samples, length -> VoiceWakeBridge.acceptPcm(samples, length) }
        } else {
            null
        }
        val created = XiaozhiAudioCapture(
            denoise = config.micDenoise,
            onPacket = { packet -> if (sendAudio) transport?.sendBinary(packet) },
            onLevel = { rms -> onVoiceLevel(rms) },
            onError = { message -> post { failSession(message) } },
            log = { message -> log(message) },
            onPcm = wakeSink
        )
        if (created.start()) {
            capture = created
            sendAudio = true
            log("开始录音")
            flushPendingPreroll(created)
        } else {
            capture = null
            sendAudio = false
            failSession("麦克风启动失败")
        }
    }

    private fun stopCaptureInternal(force: Boolean = false) {
        sendAudio = false
        val current = capture
        if (current == null) {
            releaseVoiceLink()
            return
        }
        // 说话打断 / 语音唤醒：THINKING/SPEAKING 期间只摘编码管线，AudioRecord 和通话模式都留着。
        // 通话模式不能还——HAL 的 AEC 只在那个模式下才把扬声器输出当参考，
        // 没有 AEC 就分不出「播报」和「人在说话」。
        if (!force && keepRecordingAlive()) {
            current.detachEncoder()
            log("停止上行（录音保持）")
            return
        }
        capture = null
        // 先还模式再停采：麦克风关掉之后没有任何理由继续占着通话模式
        releaseVoiceLink()
        current.stop()
        log("停止录音")
    }

    /**
     * 这个状态下要不要为了让「说话打断 / 语音唤醒」继续工作而把录音留着。
     * 用 stateMachine.state 而不是 stateSnapshot：副作用是在状态已经切过去之后执行的。
     */
    private fun keepRecordingAlive(): Boolean {
        if (!talkInterruptOn && !wakeEnabled) return false
        return when (stateMachine.state) {
            XiaozhiStateMachine.State.LISTENING,
            XiaozhiStateMachine.State.THINKING,
            XiaozhiStateMachine.State.SPEAKING -> true
            else -> false
        }
    }

    /** 把打断瞬间截下来的那段预滚补进上行（排在实时数据前面） */
    private fun flushPendingPreroll(target: XiaozhiAudioCapture) {
        val preroll = pendingInterruptAudio
        pendingInterruptAudio = null
        if (preroll == null || preroll.isEmpty()) return
        target.feedPreroll(preroll)
        log("补发打断前 ${preroll.size / 16}ms 语音")
    }

    /**
     * 录音期间切到通话音频模式：这是 `AudioSource.VOICE_COMMUNICATION` 真正生效的前提，
     * 系统语音助手也是这么做的。HAL 只有在通话模式下才会把输出（含媒体那一路）当 AEC 参考。
     *
     * [useBluetoothMic] 为 true 时顺便把上行切到蓝牙麦：降噪开着就是要靠设备侧处理，
     * 蓝牙音响/耳机的麦自带降噪和回声消除，比手机麦更合适。
     *
     * 全局状态，必须成对还原；别人已经处在通话模式（比如正在打电话）就一点都不碰。
     */
    private fun enterVoiceAudioMode(useBluetoothMic: Boolean) {
        if (previousAudioMode != AudioManager.MODE_INVALID) return
        val manager = audioManager() ?: return
        val original = manager.mode
        if (original == AudioManager.MODE_IN_COMMUNICATION) {
            log("已处于通话音频模式，跳过切换")
            return
        }
        previousAudioMode = original
        try {
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            log("音频模式 ${audioModeName(original)} -> IN_COMMUNICATION")
        } catch (e: Exception) {
            previousAudioMode = AudioManager.MODE_INVALID
            log("切换音频模式失败：${e.message}")
            return
        }
        // 通话模式不开免提就掉到听筒，实测必须开；顺序不能反，mode 不对时 set 会抛异常
        enableSpeakerphone(manager)
        if (useBluetoothMic) startBluetoothSco(manager)
    }

    /**
     * 退出通话链路。释放前只看一件事：待机监听的降噪开关还开着吗？
     * 开着就说明它马上要接手，这时候还回去纯属白拆一次——SCO 重新协商要 200~800ms，
     * 期间上行还是手机麦，唤醒后第一句开头会缺一截。
     *
     * 不能拿 sessionActive 当判据：静默超时、服务端 bye 这些路径是状态机先进 IDLE、
     * StopCapture 先于 finishSession 执行，那时候 sessionActive 还是 true，
     * 一判断就会把链路提前还掉（日志里就是"SCO 停了 → 待机起来又请求一次"）。
     *
     * 真不用了才还原：关掉语音唤醒、待机降噪关掉，都会走到 restoreAudioMode。
     */
    private fun releaseVoiceLink() {
        val ctx = appContext
        val idleTakesOver = wakeEnabled && ctx != null && MeSettings.isXiaozhiWakeDenoise(ctx)
        if (idleTakesOver && previousAudioMode != AudioManager.MODE_INVALID) {
            log("通话链路留给待机监听")
            return
        }
        restoreAudioMode()
    }

    private fun restoreAudioMode() {
        val original = previousAudioMode
        if (original == AudioManager.MODE_INVALID) return
        previousAudioMode = AudioManager.MODE_INVALID
        val manager = audioManager() ?: return
        // 先停 SCO 再退 mode：蓝牙链路还挂着的时候切 mode 会白折腾一次路由
        stopBluetoothSco(manager)
        try {
            manager.mode = original
            log("音频模式还原 -> ${audioModeName(original)}")
        } catch (e: Exception) {
            log("还原音频模式失败：${e.message}")
        }
    }

    /**
     * 把上行切到蓝牙麦。蓝牙音响/耳机的麦克风走的是 HFP 的 SCO 链路，
     * SCO 不建立，系统根本不会把它当输入设备（A2DP 那一路只有输出、没有麦）。
     *
     * 这里用已废弃的 `startBluetoothSco()`：它在 12/13/14 上仍然是有效实现，只是标了 deprecated，
     * 且只要 `MODIFY_AUDIO_SETTINGS` 普通权限；新 API `setCommunicationDevice()` 得先枚举蓝牙设备，
     * 而 Android 12+ 枚举蓝牙要 `BLUETOOTH_CONNECT` 运行时权限，代价不值得。
     *
     * 代价：SCO 是通话链路，蓝牙音响的音乐（A2DP）会被挂起，播报也变成通话音质。
     */
    @Suppress("DEPRECATION")
    private fun startBluetoothSco(manager: AudioManager) {
        if (bluetoothScoOwned) return
        try {
            manager.startBluetoothSco()
            manager.isBluetoothScoOn = true
            bluetoothScoOwned = true
//            log("请求蓝牙 SCO（上行切到蓝牙麦）")
        } catch (e: Exception) {
            log("请求蓝牙 SCO 失败：${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun stopBluetoothSco(manager: AudioManager) {
        if (!bluetoothScoOwned) return
        bluetoothScoOwned = false
        try {
            manager.stopBluetoothSco()
            manager.isBluetoothScoOn = false
//            log("停止蓝牙 SCO")
        } catch (e: Exception) {
            log("停止蓝牙 SCO 失败：${e.message}")
        }
    }

    /**
     * 通话模式下必须开免提，否则声音走听筒。
     * 只开不还：退出时会先把 mode 退回 NORMAL，这个状态对普通播放就没影响了；
     * 下次进来本来也是要重新开成 true。
     */
    @Suppress("DEPRECATION")
    private fun enableSpeakerphone(manager: AudioManager) {
        try {
            manager.isSpeakerphoneOn = true
//            log("强制外放 on")
        } catch (e: Exception) {
            log("设置外放失败：${e.message}")
        }
    }

    private fun audioManager(): AudioManager? =
        appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private fun audioModeName(mode: Int): String = when (mode) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        else -> "MODE($mode)"
    }

    private fun onVoiceLevel(rms: Double) {
        // 每一帧都要喂给打断判定（低音量帧是学底噪用的，不能提前 return）
        detectTalkInterrupt(rms)
        if (rms < VOICE_RMS_THRESHOLD) return
        val now = System.currentTimeMillis()
        if (now - lastLevelResetAt < LEVEL_RESET_INTERVAL_MS) return
        lastLevelResetAt = now
        post { resetSilenceTimerIfListening() }
    }

    /**
     * 播报期间判「有人在说话」。**跑在采集线程上**，只做几次比较加一个环形写，
     * 绝不能在这里做任何可能卡住的事——卡一下 AudioRecord 缓冲就溢出，之后是永久丢帧。
     *
     * 判据：最近 240ms（12 帧）里至少 9 帧 RMS 超过 [INTERRUPT_MIN_RMS]。
     * 阈值是**固定值**，不做自适应——这台设备开降噪后 AEC 残留只有个位数，
     * 自适应只会被环境噪声和失配瞬态带偏（实测出现过「12 帧平稳 174~239 全超阈」的误打断）。
     */
    private fun detectTalkInterrupt(rms: Double) {
        if (!talkInterruptOn) return
        if (stateSnapshot != XiaozhiStateMachine.State.SPEAKING) return
        val sinceAt = speakingSinceAt
        if (sinceAt == 0L) return
        // 新一轮播报：本地判定状态自己清（控制线程只发布 sinceAt，不碰这些字段）
        if (sinceAt != detectorForSinceAt) {
            detectorForSinceAt = sinceAt
            speakingPeak = 0.0
            interruptRmsCount = 0
            interruptRmsIdx = 0
        }
        if (rms > speakingPeak) speakingPeak = rms
        val now = android.os.SystemClock.elapsedRealtime()

        val warmingUp = now - sinceAt < INTERRUPT_GUARD_MS
        val flushing = !warmingUp && now - lastPlaybackFlushAt < INTERRUPT_FLUSH_GUARD_MS
        val cooling = !warmingUp && !flushing && now - lastInterruptAt < INTERRUPT_COOLDOWN_MS

        // 诊断日志放在所有 return 之前，否则屏蔽/暖机期间一行都打不出来
        if (INTERRUPT_DEBUG && now - lastInterruptDebugAt >= 1000L) {
            lastInterruptDebugAt = now
            val phase = when {
                warmingUp -> "暖机"
                flushing -> "屏蔽失配"
                cooling -> "冷却"
                else -> "判定中"
            }
            log(
                "说话打断[$phase] rms=${rms.toInt()} 阈值=${INTERRUPT_MIN_RMS.toInt()}" +
                        " 峰值=${speakingPeak.toInt()}"
            )
        }

        if (warmingUp) return
        if (flushing) {
            // AEC 正在重新收敛：这段里的能量全是失配残留，不判
            interruptRmsCount = 0
            return
        }
        if (cooling) return

        interruptRms[interruptRmsIdx] = rms
        interruptRmsIdx = (interruptRmsIdx + 1) % INTERRUPT_WINDOW_FRAMES
        if (interruptRmsCount < INTERRUPT_WINDOW_FRAMES) interruptRmsCount++

        val threshold = INTERRUPT_MIN_RMS
        val windowFull = interruptRmsCount >= INTERRUPT_WINDOW_FRAMES
        var hits = 0
        var firstHit = -1
        if (windowFull) {
            for (i in 0 until INTERRUPT_WINDOW_FRAMES) {
                // interruptRmsIdx 指向「下一次写入」的位置，也就是最旧的帧
                if (interruptRms[(interruptRmsIdx + i) % INTERRUPT_WINDOW_FRAMES] > threshold) {
                    hits++
                    if (firstHit < 0) firstHit = i
                }
            }
        }
        if (!windowFull || hits < INTERRUPT_HIT_FRAMES || firstHit < 0) return

        lastInterruptAt = now
        // 从突增起点那帧取到现在：前面的都是 TTS 回声，带上只会让 ASR 认错
        val framesBack = INTERRUPT_WINDOW_FRAMES - firstHit
        pendingInterruptAudio = capture?.drainPrerollFrom(framesBack)
        log("音量突增（$hits/$INTERRUPT_WINDOW_FRAMES 帧超阈），打断播报")
        post { dispatch(XiaozhiStateMachine.Event.Interrupt) }
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
