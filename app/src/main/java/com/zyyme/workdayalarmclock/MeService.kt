package com.zyyme.workdayalarmclock

import android.annotation.SuppressLint
import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.*
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
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket


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

        const val ACTION_FORWARD = "com.zyyme.workdayalarmclock.ACTION_FORWARD"

        const val NOTIFICATION_ID = 1

        // 这些设备将默认启用时钟模式  两个拼起来
        // getprop ro.product.manufacturer
        // getprop ro.product.model
        //                                 绿色陪伴音箱，叮咚play，小魔镜
        val clockModeModel = listOf<String>("softwinnerHPN_XH", "Intelcht_mrd", "sprduws6137_1h10_64b_1g", "AllwinnerQUAD-CORE A64 ococci")
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
    var defaultSeek: Int = 0

    // 用于展示的电池信息 有变化才有值，固定电量不显示
    var batInfo = ""
    var batLevel = -1

    val isBonjour = Build.MANUFACTURER + Build.MODEL == "AllwinnerQUAD-CORE A64 ococci"

    private var batteryReceiver: BroadcastReceiver? = null
    private var notificationBuilder: NotificationCompat.Builder? = null
    private var notificationManager: NotificationManager? = null
    private var wakePendingIntent: PendingIntent? = null
    private var udpServerSocket: DatagramSocket? = null


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
            notificationBuilder?.addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_ff,
                    "快进",
                    createPendingIntentForAction(ACTION_FORWARD)
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
        ClockActivity.me?.finish()
        udpServerSocket?.close()
        wakeLock?.release()
        wakeLockPlay?.release()
        wifiLock?.release()
        unregisterReceiver(batteryReceiver)
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
    fun checkAction(s: String?, fromPipe: Boolean) {
        if (s != null) {
            if (fromPipe) {
                // 在本机发送udp广播时本机也会收到，过滤重复的内容
                cmdLastT = System.currentTimeMillis()
                cmdLastMsg = s
            }
            if (s.startsWith("PLAY ")) {
                playUrl(s.substring(5), true)
            } else if (s.startsWith("LOAD ")) {
                playUrl(s.substring(5), false)
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
                if (ClockActivity.me != null) {
                    if ("工作咩闹钟" == msg) {
                        ClockActivity.me?.showMsg("停止播放")
                    } else {
                        ClockActivity.me?.showMsg(msg)
                    }
                }
                meMediaPlaybackManager?.updateMediaMetadata(65535, msg, null, null)
            } else if (s.startsWith("SEEK ")) {
                // 移动进度条 单位是ms
                val seek = s.substring(5)
                player?.seekTo(seek.toInt())
            } else if (s.startsWith("DSEEK ")) {
                // 移动进度条的默认值
                val seek = s.substring(6)
                defaultSeek = seek.toInt()
            } else if (s == "STOP") {
                print2LogView("停止播放")
                isStop = true
                player?.release()
                onPause()
                player = null
                meMediaPlaybackManager?.updateMediaMetadata(0, null,null,null)
                meMediaPlaybackManager?.updatePlaybackState(PlaybackStateCompat.STATE_CONNECTING, 0)
                ysSetLedsValue(MeYsLed.EMPTY)
            } else if (s == "SEEK") {
                // 全屋同步播放补偿
                if (defaultSeek < 0) {
                    Thread.sleep(defaultSeek * -1L)
                    player?.seekTo(0)
                } else {
                    player?.seekTo(defaultSeek)
                }
            } else if (s == "PAUSE") {
                print2LogView("暂停播放")
                player?.pause()
                onPause()
            } else if (s == "RESUME") {
                print2LogView("恢复播放")
                if (!isStop) {
                    player?.start()
                    onPlay()
                } else {
                    // 触发一键 即播放默认歌单
                    toGo("1key")
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
                    if (File(filesDir.absolutePath + "/white").exists()) {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                    } else {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                    }
                    val intent = Intent(this, ClockActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    intent.putExtra("clockMode", true)
                    intent.putExtra("keepOn", true)
                    startActivity(intent)
                    print2LogView("已亮屏")
                }
            } else if (s == "SCREENON") {
                if (File(filesDir.absolutePath + "/white").exists()) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                } else {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                }
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
                        // 取消ALARM给时钟模式的保持亮屏flag
                        if (ClockActivity.me?.isKeepScreenOn == true) {
                            ClockActivity.me?.isKeepScreenOn = false
                            ClockActivity.me?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                        print2LogView("已锁屏")
                    } catch (e: Exception) {
                        print2LogView("锁屏失败: ${e.message}")
                    }
                } else {
                    print2LogView("设备管理员未激活，无法锁屏")
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponentName)
                        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "远程锁屏需要这个权限")
                    }
                    startActivity(intent)
                }
            } else if (s == "EXIT") {
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
    fun playUrl(url:String, autoPlay:Boolean) {
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
                if (autoPlay && !mediaPlayer.isPlaying) {
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
                    if (autoPlay && i >= 10 && !mediaPlayer.isPlaying) {
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
     * 响应按键
     * 重写了监听器里的接口
     */
    fun keyHandle(keyCode: Int, holdTime: Long): Boolean {
        // 菜单 返回 退格 音量加 减
        val knownKey = intArrayOf(0, 82, 4, 67, 24, 25)
        if (keyCode !in knownKey) {
            print2LogView("holdTime $holdTime")
        }
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (!isStop) {
                    print2LogView("媒体按键 播放暂停")
                    if (player!!.isPlaying == true) {
                        ClockActivity.me?.showMsg("暂停")
                        player!!.pause()
                        onPause()
                    } else {
                        ClockActivity.me?.showMsg("播放")
                        player!!.start()
                        onPlay()
                    }
                    return true
                } else {
                    print2LogView("媒体按键 一键")
                    ClockActivity.me?.showMsg("一键")
                    // 触发一键 即播放默认歌单
                    toGo("1key")
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                print2LogView("媒体按键 播放")
                if (!isStop) {
                    ClockActivity.me?.showMsg("播放")
                    if (player?.isPlaying == false) {
                        player!!.start()
                        onPlay()
                    }
                } else {
                    ClockActivity.me?.showMsg("一键")
                    // 触发一键 即播放默认歌单
                    toGo("1key")
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                print2LogView("媒体按键 暂停")
                ClockActivity.me?.showMsg("暂停")
                if (!isStop && player?.isPlaying == true) {
                    player!!.pause()
                    onPause()
                }
                return true
            }
            // 用这个代替触摸的下一曲按钮，避免被停止
            2147483645, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // 如果当前暂停，则触发停止
                if (player?.isPlaying == true || keyCode == 2147483645) {
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
            2147483647, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_VOLUME_UP -> {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && holdTime > 800) {
                    ClockActivity.me?.showMsg("下一首")
                    print2LogView("媒体按键 长按音量加")
                    player?.pause()
                    toGo("next")
                    return true
                }
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                val per = (am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 100).toInt()
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_RAISE,AudioManager.FLAG_SHOW_UI);
                ClockActivity.me?.showMsg("音量${per}%")
                print2LogView("媒体按键 音量加 ${per}%")
                return true
            }
            2147483646, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && holdTime > 800) {
                    ClockActivity.me?.showMsg("上一首")
                    print2LogView("媒体按键 长按音量减")
                    player?.pause()
                    toGo("prev")
                    return true
                }
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                val per = (am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 100).toInt()
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_LOWER,AudioManager.FLAG_SHOW_UI);
                ClockActivity.me?.showMsg("音量${per}%")
                print2LogView("媒体按键 音量减 ${per}%")
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
                } else if (isBonjour) {
                    // 触摸太灵了，不用
//                    print2LogView("菜单 Bonjour闹钟按钮")
//                    handleSingleKey(holdTime)
                } else {
                    print2LogView("菜单 停止")
                    player?.stop()
                    toGo("stop")
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                print2LogView("媒体按键 快进")
                player?.seekTo(player!!.currentPosition + 5000)
                return true
            }
            // 这keycode其实也不知道到底应该用哪个
//            KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> {
//                print2LogView("媒体按键 快退")
//                player?.seekTo(player!!.currentPosition - 5000)
//                return true
//            }
            // 叮咚play睡眠按钮            小魔镜按钮                           Bonjour闹钟按钮按下去
            KeyEvent.KEYCODE_SOFT_SLEEP, KeyEvent.KEYCODE_ZENKAKU_HANKAKU, KeyEvent.KEYCODE_VOLUME_MUTE -> {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_MUTE && !isBonjour) {
                    // 不是Bonjour闹钟时恢复本来的静音功能
                    return false
                }
                print2LogView("媒体按键 单按钮")
                if (holdTime > 9000 && keyCode == KeyEvent.KEYCODE_ZENKAKU_HANKAKU) {
                    try {
                        val intent = Intent()
                        intent.component = ComponentName("com.example.kenna", "com.example.kenna.activity.SecondActivity")
                        startActivity(intent)
                    } catch (e : Exception) {
                        print2LogView("启动小魔镜应用失败")
                        e.printStackTrace()
                    }
                } else {
                    handleSingleKey(holdTime)
                }
                return true
            }
        }
        if (keyCode !in knownKey) {
            print2LogView("未知按键 $keyCode")
        }
        return false
    }

    /**
     * 处理单按钮事件
     */
    fun handleSingleKey(holdTime: Long = 0) {
        if (holdTime > 2500) {
            ClockActivity.me?.showMsg("停止")
            toGo("stop")
        } else if (holdTime > 1500) {
            ClockActivity.me?.showMsg("上一首")
            player?.pause()
            toGo("prev")
        } else if (holdTime > 800) {
            ClockActivity.me?.showMsg("下一首")
            player?.pause()
            toGo("next")
        } else if (!isStop) {
            if (player!!.isPlaying == true) {
                ClockActivity.me?.showMsg("暂停")
                player!!.pause()
                onPause()
            } else {
                ClockActivity.me?.showMsg("播放")
                player!!.start()
                onPlay()
            }
        } else {
            toGo("1key")
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
