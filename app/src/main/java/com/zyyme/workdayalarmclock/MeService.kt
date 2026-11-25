package com.zyyme.workdayalarmclock

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.hardware.Camera
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.support.v4.media.session.PlaybackStateCompat
import android.util.DisplayMetrics
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.zyyme.workdayalarmclock.applist.AppListActivity
import com.zyyme.workdayalarmclock.camera.AmbientBrightnessController
import com.zyyme.workdayalarmclock.camera.CameraSizes
import com.zyyme.workdayalarmclock.camera.CameraStreamFormat
import com.zyyme.workdayalarmclock.camera.CameraStreamKey
import com.zyyme.workdayalarmclock.camera.FramebufferFrameRenderer
import com.zyyme.workdayalarmclock.camera.MjpegCameraPipeline
import com.zyyme.workdayalarmclock.camera.CameraHttpServer
import com.zyyme.workdayalarmclock.xiaozhi.DoubleClickGate
import com.zyyme.workdayalarmclock.xiaozhi.XiaozhiAssistant
import com.zyyme.workdayalarmclock.xiaozhi.XiaozhiCaption
import com.zyyme.workdayalarmclock.xiaozhi.XiaozhiDeviceInfo
import com.zyyme.workdayalarmclock.xiaozhi.XiaozhiHost
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.lang.reflect.Method
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.collections.iterator
import kotlin.jvm.java
import kotlin.math.sqrt

/**
 * 后台服务
 * 维持工作咩闹钟二进制文件运行
 */
class MeService : Service() {
    private data class LyricLine(val timeMs: Int, val text: String)

    private enum class PlayerState {
        IDLE,
        PREPARING,
        PREPARED,
        PLAYING,
        PAUSED,
        BUFFERING,
        ERROR
    }

    companion object {
//        var mediaButtonReceiverHandler: Handler? = null
        var me: MeService? = null

        /**
         * 唤醒用的 CPU 锁。放在静态里是因为闹钟广播可能是冷启动拉起的进程，
         * 那时 MeService 实例还不存在，但锁必须马上拿到。
         */
        private var wakeLockNext: PowerManager.WakeLock? = null

        fun acquireNextWakeLock(context: Context) {
            val appContext = context.applicationContext
            val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLockNext == null) {
                wakeLockNext = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "workDayAlarmClock:NextWake"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            wakeLockNext?.acquire(60_000L)
        }

        val logBuilder = StringBuilder()

        const val ACTION_PLAY = "com.zyyme.workdayalarmclock.ACTION_PLAY"
        const val ACTION_NEXT = "com.zyyme.workdayalarmclock.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.zyyme.workdayalarmclock.ACTION_PREVIOUS"
        const val ACTION_STOP = "com.zyyme.workdayalarmclock.ACTION_STOP"
        const val ACTION_WAKE = "com.zyyme.workdayalarmclock.ACTION_WAKE"

        const val ACTION_FORWARD = "com.zyyme.workdayalarmclock.ACTION_FORWARD"

        const val NOTIFICATION_ID = 1
        private const val MAX_PLAYBACK_RETRIES = 1
        private const val PLAYBACK_RETRY_DELAY_MS = 1000L
        private const val PLAYBACK_RESUME_REWIND_MS = 2000
        private const val PLAYBACK_CHECKPOINT_INTERVAL_MS = 1000L
        private const val WEATHER_UPDATE_INTERVAL_MS = 60L * 60L * 1000L
        /** 界面恢复时补 wake 的最小间隔 */
        private const val NEXT_WAKE_REFRESH_INTERVAL_MS = 60L * 1000L
        private const val MEDIA_TRACK_LONG_PRESS_DELAY_MS = 900L
        private const val MEDIA_TRACK_SEEK_INTERVAL_MS = 500L
        private const val MEDIA_TRACK_SEEK_STEP_MS = 5000
        /** 命令写完等这么久，收 Go 的输出 */
        private const val COMMAND_OUTPUT_WAIT_MS = 500L
        /** 小智录音时的音乐音量，与 audiofocus ducking 保持一致 */
        private const val VOICE_DUCK_VOLUME = 0.3f
        /** 小智启用时，DPAD Center 单击延后这么久执行，用来等第二击 */
        private const val XIAOZHI_DOUBLE_CLICK_WINDOW_MS = 300L
        // 在线播放与歌词请求共用，部分 CDN 会拒绝 MediaPlayer 默认的 stagefright UA
        private const val HTTP_USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1"

        // 这些设备将默认启用时钟模式  两个拼起来
        // getprop ro.product.manufacturer
        // getprop ro.product.model
        //                                 绿色陪伴音箱            小魔镜                     法国闹钟                           小熊尼奥照照乐和Pro
        val clockModeModel = listOf<String>("softwinnerHPN_XH", "sprduws6137_1h10_64b_1g", "AllwinnerQUAD-CORE A64 ococci", "MAGNEOC110001", "MAGNEOMAGNEO")

        // 大屏时钟模式  默认勾选时钟模式和大屏时钟模式
        //                                  叮咚Play         Sayinfo音箱           国美云音箱
        val deskModeModel = listOf<String>("Intelcht_mrd", "rockchiprk3326_m2g", "rockchipGM800")

        // 无屏设备模式  电源键作为多击键 开机启动回到main 作为桌面时按home键触发1key
        //                                19元BodyGuargz监控
        val noScreenMode = listOf<String>("PUBLICTV5701")

    }

    var meMediaPlaybackManager: MeMediaPlaybackManager? = null
    private var player : MediaPlayer? = null
    var writer : PrintWriter? = null
    var lastUrl : String? = null
    var shellThread : Thread? = null
    @Volatile
    var isStop = true
    lateinit var audioManager: AudioManager
    var mBreathLedsManager: Any? = null
    var wakeLock: PowerManager.WakeLock? = null
    var wakeLockPlay: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    var wifiLock: WifiManager.WifiLock? = null
    var shellProcess: Process? = null
    var loadProgress: Int = 0
    var defaultSeek: Int = 0

    // 用于展示的电池信息 有变化才有值，固定电量不显示
    var batInfo = ""
    var batLevel = -1
    @Volatile var lastEcho = ""
    // 小智执行命令时开一个窗口，把这期间 Go 打回来的行收下来当命令结果
    @Volatile private var commandOutput: StringBuilder? = null
    private val commandOutputLock = Any()
    @Volatile var weatherText = ""
    // 天气预警信息，显示在歌词条的位置
    @Volatile var weatherAlText = ""
    @Volatile var todoText = ""
    @Volatile private var lastWeatherRequestAt = 0L
    @Volatile private var lastTodoRequestAt = 0L
    @Volatile private var lastNextWakeRefreshAt = 0L
    @Volatile private var todoRequestInFlight = false

    val isBonjour = Build.MANUFACTURER + Build.MODEL == "AllwinnerQUAD-CORE A64 ococci"

    // 给OPPO擦屁股 没有放出声音时不能设置音量
    var lastSetVol = -1

    private var batteryReceiver: BroadcastReceiver? = null
    private var notificationBuilder: NotificationCompat.Builder? = null
    private var notificationManager: NotificationManager? = null
    private var nextWakePendingIntent: PendingIntent? = null
    private var udpServerSocket: DatagramSocket? = null
    private var cameraHttpServer: CameraHttpServer? = null
    @Volatile private var framebufferStreaming = false
    @Volatile private var screenOffWhenStreamStop = false
    private lateinit var ambientBrightness: AmbientBrightnessController
    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoBackClockHandler = Handler(Looper.getMainLooper())
    private var autoBackClockRunnable: Runnable? = null
    private var startupClockReturnRunnable: Runnable? = null
    private val playbackHandler = Handler(Looper.getMainLooper())
    private var playerState = PlayerState.IDLE
        set(value) {
            field = value
            playbackStateSnapshot = value
        }

    /** 给其它线程看播放状态的快照（小智字幕/ducking 判断用） */
    @Volatile
    private var playbackStateSnapshot = PlayerState.IDLE

    /** 小智录音期间压低音乐音量 */
    @Volatile
    private var voiceDuckActive = false
    private var playWhenReady = false
        set(value) {
            if (field == value) return
            field = value
            MainActivity.me?.updatePlaybackButton(value)
            DeskActivity.me?.updatePlaybackButton(value)
        }
    private var resumeAfterAudioFocusGain = false
    private var playbackGeneration = 0
    private var retryCount = 0
    private var lastKnownPositionMs = 0
    private var playbackDurationMs = 0
    private var pendingSeekPositionMs: Int? = null
    @Volatile private var currentSongId: String? = null
    @Volatile private var lyricSongId: String? = null
    @Volatile private var lyricLines = emptyList<LyricLine>()
    @Volatile private var lyricVersion = 0
    @Volatile private var hasPendingSongId = false

    // 存一下真实的屏幕尺寸，给fbinfo用
    var fbinfoX = 0
    var fbinfoY = 0

