package com.zyyme.workdayalarmclock

import android.annotation.SuppressLint
import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter


class MeService : Service() {
    companion object {
//        var mediaButtonReceiverHandler: Handler? = null
        var me: MeService? = null

        var logBuilder = StringBuilder()

        const val ACTION_PLAY = "com.zyyme.workdayalarmclock.ACTION_PLAY"
        const val ACTION_NEXT = "com.zyyme.workdayalarmclock.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.zyyme.workdayalarmclock.ACTION_PREVIOUS"
        const val ACTION_STOP = "com.zyyme.workdayalarmclock.ACTION_STOP"
        const val ACTION_WAKE = "com.zyyme.workdayalarmclock.ACTION_WAKE"
        const val NOTIFICATION_ID = 1

        // 这些设备将默认启用时钟模式
        // getprop ro.product.model
        //                                 绿色陪伴音箱，叮咚play
        val clockModeModel = listOf<String>("HPN_XH", "cht_mrd")
    }

    var meMediaPlaybackManager: MeMediaPlaybackManager? = null
    var player : MediaPlayer? = null
    var writer : PrintWriter? = null
    var lastUrl : String? = null
    var shellThread : Thread? = null
    var isStop = true
    var mBreathLedsManager: Any? = null
    var wakeLock: PowerManager.WakeLock? = null
    var wakeLockPlay: PowerManager.WakeLock? = null
    var wifiLock: WifiManager.WifiLock? = null
    var shellProcess: Process? = null
    var loadProgress: Int = 0

    private var notificationBuilder: NotificationCompat.Builder? = null
    private var notificationManager: NotificationManager? = null
    private var wakePendingIntent: PendingIntent? = null

    override fun onBind(intent: Intent): IBinder {
        me = this
        TODO("Return the communication channel to the service.")
    }

    @SuppressLint("WrongConstant")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        me = this

        // 初始化音频服务
        meMediaPlaybackManager = MeMediaPlaybackManager(this)

