package com.zyyme.workdayalarmclock.xiaozhi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.zyyme.workdayalarmclock.MeSettings
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 小智AI 会话编排。
 *
 * 线程模型：
 * - 控制面：[handler]（HandlerThread "xiaozhi-ctrl"）上跑状态机、socket 收发、各种计时器；
 * - 采集、播放与待机唤醒各有自己的线程，回调一律 post 回控制面；
 * - 宿主（MeService / Activity）的方法都在主线程调用。
 *
 * 手势 / 触屏 / 管道 / 离线语音唤醒汇流到同一个会话状态机。
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
    private const val STANDBY_RETRY_MAX = 3
    private const val STANDBY_RETRY_BASE_MS = 2000L

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

    @Volatile
    private var standbyMonitoring = false

    private var transport: XiaozhiTransport? = null
    private var capture: XiaozhiAudioCapture? = null
    private var player: XiaozhiAudioPlayer? = null

    private val wakeWordEngine = XiaozhiWakeWordEngine()
    private var wakeWordMonitor: XiaozhiWakeWordMonitor? = null
    private val bargeInDetector = XiaozhiBargeInDetector()
    private val softwareAec = XiaozhiSoftwareAec()

    @Volatile
    private var standbyPrepareInFlight = false
    private var preparedWakeWords = ""
    private var preparedWakeThreshold = -1
    private var preparedVad = false
    private var standbyRetryAttempt = 0
    private var standbyRetryRunnable: Runnable? = null

    private var currentSessionId = ""
    private var pendingWakeWord: String? = null
    private var pendingWakeWordPcm: ShortArray? = null

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

    @Volatile
    private var latestCaptureRms = 0.0

    /** 本次会话是否由我们暂停了音乐，只有 true 才在结束时恢复 */
    private var mediaPausedByUs = false

    /**
     * 录音期间被我们改掉的原始音频模式，`MODE_INVALID` 表示当前不归我们管。
     * 只有它 != INVALID 时才会去还原，避免踩到别人（比如系统通话）已经设好的模式。
     */
    private var previousAudioMode = AudioManager.MODE_INVALID

    /** 外放是我们强制打开时就记下原值，结束时还原 */
    private var previousSpeakerphoneOn = false
    private var speakerphoneOwned = false

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
        val ctrlHandler = handler
        ctrlHandler?.post {
            stopStandbyMonitor()
            wakeWordEngine.release()
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
        standbyMonitoring = false
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
            wakeWordEnabled = MeSettings.isXiaozhiWakeWord(ctx),
            wakeWords = MeSettings.getXiaozhiWakeWords(ctx),
            wakeThreshold = MeSettings.getXiaozhiWakeThreshold(ctx),
            wakeWordInterrupt = MeSettings.isXiaozhiWakeWordInterrupt(ctx),
            realtimeInterrupt = MeSettings.isXiaozhiRealtimeInterrupt(ctx),
            softAec = MeSettings.isXiaozhiSoftAec(ctx)
        )
        config = cfg
        // 地址/token/设备ID 变过，缓存的连接配置就不认了（只改自动续听之类不动它）
        if (previous.otaUrl != cfg.otaUrl || previous.wsUrl != cfg.wsUrl ||
            previous.token != cfg.token || previous.deviceMac != cfg.deviceMac
        ) {
            invalidateCachedEndpoint("配置变更")
        }
        stateMachine.autoContinue = cfg.autoContinue
        stateMachine.silenceTimeoutMs = cfg.silenceTimeoutMs
        stateMachine.realtimeMode = cfg.realtimeInterrupt
        stateMachine.keepCaptureWhileSpeaking = cfg.realtimeInterrupt || cfg.wakeWordInterrupt
        if (!cfg.enabled && sessionActive) stop("模块已关闭")
        post { syncStandbyWakeWord() }
    }

    fun isSessionActive(): Boolean = sessionActive

    /** 会话中或待机唤醒监听中都视为小智正在占用麦克风 */
    fun isMicrophoneInUse(): Boolean = sessionActive || standbyMonitoring

    fun isEnabled(): Boolean = config.enabled

    // ---------------------------------------------------------------- 对外入口

    /**
     * 唤醒 / 打断。返回 false 表示这次不该被消费（未启用 / 无权限），宿主落回原逻辑。
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

    // ---------------------------------------------------------------- 待机语音唤醒

    private fun syncStandbyWakeWord() {
        val ctx = appContext ?: return
        val cfg = config
        val wantStandby = cfg.enabled && cfg.wakeWordEnabled && !sessionActive && hasRecordPermission(ctx)
        if (!wantStandby) {
            cancelStandbyRetry()
            standbyRetryAttempt = 0
            stopStandbyMonitor()
            return
        }

        val configChanged = preparedWakeWords != cfg.wakeWords ||
            preparedWakeThreshold != cfg.wakeThreshold ||
            (cfg.realtimeInterrupt && !preparedVad)

        if (wakeWordMonitor?.isRunning == true && !configChanged) {
            return
        }

        if (configChanged) {
            stopStandbyMonitor()
        }

        if (standbyPrepareInFlight) return
        val executor = otaExecutor ?: return
        standbyPrepareInFlight = true
        val reqWakeWords = cfg.wakeWords
        val reqThreshold = cfg.wakeThreshold
        val reqRealtime = cfg.realtimeInterrupt
        executor.execute {
            val ok = wakeWordEngine.ensureReady(
                context = ctx,
                rawWakeWords = reqWakeWords,
                thresholdPercent = reqThreshold,
                needKws = true,
                needVad = reqRealtime,
                log = { log(it) }
            )
            post {
                standbyPrepareInFlight = false
                if (!ok) return@post
                preparedWakeWords = reqWakeWords
                preparedWakeThreshold = reqThreshold
                preparedVad = preparedVad || reqRealtime
                val latest = config
                if (latest.enabled && latest.wakeWordEnabled && !sessionActive && hasRecordPermission(ctx)) {
                    if (latest.wakeWords != reqWakeWords || latest.wakeThreshold != reqThreshold) {
                        syncStandbyWakeWord()
                    } else {
                        startStandbyMonitor()
                    }
                }
            }
        }
    }

    private fun startStandbyMonitor() {
        if (wakeWordMonitor?.isRunning == true || sessionActive) return
        host?.ensureForegroundMicType()
        host?.claimMicrophone()
        val monitor = XiaozhiWakeWordMonitor(
            engine = wakeWordEngine,
            onDetected = { wakeWord, prerollPcm ->
                post { onStandbyWakeWordDetected(wakeWord, prerollPcm) }
            },
            onError = { message ->
                post { onStandbyMonitorError(message) }
            },
            log = { message -> log(message) }
        )
        if (monitor.start()) {
            wakeWordMonitor = monitor
            standbyMonitoring = true
            standbyRetryAttempt = 0
        } else {
            standbyMonitoring = false
            scheduleStandbyRetry()
        }
    }

    private fun stopStandbyMonitor() {
        val current = wakeWordMonitor
        wakeWordMonitor = null
        standbyMonitoring = false
        current?.stopAndAwait(600L)
    }

    private fun onStandbyMonitorError(message: String) {
        log(message)
        wakeWordMonitor = null
        standbyMonitoring = false
        if (config.enabled && config.wakeWordEnabled && !sessionActive) {
            scheduleStandbyRetry()
        }
    }

    private fun scheduleStandbyRetry() {
        cancelStandbyRetry()
        if (standbyRetryAttempt >= STANDBY_RETRY_MAX) return
        standbyRetryAttempt++
        val delayMs = STANDBY_RETRY_BASE_MS * standbyRetryAttempt
        val runnable = Runnable {
            standbyRetryRunnable = null
            syncStandbyWakeWord()
        }
        standbyRetryRunnable = runnable
        handler?.postDelayed(runnable, delayMs)
    }

    private fun cancelStandbyRetry() {
        standbyRetryRunnable?.let { handler?.removeCallbacks(it) }
        standbyRetryRunnable = null
    }

    private fun onStandbyWakeWordDetected(wakeWord: String, prerollPcm: ShortArray) {
        wakeWordMonitor = null
        standbyMonitoring = false
        if (!config.enabled || sessionActive) return
        cancelStandbyRetry()
        pendingWakeWord = wakeWord
        pendingWakeWordPcm = prerollPcm
        setCaption("已唤醒：$wakeWord")
        log("语音唤醒：$wakeWord")
        beginSession(hint = null)
    }

    // ---------------------------------------------------------------- 会话

    private fun onTrigger(hint: String?) {
        if (stateMachine.isActive) {
            interruptInternal()
        } else {
            stopStandbyMonitor()
            cancelStandbyRetry()
            pendingWakeWord = null
            pendingWakeWordPcm = null
            beginSession(hint)
        }
    }

    private fun beginSession(hint: String?) {
        if (stateMachine.isActive) return
        val suffix = if (hint.isNullOrBlank()) "" else "：$hint"
        log("开始会话$suffix")
        sessionActive = true
        currentSessionId = ""
        reconnectCount = 0
        reusedCachedEndpoint = false
        turnLead.setLength(0)
        stopAfterTurn = false
        bargeInDetector.reset()

        // 先停音乐再压低音量：外放的音乐会糊住麦克风，识别会缺字
        mediaPausedByUs = config.pauseMedia && host?.pauseMediaForVoice() == true
        // 整个会话期间都把音乐压低（等同"丢掉焦点"），直到会话结束/断线才还原
        host?.setVoiceDuck(true)

        // 实时打断或降噪模式需在建 AudioTrack/AudioRecord 之前切到通话音频模式，
        // 否则 HAL 不会将下游输出接入 AEC 参考通路。
        if (config.realtimeInterrupt || config.micDenoise) {
            enterVoiceAudioMode()
        }
        if (pendingWakeWord != null) {
            XiaozhiToneCue.playWakeCue(useVoiceStream = config.realtimeInterrupt)
        }

        ensureSessionModelsReadyAsync()
        stateMachine.reset()
        dispatch(XiaozhiStateMachine.Event.Start)
        resolveEndpointAndConnect()
    }

    private fun ensureSessionModelsReadyAsync() {
        val ctx = appContext ?: return
        val cfg = config
        val needKws = cfg.wakeWordEnabled || cfg.wakeWordInterrupt
        val needVad = cfg.realtimeInterrupt
        if ((!needKws && !needVad) || Build.VERSION.SDK_INT < 21) return
        val needReload = (needKws &&
            (preparedWakeWords != cfg.wakeWords || preparedWakeThreshold != cfg.wakeThreshold)) ||
            (needVad && !preparedVad)
        if (!needReload && wakeWordEngine.available) return
        otaExecutor?.execute {
            val ok = wakeWordEngine.ensureReady(
                context = ctx,
                rawWakeWords = cfg.wakeWords,
                thresholdPercent = cfg.wakeThreshold,
                needKws = needKws,
                needVad = needVad,
                log = { log(it) }
            )
            if (ok) {
                post {
                    if (needKws) {
                        preparedWakeWords = cfg.wakeWords
                        preparedWakeThreshold = cfg.wakeThreshold
                    }
                    if (needVad) {
                        preparedVad = true
                    }
                }
            }
        }
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
        bargeInDetector.reset()
        wakeWordEngine.resetWakeWord()
        wakeWordEngine.resetVad()
        dispatch(XiaozhiStateMachine.Event.Interrupt)
    }

    private fun onInSessionWakeWord(wakeWord: String) {
        if (!sessionActive) return
        val st = stateMachine.state
        if (st != XiaozhiStateMachine.State.SPEAKING && st != XiaozhiStateMachine.State.THINKING) {
            return
        }
        log("唤醒词打断：$wakeWord")
        XiaozhiToneCue.playInterruptCue(useVoiceStream = config.realtimeInterrupt)
        turnLead.setLength(0)
        stopAfterTurn = false
        bargeInDetector.reset()
        wakeWordEngine.resetVad()
        dispatch(XiaozhiStateMachine.Event.WakeWordInterrupt(wakeWord))
    }

    private fun onLocalVoiceBargeIn() {
        if (!sessionActive || stateMachine.state != XiaozhiStateMachine.State.SPEAKING) return
        log("语音实时打断")
        turnLead.setLength(0)
        stopAfterTurn = false
        bargeInDetector.reset()
        wakeWordEngine.resetWakeWord()
        wakeWordEngine.resetVad()
        dispatch(XiaozhiStateMachine.Event.VoiceBargeIn)
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
        currentSessionId = ""
        pendingWakeWord = null
        pendingWakeWordPcm = null
        bargeInDetector.reset()
        softwareAec.reset()
        cancelTimers()
        stopCaptureInternal(awaitRelease = true)
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
        syncStandbyWakeWord()
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
        override fun onBinary(data: ByteArray) = post {
            if (stateMachine.state == XiaozhiStateMachine.State.SPEAKING) {
                player?.enqueue(data)
            }
        }
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
            XiaozhiProtocol.helloJson(
                mapOf(
                    "mcp" to true,
                    "mcp_20260728" to true
                )
            )
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
                currentSessionId = event.sessionId
                // 缓存配置连通了，这次会话后面再断线重连也不用再问 OTA
                reusedCachedEndpoint = false
                cancelHelloTimeout()
                createPlayer(event.sampleRate)
                sendWakeWordDetectIfPending()
                dispatch(XiaozhiStateMachine.Event.HelloReceived(event.sampleRate))
            }

            is XiaozhiProtocol.ServerEvent.Stt -> {
                if (stateMachine.state == XiaozhiStateMachine.State.SPEAKING) {
                    log("服务端触发语音打断")
                }
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

    private fun sendWakeWordDetectIfPending() {
        val wakeWord = pendingWakeWord ?: return
        val pcm = pendingWakeWordPcm
        pendingWakeWord = null
        pendingWakeWordPcm = null

        val activeTransport = transport ?: return
        if (pcm != null && pcm.isNotEmpty()) {
            try {
                val encoder = ConcentusOpusCodec.createEncoder(complexity = 2)
                val chunker = PcmFrameChunker(XiaozhiProtocol.UPLOAD_FRAME_SAMPLES)
                for (frame in chunker.append(pcm, 0, pcm.size)) {
                    val packet = encoder.encode(frame, 0, frame.size)
                    if (packet != null && packet.isNotEmpty()) {
                        activeTransport.sendBinary(packet)
                    }
                }
                encoder.release()
            } catch (e: Exception) {
                log("发送唤醒音频失败：${e.message}")
            }
        }
        activeTransport.sendText(XiaozhiProtocol.listenDetectJson(wakeWord, currentSessionId))
        stateMachine.expectListeningAfterTurn = true
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
        if (stateMachine.state == XiaozhiStateMachine.State.IDLE) {
            finishSession()
        }
    }

    private fun onTts(state: String, text: String) {
        when (state) {
            "start" -> {
                bargeInDetector.onSpeakingStarted(SystemClock.elapsedRealtime())
                wakeWordEngine.resetWakeWord()
                wakeWordEngine.resetVad()
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
                if (stateMachine.state == XiaozhiStateMachine.State.SPEAKING) {
                    player?.markEndOfTurn()
                }
            }
            "sentence_end" -> {
                // 不需要特殊处理，等 tts stop 再排空
            }
            else -> log("未知 tts 状态 $state")
        }
    }

    private fun createPlayer(sampleRate: Int) {
        player?.stop()
        if (config.realtimeInterrupt || config.micDenoise) {
            enterVoiceAudioMode()
        }
        val created = XiaozhiAudioPlayer(
            sampleRate = sampleRate,
            useVoiceStream = config.realtimeInterrupt
        ) { log(it) }
        created.onDecodedPcm = { pcm, len, rate ->
            if (config.softAec) softwareAec.feedFarEnd(pcm, len, rate)
        }
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
    }

    private fun applyEffects(effects: List<XiaozhiStateMachine.SideEffect>) {
        for (effect in effects) {
            when (effect) {
                is XiaozhiStateMachine.SideEffect.StartCapture -> startCapture()
                is XiaozhiStateMachine.SideEffect.StopCapture -> stopCaptureInternal(awaitRelease = false)
                is XiaozhiStateMachine.SideEffect.SetUplinkEnabled -> capture?.setUplinkEnabled(effect.enabled)
                is XiaozhiStateMachine.SideEffect.FlushUplinkQueue -> capture?.clearPendingUplink()
                is XiaozhiStateMachine.SideEffect.SendPrerollAudio -> capture?.enqueuePrerollForUplink(400)
                is XiaozhiStateMachine.SideEffect.SendListenStart ->
                    transport?.sendText(XiaozhiProtocol.listenStartJson(config.effectiveMode(), currentSessionId))
                is XiaozhiStateMachine.SideEffect.SendListenStop ->
                    transport?.sendText(XiaozhiProtocol.listenStopJson(currentSessionId))
                is XiaozhiStateMachine.SideEffect.SendAbort ->
                    transport?.sendText(XiaozhiProtocol.abortJson(effect.reason, currentSessionId))
                is XiaozhiStateMachine.SideEffect.FlushPlayback -> {
                    player?.flushAndPause()
                    softwareAec.clearFarEnd()
                }
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
        val useVoiceMode = config.micDenoise || config.realtimeInterrupt
        if (useVoiceMode) enterVoiceAudioMode()
        softwareAec.reset()
        if (config.softAec) {
            log("软件回声消除/降噪：开启")
        }
        val created = XiaozhiAudioCapture(
            denoise = config.micDenoise,
            enableAec = config.realtimeInterrupt || config.micDenoise,
            onPacket = { packet -> if (sendAudio) transport?.sendBinary(packet) },
            onLevel = { rms -> latestCaptureRms = rms },
            onPcm = { samples, length -> onCapturePcm(samples, length) },
            onError = { message -> post { failSession(message) } },
            pcmProcessor = if (config.softAec) {
                { samples, length -> softwareAec.processNearEnd(samples, length) }
            } else {
                null
            },
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

    private fun stopCaptureInternal(awaitRelease: Boolean) {
        sendAudio = false
        val current = capture
        capture = null
        // 仅在会话结束或非全双工模式真正停麦时还原音频模式，避免刚开会话在 Event.Start 处反复切换 HAL 模式
        if (!sessionActive || (current != null && !config.realtimeInterrupt)) {
            restoreAudioMode()
        }
        if (current != null) {
            if (awaitRelease) {
                current.stopAndAwait(500L)
            } else {
                current.stop()
            }
            log("停止录音")
        }
    }

    /**
     * 录音期间切到通话音频模式：这是 `AudioSource.VOICE_COMMUNICATION` 真正生效的前提，
     * 系统语音助手也是这么做的。HAL 只有在通话模式下才会把输出（含语音通话那一路）当 AEC 参考。
     *
     * 全局状态，必须成对还原；别人已经处在通话模式（比如正在打电话）就一点都不碰。
     */
    private fun enterVoiceAudioMode() {
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
        // 通话模式下媒体默认可能走听筒，明确要求外放；顺序不能反，mode 不对时 set 会抛异常
        enableSpeakerphone(manager)
    }

    private fun restoreAudioMode() {
        val original = previousAudioMode
        if (original == AudioManager.MODE_INVALID) return
        previousAudioMode = AudioManager.MODE_INVALID
        val manager = audioManager() ?: return
        // 先把外放还原，再退 mode：退出通话模式之后 setSpeakerphoneOn 就没意义了
        restoreSpeakerphone(manager)
        try {
            manager.mode = original
            log("音频模式还原 -> ${audioModeName(original)}")
        } catch (e: Exception) {
            log("还原音频模式失败：${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun enableSpeakerphone(manager: AudioManager) {
        if (speakerphoneOwned) return
        try {
            previousSpeakerphoneOn = manager.isSpeakerphoneOn
            speakerphoneOwned = true
            manager.isSpeakerphoneOn = true
            log("强制外放 on（原 ${previousSpeakerphoneOn}）")
        } catch (e: Exception) {
            speakerphoneOwned = false
            log("设置外放失败：${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun restoreSpeakerphone(manager: AudioManager) {
        if (!speakerphoneOwned) return
        speakerphoneOwned = false
        try {
            manager.isSpeakerphoneOn = previousSpeakerphoneOn
            log("外放还原 ${previousSpeakerphoneOn}")
        } catch (e: Exception) {
            log("还原外放失败：${e.message}")
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

    private fun onCapturePcm(samples: ShortArray, length: Int) {
        if (!sessionActive) return
        val state = stateMachine.state
        val rms = latestCaptureRms

        if ((state == XiaozhiStateMachine.State.SPEAKING || state == XiaozhiStateMachine.State.THINKING) &&
            config.wakeWordInterrupt
        ) {
            val hit = wakeWordEngine.feedWakeWordPcm(samples, length)
            if (hit != null) {
                post { onInSessionWakeWord(hit) }
                return
            }
        }

        if (state == XiaozhiStateMachine.State.SPEAKING && config.realtimeInterrupt) {
            val vadProb = wakeWordEngine.computeVadProb(samples, length)
            val playbackPeak = player?.currentPlaybackRmsPeak() ?: 0.0
            when (
                bargeInDetector.onFrame(
                    micRms = rms,
                    playbackRmsPeak = playbackPeak,
                    vadProb = vadProb,
                    frameMs = 20L,
                    nowMs = SystemClock.elapsedRealtime()
                )
            ) {
                XiaozhiBargeInDetector.Result.TRIGGERED -> {
                    post { onLocalVoiceBargeIn() }
                    return
                }
                XiaozhiBargeInDetector.Result.ECHO_SUPPRESSED_ONCE -> {
                    log("检测到较强扬声器回声，已自动抑制本地快打断（仍可用唤醒词或按键打断）")
                }
                XiaozhiBargeInDetector.Result.NONE -> Unit
            }
        }

        if (state == XiaozhiStateMachine.State.LISTENING) {
            val vadProb = if (config.realtimeInterrupt) {
                wakeWordEngine.computeVadProb(samples, length)
            } else {
                null
            }
            val activeSpeech = (vadProb != null && vadProb >= 0.6f) || rms >= VOICE_RMS_THRESHOLD
            if (!activeSpeech) return
            val now = System.currentTimeMillis()
            if (now - lastLevelResetAt < LEVEL_RESET_INTERVAL_MS) return
            lastLevelResetAt = now
            post { resetSilenceTimerIfListening() }
        }
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