    private val playbackCheckpointRunnable = object : Runnable {
        override fun run() {
            if (playerState == PlayerState.PLAYING || playerState == PlayerState.BUFFERING) {
                val previousPositionMs = lastKnownPositionMs
                snapshotPlaybackPosition()
                if (lastSetVol != -1 && lastKnownPositionMs > previousPositionMs) {
                    val volume = lastSetVol
                    audioManager.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        volume,
                        AudioManager.FLAG_SHOW_UI
                    )
                    lastSetVol = -1
                    print2LogView("检测到播放进度变化，再次设置音量为$volume")
                }
                playbackHandler.postDelayed(this, PLAYBACK_CHECKPOINT_INTERVAL_MS)
            }
        }
    }

    // 多击检测状态
    private val multiClickHandler = Handler(Looper.getMainLooper())
    private var multiClickState = 0  // 0=IDLE, 1=KEY_DOWN, 2=WAIT_CLICKS, 3=LONG_PRESSED
    private var multiClickCount = 0
    private var multiClickKeyCode = 0
    private var longPressTriggered = false
    private var longPressDownTime = 0L
    private var longPressRunnable: Runnable? = null

    /** DPAD Center 单击/双击消歧（只有小智启用时才需要等第二击） */
    private val centerClickGate = DoubleClickGate(XIAOZHI_DOUBLE_CLICK_WINDOW_MS)
    private var centerSingleClickRunnable: Runnable? = null
    private var clickWindowRunnable: Runnable? = null

    // 实体上一首/下一首媒体键长按快退/快进状态
    private var mediaTrackKeyCode = 0
    private var mediaTrackLongPressTriggered = false
    private var mediaTrackLongPressRunnable: Runnable? = null
    private var mediaTrackSeekRunnable: Runnable? = null
    private var mediaPlayKeyCode = 0
    private var mediaPlayLongPressTriggered = false
    private var mediaPlayLongPressRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        // 尽早放宽 HTTPS 校验并打开 TLS1.2，老系统才能访问更新/歌词这些接口
        UnsafeHttps.install()
        ambientBrightness = AmbientBrightnessController(this) { message ->
            print2LogView(message)
        }
    }


    override fun onBind(intent: Intent): IBinder {
        me = this
        TODO("Return the communication channel to the service.")
    }

    private fun applyFirstLaunchModeDefaults() {
        val configFile = File(filesDir.absolutePath, "workdayAlarmClock.json")
        if (configFile.exists()) return

        val deviceModel = Build.MANUFACTURER + Build.MODEL
        val useDeskClockByModel = deskModeModel.contains(deviceModel)
        if (clockModeModel.contains(deviceModel) || useDeskClockByModel) {
            MeSettings.setEnabled(this, MeSettings.KEY_CLOCK, true)
        }
        if (noScreenMode.contains(deviceModel)) {
            MeSettings.setEnabled(this, MeSettings.KEY_NO_SCREEN, true)
        }
        if (useDeskClockByModel || isLargeLandscapeScreen()) {
            MeSettings.setEnabled(this, MeSettings.KEY_DESK_CLOCK, true)
            MeSettings.setEnabled(this, MeSettings.KEY_LYRICS, true)
        }
    }

    private fun isLargeLandscapeScreen(): Boolean {
        val display = (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            display.getRealMetrics(metrics)
        } else {
            display.getMetrics(metrics)
        }
        if (metrics.widthPixels <= metrics.heightPixels || metrics.xdpi <= 0f || metrics.ydpi <= 0f) {
            return false
        }

        val widthInches = metrics.widthPixels / metrics.xdpi
        val heightInches = metrics.heightPixels / metrics.ydpi
        return sqrt(widthInches * widthInches + heightInches * heightInches) > 6.7f
    }

    @SuppressLint("WrongConstant")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startAction = intent?.action
        if (me != null) {
            // 服务已经在跑，如果是定时器请求给个wake
            if (startAction == ACTION_WAKE) {
                acquireNextWakeLock(applicationContext)
                // getForegroundService 要求服务保持在前台，这里补一次（已在前台就是刷新通知）
                updateForegroundServiceType(cameraFeaturesEnabled(), microphoneFeaturesEnabled())
                toGo("wake")
            }
            return 0
        }
        me = this
        applyFirstLaunchModeDefaults()

        // 初始化音频服务
        meMediaPlaybackManager = MeMediaPlaybackManager(this)

        // 保活通知 8.0开始channel不是个字符串 不会保持唤醒
        val channelId = "me_bg"
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val notificationChannel = NotificationChannel(channelId, "保活通知", importance).apply {
                description = "保活通知"
            }
            notificationManager?.createNotificationChannel(notificationChannel)
        }

        val mainIntent = MeSettings.createClockIntent(applicationContext)
        // 清除任务栈并创建新任务
        mainIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            mainIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        notificationBuilder = NotificationCompat.Builder(this, channelId)
            // 他一定要设置一个图标
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setSubText("正在运行")
            .setContentTitle(this.getString(R.string.app_name))
            .setContentText("咩咩")
            .setUsesChronometer(true)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)

        if (Build.VERSION.SDK_INT >= 21) {
            // 媒体控制按钮
            val sessionToken = meMediaPlaybackManager?.getSessionToken()
            val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(sessionToken)

            notificationBuilder?.addAction(
                NotificationCompat.Action(
                    R.drawable.icon_previous,
                    "上一首",
                    createPendingIntentForAction(ACTION_PREVIOUS)
                )
            )
            notificationBuilder?.addAction(
                NotificationCompat.Action(
                    R.drawable.icon_play,
                    "播放",
                    createPendingIntentForAction(ACTION_PLAY)
                )
            )
            notificationBuilder?.addAction(
                NotificationCompat.Action(
                    R.drawable.icon_next,
                    "下一首",
                    createPendingIntentForAction(ACTION_NEXT)
                )
            )
            notificationBuilder?.addAction(
                NotificationCompat.Action(
                    R.drawable.icon_stop,
                    "停止",
                    createPendingIntentForAction(ACTION_STOP)
                )
            )
//            notificationBuilder?.addAction(
//                NotificationCompat.Action(
//                    android.R.drawable.ic_media_ff,
//                    "快进",
//                    createPendingIntentForAction(ACTION_FORWARD)
//                )
//            )
            // 设置显示的按钮 缩小的时候
            mediaStyle.setShowActionsInCompactView(0, 1, 2, 3)
            notificationBuilder?.setStyle(mediaStyle)
        }

        syncAmbientBrightnessSetting()
        updateForegroundServiceType(cameraFeaturesEnabled(), microphoneFeaturesEnabled())
        syncCameraServerSetting()
        // 小智模块只需要上下文 + 宿主回调，放在"可能提前 return"之前接上
        XiaozhiAssistant.attach(this, buildXiaozhiHost())

        // 如果不需要启动Go服务，这个服务将只有播放音频url的功能
        if (!MainActivity.startService) {
            return super.onStartCommand(intent, flags, startId)
        }

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        // 创建 OnAudioFocusChangeListener
        val afChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    // 永久失去焦点，停止播放
                    print2LogView("AUDIOFOCUS_LOSS, stopping playback.")
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    // 短暂失去焦点，暂停播放
                    print2LogView("AUDIOFOCUS_LOSS_TRANSIENT, pausing playback.")
                    cancelAutoBackToClock()
                    pausePlaybackForAudioFocus()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // 短暂失去焦点，可以降低音量 (如果你的播放器支持)
                    print2LogView("AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, ducking volume.")
                    cancelAutoBackToClock()
                    runOnPlaybackThread {
                        if (isPlayerControllable()) {
                            player?.setVolume(0.3f, 0.3f)
                        }
                    }
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    // 重新获得焦点，恢复播放 (如果之前暂停了或降低了音量)
                    print2LogView("AUDIOFOCUS_GAIN, resuming playback or restoring volume.")
                    resumePlaybackAfterAudioFocus()
                }
            }
        }

        // 注册电量变化监听
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val bat = intent.getIntExtra("level", -1)
                // 第一次不显示，有变化再显示
                if (bat != -1 && batLevel != -1 && bat != batLevel) {
                    batInfo = "$bat% "
                }
                batLevel = bat
            }
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))


        // 请求音频焦点
        val result = audioManager.requestAudioFocus(
            afChangeListener,
            AudioManager.STREAM_MUSIC, // 要播放的音频流类型
            AudioManager.AUDIOFOCUS_GAIN // 请求永久焦点 (或 AUDIOFOCUS_GAIN_TRANSIENT 等)
        )
//        print2LogView("获取音频焦点 " + (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED))

        // 抢夺音频焦点
//        player = MediaPlayer()
//        player!!.setDataSource("http://127.0.0.1:1")
//        player!!.start()
//        player!!.stop()
//        player!!.release()
//        player = null

        print2LogView("机型代号：" + Build.MANUFACTURER + Build.MODEL)

        // udp广播监听 群控
        startUdpServer()

        // 开一个新线程跑shell
        runShell()

