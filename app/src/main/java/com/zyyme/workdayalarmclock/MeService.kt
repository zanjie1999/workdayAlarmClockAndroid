package com.zyyme.workdayalarmclock

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter


class MeService : Service() {
    companion object {
//        var mediaButtonReceiverHandler: Handler? = null
        var me: MeService? = null
    }

    var player : MediaPlayer? = null
    var writer : PrintWriter? = null
    var lastUrl : String? = null
    var shellThread : Thread? = null
    var isStop = true
    var mBreathLedsManager: Any? = null
    var wakeLock: PowerManager.WakeLock? = null
    var shellProcess: Process? = null
    var loadProgress: Int = 0

    override fun onBind(intent: Intent): IBinder {
        me = this
        TODO("Return the communication channel to the service.")
    }

    @SuppressLint("WrongConstant")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        me = this

        // 保活通知 8.0开始channel不是个字符串 不会保持唤醒
        val channelId = "me_bg"
        if (Build.VERSION.SDK_INT >= 26) {
            val importance = NotificationManager.IMPORTANCE_LOW
            val notificationChannel = NotificationChannel(channelId, "保活通知", importance).apply {
                description = "保活通知"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(notificationChannel)
        }

        val mainIntent: Intent = Intent(applicationContext, ClockActivity::class.java)
        // 清除任务栈并创建新任务
        mainIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            mainIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, channelId)
            // 他一定要设置一个图标
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setSubText("正在运行")
            .setContentTitle("诶嘿")
            .setContentText(this.getString(R.string.app_name))
            .setUsesChronometer(true)
            .setContentIntent(pendingIntent)
            .build()
        try {
            startForeground(1, notification)
        } catch (e: Exception) {
            print2LogView("切换前台服务失败 $e")
            e.printStackTrace()
        }

        // 如果不需要启动Go服务，这个服务将只有播放音频url的功能
        if (!MainActivity.startService) {
            return super.onStartCommand(intent, flags, startId)
        }

        // 抢夺音频焦点
        player = MediaPlayer()
        player!!.setDataSource("http://127.0.0.1:1")
        player!!.start()
        player!!.stop()
        player!!.release()
        player = null

        // 开一个新线程跑shell
        runShell()

//        mediaButtonReceiverHandler = Handler(Looper.getMainLooper()) { msg ->
//            keyHandle(msg.obj as Int)
//            true
//        }

        // 一说宝宝特有系统服务用于控制led灯板
        try {
            mBreathLedsManager = getSystemService("breath_leds")
            Log.d("mBreathLedsManager", "获取成功 $mBreathLedsManager")
            // 关灯
            ysSetLedsValue(MeYsLed.EMPTY)
        } catch (e: Exception) {
            Log.d("mBreathLedsManager", "获取失败")
            e.printStackTrace()
        }

