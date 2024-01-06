package com.zyyme.workdayalarmclock

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter


class MeService : Service() {
    companion object {
//        var mediaButtonReceiverHandler: Handler? = null
        var me: MeService? = null
    }

    var player : MediaPlayer = MediaPlayer()
    var writer : PrintWriter? = null
    var lastUrl : String? = null
    var shellThread : Thread? = null
    var isStop = true
    var mBreathLedsManager: Any? = null

    override fun onBind(intent: Intent): IBinder {
        me = this
        TODO("Return the communication channel to the service.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        me = this
        // 加cpu唤醒锁
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val wl: PowerManager.WakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, this.javaClass.canonicalName)
        wl.acquire()

        // 保活通知 8.0开始channel不是个字符串
        val channelId = "me_bg"
        if (Build.VERSION.SDK_INT >= 26) {
            val importance = NotificationManager.IMPORTANCE_LOW
            val notificationChannel = NotificationChannel(channelId, "保活通知", importance).apply {
                description = "保活通知"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(notificationChannel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setSubText("正在运行")
            .setContentTitle("诶嘿")
            .setContentText(this.getString(R.string.app_name))
            .setUsesChronometer(true)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
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
        player.setDataSource("http://127.0.0.1:1")
        player.start()
        player.stop()
        player.reset()

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

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        shellThread?.interrupt()
        super.onDestroy()
    }

    /**
     * 反射调用一说宝宝设置灯板
     * end小于start的时候将直接设置
     */
    var ysLedThread: Thread? = null
    fun ysSetLedsValue(start: Int, end: Int =0) {
        if (mBreathLedsManager != null) {
            if (ysLedThread != null) {
                ysLedThread?.interrupt()
                ysLedThread = null
            }
            val c = Class.forName("android.app.BreathLedsManager")
            val m = c.getDeclaredMethod("setLedsValue", Int::class.javaPrimitiveType)
            m.setAccessible(true)
            if (end > start) {
                ysLedThread = Thread(Runnable {
                    try {
                        while (true) {
                            for (i in start..end) {
                                m.invoke(mBreathLedsManager, i)
                                Thread.sleep(300)
                            }
                        }
                    } catch (e: InterruptedException) {
                        // interrupt必会抛出异常
                    }
                })
                ysLedThread!!.start()
            } else {
                m.invoke(mBreathLedsManager, start)
            }
        }
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
                player.reset()
                ysSetLedsValue(MeYsLed.EMPTY)
            } else if (s == "PAUSE") {
                print2LogView("暂停播放")
                player.pause()
            } else if (s == "RESUME") {
                print2LogView("恢复播放")
                if (!isStop) {
                    player.start()
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
            } else if (s == "EXIT") {
                MainActivity.me?.finish()
                stopSelf()
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
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val wl: PowerManager.WakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, this.javaClass.canonicalName)
            wl.acquire()

            ysSetLedsValue(MeYsLed.TALKING_1, MeYsLed.TALKING_6)
            print2LogView("播放 " + url)
            lastUrl = url
            if (!isStop) {
                player.reset()
            }
            isStop = false
            player.setDataSource(url)
            player.setOnCompletionListener({mediaPlayer ->
                //播放完成监听
                isStop = true
                player.reset()
//                player = null
                print2LogView("播放完成")
                if (!MainActivity.startService) {
                    // 无需启服务 放完就退出
                    MainActivity.me?.finish()
                }
                toGo("next")
            })
            player.setOnPreparedListener(MediaPlayer.OnPreparedListener { mediaPlayer ->
                //异步准备监听
                print2LogView("加载完成 时长" + (mediaPlayer.duration / 1000).toString())
                if (!mediaPlayer.isPlaying) {
                    // 规避Android停止又播放同一首进度不对的bug
                    mediaPlayer.seekTo(0)
                    mediaPlayer.start()
                    ysSetLedsValue(MeYsLed.EMPTY)
                }
            })
            player.setOnBufferingUpdateListener(MediaPlayer.OnBufferingUpdateListener { mediaPlayer, i ->
                //文件缓冲监听
                if (i != 100) {
                    if (i != 99) {
                        // 规避Android停止又播放同一首会一直加载99%的bug
                        print2LogView("加载音频 $i%")
                    }
                    if (i > 10 && !mediaPlayer.isPlaying) {
                        // 其实是支持边缓冲边放的 得让他先冲一会再调播放
                        mediaPlayer.start()
                        ysSetLedsValue(MeYsLed.EMPTY)
                    }
                }
            })
            player.prepareAsync()
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
            var process: Process? = null
            try {
                val command = "cd " + getFilesDir().getAbsolutePath() + ";pwd;" + applicationInfo.nativeLibraryDir + "/libWorkdayAlarmClock.so app"
                process = ProcessBuilder("sh")
                    .redirectErrorStream(true)
                    .start()

                val reader = BufferedReader(InputStreamReader(process!!.inputStream))
                writer = PrintWriter(process!!.outputStream)
                send2Shell(command)

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    try {
                        checkAction(line)
                    } catch (e: InterruptedException) {
                        // interrupt必会抛出异常
                        process.destroy()
                        break
                    } catch (e: Exception) {
                        e.printStackTrace()
                        print2LogView("Shell解析出错 $e")
                    }
                }
            } catch (e: InterruptedException) {
                // interrupt必会抛出异常
                process?.destroy()
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
                print2LogView("设置一说led $mBreathLedsManager " + cmd.substring(6))
                val n = cmd.substring(6).split("-")
                ysSetLedsValue(n[0].toInt(), n.last().toInt())
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
    fun keyHandle(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                print2LogView("媒体按键 播放暂停")
                if (!player.isPlaying && lastUrl == null && isStop) {
                    print2LogView("初次启动 触发上一首")
                    toGo("prev")
                } else if (!isStop) {
                    if (player.isPlaying == true) {
                        player.pause()
                    } else {
                        player.start()
                    }
                } else if (lastUrl != null) {
                    playUrl(lastUrl!!)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                print2LogView("媒体按键 播放")
                if (!player.isPlaying && lastUrl == null && isStop) {
                    print2LogView("初次启动 触发上一首")
                    toGo("prev")
                } else if (!isStop) {
                    player.start()
                } else if (lastUrl != null) {
                    playUrl(lastUrl!!)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                print2LogView("媒体按键 暂停")
                player.pause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                print2LogView("媒体按键 下一首")
                toGo("next")
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                print2LogView("媒体按键 上一首")
                toGo("prev")
                return true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                print2LogView("媒体按键 音量加")
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_RAISE,AudioManager.FLAG_SHOW_UI);
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                print2LogView("媒体按键 音量减")
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_LOWER,AudioManager.FLAG_SHOW_UI);
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                print2LogView("媒体按键 停止")
                toGo("stop")
                return true
            }
            KeyEvent.KEYCODE_FOCUS -> {
                // 实际是拍照对焦键
                print2LogView("媒体按键 鼻子")
                toGo("stop")
                return true
            }
        }
        print2LogView("未知按键 $keyCode")
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
        System.exit(0)
    }
}