        // 保活通知 8.0开始channel不是个字符串 不会保持唤醒
        val channelId = "me_bg"
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val notificationChannel = NotificationChannel(channelId, "保活通知", importance).apply {
                description = "保活通知"
            }
            notificationManager?.createNotificationChannel(notificationChannel)
        }

        val mainIntent: Intent = Intent(applicationContext, ClockActivity::class.java)
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
                    android.R.drawable.ic_media_previous,
                    "上一首",
                    createPendingIntentForAction(ACTION_PREVIOUS)
                )
            )
            notificationBuilder?.addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_play,
                    "播放",
                    createPendingIntentForAction(ACTION_PLAY)
                )
            )
            notificationBuilder?.addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_next,
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
            // 设置显示的按钮 缩小的时候
            mediaStyle.setShowActionsInCompactView(0, 1, 2, 3)
            notificationBuilder?.setStyle(mediaStyle)
        }

        try {
            startForeground(NOTIFICATION_ID, notificationBuilder?.build())
        } catch (e: Exception) {
            print2LogView("切换前台服务失败 $e")
            e.printStackTrace()
        }

        // 如果不需要启动Go服务，这个服务将只有播放音频url的功能
        if (!MainActivity.startService) {
            return super.onStartCommand(intent, flags, startId)
        }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

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
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // 短暂失去焦点，可以降低音量 (如果你的播放器支持)
                    print2LogView("AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, ducking volume.")
                    // player?.setVolume(0.3f, 0.3f)
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    // 重新获得焦点，恢复播放 (如果之前暂停了或降低了音量)
                    print2LogView("AUDIOFOCUS_GAIN, resuming playback or restoring volume.")
                    // player?.setVolume(1.0f, 1.0f) // 恢复音量
                    // 如果之前是因为短暂丢失焦点而暂停，可以考虑恢复播放
                    // 但要注意不要与用户的明确暂停冲突
                    // if (wasPausedDueToTransientLoss) {
                    //    onPlayRequest()
                    // }
                }
            }
        }

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

        return super.onStartCommand(intent, flags, startId)
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

    fun updateNotificationTitle(newTitle: String) {
        if (notificationBuilder != null && notificationManager != null) {
            notificationBuilder?.setContentTitle(newTitle)
            notificationManager?.notify(NOTIFICATION_ID, notificationBuilder?.build())
        }
    }

    override fun onDestroy() {
        shellProcess?.destroy()
        shellThread?.interrupt()
        MainActivity.me?.finish()
        wakeLock?.release()
        wakeLockPlay?.release()
        wifiLock?.release()
        stopForeground(true)
        if (wakePendingIntent != null) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(wakePendingIntent)
        }
        super.onDestroy()
        // 不知道为什么服务进程不退出 给他退出强制回收掉
        System.exit(0)
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


    fun print2LogView(s:String) {
        Log.d("logView", s)
        logBuilder.append(s + "\n")
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
        MainActivity.me?.show2LogView()
    }

    /**
     * Go控制台输出调用App
     */
    fun checkAction(s: String?) {
        if (s != null) {
            if (s.startsWith("PLAY ")) {
                playUrl(s.substring(5))
            } else if (s.startsWith("VOL ")) {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val per = s.substring(4).toInt()
                val set = (max * per / 100)
                print2LogView("音量最高" + max + "对应" + per + "%设置为" + set)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, set, AudioManager.FLAG_SHOW_UI);
            } else if (s.startsWith("ECHO ")) {
                val msg = s.substring(5)
                updateNotificationTitle(msg)
                ClockActivity.me?.showMsg(msg)
                meMediaPlaybackManager?.updateMediaMetadata(65535, msg, null, null)
            } else if (s == "STOP") {
                print2LogView("停止播放")
                isStop = true
                player?.release()
                onPause()
                player = null
                meMediaPlaybackManager?.updateMediaMetadata(0, null,null,null)
                meMediaPlaybackManager?.updatePlaybackState(PlaybackStateCompat.STATE_CONNECTING, 0)
                ysSetLedsValue(MeYsLed.EMPTY)
            } else if (s == "PAUSE") {
                print2LogView("暂停播放")
                player?.pause()
                onPause()
            } else if (s == "RESUME") {
                print2LogView("恢复播放")
                if (!isStop) {
                    player?.start()
                    onPlay()
                } else if (lastUrl != null) {
                    playUrl(lastUrl!!)
                }
            } else if (s == "VOLP") {
                print2LogView("音量加")
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE,
                    AudioManager.FLAG_SHOW_UI);
            } else if (s == "VOLM") {
                print2LogView("音量减")
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(
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
                wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                    newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "workDayAlarmClock::MeService").apply {
                        acquire()
                        print2LogView("已启用CPU唤醒锁")
                    }
                }
                wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL, "workDayAlarmClock:WifiLock")
                wifiLock?.acquire()
            } else if (s == "ALARMON") {
                if (wakePendingIntent == null) {
                    // 每分钟唤醒一下的闹钟
                    val alarmManager = applicationContext.getSystemService(ALARM_SERVICE) as AlarmManager
                    wakePendingIntent = createPendingIntentForAction(ACTION_WAKE)
                    // 秒对齐 实测对的不是很齐
                    alarmManager.setRepeating(
                        AlarmManager.RTC_WAKEUP,
                        60000 - System.currentTimeMillis() % 60000, 60000,
                        wakePendingIntent
                    )
                    print2LogView("已启用每分钟唤醒")
                }
            } else if (s == "ALARMOFF") {
                if (wakePendingIntent != null) {
                    val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
                    alarmManager.cancel(wakePendingIntent)
                    wakePendingIntent = null
                    print2LogView("已关闭每分钟唤醒")
                }
            } else if (s == "ALARM") {
                val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val adminComponentName = ComponentName(this, MeDeviceAdminReceiver::class.java)
                if (devicePolicyManager.isAdminActive(adminComponentName)) {
                    // 闹钟时，有关闭屏幕权限再打开屏幕
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                    val intent = Intent(this, ClockActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    intent.putExtra("clockMode", true)
                    startActivity(intent)
                    print2LogView("已亮屏")
                }
            } else if (s == "SCREENON") {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                val intent = Intent(this, ClockActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                intent.putExtra("clockMode", true)
                startActivity(intent)
                print2LogView("已亮屏")
            } else if (s == "SCREENOFF") {
                val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val adminComponentName = ComponentName(this, MeDeviceAdminReceiver::class.java)
                if (devicePolicyManager.isAdminActive(adminComponentName)) {
                    try {
                        devicePolicyManager.lockNow()
                        print2LogView("已锁屏")
                    } catch (e: Exception) {
                        print2LogView("锁屏失败: ${e.message}")
                    }
                } else {
                    print2LogView("设备管理员未激活，无法锁屏")
//                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
//                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
//                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponentName)
//                        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "远程锁屏需要这个权限")
//                    }
//                    startActivity(intent)
                }
            } else if (s == "EXIT") {
                stopSelf()
//                System.exit(0)
            } else if (s == "RESTART") {
                restartApp()
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
     * 暂停时调用 锁处理
     */
    fun onPause() {
        Log.d("logView MeService", "onPause")
        meMediaPlaybackManager?.updatePlaybackState(PlaybackStateCompat.STATE_PAUSED, 0)
        if (wakeLockPlay != null) {
            wakeLockPlay!!.release()
            wakeLockPlay = null
            wifiLock?.release()
            wifiLock = null
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
            wakeLockPlay = (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "workDayAlarmClock::MeServicePlay").apply {
                    acquire()
                }
            }
            if (wifiLock == null) {
                wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "workDayAlarmClock:WifiLock")
                wifiLock?.acquire()
            }
        }
    }

    /**
     * 播放器播放url
     */
    fun playUrl(url:String) {
        try {
            // 因为待机可能导致音乐无法进行下一首播放（服务运行正常就音乐放不了）需要重新给cpu加唤醒锁
//            val pm = getSystemService(POWER_SERVICE) as PowerManager
//            val wl: PowerManager.WakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, this.javaClass.canonicalName)
//            wl.acquire()

//            ysSetLedsValue(MeYsLed.TALKING_1, MeYsLed.TALKING_6)
//            ysSetLedsValue(MeYsLed.TALKING_1, MeYsLed.TALKING_5, 100, true)

            ysSetLedsValue(MeYsLed.VIOLENCE_1, MeYsLed.VIOLENCE_4, 500)
            print2LogView("play播放 " + url)
            loadProgress = -1
            val isPlayLastUrl = lastUrl == url
            lastUrl = url
            if (player == null) {
                player = MediaPlayer()
            } else {
                // 可以打断正在进行的prepareAsync加载避免阻塞
                if (player!!.isPlaying) player!!.pause()
                player!!.reset()
            }
            isStop = false
            // 给音频服务反馈 正在加载
            meMediaPlaybackManager?.updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING, 0)
            // 在播放时保持唤醒，暂停和停止时自动销毁   但在跳下一首的时候锁不住，所以其实没啥用
            player!!.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK);
            if (url.startsWith("./")) {
                player!!.setDataSource(filesDir.absolutePath + "/" + url)
            } else {
                player!!.setDataSource(url)
            }
            player!!.setOnCompletionListener { mediaPlayer ->
                //播放完成监听
                isStop = true
                print2LogView("play播放完成")
                meMediaPlaybackManager?.updateMediaMetadata(1, null, null, null)
                meMediaPlaybackManager?.updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING, 1)
                if (!MainActivity.startService) {
                    // 无需启服务 放完就退出
                    MainActivity.me?.finish()
                }
                toGo("next")
                // 此处等待返回决定是回收还是reset
            }
            player!!.setOnPreparedListener { mediaPlayer ->
                //异步准备监听
                print2LogView("play音频时长 " + (mediaPlayer.duration / 1000).toString())
                // 给音频服务上报总时长
                meMediaPlaybackManager?.updateMediaMetadata(mediaPlayer.duration.toLong(), meMediaPlaybackManager!!.lastTitle,null,null)
                if (isPlayLastUrl && !mediaPlayer.isPlaying) {
                    // 规避Android停止又播放同一首进度不对的bug
                    mediaPlayer.seekTo(0)
                }
                if (!mediaPlayer.isPlaying) {
                    if (url.startsWith("http") && loadProgress < 10) {
                        // 直接等2秒吧，Android经常乱上报加载进度
                        Thread.sleep(2000)
                    }
                    mediaPlayer.start()
                    onPlay()
                    ysSetLedsValue(MeYsLed.EMPTY)
                }
            }
            player!!.setOnBufferingUpdateListener { mediaPlayer, i ->
                //文件缓冲监听
                if (i != 100) {
                    if (i > loadProgress) {
                        loadProgress = i
                        print2LogView("play加载音频 $i%")
                    }
                    if (i >= 10 && !mediaPlayer.isPlaying) {
                        // 其实是支持边缓冲边放的 得让他先冲一会再调播放
                        mediaPlayer.start()
                        onPlay()
                        ysSetLedsValue(MeYsLed.EMPTY)
                    }
                }
            }
            player!!.setOnInfoListener { mp, what, extra ->
                print2LogView("play信息: what=$what, extra=$extra")
                when (what) {
                    MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                        print2LogView("play正在缓冲")
                        true
                    }

                    MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                        print2LogView("play缓冲结束")
                        true
                    }
                    else -> false
                }
            }
            player!!.setOnErrorListener { mp, what, extra ->
                print2LogView("play播放错误: what=$what, extra=$extra")
                // 解释错误代码
                when (what) {
                    MediaPlayer.MEDIA_ERROR_SERVER_DIED -> print2LogView("play错误: 服务器连接断开")
                    MediaPlayer.MEDIA_ERROR_UNSUPPORTED -> print2LogView("play错误: 不支持的格式")
                    MediaPlayer.MEDIA_ERROR_TIMED_OUT -> print2LogView("play错误: 操作超时")
                    else -> print2LogView("play错误: 未知错误 ($what, $extra)")
                }
                // false会自动OnCompletionListener
                false
            }
            player!!.prepareAsync()
        } catch (e: Exception) {
            e.printStackTrace()
            print2LogView("播放失败" + e.toString())
        }
    }

    /**
     * 在shell中运行主程序
     */
    private fun runShell() {
        shellThread = Thread(Runnable {
            try {
                // 输入start可以启动 exit可以退出
                val command = "alias exit='echo EXIT'\n" +
                        "alias run='cd " + filesDir.absolutePath + ";pwd;getprop ro.product.cpu.abilist;getprop ro.product.cpu.abi;ip a|grep \"et \";" + applicationInfo.nativeLibraryDir + "/libWorkdayAlarmClock.so app'\n" +
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
                        checkAction(line)
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
            }
            writer?.println(cmd)
            writer?.flush()
        } catch (e: Exception) {
            e.printStackTrace()
            print2LogView("Shell执行出错 $e")
        }
    }


    /**
     * 响应按键
     * 重写了监听器里的接口
     */
    fun keyHandle(keyCode: Int, holdTime: Long): Boolean {
        print2LogView("holdTime $holdTime")
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_DPAD_CENTER -> {
                ClockActivity.me?.showMsg("播放暂停")
                print2LogView("媒体按键 播放暂停")
                if (!isStop) {
                    if (player!!.isPlaying == true) {
                        player!!.pause()
                        onPause()
                    } else {
                        player!!.start()
                        onPlay()
                    }
                    return true
                } else {
                    // 触发一键 即播放默认歌单
                    toGo("1key")
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                print2LogView("媒体按键 播放")
                if (!isStop) {
                    if (player?.isPlaying == false) {
                        player!!.start()
                        onPlay()
                    }
                } else {
                    // 触发一键 即播放默认歌单
                    toGo("1key")
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                print2LogView("媒体按键 暂停")
                if (!isStop && player?.isPlaying == true) {
                    player!!.pause()
                    onPause()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // 如果当前暂停，则触发停止
                if (player?.isPlaying == true) {
                    ClockActivity.me?.showMsg("下一首")
                    print2LogView("媒体按键 下一首")
                    player?.pause()
                    toGo("next")
                } else {
                    ClockActivity.me?.showMsg("触发停止")
                    print2LogView("媒体按键 下一首 触发停止")
                    player?.pause()
                    toGo("stop")
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_DPAD_LEFT -> {
                ClockActivity.me?.showMsg("上一首")
                print2LogView("媒体按键 上一首")
                if (!isStop &&player?.isPlaying == true) player!!.pause()
                toGo("prev")
                return true
            }
            // 好像系统都会强制响应音量键 那这就用int最大值来代替这个位置
            2147483647, KeyEvent.KEYCODE_DPAD_UP -> {
                ClockActivity.me?.showMsg("音量加")
                print2LogView("媒体按键 音量加")
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_RAISE,AudioManager.FLAG_SHOW_UI);
                return true
            }
            2147483646, KeyEvent.KEYCODE_DPAD_DOWN -> {
                ClockActivity.me?.showMsg("音量减")
                print2LogView("媒体按键 音量减")
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_LOWER,AudioManager.FLAG_SHOW_UI);
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                ClockActivity.me?.showMsg("停止")
                print2LogView("媒体按键 停止")
                player?.stop()
                toGo("stop")
                return true
            }
            KeyEvent.KEYCODE_FOCUS -> {
                // 实际是拍照对焦键
                print2LogView("媒体按键 鼻子")
                player?.stop()
                toGo("stop")
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (ysLedStatus()) {
                    print2LogView("菜单 一说宝宝摸头")
                } else {
                    print2LogView("菜单 停止")
                    player?.stop()
                    toGo("stop")
                }
                return true
            }
            KeyEvent.KEYCODE_SOFT_SLEEP -> {
                print2LogView("媒体按键 叮咚Play睡眠")
                if (player != null) {
                    if (!player!!.isPlaying && isStop) {
                        print2LogView("触发上一首")
                        toGo("prev")
                    } else if (!isStop) {
                        if (holdTime > 1200) {
                            toGo("prev")
                        } else if (holdTime > 500) {
                            player?.stop()
                            toGo("stop")
                        } else if (player!!.isPlaying) {
                            player!!.pause()
                            onPause()
                        } else {
                            player!!.start()
                            onPlay()
                        }
                    }
                } else {
                    print2LogView("触发上一首")
                    toGo("prev")
                }
                return true
            }
        }
        // 菜单 返回 退格
//        if (keyCode !in intArrayOf(0, 82, 4, 67)) {
            print2LogView("未知按键 $keyCode")
//        }
        return false
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
            pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_ONE_SHOT);
        }
        val manager = getSystemService(ALARM_SERVICE) as AlarmManager
        manager[AlarmManager.RTC, System.currentTimeMillis() + 2000] = pendingIntent
//        System.exit(0)
        onDestroy()
        Thread.sleep(1000)
        stopSelf()
    }
}