        // 每分钟唤醒一下
//        alarmManager = applicationContext.getSystemService(ALARM_SERVICE) as AlarmManager
////        val pendingIntent = PendingIntent.getService(this, 0, intent!!, PendingIntent.FLAG_IMMUTABLE);
//        alarmManager!!.setRepeating(AlarmManager.RTC_WAKEUP, 60000 - System.currentTimeMillis()%60000, 60000, null)

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        shellProcess?.destroy()
        shellThread?.interrupt()
        MainActivity.me?.finish()
        wakeLock?.release()
        stopForeground(true)
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
        if (mBreathLedsManager != null && ysLedThread == null && Build.VERSION.SDK_INT >= 21) {
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
        MainActivity.me?.print2LogView(s)
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
            } else if (s == "STOP") {
                print2LogView("停止播放")
                isStop = true
                player?.release()
                player = null
                ysSetLedsValue(MeYsLed.EMPTY)
            } else if (s == "PAUSE") {
                print2LogView("暂停播放")
                player?.pause()
            } else if (s == "RESUME") {
                print2LogView("恢复播放")
                if (!isStop) {
                    player?.start()
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
            } else if (s == "EXIT") {
                stopSelf()
//                System.exit(0)
            } else if (s == "RESTART") {
                restartApp()
            } else {
                print2LogView(s)
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
            print2LogView("播放 " + url)
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
            // 在播放时保持唤醒，暂停和停止时自动销毁
            player!!.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            player!!.setDataSource(url)
            player!!.setOnCompletionListener({mediaPlayer ->
                //播放完成监听
                isStop = true
                print2LogView("播放完成")
                if (!MainActivity.startService) {
                    // 无需启服务 放完就退出
                    MainActivity.me?.finish()
                }
                toGo("next")
                // 此处等待返回决定是回收还是reset
            })
            player!!.setOnPreparedListener(MediaPlayer.OnPreparedListener { mediaPlayer ->
                //异步准备监听
                print2LogView("加载完成 时长" + (mediaPlayer.duration / 1000).toString())
                if (isPlayLastUrl && !mediaPlayer.isPlaying) {
                    // 规避Android停止又播放同一首进度不对的bug
                    mediaPlayer.seekTo(0)
                }
                if (!mediaPlayer.isPlaying) {
                    if (loadProgress < 10) {
                        // 直接等2秒吧，Android经常乱上报加载进度
                        Thread.sleep(2000)
                    }
                    mediaPlayer.start()
                    ysSetLedsValue(MeYsLed.EMPTY)
                }
            })
            player!!.setOnBufferingUpdateListener(MediaPlayer.OnBufferingUpdateListener { mediaPlayer, i ->
                //文件缓冲监听
                if (i != 100) {
                    if (i > loadProgress) {
                        loadProgress = i
                        print2LogView("加载音频 $i%")
                    }
                    if (i >= 10 && !mediaPlayer.isPlaying) {
                        // 其实是支持边缓冲边放的 得让他先冲一会再调播放
                        mediaPlayer.start()
                        ysSetLedsValue(MeYsLed.EMPTY)
                    }
                }
            })
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
                        "alias start='cd " + getFilesDir().getAbsolutePath() + ";pwd;getprop ro.product.cpu.abilist;getprop ro.product.cpu.abi;ip a|grep \"et \";" + applicationInfo.nativeLibraryDir + "/libWorkdayAlarmClock.so app'\n" +
                        "start"
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
                print2LogView("媒体按键 播放暂停")
                if (player != null) {
                    if (!player!!.isPlaying && lastUrl == null && isStop) {
                        print2LogView("初次启动 触发上一首")
                        toGo("prev")
                    } else if (!isStop) {
                        if (player!!.isPlaying == true) {
                            player!!.pause()
                        } else {
                            player!!.start()
                        }
                    } else if (lastUrl != null) {
                        playUrl(lastUrl!!)
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                print2LogView("媒体按键 播放")
                if (player != null) {
                    if (!player!!.isPlaying && lastUrl == null && isStop) {
                        print2LogView("触发上一首")
                        toGo("prev")
                    } else if (!isStop) {
                        if (player?.isPlaying == false) player!!.start()
                    } else if (lastUrl != null) {
                        playUrl(lastUrl!!)
                    }
                } else {
                    print2LogView("触发上一首")
                    toGo("prev")
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                print2LogView("媒体按键 暂停")
                if (!isStop && player?.isPlaying == true) player!!.pause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // 如果没有在播放，触发停止
                if (player?.isPlaying == true) {
                    print2LogView("媒体按键 下一首")
                    player?.pause()
                    toGo("next")
                } else {
                    print2LogView("媒体按键 下一首 触发停止")
                    player?.pause()
                    toGo("stop")
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_DPAD_LEFT -> {
                print2LogView("媒体按键 上一首")
                if (!isStop &&player?.isPlaying == true) player!!.pause()
                toGo("prev")
                return true
            }
            // 好像系统都会强制响应音量键 那这就用int最大值来代替这个位置
            2147483647, KeyEvent.KEYCODE_DPAD_UP -> {
                print2LogView("媒体按键 音量加")
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_RAISE,AudioManager.FLAG_SHOW_UI);
                return true
            }
            2147483646, KeyEvent.KEYCODE_DPAD_DOWN -> {
                print2LogView("媒体按键 音量减")
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_LOWER,AudioManager.FLAG_SHOW_UI);
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
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
                        } else {
                            player!!.start()
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
                    or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        )
        var pendingIntent: PendingIntent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        } else {
            pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_ONE_SHOT);
        }
        val manager = getSystemService(ALARM_SERVICE) as AlarmManager
        manager[AlarmManager.RTC, System.currentTimeMillis() + 1000] = pendingIntent
//        System.exit(0)
        stopSelf()
    }
}