//        mediaButtonReceiverHandler = Handler(Looper.getMainLooper()) { msg ->
//            keyHandle(msg.obj as Int)
//            true
//        }

        // 一说宝宝特有系统服务用于控制led灯板
        if (Build.MODEL == "S14G") {
            try {
                mBreathLedsManager = getSystemService("breath_leds")
                Log.d("mBreathLedsManager", "获取成功 $mBreathLedsManager")
                // 关灯
                ysSetLedsValue(MeYsLed.EMPTY)
            } catch (e: Exception) {
                Log.d("mBreathLedsManager", "获取失败")
                e.printStackTrace()
            }
        }

        // 自动开热点
        if (MeSettings.isEnabled(this, MeSettings.KEY_AP)) {
            startAp()
        }

        return super.onStartCommand(intent, flags, startId)
    }

    private fun scheduleAutoBackToClock() {
        cancelAutoBackToClock()
        if (!MeSettings.isEnabled(this, MeSettings.KEY_AUTO_BACK_CLOCK)) {
            return
        }
        autoBackClockRunnable = Runnable {
            autoBackClockRunnable = null
            if (!MeSettings.isEnabled(this, MeSettings.KEY_AUTO_BACK_CLOCK)) {
                return@Runnable
            }
            // 解决小爱的悬浮窗不会消失的bug  虽然不知道当前显示的app是什么，但是直接启动一下本应启动的小爱同学也无伤大雅
            val storedApps = this.getSharedPreferences("app_list", MODE_PRIVATE).getString("startup_apps", null).orEmpty().trim()
            Log.d("scheduleAutoBackToClock", "storedApps: $storedApps")
            if (storedApps.contains("\"com.xiaomi.xiaoailite\"")) {
                val intent = packageManager.getLaunchIntentForPackage("com.xiaomi.xiaoailite")
                if (intent != null) {
                    Log.d("scheduleAutoBackToClock", "给小米擦屁股")
                    startActivity(intent)
                    Thread.sleep(500)
                }
            }
            val intent = MeSettings.createClockIntent(this)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            intent.putExtra("clockMode", true)
            startActivity(intent)
            print2LogView("自动回到时钟")
        }
        autoBackClockHandler.postDelayed(autoBackClockRunnable!!, 10000)
    }

    private fun cancelAutoBackToClock() {
        autoBackClockRunnable?.let { autoBackClockHandler.removeCallbacks(it) }
        autoBackClockRunnable = null
    }

    /**
     * 有的开机启动app启动的比较慢，导致最后显示的界面是别的app
     */
    fun scheduleStartupClockReturn(delayMillis: Long = 10000L) {
        startupClockReturnRunnable?.let { mainHandler.removeCallbacks(it) }
        startupClockReturnRunnable = Runnable {
            startupClockReturnRunnable = null
            if (!MeSettings.isEnabled(this, MeSettings.KEY_CLOCK)) return@Runnable
            try {
                MeSettings.applyClockTheme(this)
                startActivity(MeSettings.createClockIntent(this).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("clockMode", true)
                })
            } catch (e: Exception) {
                Log.v("workdayAlarmClock", "服务中切回时钟失败", e)
            }
        }
        mainHandler.postDelayed(startupClockReturnRunnable!!, delayMillis)
    }

    // 用于为通知操作创建 PendingIntent
    private fun createPendingIntentForAction(action: String): PendingIntent {
        val intent = Intent(this, MeMediaButtonReceiver::class.java).setAction(action)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        // 发送一个广播，让MediaButtonReceiver接收
        return PendingIntent.getBroadcast(this, action.hashCode(), intent, flags)
    }

    /**
     * 闹钟唤醒专用：用 getForegroundService 让系统直接把 MeService 当 FGS 启动，
     * 不经过广播中转。广播中转时 Android 12 会在服务里把 startForeground() 判成后台调用。
     */
    private fun createWakePendingIntent(): PendingIntent {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(this, MeService::class.java).setAction(ACTION_WAKE)
            PendingIntent.getForegroundService(this, ACTION_WAKE.hashCode(), intent, flags)
        } else {
            // 8.0 以下没有 getForegroundService，只能广播 + receiver 拉服务
            val intent = Intent(this, MeMediaButtonReceiver::class.java).setAction(ACTION_WAKE)
            PendingIntent.getBroadcast(this, ACTION_WAKE.hashCode(), intent, flags)
        }
    }

    /**
     * 只有用户主动关闭（EXIT、退出应用）才置 true，
     * onDestroy 据此决定要不要把 NEXTWAKE 闹钟一起取消。
     */
    var cancelNextWakeOnDestroy = false

    fun cancelNextWakeAlarm() {
        nextWakePendingIntent?.let {
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(it)
            print2LogView("已取消下次唤醒")
        }
        nextWakePendingIntent = null
    }

    /**
     * Go 算好下一次该醒的绝对毫秒时间戳，这里排一个一次性精确闹钟。
     * 0 表示没有下一次。
     */
    private fun scheduleNextWake(ms: Long) {
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        nextWakePendingIntent?.let { alarmManager.cancel(it) }
        nextWakePendingIntent = null
        if (ms <= 0L) {
            return
        }
        // 加 500ms 避开分钟边界：timeJob() 用 time.Now() 的 hh:mm 判闹钟，
        // 万一 RTC 在 07:59:59.999 醒，这一分钟的闹钟就漏了
        val trigger = maxOf(ms + 500L, System.currentTimeMillis() + 1000L)
        val pendingIntent = createWakePendingIntent()
        nextWakePendingIntent = pendingIntent
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !alarmManager.canScheduleExactAlarms()
            ) {
                // 没有精确闹钟权限，退化成不精确，总比没有强
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent)
                print2LogView("没有精确闹钟权限，使用不精确唤醒")
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, null), pendingIntent)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, trigger, pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                alarmManager.set(AlarmManager.RTC_WAKEUP, trigger, pendingIntent)
            }
            val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
            print2LogView("下次唤醒 " + fmt.format(Date(trigger)))
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                alarmManager.set(AlarmManager.RTC_WAKEUP, trigger, pendingIntent)
            }
            print2LogView("精确闹钟被拒绝: ${e.message}")
        }
    }

    fun updateNotificationTitle(newTitle: String) {
        if (notificationBuilder != null && notificationManager != null) {
            notificationBuilder?.setContentTitle(newTitle)
            notificationManager?.notify(NOTIFICATION_ID, notificationBuilder?.build())
        }
    }

    fun syncCameraServerSetting() {
        val cameraConfigured = MeSettings.isEnabled(this, MeSettings.KEY_CAMERA_SERVER)
        val speakerConfigured = MeSettings.isEnabled(this, MeSettings.KEY_COMPUTER_SPEAKER)
        val cameraReady = !cameraConfigured || hasCameraPermission()
        if (cameraConfigured && !cameraReady) {
            MeSettings.setEnabled(this, MeSettings.KEY_CAMERA_SERVER, false)
            print2LogView("摄像头权限未授权，IP摄像头未启动")
        }

        updateForegroundServiceType(cameraFeaturesEnabled(), microphoneFeaturesEnabled())
        if (!speakerConfigured && !MeSettings.isEnabled(this, MeSettings.KEY_CAMERA_SERVER)) {
            cameraHttpServer?.stop()
            cameraHttpServer = null
            return
        }

        val server = cameraHttpServer ?: CameraHttpServer(
            this,
            ambientBrightness,
            { message -> print2LogView(message) },
            { MeSettings.isEnabled(this, MeSettings.KEY_CAMERA_SERVER) },
            { MeSettings.isEnabled(this, MeSettings.KEY_COMPUTER_SPEAKER) },
            { frame, length -> FramebufferFrameRenderer.submit(frame, length) },
            { active ->
                framebufferStreaming = active
                FramebufferFrameRenderer.setStreaming(active)
                mainHandler.post { DeskActivity.me?.setFramebufferStreaming(active) }
                if (screenOffWhenStreamStop && !active && !ambientBrightness.markScreenKeptOnForAlarm()) {
                    print2LogView("流停止且不满足亮屏条件 熄屏")
                    requestScreenOff()
                }
            },
            { XiaozhiAssistant.isSessionActive() }
        ).also { cameraHttpServer = it }
        server.start(MeSettings.getCameraPassword(this))
    }

    /** 设置页改动小智开关后：刷新前台服务类型 + 重新读配置 */
    fun syncXiaozhiSetting() {
        updateForegroundServiceType(cameraFeaturesEnabled(), microphoneFeaturesEnabled())
        XiaozhiAssistant.reloadConfig()
    }

    private fun buildXiaozhiHost(): XiaozhiHost = object : XiaozhiHost {
        override fun log(message: String) {
            print2LogView(message)
        }

        override fun showStatus(message: String) {
            mainHandler.post {
                ClockActivity.me?.showMsg(message)
                DeskActivity.me?.showEcho(message)
            }
        }

        override fun appVersion(): String = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }

        override fun setVoiceDuck(on: Boolean) {
            this@MeService.setVoiceDuck(on)
        }

        override fun pauseMediaForVoice(): Boolean = this@MeService.pauseMediaForVoice()

        override fun resumeMediaAfterVoice() = this@MeService.resumeMediaAfterVoice()

        override fun runPlayerCommand(command: String): String =
            this@MeService.runPlayerCommand(command)

        override fun isMediaPlaying(): Boolean = this@MeService.isMediaPlaying()

        override fun deviceStatusJson(): String = this@MeService.deviceStatusJson()

        override fun capturePhoto(
            question: String,
            cameraIndex: Int,
            visionUrl: String,
            visionToken: String
        ): String? = this@MeService.capturePhoto(question, cameraIndex, visionUrl, visionToken)

        override fun claimMicrophone() {
            // 摄像头 /aac 推流占着麦克风时先让出去
            cameraHttpServer?.suspendAudioForExternalUser()
        }

        override fun releaseMicrophone() {
        }

        override fun ensureForegroundMicType() {
            updateForegroundServiceType(cameraFeaturesEnabled(), microphoneFeaturesEnabled())
        }
    }

    fun syncAmbientBrightnessSetting() {
        if (MeSettings.isEnabled(this, MeSettings.KEY_CAMERA_AUTO_BRIGHTNESS) && !hasCameraPermission()) {
            MeSettings.setEnabled(this, MeSettings.KEY_CAMERA_AUTO_BRIGHTNESS, false)
            print2LogView("摄像头权限未授权，自动亮度未启动")
        }
        ambientBrightness.syncSettings()
        updateForegroundServiceType(cameraFeaturesEnabled(), microphoneFeaturesEnabled())
    }

    fun ambientBrightnessLevel(): Int = ambientBrightness.level

    fun ambientBrightnessValue(): Int = ambientBrightness.currentSystemBrightness()

    fun ambientFaceStatus(): String = ambientBrightness.faceStatusText()

    /**
     * 设备状态，一行紧凑 JSON，给小智的 MCP 工具直接当文本返回。
     * 所有字段都按 minSdk 14 能用的 API 取，别换成 19+ 才有的符号。
     */
    fun deviceStatusJson(): String {
        val json = JSONObject()

        // 电量/充电：读 ACTION_BATTERY_CHANGED 的粘性广播，API 1 就有
        try {
            val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            json.put("battery", if (level >= 0 && scale > 0) level * 100 / scale else -1)
            val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            json.put(
                "charging",
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            )
        } catch (e: Exception) {
            json.put("battery", -1)
            json.put("charging", false)
        }

        // 音量：不能读 audioManager 字段，它在 onStartCommand 里才初始化，可能还没 init
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            json.put(
                "volume",
                if (max > 0) am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max else -1
            )
        } catch (e: Exception) {
            json.put("volume", -1)
        }

        json.put("ip", try { getIp() } catch (e: Exception) { "" })

        // 存储：availableBytes 是 API 18，老机器走 blocks*size
        json.put("storageFreeMB", try {
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            val freeBytes = if (Build.VERSION.SDK_INT >= 18) {
                stat.availableBytes
            } else {
                stat.availableBlocks.toLong() * stat.blockSize
            }
            freeBytes / 1048576L
        } catch (e: Exception) { -1L })

        json.put("memFreeMB", try {
            val info = ActivityManager.MemoryInfo()
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
            info.availMem / 1048576L
        } catch (e: Exception) { -1L })

        json.put("playing", isMediaPlaying())
        json.put("version", try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) { "1.0" })

        return json.toString()
    }

    /**
     * 拍照并上传到网关下发的视觉地址，返回画面描述；任何一步失败返回 null。
     *
     * @param cameraIndex 摄像头编号，直接给 `Camera.open`
     */
    fun capturePhoto(
        question: String,
        cameraIndex: Int,
        visionUrl: String,
        visionToken: String
    ): String? {
        if (visionUrl.isEmpty()) {
            print2LogView("拍照失败：网关没有下发视觉地址")
            return null
        }
        // 负数表示没有摄像头
        if (cameraIndex < 0) {
            print2LogView("拍照失败：没有摄像头")
            return null
        }
        // 编号超出就退到最后一个摄像头，免得模型瞎猜一个不存在的高编号
        val cameraCount = Camera.getNumberOfCameras()
        val index = if (cameraCount > 0 && cameraIndex >= cameraCount) cameraCount - 1
        else cameraIndex
        val jpeg = captureJpeg(index) ?: return null
        return uploadPhoto(question, jpeg, visionUrl, visionToken)
    }

    /** 拍一帧，返回 MJPEG 管线出的原始 JPEG */
    private fun captureJpeg(cameraIndex: Int): ByteArray? {
        if (!hasCameraPermission()) {
            print2LogView("拍照失败：没有相机权限")
            return null
        }
        var pipeline: MjpegCameraPipeline? = null
        try {
            // 实测小智最大可用分辨率1440x1080 大概278k
            // 档位是 CameraSizes 排好序的下标（面积降序），挑离 1440x1080 最近的那个。
            // 都比 1440x1080 小时，最近的自然就是最大那档
            val sizes = CameraSizes.cachedMjpeg(cameraIndex)
                ?: MjpegCameraPipeline.supportedResolutions(cameraIndex)
            val resolutionIndex = sizes.indices.minByOrNull {
                Math.abs(sizes[it].first - 1440) +
                    Math.abs(sizes[it].second - 1080)
            }
            val created = MjpegCameraPipeline(
                CameraStreamKey(CameraStreamFormat.MJPEG, cameraIndex, resolutionIndex),
                { message -> print2LogView(message) }
            )
            pipeline = created
            if (!created.start()) {
                XiaozhiCaption.set("打不开摄像头呢!")
                print2LogView("拍照失败：相机打不开，可能被 IP 摄像头或摄像头自动亮度占着")
                return null
            }
            // 队列里还没有帧时会最多等 3 秒
            var packet = created.awaitPacket(0)
            XiaozhiCaption.set("正在拍...")
            // 头几帧自动曝光还没收敛，容易全黑，往后多取两帧
            repeat(12) {
                val next = created.awaitPacket(packet?.sequence ?: 0)
                if (next != null) packet = next
            }
            val frame = packet ?: run {
                print2LogView("拍照失败：没拿到预览帧")
                return null
            }
            // 更新歌词栏
            XiaozhiCaption.set("拍好了!")
            return frame.data
        } catch (e: Exception) {
            print2LogView("拍照失败 $e")
            return null
        } finally {
            try {
                pipeline?.stop()
            } catch (e: Exception) {
                Log.w("MeService", "stop camera pipeline failed", e)
            }
        }
    }

    /**
     * 按官方固件的格式上传：multipart/form-data，`question` 文本字段 + `file` 图片字段。
     * 显式给 Content-Length（服务端不接受 chunked），token 走 Bearer。
     */
    private fun uploadPhoto(
        question: String,
        jpeg: ByteArray,
        visionUrl: String,
        visionToken: String
    ): String? {
        var conn: HttpURLConnection? = null
        try {
            val boundary = "----XiaozhiAndroidBoundary"
            val head = (
                "--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"question\"\r\n\r\n" +
                    question + "\r\n" +
                    "--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"file\"; filename=\"camera.jpg\"\r\n" +
                    "Content-Type: image/jpeg\r\n\r\n"
                ).toByteArray(Charsets.UTF_8)
            val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)

            conn = URL(visionUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 20000
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setRequestProperty("Device-Id", XiaozhiDeviceInfo.deviceMac(this))
            conn.setRequestProperty("Client-Id", XiaozhiDeviceInfo.clientId(this))
            if (visionToken.isNotEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer $visionToken")
            }
            conn.setFixedLengthStreamingMode(head.size + jpeg.size + tail.size)

            val out = conn.outputStream
            out.write(head)
            out.write(jpeg)
            out.write(tail)
            out.flush()
            out.close()

            val code = conn.responseCode
            val body = try {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            } catch (e: Exception) {
                ""
            }
            if (code !in 200..299) {
                print2LogView("拍照失败：视觉服务返回 $code $body")
                return null
            }
            return parseVisionReply(body)
        } catch (e: Exception) {
            print2LogView("拍照失败：上传异常 $e")
            return null
        } finally {
            try {
                conn?.disconnect()
            } catch (e: Exception) {
            }
        }
    }

    /** 视觉服务回的是 {"success":true,"response":"..."}，取 response；不是 json 就把原文给模型 */
    private fun parseVisionReply(body: String): String? {
        if (body.isBlank()) {
            print2LogView("拍照失败：视觉服务返回空内容")
            return null
        }
        return try {
            val json = JSONObject(body)
            val text = json.optString("response")
            if (text.isNotEmpty()) text else json.optString("message").ifEmpty { body }
        } catch (e: Exception) {
            body
        }
    }

    private fun shouldKeepScreenOnForAlarm(): Boolean {
        mainHandler.post {
            // 取消ALARM给时钟模式的保持亮屏flag
            if (ClockActivity.me?.isKeepScreenOn == true) {
                ClockActivity.me?.isKeepScreenOn = false
                ClockActivity.me?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            if (DeskActivity.me?.isKeepScreenOn == true) {
                DeskActivity.me?.isKeepScreenOn = false
                DeskActivity.me?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        // 满足亮屏条件的会标记成因条件而亮
        if (!ambientBrightness.markScreenKeptOnForAlarm()) {
            if (framebufferStreaming) {
                // 流停止且不满足亮屏条件后熄屏
                screenOffWhenStreamStop = true
                print2LogView("不满足亮屏条件 但是在串流 不熄屏")
                return true
            }
            print2LogView("不满足亮屏条件 熄屏")
            return false
        }
        print2LogView("满足亮屏条件 不熄屏")
        return true
    }

    private fun requestScreenOff() {
                val devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val adminComponentName = ComponentName(this, MeDeviceAdminReceiver::class.java)
                if (devicePolicyManager.isAdminActive(adminComponentName)) {
                    try {
                        mainHandler.post {
                            // 取消ALARM给时钟模式的保持亮屏flag
                            if (ClockActivity.me?.isKeepScreenOn == true) {
                                ClockActivity.me?.isKeepScreenOn = false
                                ClockActivity.me?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            }
                            if (DeskActivity.me?.isKeepScreenOn == true) {
                                DeskActivity.me?.isKeepScreenOn = false
                                DeskActivity.me?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            }
                            devicePolicyManager.lockNow()
                            print2LogView("已锁屏")
                        }
                    } catch (e: Exception) {
                        print2LogView("锁屏失败: ${e.message}")
                    }
                } else {
                    print2LogView("设备管理员未激活，无法锁屏")
                    Handler(Looper.getMainLooper()).post {
                        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponentName)
                            putExtra(
                                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                "远程锁屏需要这个权限"
                            )
                        }
                        startActivity(intent)
                        Toast.makeText(this,"请激活设备管理员权限\n远程锁屏需要这个权限\n卸载app需要在这里卸载", Toast.LENGTH_LONG).show()
                    }
                }
    }

    fun startAmbientBrightnessPreview() {
        ambientBrightness.beginLivePreview()
    }

    fun stopAmbientBrightnessPreview() {
        ambientBrightness.endLivePreview()
    }

    fun setAmbientPreviewFaceWakeEnabled(enabled: Boolean) {
        ambientBrightness.setPreviewFaceWakeEnabled(enabled)
    }

    private fun cameraFeaturesEnabled(): Boolean {
        return (MeSettings.isEnabled(this, MeSettings.KEY_CAMERA_SERVER) ||
            MeSettings.isEnabled(this, MeSettings.KEY_CAMERA_AUTO_BRIGHTNESS) ||
            MeSettings.isXiaozhiAllowPhoto(this)) && hasCameraPermission()
    }

    private fun microphoneFeaturesEnabled(): Boolean {
        if (!hasAudioPermission()) return false
        if (MeSettings.isEnabled(this, MeSettings.KEY_XIAOZHI)) return true
        return MeSettings.isEnabled(this, MeSettings.KEY_CAMERA_SERVER)
    }

    fun updateCameraPassword() {
        cameraHttpServer?.updatePassword(MeSettings.getCameraPassword(this))
    }

    private fun hasCameraPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasAudioPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasAnyMediaPermission(): Boolean = hasCameraPermission() || hasAudioPermission()

    private fun updateForegroundServiceType(cameraEnabled: Boolean, microphoneEnabled: Boolean) {
        val notification = notificationBuilder?.build() ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                if (cameraEnabled) {
                    serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
                if (microphoneEnabled) {
                    serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                startForeground(NOTIFICATION_ID, notification, serviceType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            print2LogView("切换前台服务失败 $e")
            e.printStackTrace()
        }
    }

    /**
     * App 有可见界面时把服务拉回前台。
     * 冷启动时进程是 BFGS，系统不让转前台；界面起来后进程变 TOP 才允许。
     */
    fun restoreForeground() {
        updateForegroundServiceType(cameraFeaturesEnabled(), microphoneFeaturesEnabled())
    }

    override fun onDestroy() {
        XiaozhiAssistant.shutdown()
        stopMediaTrackLongPress()
        stopMediaPlayLongPress()
        cameraHttpServer?.stop()
        cameraHttpServer = null
        if (::ambientBrightness.isInitialized) {
            ambientBrightness.shutdown()
        }
        cancelAutoBackToClock()
        startupClockReturnRunnable?.let { mainHandler.removeCallbacks(it) }
        startupClockReturnRunnable = null
        playbackGeneration++
        playbackHandler.removeCallbacksAndMessages(null)
        releasePlayer()
        shellProcess?.destroy()
        shellThread?.interrupt()
        MainActivity.me?.finish()
        ClockActivity.me?.finish()
        DeskActivity.me?.finish()
        udpServerSocket?.close()
        wakeLock?.release()
        wakeLockPlay?.release()
        if (wakeLockNext?.isHeld == true) {
            wakeLockNext?.release()
        }
        wakeLockNext = null
        if (screenWakeLock?.isHeld == true) {
            screenWakeLock?.release()
        }
        screenWakeLock = null
        wifiLock?.release()
        unregisterReceiver(batteryReceiver)
        stopForeground(true)
        // 只有用户主动退出才取消闹钟。普通重启、被系统停掉要留着，
        // 否则下一次 NEXTWAKE 永远不会来
        if (cancelNextWakeOnDestroy) {
            cancelNextWakeAlarm()
        } else {
            nextWakePendingIntent = null
        }
        me = null
        super.onDestroy()
        // 不知道为什么服务进程不退出 给他退出强制回收掉
        System.exit(0)
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        try {
            if (screenWakeLock == null) {
                val powerManager = getSystemService(POWER_SERVICE) as PowerManager
                screenWakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "workDayAlarmClock:ScreenWake"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            screenWakeLock?.acquire(5000L)
            print2LogView("已请求唤醒屏幕")
        } catch (e: Exception) {
            print2LogView("唤醒屏幕失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 反射调用一说宝宝设置灯板
     * end小于start的时候将直接设置
     */
    var ysLedThread: Thread? = null
    fun ysSetLedsValue(start: Int, end: Int=start, delay: Long=300, reverse: Boolean=false) {
        if (Build.MODEL != "S14G") {
            return
        }
        if (mBreathLedsManager != null) {
            if (ysLedThread != null) {
                ysLedThread?.interrupt()
                ysLedThread = null
            }
            val c = Class.forName("android.app.BreathLedsManager")
            val m = c.getDeclaredMethod("setLedsValue", Int::class.javaPrimitiveType)
            m.setAccessible(true)
            if (end != start) {
                var meThread: Thread? = null
                meThread = Thread(Runnable {
                    try {
                        while (true) {
                            for (i in start..end) {
                                m.invoke(mBreathLedsManager, i)
                                Thread.sleep(delay)
                            }
                            if (reverse) {
                                for (i in end downTo start) {
                                    m.invoke(mBreathLedsManager, i)
                                    Thread.sleep(delay)
                                }
                            }
                            if (meThread != this.ysLedThread) {
                                // 防止多线程冲突没interrupt到
                                m.invoke(mBreathLedsManager, MeYsLed.EMPTY)
                                break
                            }
                        }
                    } catch (e: InterruptedException) {
                        // interrupt必会抛出异常
                    }
                })
                ysLedThread = meThread
                meThread.start()
            } else {
                m.invoke(mBreathLedsManager, start)
            }
        }
    }

    /**
     * 使用灯板展示状态
     */
    fun ysLedStatus(): Boolean {
        if (Build.MODEL != "S14G") {
            return false
        }
        if (ysLedThread != null) {
            return true
        }
        if (mBreathLedsManager != null && Build.VERSION.SDK_INT >= 21) {
             ysLedThread = Thread(Runnable {
                try {
                    val c = Class.forName("android.app.BreathLedsManager")
                    val m = c.getDeclaredMethod("setLedsValue", Int::class.javaPrimitiveType)
                    m.setAccessible(true)
                    // 因为一说系统6.0并且其他设备也不走这里那就怎么简单怎么来了
                    val batLevel =(applicationContext.getSystemService(BATTERY_SERVICE) as BatteryManager)
                        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    val v :Int =  16 + (6 * batLevel / 100)
                    if (v == 16) {
                        m.invoke(mBreathLedsManager, MeYsLed.CRY_1)
                        Thread.sleep(200)
                        m.invoke(mBreathLedsManager, MeYsLed.CRY_2)
                        Thread.sleep(200)
                    } else {
                        m.invoke(mBreathLedsManager, v)
                        Thread.sleep(400)
                    }
                    m.invoke(mBreathLedsManager, MeYsLed.EMPTY)
                    Thread.sleep(150)
//                    for (three in 1..2) {
                        for (i in 4 downTo 1) {
                            if (i == 2) {continue}
                            m.invoke(mBreathLedsManager, i)
                            Thread.sleep(200)
                        }
                        Thread.sleep(1000)
                        for (i in 1..4) {
                            if (i == 2) {continue}
                            m.invoke(mBreathLedsManager, i)
                            Thread.sleep(200)
                        }
                        m.invoke(mBreathLedsManager, MeYsLed.EMPTY)
                        Thread.sleep(200)
//                    }
                } catch (e: InterruptedException) {
                    // interrupt必会抛出异常
                } finally {
                    ysLedThread = null
                }
            })
            ysLedThread!!.start()
            return true
        }
        return false
    }


    fun getIp(): String {
        val interfaces = NetworkInterface.getNetworkInterfaces()

        while (interfaces.hasMoreElements()) {
            val intf = interfaces.nextElement()

            if (!intf.isUp || intf.isLoopback)
                continue

            for (addr in intf.inetAddresses) {
                if (!addr.isLoopbackAddress &&
                    addr is Inet4Address
                ) {
                    return addr.hostAddress
                }
            }
        }

        return "在Wi-Fi设置查看IP"
    }

    fun print2LogView(inp:String) {
        // go获取不到ip，得靠Android了
        var s = inp
        if (inp.contains("设备ip")) {
            s = inp.replace("设备ip", getIp())
        }
        Log.d("logView", s)
        synchronized(logBuilder) {
            logBuilder.append(s).append('\n')
            // 检查长度并截断
            val maxLogLength = 10000
            if (logBuilder.length > maxLogLength) {
                // 找到第一个换行符的位置，从那里开始截断
                val firstNewLine = logBuilder.indexOf("\n", logBuilder.length - maxLogLength)
                if (firstNewLine != -1) {
                    logBuilder.delete(0, firstNewLine + 1) // +1 是\n
                } else {
                    // 如果没有找到换行符，直接截断到最大长度
                    logBuilder.delete(0, logBuilder.length - maxLogLength)
                }
            }
        }
        // checkAction() can be called by UDP/shell worker threads. View updates
        // must always be dispatched to the thread that created the hierarchy.
        mainHandler.post {
            MainActivity.me?.show2LogView()
        }
    }

    /**
     * Go控制台输出调用App
     */
    fun checkAction(s: String?, fromPipe: Boolean) {
        if (s != null) {
            if (fromPipe) {
                // 小智正在等这条命令的输出，顺手收一份（不影响下面原有的处理）
                // 前两个字符是大写的是 PLAY/VOL/SONGID/WEATHER/ECHO 这类协议行，别塞给模型
                val buffer = commandOutput
                if (buffer != null && !isProtocolLine(s)) {
                    synchronized(commandOutputLock) { buffer.append(s).append('\n') }
                }
                // 在本机发送udp广播时本机也会收到，过滤重复的内容
                cmdLastT = System.currentTimeMillis()
                cmdLastMsg = s
            }
            if (s.startsWith("SONGID ")) {
                val songId = s.substring(7).trim().toLongOrNull()
                if (songId != null && songId > 0) {
                    hasPendingSongId = true
                    synchronized(this) {
                        currentSongId = songId.toString()
                        lyricSongId = null
                        lyricLines = emptyList()
                        lyricVersion++
                    }
                    syncLyricsSetting()
                } else {
                    hasPendingSongId = false
                    print2LogView("歌曲ID无效: $s")
                }
            } else if (s.startsWith("PLAY ")) {
                playUrl(s.substring(5), true, hasPendingSongId)
                hasPendingSongId = false
            } else if (s.startsWith("LOAD ")) {
                playUrl(s.substring(5), false, hasPendingSongId)
                hasPendingSongId = false
            } else if (s.startsWith("VOL ")) {
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val per = s.substring(4).toInt()
                val set = (max * per / 100)
                lastSetVol = set
                print2LogView("音量最高" + max + "对应" + per + "%设置为" + set)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, set, AudioManager.FLAG_SHOW_UI);
            } else if (s.startsWith("WEATHER ")) {
                weatherText = s.substring(8).trim()
            } else if (s == "WEATHERAL" || s.startsWith("WEATHERAL ")) {
                // 和歌词共用一条显示位置，空内容时两个界面都会收起这条
                weatherAlText = s.substring(9).trim()
                mainHandler.post {
                    DeskActivity.me?.refreshLyricsBar()
                }
            } else if (s.startsWith("ECHO ")) {
                var msg = s.substring(5)
                // go获取不到ip，得靠Android了
                if (msg.contains("设备ip")) {
                    msg = msg.replace("设备ip", getIp())
                }
                lastEcho = msg
                updateNotificationTitle(msg)
                mainHandler.post {
                    DeskActivity.me?.showEcho(msg)
                    if (ClockActivity.me != null) {
                        if ("工作咩闹钟" == msg) {
                            ClockActivity.me?.showMsg("停止播放")
                        } else {
                            ClockActivity.me?.showMsg(msg)
                        }
                    }
                }
                meMediaPlaybackManager?.updateMediaMetadata(65535, msg, null, null)
            } else if (s.startsWith("SEEK ")) {
                // 移动进度条 单位是ms
                val seek = s.substring(5)
                seekPlaybackTo(seek.toInt())
            } else if (s.startsWith("DSEEK ")) {
                // 移动进度条的默认值
                val seek = s.substring(6)
                defaultSeek = seek.toInt()
            } else if (s == "STOP") {
                print2LogView("停止播放")
                stopPlayback()
                // 给超大的闹钟停止收起来
                mainHandler.post {
                    if (DeskActivity.me?.alarmMode == true) {
                        DeskActivity.me?.showAlarmControls(false)
                    }
                }
            } else if (s == "SEEK") {
                // 全屋同步播放补偿
                if (defaultSeek < 0) {
                    Thread.sleep(defaultSeek * -1L)
                    seekPlaybackTo(0)
                } else {
                    seekPlaybackTo(defaultSeek)
                }
            } else if (s == "PAUSE") {
                // Go 端已经置了 IsPaused，这里只做本地暂停，不能再回传 pause1
                print2LogView("暂停播放")
                requestPausePlayback(notifyGo = false)
            } else if (s == "RESUME") {
                print2LogView("恢复播放")
                if (!isStop) {
                    requestResumePlayback()
                } else {
                    // 触发一键 即播放默认歌单
                    toGo("1key")
                }
            } else if (s == "VOLP") {
                print2LogView("音量加")
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE,
                    AudioManager.FLAG_SHOW_UI);
            } else if (s == "VOLM") {
                print2LogView("音量减")
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI);
            } else if (s.startsWith("YSLED ")) {
                val n = s.substring(6).split("-")
                ysSetLedsValue(n[0].toInt(), n.last().toInt())
            } else if (s.startsWith("YSLEDRE ")) {
                val n = s.substring(8).split("-")
                ysSetLedsValue(n[0].toInt(), n.last().toInt(), n[1].toLong(), true)
            } else if (s == "WAKELOCK") {
                // 加cpu唤醒锁 2000mah电池平均每小时耗电1% 但工作稳定
                wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).run {
                    newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "workDayAlarmClock::MeService").apply {
                        acquire()
                        print2LogView("已启用CPU唤醒锁")
                    }
                }
                wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL, "workDayAlarmClock:WifiLock")
                wifiLock?.acquire()
            } else if (s.startsWith("NEXTWAKE ")) {
                val ms = s.substring(9).trim().toLongOrNull()
                if (ms != null) {
                    scheduleNextWake(ms)
                }
            } else if (s == "ALARM") {
                val devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val adminComponentName = ComponentName(this, MeDeviceAdminReceiver::class.java)
                if (devicePolicyManager.isAdminActive(adminComponentName)) {
                    Handler(Looper.getMainLooper()).post {
                        // 闹钟时，有关闭屏幕权限再打开屏幕
                        MeSettings.applyClockTheme(this)
                        val intent = MeSettings.createClockIntent(this)
                        intent.flags =
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        intent.putExtra("clockMode", true)
                        intent.putExtra("keepOn", true)
                        intent.putExtra(DeskActivity.EXTRA_ALARM_MODE, true)
                        wakeScreen()
                        startActivity(intent)
                        print2LogView("已亮屏")
                    }
                }
            } else if (s == "SCREENON") {
                Handler(Looper.getMainLooper()).post {
                    wakeScreen()
                }
                print2LogView("已亮屏")
            } else if (s == "SCREENOFF") {
                requestScreenOff()
            } else if (s == "ALARMSTOP") {
                mainHandler.post { DeskActivity.me?.showAlarmControls(false) }
                if (!shouldKeepScreenOnForAlarm()) {
                    requestScreenOff()
                }
            } else if (s == "EXIT") {
                cancelNextWakeOnDestroy = true
                stopSelf()
//                System.exit(0)
            } else if (s == "RESTART") {
                restartApp()
            } else if (s == "REWIFI") {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "svc wifi disable&&svc wifi enable"))
            } else if (s == "READB") {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "settings put global adb_enabled 1;stop adbd;start adbd"))
            } else if (s == "REBOOT") {
                Runtime.getRuntime().exec("reboot")
                Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot"))
            } else if (s == "STARTAP") {
                startAp()
            } else if (s == "HASUPDATE") {
                // go检测到有新版本，在菜单里「立即更新」的显示
                MeUpdater.onHasUpdate()
                print2LogView("点击右上角菜单「立即更新」")
                // root 设备自动静默更新（下载→静默安装→重启）
                MeUpdater.autoSilentUpdate(this)
            } else if (s == "AI" || s.startsWith("AI ")) {
                // Go 侧将来的唤醒入口
                XiaozhiAssistant.onPipeCommand(if (s.length > 3) s.substring(3) else "")
            } else if (s == "Segmentation fault") {
                print2LogView("出现系统错误，进行重启")
                Thread {
                    Thread.sleep(1000)
                    runShell()
                }.start()
                shellProcess?.destroy()
                shellThread?.interrupt()
            } else {
                print2LogView(s)
            }
        }
    }

    /**
     * UDP广播接收器 群控
     */
    var cmdLastT = 0L
    var cmdLastMsg = "";
    private fun startUdpServer() {
        Thread {
            try {
                // 广播端口监听
                udpServerSocket = DatagramSocket(25525).apply {
                    broadcast = true
                }
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)

                while (udpServerSocket != null && !udpServerSocket!!.isClosed) {
                    udpServerSocket?.receive(packet)
                    val msg = String(packet.data, 0, packet.length)

                    val t = System.currentTimeMillis()
                    if (t - cmdLastT < 1000 && msg == cmdLastMsg) {
                        // 去重
                        packet.length = buffer.size
                        continue
                    }
                    cmdLastT = t
                    cmdLastMsg = msg
                    print2LogView("从 ${packet.address.hostAddress} 收到广播 ${msg}")
                    try {
                        checkAction(msg, false)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        print2LogView("广播解析出错 $e")
                    }
                    // 重置包
                    packet.length = buffer.size
                }
            } catch (e: Exception) {
                print2LogView("UDP广播接收器出错 ${e.message}")
                e.printStackTrace()
            }
        }.start()
        print2LogView("UDP广播接收器启动")
    }

    /**
     * 暂停时调用 锁处理
     */
    fun onPause() {
        Log.d("logView MeService", "onPause")
        meMediaPlaybackManager?.updatePlaybackState(PlaybackStateCompat.STATE_PAUSED, 0)
        if (wakeLockPlay != null) {
            wakeLockPlay!!.release()
            wakeLockPlay = null
            // 如果启动时加了cpu唤醒锁，则保持wifi锁
            if (wakeLock == null) {
                wifiLock?.release()
                wifiLock = null
            }
        }

    }

    /**
     * 播放或恢复播时调用 锁处理
     */
    fun onPlay() {
        Log.d("logView MeService", "onPlay")
        meMediaPlaybackManager?.updatePlaybackState(PlaybackStateCompat.STATE_PLAYING, 0)
        // 如果没有唤醒锁，在开播时增加唤醒锁
        if (wakeLock == null && wakeLockPlay == null) {
            wakeLockPlay = (getSystemService(POWER_SERVICE) as PowerManager).run {
                newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "workDayAlarmClock::MeServicePlay").apply {
                    acquire()
                }
            }
            if (wifiLock == null) {
                wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "workDayAlarmClock:WifiLock")
                wifiLock?.acquire()
            }
        }
    }

    /**
     * 播放器播放url
     */
    fun playUrl(url:String, autoPlay:Boolean, keepLyrics:Boolean = false) {
        if (!keepLyrics) {
            clearLyrics()
        }
        runOnPlaybackThread {
            playbackGeneration++
            retryCount = 0
            lastKnownPositionMs = 0
            playbackDurationMs = 0
            pendingSeekPositionMs = null
            playWhenReady = autoPlay
            resumeAfterAudioFocusGain = false
            lastUrl = url
            startPlaybackAttempt(playbackGeneration, url, 0)
        }
    }

    private fun startPlaybackAttempt(generation: Int, url: String, resumePositionMs: Int) {
        if (generation != playbackGeneration) {
            return
        }

        releasePlayer()
        loadProgress = -1
        isStop = false
        playerState = PlayerState.PREPARING
        meMediaPlaybackManager?.updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING, 0)

        val mediaPlayer = MediaPlayer()
        player = mediaPlayer

        try {
            ysSetLedsValue(MeYsLed.VIOLENCE_1, MeYsLed.VIOLENCE_4, 500)
            print2LogView("play播放 $url")
            mediaPlayer.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)

            if (url.startsWith("./")) {
                mediaPlayer.setDataSource(filesDir.absolutePath + "/" + url)
            } else if (url.startsWith("http://") || url.startsWith("https://")) {
                // 带个UA
                // 必须是可变map：MTK的OMA定制层会往这个map里put自己的头，不可变map会抛UnsupportedOperationException
                mediaPlayer.setDataSource(
                    applicationContext,
                    Uri.parse(url),
                    hashMapOf("User-Agent" to HTTP_USER_AGENT)
                )
            } else {
                mediaPlayer.setDataSource(url)
            }

            mediaPlayer.setOnCompletionListener { completedPlayer ->
                if (!isCurrentPlayer(generation, completedPlayer)) {
                    return@setOnCompletionListener
                }
                finishPlaybackAndGoNext(completedPlayer, false)
            }

            mediaPlayer.setOnPreparedListener { preparedPlayer ->
                if (!isCurrentPlayer(generation, preparedPlayer)) {
                    return@setOnPreparedListener
                }

                playerState = PlayerState.PREPARED
                playbackDurationMs = preparedPlayer.duration.coerceAtLeast(0)
                print2LogView("play音频时长 ${playbackDurationMs / 1000}")
                meMediaPlaybackManager?.updateMediaMetadata(
                    playbackDurationMs.toLong(),
                    meMediaPlaybackManager!!.lastTitle,
                    null,
                    null
                )

                val requestedPosition = pendingSeekPositionMs ?: resumePositionMs
                pendingSeekPositionMs = null
                if (requestedPosition > 0) {
                    val seekPosition = if (playbackDurationMs > 0) {
                        requestedPosition.coerceAtMost(playbackDurationMs - 1)
                    } else {
                        requestedPosition
                    }
                    try {
                        preparedPlayer.seekTo(seekPosition)
                        lastKnownPositionMs = seekPosition
                        print2LogView("play重试续播 ${seekPosition / 1000}秒")
                    } catch (e: RuntimeException) {
                        lastKnownPositionMs = 0
                        print2LogView("play续播失败，从头播放: $e")
                    }
                }

                if (playWhenReady) {
                    startPreparedPlayer(preparedPlayer)
                }
            }

            mediaPlayer.setOnBufferingUpdateListener { bufferingPlayer, percent ->
                if (!isCurrentPlayer(generation, bufferingPlayer)) {
                    return@setOnBufferingUpdateListener
                }
                if (percent != 100 && percent > loadProgress) {
                    loadProgress = percent
                    print2LogView("play加载音频 $percent%")
                }
            }

            mediaPlayer.setOnInfoListener { infoPlayer, what, extra ->
                if (!isCurrentPlayer(generation, infoPlayer)) {
                    return@setOnInfoListener true
                }
                print2LogView("play信息: what=$what, extra=$extra")
                when (what) {
                    MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                        snapshotPlaybackPosition()
                        playerState = PlayerState.BUFFERING
                        meMediaPlaybackManager?.updatePlaybackState(
                            PlaybackStateCompat.STATE_BUFFERING,
                            lastKnownPositionMs.toLong()
                        )
                        print2LogView("play正在缓冲")
                        true
                    }
                    MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                        playerState = if (playWhenReady) {
                            PlayerState.PLAYING
                        } else {
                            PlayerState.PAUSED
                        }
                        if (playWhenReady) {
                            meMediaPlaybackManager?.updatePlaybackState(
                                PlaybackStateCompat.STATE_PLAYING,
                                lastKnownPositionMs.toLong()
                            )
                        }
                        print2LogView("play缓冲结束")
                        true
                    }
                    else -> false
                }
            }

            mediaPlayer.setOnErrorListener { errorPlayer, what, extra ->
                if (!isCurrentPlayer(generation, errorPlayer)) {
                    return@setOnErrorListener true
                }
                print2LogView("play播放错误: what=$what, extra=$extra")
                handlePlaybackError(generation, errorPlayer, what, extra)
                true
            }

            mediaPlayer.prepareAsync()
        } catch (e: IOException) {
            e.printStackTrace()
            print2LogView("播放失败$e")
            handlePlaybackError(
                generation,
                mediaPlayer,
                MediaPlayer.MEDIA_ERROR_UNKNOWN,
                MediaPlayer.MEDIA_ERROR_IO
            )
        } catch (e: Exception) {
            e.printStackTrace()
            print2LogView("播放失败$e")
            handlePlaybackError(
                generation,
                mediaPlayer,
                MediaPlayer.MEDIA_ERROR_UNKNOWN,
                0
            )
        }
    }

    private fun handlePlaybackError(
        generation: Int,
        errorPlayer: MediaPlayer,
        what: Int,
        extra: Int
    ) {
        if (!isCurrentPlayer(generation, errorPlayer)) {
            return
        }

        playerState = PlayerState.ERROR
        stopPlaybackCheckpoint()
        val errorMessage = when {
            what == MediaPlayer.MEDIA_ERROR_SERVER_DIED -> "服务器连接断开"
            extra == MediaPlayer.MEDIA_ERROR_IO -> "文件或网络读取失败"
            extra == MediaPlayer.MEDIA_ERROR_MALFORMED -> "音频数据损坏"
            extra == MediaPlayer.MEDIA_ERROR_UNSUPPORTED -> "不支持的格式"
            extra == MediaPlayer.MEDIA_ERROR_TIMED_OUT -> "操作超时"
            extra == -38 -> "播放器状态异常"
            else -> "未知错误 ($what, $extra)"
        }
        print2LogView("play错误: $errorMessage")
        meMediaPlaybackManager?.updatePlaybackState(
            PlaybackStateCompat.STATE_ERROR,
            lastKnownPositionMs.toLong(),
            errorMessage
        )

        val retryUrl = lastUrl
        val retryPosition = (lastKnownPositionMs - PLAYBACK_RESUME_REWIND_MS).coerceAtLeast(0)
        releasePlayer()

        if (retryUrl != null && retryCount < MAX_PLAYBACK_RETRIES) {
            retryCount++
            print2LogView("play将在1秒后重试，位置 ${retryPosition / 1000}秒")
            playbackHandler.postDelayed({
                if (generation == playbackGeneration && lastUrl == retryUrl && !isStop) {
                    startPlaybackAttempt(generation, retryUrl, retryPosition)
                }
            }, PLAYBACK_RETRY_DELAY_MS)
        } else {
            finishPlaybackAndGoNext(errorPlayer, true)
        }
    }

    private fun finishPlaybackAndGoNext(mediaPlayer: MediaPlayer, fromError: Boolean) {
        if (!fromError && mediaPlayer !== player) {
            return
        }

        stopPlaybackCheckpoint()
        releasePlayer()
        playerState = PlayerState.IDLE
        isStop = true
        playWhenReady = false
        resumeAfterAudioFocusGain = false
        retryCount = 0
        lastKnownPositionMs = 0
        playbackDurationMs = 0
        pendingSeekPositionMs = null
        clearLyrics()

        print2LogView(if (fromError) "play重试失败，跳到下一首" else "play播放完成")
        onPause()
        meMediaPlaybackManager?.updateMediaMetadata(1, null, null, null)
        meMediaPlaybackManager?.updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING, 1)
        if (!MainActivity.startService) {
            MainActivity.me?.finish()
        }
        toGo("next")
    }

    private fun startPreparedPlayer(mediaPlayer: MediaPlayer) {
        if (mediaPlayer !== player ||
            (playerState != PlayerState.PREPARED && playerState != PlayerState.PAUSED)
        ) {
            return
        }

        try {
            mediaPlayer.start()
            playerState = PlayerState.PLAYING
            playWhenReady = true
            // 小智会话中，中途起播的新歌也要立刻压到 duck 音量
            if (voiceDuckActive) {
                try {
                    mediaPlayer.setVolume(VOICE_DUCK_VOLUME, VOICE_DUCK_VOLUME)
                } catch (ignored: RuntimeException) {
                }
            }
            onPlay()
            startPlaybackCheckpoint()
            ysSetLedsValue(MeYsLed.EMPTY)
        } catch (e: IllegalStateException) {
            print2LogView("play启动失败: $e")
        }
    }

    private fun isCurrentPlayer(generation: Int, mediaPlayer: MediaPlayer): Boolean {
        return generation == playbackGeneration && mediaPlayer === player
    }

    private fun releasePlayer() {
        stopPlaybackCheckpoint()
        val oldPlayer = player
        player = null
        try {
            oldPlayer?.release()
        } catch (e: RuntimeException) {
            Log.w("MeService", "release player failed", e)
        }
    }

    private fun runOnPlaybackThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            playbackHandler.post(action)
        }
    }

    private fun startPlaybackCheckpoint() {
        playbackHandler.removeCallbacks(playbackCheckpointRunnable)
        playbackHandler.postDelayed(
            playbackCheckpointRunnable,
            PLAYBACK_CHECKPOINT_INTERVAL_MS
        )
    }

    private fun stopPlaybackCheckpoint() {
        playbackHandler.removeCallbacks(playbackCheckpointRunnable)
    }

    private fun snapshotPlaybackPosition() {
        if (!isPlayerControllable()) {
            return
        }
        try {
            lastKnownPositionMs = player?.currentPosition?.coerceAtLeast(0)
                ?: lastKnownPositionMs
        } catch (e: RuntimeException) {
            Log.w("MeService", "read playback position failed", e)
        }
    }

    private fun isPlayerControllable(): Boolean {
        return playerState == PlayerState.PREPARED ||
            playerState == PlayerState.PLAYING ||
            playerState == PlayerState.PAUSED ||
            playerState == PlayerState.BUFFERING
    }

    private fun isPlaybackPlaying(): Boolean {
        return playerState == PlayerState.PLAYING || playerState == PlayerState.BUFFERING
    }

    fun wakeScreenForAmbient() {
        Handler(Looper.getMainLooper()).post {
            wakeScreen()
        }
    }

    fun shouldShowPauseIcon(): Boolean = playWhenReady

    /** 当前是否真的在出声（可跨线程读） */
    fun isMediaPlaying(): Boolean =
        playbackStateSnapshot == PlayerState.PLAYING ||
            playbackStateSnapshot == PlayerState.BUFFERING

    /** 是否完全没有音源 */
    fun isMediaIdle(): Boolean = isStop

    /**
     * 小智会话期间压低音乐音量（等同 AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK 的处理），
     * 从开始对话一直压到会话结束（WS 断开）才还原。
     * 刻意不做"拦截 Go 起播"：它想播就播，只是这段时间声音小一点。
     */
    fun setVoiceDuck(on: Boolean) {
        if (voiceDuckActive == on) return
        voiceDuckActive = on
        runOnPlaybackThread {
            if (isPlayerControllable()) {
                val volume = if (on) VOICE_DUCK_VOLUME else 1.0f
                try {
                    player?.setVolume(volume, volume)
                } catch (e: RuntimeException) {
                    Log.w("MeService", "set duck volume failed", e)
                }
            }
        }
    }

    /**
     * 小智会话开始：暂停音乐，返回 true 表示这次真的停了（会话结束要接着播）。
     * 没在播就不动，免得把用户自己暂停的状态当成"要恢复"。
     */
    fun pauseMediaForVoice(): Boolean {
        if (!isMediaPlaying()) return false
        print2LogView("小智对话，暂停播放")
        requestPausePlayback()
        return true
    }

    /** 小智会话结束：把 [pauseMediaForVoice] 暂停的播放接着放下去 */
    fun resumeMediaAfterVoice() {
        if (isStop) return
        print2LogView("小智对话结束，恢复播放")
        requestResumePlayback()
    }

    /**
     * 小智 MCP 工具下发的命令（上游已按白名单校验过）。
     * 走和按键/语音同一条 Go 命令管道，所以界面上看到的状态是同步的。
     * Go 处理都很快，写完等 500ms 把它这期间打回来的行一起交给模型；
     * 一条都没有就说明这条命令本来就没输出。
     */
    fun runPlayerCommand(command: String): String {
        if (writer == null) return "播放器未启动"
        val buffer = StringBuilder()
        synchronized(commandOutputLock) { commandOutput = buffer }
        return try {
            if (!writeGoCommand(command)) {
                "播放器未启动"
            } else {
                // 等的时候不能占着 MeService 的锁，读管道那头的线程要用
                Thread.sleep(COMMAND_OUTPUT_WAIT_MS)
                val text = synchronized(commandOutputLock) { buffer.toString().trim() }
                if (text.isEmpty()) "已执行 $command" else text
            }
        } catch (e: Exception) {
            Log.w("MeService", "run player command failed", e)
            "命令发送失败：${e.message}"
        } finally {
            synchronized(commandOutputLock) { commandOutput = null }
        }
    }

    /** 只负责把命令写进 Go 的命令管道，返回 false 表示没写成功 */
    @Synchronized
    private fun writeGoCommand(command: String): Boolean {
        val out = writer ?: return false
        out.println(command)
        out.flush()
        return true
    }

    /** Go 给 App 的协议行都是大写开头的（PLAY / VOL / SONGID / WEATHER ...），这些不给模型看 */
    private fun isProtocolLine(line: String): Boolean =
        line.length >= 2 && line[0] in 'A'..'Z' && line[1] in 'A'..'Z'

    private fun requestPausePlayback(
        resumeAfterFocusGain: Boolean = false,
        // Go 自己发来的 PAUSE 不能再回传 pause1，否则这条命令会在
        // 下一次 PlayUrl 之后才被 Go 读到，把 IsPaused 永久置为 true
        notifyGo: Boolean = true
    ) {
        if (notifyGo) {
            toGo("pause1")
        }
        runOnPlaybackThread {
            this.resumeAfterAudioFocusGain = resumeAfterFocusGain && playWhenReady
            playWhenReady = false
            if (isPlaybackPlaying()) {
                snapshotPlaybackPosition()
                try {
                    player?.pause()
                    playerState = PlayerState.PAUSED
                    stopPlaybackCheckpoint()
                    onPause()
                } catch (e: IllegalStateException) {
                    print2LogView("play暂停失败: $e")
                }
            }
        }
    }

    private fun requestResumePlayback() {
        toGo("pause0")
        runOnPlaybackThread {
            playWhenReady = true
            resumeAfterAudioFocusGain = false
            val currentPlayer = player
            if (currentPlayer != null &&
                (playerState == PlayerState.PREPARED || playerState == PlayerState.PAUSED)
            ) {
                startPreparedPlayer(currentPlayer)
            }
        }
    }

    private fun pausePlaybackForAudioFocus() {
        requestPausePlayback(true)
    }

    private fun resumePlaybackAfterAudioFocus() {
        runOnPlaybackThread {
            if (isPlayerControllable()) {
                player?.setVolume(1.0f, 1.0f)
            }
            if (resumeAfterAudioFocusGain) {
                requestResumePlayback()
            }
            // 无论之前是否在播放，重新获得焦点后都要安排自动回到时钟
            scheduleAutoBackToClock()
        }
    }

    fun seekPlaybackTo(positionMs: Int) {
        runOnPlaybackThread {
            val target = positionMs.coerceAtLeast(0)
            if (playerState == PlayerState.PREPARING) {
                pendingSeekPositionMs = target
                return@runOnPlaybackThread
            }
            if (!isPlayerControllable()) {
                return@runOnPlaybackThread
            }
            try {
                val clampedTarget = if (playbackDurationMs > 0) {
                    target.coerceAtMost(playbackDurationMs - 1)
                } else {
                    target
                }
                player?.seekTo(clampedTarget)
                lastKnownPositionMs = clampedTarget
            } catch (e: IllegalStateException) {
                print2LogView("play跳转失败: $e")
            }
        }
    }

    fun getPlaybackPosition(): Int? {
        if (!isPlayerControllable()) {
            return null
        }
        return try {
            player?.currentPosition
        } catch (e: IllegalStateException) {
            null
        }
    }

    fun getPlaybackDuration(): Int {
        return if (isPlayerControllable()) playbackDurationMs else 0
    }

    fun getCurrentLyric(positionMs: Int?): String? {
        if (positionMs == null || !MeSettings.isEnabled(this, MeSettings.KEY_LYRICS)) {
            return null
        }
        val lines = lyricLines
        if (lines.isEmpty()) return null
        val result = lines.binarySearchBy(positionMs + 200) { it.timeMs }
        val index = if (result >= 0) result else -result - 2
        return if (index >= 0) lines[index].text else ""
    }

    /**
     * 当前这首歌有没有歌词（与播到哪一句无关），用于让歌词条常驻占位。
     */
    fun hasLyrics(): Boolean {
        return MeSettings.isEnabled(this, MeSettings.KEY_LYRICS) && lyricLines.isNotEmpty()
    }

    @Synchronized
    fun syncLyricsSetting() {
        if (!MeSettings.isEnabled(this, MeSettings.KEY_LYRICS)) {
            clearLyrics(false)
            return
        }
        val songId = currentSongId
        if (songId != null && lyricSongId != songId) {
            loadLyrics(songId)
        }
    }

    @Synchronized
    private fun clearLyrics(clearSongId: Boolean = true) {
        lyricVersion++
        lyricSongId = null
        lyricLines = emptyList()
        if (clearSongId) {
            currentSongId = null
            hasPendingSongId = false
        }
    }

    private fun loadLyrics(songId: String) {
        val version = synchronized(this) {
            lyricVersion++
            lyricSongId = songId
            lyricLines = emptyList()
            lyricVersion
        }
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = URL("http://music.163.com/api/song/lyric?lv=-1&tv=-1&id=$songId")
                    .openConnection() as HttpURLConnection
                UnsafeHttps.configure(connection)
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.setRequestProperty("User-Agent", HTTP_USER_AGENT)
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("HTTP ${connection.responseCode}")
                }
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val result = if (json.optBoolean("pureMusic")) {
                    emptyList()
                } else {
                    val original = parseLyric(json.optJSONObject("lrc")?.optString("lyric").orEmpty())
                    val translated = parseLyric(json.optJSONObject("tlyric")?.optString("lyric").orEmpty())
                        .associateBy { it.timeMs }
                    val hasTranslation = translated.values.any { it.text.isNotBlank() }
                    original.map { line ->
                        val translatedLine = translated[line.timeMs]
                        val translation = translatedLine?.text
                        when {
                            line.text.isBlank() -> line
                            !translation.isNullOrBlank() && translation != line.text -> {
                                line.copy(text = line.text + "\n" + translation)
                            }
                            translatedLine != null -> line
                            // 双语歌词可能只有部分原文行能在 tlyric 中找到对应翻译，
                            // 这些行也保留双语布局所需的末尾换行；单语歌词不补换行。
                            hasTranslation -> line.copy(text = line.text + "\n")
                            else -> line
                        }
                    }
                }

                // 切歌后丢弃旧请求返回的歌词
                synchronized(this) {
                    if (version == lyricVersion && currentSongId == songId &&
                        MeSettings.isEnabled(this, MeSettings.KEY_LYRICS)) {
                        lyricLines = result
                    }
                }
            } catch (e: Exception) {
                print2LogView("获取歌词失败: ${e.message}")
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun parseLyric(text: String): List<LyricLine> {
        val timeRegex = Regex("\\[(\\d+):(\\d{2})(?:[.:](\\d{1,3}))?]")
        val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val result = mutableListOf<LyricLine>()

        // 一行可能对应多个时间点
        text.lineSequence().forEach { row ->
            val times = timeRegex.findAll(row).toList()
            if (times.isEmpty()) return@forEach
            val content = row.substring(times.last().range.last + 1).trim()
            times.forEach { match ->
                val fraction = match.groupValues[3].padEnd(3, '0').take(3).toInt()
                val timeMs = (match.groupValues[1].toInt() * 60000 +
                    match.groupValues[2].toInt() * 1000 + fraction + offset).coerceAtLeast(0)
                result.add(LyricLine(timeMs, content))
            }
        }
        return result.groupBy { it.timeMs }
            .map { it.value.last() }
            .sortedBy { it.timeMs }
    }

    private fun stopPlayback(resetProgress: Boolean = true) {
        clearLyrics()
        runOnPlaybackThread {
            playbackGeneration++
            releasePlayer()
            playerState = PlayerState.IDLE
            playWhenReady = false
            resumeAfterAudioFocusGain = false
            retryCount = 0
            pendingSeekPositionMs = null
            isStop = true
            if (resetProgress) {
                lastKnownPositionMs = 0
                playbackDurationMs = 0
            }
            onPause()
            meMediaPlaybackManager?.updateMediaMetadata(0, null, null, null)
            meMediaPlaybackManager?.updatePlaybackState(PlaybackStateCompat.STATE_CONNECTING, 0)
            ysSetLedsValue(MeYsLed.EMPTY)
        }
    }

    private fun requestTrackChange(action: String) {
        // 任何来源（按钮/媒体控件）的停止，只要已经处于停止态且小智开启，就用唤醒小智代替stop
        if (action == "stop" && isStop && MeSettings.isEnabled(this, MeSettings.KEY_XIAOZHI)) {
            print2LogView("停止状态下 唤醒小智")
            XiaozhiAssistant.start()
            return
        }
        clearLyrics()
        runOnPlaybackThread {
            playbackGeneration++
            releasePlayer()
            playerState = PlayerState.IDLE
            playWhenReady = false
            resumeAfterAudioFocusGain = false
            retryCount = 0
            lastKnownPositionMs = 0
            playbackDurationMs = 0
            pendingSeekPositionMs = null
            isStop = true
            toGo(action)
        }
    }

    /**
     * 在shell中运行主程序
     */
    private fun runShell() {
        shellThread = Thread(Runnable {
            try {
                // 因为经常只更新android app，传个版本号进去
                val versionName = packageManager.getPackageInfo(packageName, 0).versionName
                // 输入start可以启动 exit可以退出
                val workdayAlarmClock = applicationInfo.nativeLibraryDir + "/libWorkdayAlarmClock.so app "
                val command = "alias exit='echo EXIT'\n" +
                        "alias run='cd " + filesDir.absolutePath + ";pwd;getprop ro.product.cpu.abilist;getprop ro.product.cpu.abi;ip a|grep \"et \";" + workdayAlarmClock + versionName + "||" + workdayAlarmClock + "'\n" +
                        "run"
                shellProcess = ProcessBuilder("sh")
                    .redirectErrorStream(true)
                    .start()

                val reader = BufferedReader(InputStreamReader(shellProcess!!.inputStream))
                writer = PrintWriter(shellProcess!!.outputStream)
                send2Shell(command)

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    try {
                        checkAction(line, true)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        print2LogView("Shell解析出错 $e")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                print2LogView("Shell运行出错 $e")
            }
        })
        shellThread!!.start()
    }

    private fun readProcessOutput(process: Process) {
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                try {
                    checkAction(line, true)
                } catch (e: Exception) {
                    e.printStackTrace()
                    print2LogView("进程输出解析出错 $e")
                }
            }
        }
    }

    /**
     * 向shell输入
     */
    fun send2Shell(cmd: String) {
        try {
            // 内部指令
            if (cmd.startsWith("ysled ")) {
                val n = cmd.substring(6).split("-")
                print2LogView("设置一说led ${n[0]}到${n.last()} $mBreathLedsManager")
                ysSetLedsValue(n[0].toInt(), n.last().toInt())
                return
            } else if (cmd.startsWith("ysledre ")) {
                val n = cmd.substring(8).split("-")
                print2LogView("设置一说led重复 ${n[0]}到${n.last()} 延迟${n[1]}ms ")
                ysSetLedsValue(n[0].toInt(), n.last().toInt(), n[1].toLong(), true)
                return
            } else if (cmd == "app") {
                // 打开应用列表
                val appListIntent = Intent(this, AppListActivity::class.java)
                startActivity(appListIntent)
                return
            }
            writer?.println(cmd)
            writer?.flush()
        } catch (e: Exception) {
            e.printStackTrace()
            print2LogView("Shell执行出错 $e")
        }
    }


    /**
     * 响应按键 (统一入口)
     * 有长按检测，UI按钮不能用这个
     * @param keyCode 按键码
     * @param isDown true=按下, false=抬起
     */
    fun keyHandle(keyCode: Int, isDown: Boolean): Boolean {
        if (cameraHttpServer?.handleSensorKeyEvent(keyCode, isDown) == true) {
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
            || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            return handleMediaPlayKey(keyCode, isDown)
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT
            || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return handleMediaTrackKey(keyCode, isDown)
        }
        // 维护多击键 一键 1key键
        val isMultiClick = keyCode in setOf(
            // 叮咚Play
            KeyEvent.KEYCODE_SOFT_SLEEP,
            // 照照乐
            KeyEvent.KEYCODE_ZENKAKU_HANKAKU,
            if (isBonjour) KeyEvent.KEYCODE_VOLUME_MUTE else 0,
            if (Build.MODEL == "S14G") 0 else KeyEvent.KEYCODE_FOCUS,
            // 国美云
            KeyEvent.KEYCODE_F11
        ) || (keyCode == KeyEvent.KEYCODE_POWER &&
                MeSettings.isEnabled(this, MeSettings.KEY_NO_SCREEN))
        val isVolKey = keyCode in setOf(
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN
        )

        // === 按下 ===
        if (isDown) {
            if (isMultiClick || isVolKey) {
                if (multiClickKeyCode == keyCode) {
                    return true
                }
                if (multiClickKeyCode == KeyEvent.KEYCODE_VOLUME_UP && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                    || multiClickKeyCode == KeyEvent.KEYCODE_VOLUME_DOWN && keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    // 同时按下两个音量键
                    resetMultiClickState()
                    print2LogView("两个音量键 停止")
                    ClockActivity.me?.showMsg("停止")
                    toGo("stop")
                    return true
                }
                // 取消已有窗口定时器
                if (multiClickState == 2) {
                    clickWindowRunnable?.let { multiClickHandler.removeCallbacks(it) }
                    clickWindowRunnable = null
                }
                multiClickKeyCode = keyCode
                longPressDownTime = System.currentTimeMillis()
                longPressTriggered = false
                multiClickState = 1 // KEY_DOWN
                // 启动900ms长按定时器
                longPressRunnable = Runnable {
                    if (multiClickKeyCode != keyCode || multiClickState != 1) {
                        return@Runnable
                    }
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                        ClockActivity.me?.showMsg("下一首")
                        print2LogView("长按音量加 下一首")
                        requestTrackChange("next")
                        longPressTriggered = true
                    } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                        ClockActivity.me?.showMsg("上一首")
                        print2LogView("长按音量减 上一首")
                        requestTrackChange("prev")
                        longPressTriggered = true
                    } else {
                        print2LogView("长按停止")
                        ClockActivity.me?.showMsg("停止")
                        requestTrackChange("stop")
                        longPressTriggered = true
                    }
                    multiClickState = 3 // LONG_PRESSED
                }
                multiClickHandler.postDelayed(longPressRunnable!!, 900)
                return true
            }
            // 非多击/音量键: 按下立即响应
            return keyHandleAction(keyCode)
        }

        // === 抬起 ===
        if (isMultiClick) {
            longPressRunnable?.let { multiClickHandler.removeCallbacks(it) }
            longPressRunnable = null
            if (longPressTriggered) {
                if (keyCode == KeyEvent.KEYCODE_ZENKAKU_HANKAKU) {
                    val holdTime = System.currentTimeMillis() - longPressDownTime
                    if (holdTime > 11000) {
                        try {
                            val intent = Intent()
                            intent.component = ComponentName("com.zyyme.moonlight.unofficial", "com.limelight.PcView")
                            startActivity(intent)
                        } catch (e: Exception) {
                            print2LogView("启动咩Moonlight失败")
                            e.printStackTrace()
                        }
                    } else if (holdTime > 9000) {
                        try {
                            val intent = Intent()
                            intent.component = ComponentName("com.example.kenna", "com.example.kenna.activity.SecondActivity")
                            startActivity(intent)
                        } catch (e: Exception) {
                            print2LogView("启动小魔镜应用失败")
                            e.printStackTrace()
                        }
                    } else if (holdTime > 7000) {
                        startAp()
                    }
                }
                resetMultiClickState()
                return true
            }
            multiClickCount++
            multiClickState = 2 // WAIT_CLICKS
            clickWindowRunnable = Runnable {
                processMultiClick(multiClickKeyCode, multiClickCount)
            }
            multiClickHandler.postDelayed(clickWindowRunnable!!, 500)
            return true
        }

        if (isVolKey) {
            longPressRunnable?.let { multiClickHandler.removeCallbacks(it) }
            longPressRunnable = null
            if (longPressTriggered) {
                resetMultiClickState()
                return true
            }
            // 立即调音量
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            } else {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            }
            val per = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 100).toInt()
            ClockActivity.me?.showMsg("音量${per}%")
            print2LogView("音量${if (keyCode in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_VOLUME_UP)) "加" else "减"} ${per}%")
            resetMultiClickState()
            return true
        }

        // 其他键 up不处理
        return false
    }

    /**
     * 处理实体/蓝牙上一首、下一首键。短按在抬起时切歌，长按后连续 seek。
     */
    private fun handleMediaTrackKey(keyCode: Int, isDown: Boolean): Boolean {
        if (isDown) {
            // 部分设备会重复发送 ACTION_DOWN，保持同一次按键状态即可。
            if (mediaTrackKeyCode == keyCode) return true
            stopMediaTrackLongPress()
            mediaTrackKeyCode = keyCode
            mediaTrackLongPressTriggered = false
            mediaTrackLongPressRunnable = Runnable {
                if (mediaTrackKeyCode != keyCode || mediaTrackLongPressTriggered) return@Runnable
                mediaTrackLongPressTriggered = true
                val direction = if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                print2LogView(if (direction > 0) "长按媒体按键 快进" else "长按媒体按键 快退")
                seekMediaTrack(direction)
                mediaTrackSeekRunnable = object : Runnable {
                    override fun run() {
                        if (mediaTrackKeyCode != keyCode || !mediaTrackLongPressTriggered) return
                        seekMediaTrack(direction)
                        multiClickHandler.postDelayed(this, MEDIA_TRACK_SEEK_INTERVAL_MS)
                    }
                }
                multiClickHandler.postDelayed(mediaTrackSeekRunnable!!, MEDIA_TRACK_SEEK_INTERVAL_MS)
            }
            multiClickHandler.postDelayed(mediaTrackLongPressRunnable!!, MEDIA_TRACK_LONG_PRESS_DELAY_MS)
            return true
        }

        if (mediaTrackKeyCode != keyCode) return true
        val wasLongPress = mediaTrackLongPressTriggered
        stopMediaTrackLongPress()
        if (!wasLongPress) {
            if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                ClockActivity.me?.showMsg("下一首")
                print2LogView("媒体按键 下一首")
                requestTrackChange("next")
            } else {
                ClockActivity.me?.showMsg("上一首")
                print2LogView("媒体按键 上一首")
                requestTrackChange("prev")
            }
        }
        return true
    }

    private fun seekMediaTrack(direction: Int) {
        getPlaybackPosition()?.let { position ->
            seekPlaybackTo(position + direction * MEDIA_TRACK_SEEK_STEP_MS)
        }
    }

    /**
     * 实体播放键：
     * - 双击（确认键 / 播放暂停 / 播放）= 唤醒小智 / 打断（仅小智模块启用时，单击会因此延后 [XIAOZHI_DOUBLE_CLICK_WINDOW_MS]）
     * - 长按 900ms = 一键（保持原样）
     * - 短按 = 播放/暂停（保持原样）
     *
     * 只有多击键和播放控制键会进这个方法
     */
    private fun handleMediaPlayKey(keyCode: Int, isDown: Boolean): Boolean {
        val xiaozhiEnabled = MeSettings.isEnabled(this, MeSettings.KEY_XIAOZHI)
        if (isDown) {
            if (mediaPlayKeyCode == keyCode) return true
            if (xiaozhiEnabled &&
                centerClickGate.onDown(
                    System.currentTimeMillis(),
                    true
                ) == DoubleClickGate.DownResult.DOUBLE
            ) {
                // 第一击排队的单击要作废，这一击的长按也不要
                stopMediaPlayLongPress()
                print2LogView("双击 唤醒小智")
                XiaozhiAssistant.start()
                return true
            }
            stopMediaPlayLongPress()
            mediaPlayKeyCode = keyCode
            mediaPlayLongPressTriggered = false
            mediaPlayLongPressRunnable = Runnable {
                if (mediaPlayKeyCode != keyCode || mediaPlayLongPressTriggered) return@Runnable
                mediaPlayLongPressTriggered = true
                print2LogView("长按媒体按键 一键")
                ClockActivity.me?.showMsg("一键")
                toGo("1key")
            }
            multiClickHandler.postDelayed(mediaPlayLongPressRunnable!!, MEDIA_TRACK_LONG_PRESS_DELAY_MS)
            return true
        }
        if (mediaPlayKeyCode != keyCode) return true
        val wasLongPress = mediaPlayLongPressTriggered
        stopMediaPlayLongPress()
        if (wasLongPress) {
            centerClickGate.reset()
            return true
        }
        if (xiaozhiEnabled) {
            when (centerClickGate.onUp(System.currentTimeMillis(), true, false)) {
                DoubleClickGate.UpResult.DEFER -> {
                    val runnable = Runnable {
                        centerSingleClickRunnable = null
                        if (centerClickGate.consumeSingle()) keyHandleAction(keyCode)
                    }
                    centerSingleClickRunnable = runnable
                    multiClickHandler.postDelayed(runnable, XIAOZHI_DOUBLE_CLICK_WINDOW_MS)
                    return true
                }
                else -> return true
            }
        }
        keyHandleAction(keyCode)
        return true
    }

    private fun stopMediaPlayLongPress() {
        mediaPlayLongPressRunnable?.let { multiClickHandler.removeCallbacks(it) }
        mediaPlayLongPressRunnable = null
        centerSingleClickRunnable?.let { multiClickHandler.removeCallbacks(it) }
        centerSingleClickRunnable = null
        mediaPlayKeyCode = 0
        mediaPlayLongPressTriggered = false
    }

    private fun stopMediaTrackLongPress() {
        mediaTrackLongPressRunnable?.let { multiClickHandler.removeCallbacks(it) }
        mediaTrackSeekRunnable?.let { multiClickHandler.removeCallbacks(it) }
        mediaTrackLongPressRunnable = null
        mediaTrackSeekRunnable = null
        mediaTrackKeyCode = 0
        mediaTrackLongPressTriggered = false
    }

    /**
     * 非多击/音量键的立即响应逻辑
     * 直接触发，跳过长按检测
     */
    fun keyHandleAction(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (!isStop) {
                    print2LogView("媒体按键 播放暂停")
                    if (playWhenReady) {
                        ClockActivity.me?.showMsg("暂停")
                        requestPausePlayback()
                    } else {
                        ClockActivity.me?.showMsg("播放")
                        requestResumePlayback()
                    }
                    return true
                } else {
                    print2LogView("媒体按键 一键")
                    ClockActivity.me?.showMsg("一键")
                    toGo("1key")
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                print2LogView("媒体按键 播放")
                if (!isStop) {
                    ClockActivity.me?.showMsg("播放")
                    requestResumePlayback()
                } else {
                    ClockActivity.me?.showMsg("一键")
                    toGo("1key")
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                print2LogView("媒体按键 暂停")
                ClockActivity.me?.showMsg("暂停")
                if (!isStop) {
                    requestPausePlayback()
                }
                return true
            }
            2147483645, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isPlaybackPlaying() || keyCode == 2147483645) {
                    ClockActivity.me?.showMsg("下一首")
                    print2LogView("媒体按键 下一首")
                    requestTrackChange("next")
                } else {
                    ClockActivity.me?.showMsg("触发停止")
                    print2LogView("媒体按键 下一首 触发停止")
                    requestTrackChange("stop")
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_DPAD_LEFT -> {
                ClockActivity.me?.showMsg("上一首")
                print2LogView("媒体按键 上一首")
                requestTrackChange("prev")
                return true
            }
            // 自定义音量键（按钮栏用，立即响应，无长按）
            2147483647, 2147483646, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                adjustVolume(keyCode)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                ClockActivity.me?.showMsg("停止")
                print2LogView("媒体按键 停止")
                requestTrackChange("stop")
                return true
            }
            KeyEvent.KEYCODE_FOCUS -> {
                print2LogView("媒体按键 鼻子")
                requestTrackChange("stop")
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (ysLedStatus()) {
                    print2LogView("菜单 一说宝宝摸头")
                } else if (isBonjour) {
                    // 触摸太灵了，不用
                } else {
                    print2LogView("菜单 停止")
                    requestTrackChange("stop")
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                print2LogView("媒体按键 快进")
                getPlaybackPosition()?.let { seekPlaybackTo(it + 5000) }
                return true
            }
        }
        print2LogView("未知按键 $keyCode")
        return false
    }

    /**
     * 多击窗口到期处理
     */
    private fun processMultiClick(keyCode: Int, count: Int) {
        when (count) {
            1 -> {
                if (!isStop) {
                    if (playWhenReady) {
                        ClockActivity.me?.showMsg("暂停")
                        print2LogView("单击 暂停")
                        requestPausePlayback()
                    } else {
                        ClockActivity.me?.showMsg("播放")
                        print2LogView("单击 播放")
                        requestResumePlayback()
                    }
                } else {
                    print2LogView("单击 一键")
                    ClockActivity.me?.showMsg("一键")
                    toGo("1key")
                }
            }
            2 -> {
                ClockActivity.me?.showMsg("下一首")
                print2LogView("双击 下一首")
                requestTrackChange("next")
            }
            3 -> {
                ClockActivity.me?.showMsg("上一首")
                print2LogView("三击 上一首")
                requestTrackChange("prev")
            }
            4 -> {
                // 只有播放键/确认键能用的设备靠这个唤醒；未启用小智时保持原来的无动作
                if (MeSettings.isEnabled(this, MeSettings.KEY_XIAOZHI)) {
                    print2LogView("四击 唤醒小智")
                    XiaozhiAssistant.start()
                }
            }
        }
        resetMultiClickState()
    }

    private fun resetMultiClickState() {
        multiClickKeyCode = 0
        longPressRunnable?.let { multiClickHandler.removeCallbacks(it) }
        clickWindowRunnable?.let { multiClickHandler.removeCallbacks(it) }
        multiClickState = 0
        multiClickCount = 0
        longPressTriggered = false
        longPressRunnable = null
        clickWindowRunnable = null
    }

    private fun adjustVolume(keyCode: Int) {
        if (keyCode in setOf(2147483647, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_VOLUME_UP)) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        } else {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        }
        val per = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 100).toInt()
        ClockActivity.me?.showMsg("音量${per}%")
        print2LogView("按键 音量${if (keyCode in setOf(2147483647, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_VOLUME_UP)) "加" else "减"} ${per}%")
    }

    /**
     * 开热点
     */
    fun startAp() {
        try {
            // 用反射开热点
            val wifiManager: WifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            var wifiCfg: WifiConfiguration? = null
            try {
                // 获取系统热点设置  新系统大概率是不行的
                val method: Method = WifiManager::class.java.getMethod("getWifiApConfiguration")
                wifiCfg = method.invoke(wifiManager) as? WifiConfiguration
            } catch (e: Exception) {
                print2LogView("获取系统设置出错，使用Sparkle密码password")
                wifiCfg = WifiConfiguration().apply {
                    SSID = "Sparkle"
                    preSharedKey = "password"
                    hiddenSSID = false
                }
                e.printStackTrace()
            }
            val method: Method = wifiManager.javaClass.getMethod(
                "setWifiApEnabled",
                WifiConfiguration::class.java,
                Boolean::class.javaPrimitiveType
            )
            if (method.invoke(wifiManager, wifiCfg, true) as Boolean) {
                print2LogView("热点已开启")
                ClockActivity.me?.showMsg("热点已开启")
                return
            } else {
                print2LogView("热点开启失败")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            print2LogView("热点开启出错，没有修改系统设置的权限")
        }
        try {
            // 打开热点设置
            Handler(Looper.getMainLooper()).post {
                print2LogView("如果没有自动点击，请确保无障碍服务已开启")
                Toast.makeText(this,"如果没有自动点击，请确保无障碍服务已开启", Toast.LENGTH_LONG).show()
                startActivity(Intent().apply {
                    component = ComponentName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        } catch (e: Exception) {
            e.printStackTrace()
            print2LogView("打开热点设置出错：${e.message}")
            try {
                startActivity(Intent().apply {
                    component = ComponentName("com.android.settings", "com.android.settings.Settings\$NetworkDashboardActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Exception) {
                e.printStackTrace()
                print2LogView("打开网络设置出错：${e.message}")
            }
        }
    }



    /**
     * 调用Go Api
     */
    fun toGo(action : String) {
        // 改用shell通信
        writer?.println(action)
        writer?.flush()
//        Thread(Runnable {
//            try {
//                val conn = URL("http://127.0.0.1:8080/" + action).openConnection() as HttpURLConnection
//                conn.requestMethod = "GET"
//                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
//                    print2LogView("Failed : HTTP error code : " + conn.responseCode)
//                }
//                val br = BufferedReader(InputStreamReader(conn.inputStream))
//                var output: String?
//                while (br.readLine().also { output = it } != null) {
////                    checkAction(output)
//                }
//                conn.disconnect()
//            } catch (e: Exception) {
//                e.printStackTrace()
//                print2LogView(e.toString())
//            }
//        }).start()
    }

    /**
     * 界面恢复到前台时补一次 wake。Go 侧会去重，收到 wake 后重新下发 NEXTWAKE，
     * 用来兜住「NEXTWAKE 闹钟被系统/ROM 清掉，之后永远不会再唤醒」的情况。
     * 60s 内只补一次，避免来回切界面反复触发 Go 的唤醒流程。
     */
    @Synchronized
    fun requestNextWakeRefresh() {
        if (writer == null) return
        val now = System.currentTimeMillis()
        if (now - lastNextWakeRefreshAt < NEXT_WAKE_REFRESH_INTERVAL_MS) {
            return
        }
        lastNextWakeRefreshAt = now
        acquireNextWakeLock(applicationContext)
        toGo("wake")
        print2LogView("界面恢复，补一次唤醒检查")
    }

    @Synchronized
    fun requestWeatherIfNeeded(): Boolean {
        val now = System.currentTimeMillis()
        if (writer == null || (now >= lastWeatherRequestAt &&
                    now - lastWeatherRequestAt < WEATHER_UPDATE_INTERVAL_MS)
        ) {
            return false
        }
        lastWeatherRequestAt = now
        toGo("weather")
        return true
    }

    @Synchronized
    fun requestTodoIfNeeded(): Boolean {
        val url = MeSettings.getTodoUrl(this)
        if (url.isEmpty()) {
            if (todoText.isNotEmpty()) publishTodoText("")
            lastTodoRequestAt = 0L
            return false
        }
        val now = System.currentTimeMillis()
        if (todoRequestInFlight || now - lastTodoRequestAt < WEATHER_UPDATE_INTERVAL_MS ||
            !isDeskScreenInteractive()
        ) {
            return false
        }
        lastTodoRequestAt = now
        todoRequestInFlight = true
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                UnsafeHttps.configure(connection)
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.requestMethod = "GET"
                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val result = if (responseCode in 200..299) {
                    formatTodoResponse(body)
                } else {
                    if (body.isBlank()) "HTTP $responseCode" else body
                }
                if (MeSettings.getTodoUrl(this) == url) {
                    publishTodoText(result)
                }
            } catch (e: Exception) {
                if (MeSettings.getTodoUrl(this) == url) {
                    publishTodoText(e.message ?: e.toString())
                }
            } finally {
                connection?.disconnect()
                todoRequestInFlight = false
            }
        }.start()
        return true
    }

    fun refreshTodoNow() {
        synchronized(this) {
            lastTodoRequestAt = 0L
        }
        requestTodoIfNeeded()
    }

    private fun formatTodoResponse(body: String): String {
        return try {
            val array = JSONArray(body)
            buildString {
                for (index in 0 until array.length()) {
                    if (index > 0) append('\n')
                    append(index + 1).append(". ").append(array.opt(index)?.toString().orEmpty())
                }
            }
        } catch (_: Exception) {
            body
        }
    }

    private fun publishTodoText(value: String) {
        todoText = value
        mainHandler.post {
            if (isDeskScreenInteractive()) {
                DeskActivity.me?.updateTodoText(value)
            }
        }
    }

    private fun isDeskScreenInteractive(): Boolean {
        val desk = DeskActivity.me ?: return false
        if (!desk.isActivityStarted) return false
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            powerManager.isInteractive
        } else {
            powerManager.isScreenOn
        }
    }

    /**
     * 重启自己
     */
    fun restartApp() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TASK
//                    or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        )
        var pendingIntent: PendingIntent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        } else {
            pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE);
        }
        val manager = getSystemService(ALARM_SERVICE) as AlarmManager
        manager[AlarmManager.RTC, System.currentTimeMillis() + 2000] = pendingIntent
//        System.exit(0)
        onDestroy()
        Thread.sleep(1000)
        stopSelf()
    }
}
