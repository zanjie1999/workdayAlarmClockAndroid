package com.zyyme.workdayalarmclock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaPlayer.OnBufferingUpdateListener
import android.media.MediaPlayer.OnPreparedListener
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter


class MainActivity : AppCompatActivity() {
    companion object {
        var mbrHandler: Handler? = null
    }

    var player : MediaPlayer = MediaPlayer()
    var writer : PrintWriter? = null
    var lastUrl : String? = null
    var shellThread : Thread? = null
    var isStop = true
    var mediaSessionCompat: MediaSessionCompat? = null
    var mediaComponentName: ComponentName? = null

    fun print2LogView(s:String?) {
        if (s != null) {
            Log.d("logView", s)
            runOnUiThread(Runnable {
                findViewById<TextView>(R.id.logView).append(s + "\n")
                val scrollView = findViewById<ScrollView>(R.id.scrollView)
                scrollView.post(Runnable { scrollView.fullScroll(ScrollView.FOCUS_DOWN) })
            })
        }
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
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_RAISE,AudioManager.FLAG_SHOW_UI);
            } else if (s == "VOLM") {
                print2LogView("音量减")
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_LOWER,AudioManager.FLAG_SHOW_UI);
            } else if (s == "EXIT") {
                finish()
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
                toGo("next")
            })
            player.setOnPreparedListener(OnPreparedListener { mediaPlayer ->
                //异步准备监听
                print2LogView("加载完成 时长"+(mediaPlayer.duration / 1000).toString())
                if (!mediaPlayer.isPlaying) {
                    // 规避Android停止又播放同一首进度不对的bug
                    mediaPlayer.seekTo(0)
                    mediaPlayer.start()
                }
            })
            player.setOnBufferingUpdateListener(OnBufferingUpdateListener { mediaPlayer, i ->
                //文件缓冲监听
                if (i != 100) {
                    if (i != 99) {
                        // 规避Android停止又播放同一首会一直加载99%的bug
                        print2LogView("加载音频 $i%")
                    }
                    if (i > 10 && !mediaPlayer.isPlaying) {
                        // 其实是支持边缓冲边放的 得让他先冲一会再调播放
                        mediaPlayer.start()
                    }
                }
            })
            player.prepareAsync()
        } catch (e: Exception) {
            e.printStackTrace()
            print2LogView("播放失败" + e.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 加cpu唤醒锁
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val wl: WakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, this.javaClass.canonicalName)
        wl.acquire()

        // am start -n com.zyyme.workdayalarmclock/.MainActivity -d http://...
        val amUrl = getIntent().getDataString();
        if (amUrl != null) {
            print2LogView("收到外部播放链接 将不启动服务\n" + amUrl)
            playUrl(amUrl!!)
            return
        }

        // 存储空间权限
//        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE ) != PackageManager.PERMISSION_GRANTED) {
//            Toast.makeText(this,"请允许权限\n用于更新Go程序二进制文件", Toast.LENGTH_LONG).show()
//            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
//        }

        // 检查二进制文件更新  lib不可写入 写入了重启也会恢复 于是没有热更新了
//        try {
//            val updateFile = File(Environment.getExternalStorageDirectory().absolutePath + "/libWorkdayAlarmClock.so")
//            if (updateFile.exists()) {
//                print2LogView("找到 /sdcard/libWorkdayAlarmClock.so 开始更新")
//                val libdir = File(applicationInfo.nativeLibraryDir)
//                if (!libdir.exists()) {
//                    libdir.mkdirs()
//                }
//                val inputStream: InputStream = FileInputStream(updateFile)
//                val outputFile = File(libdir, "libWorkdayAlarmClock.so")
//                val outputStream: OutputStream = FileOutputStream(outputFile)
//                val buffer = ByteArray(1024)
//                var length: Int
//                while (inputStream.read(buffer).also { length = it } > 0) {
//                    outputStream.write(buffer, 0, length)
//                }
//                outputStream.close()
//                inputStream.close()
//                print2LogView("更新完成")
//            } else {
//                print2LogView("没有找到 /sdcard/libWorkdayAlarmClock.so 不更新")
//            }
//        } catch (e: IOException) {
//            e.printStackTrace()
//            print2LogView(e.toString())
//        }

        // 开一个新线程跑shell
        mediaButtonReceiverInit()
        runShell()

        // 抢夺音频焦点
        player.setDataSource("http://127.0.0.1:1")
        player.start()
        player.stop()
        player.reset()

        mbrHandler = Handler(Looper.getMainLooper()) { msg ->
            keyHandle(msg.obj as Int)
            true
        }
    }

    override fun onDestroy() {
        print2LogView("即将退出...")
        shellThread?.interrupt()
        shellThread?.stop()
        mediaButtonReceiverDestroy()
        Toast.makeText(this, "${this.getString(R.string.app_name)} 已退出", Toast.LENGTH_SHORT).show()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyHandle(keyCode)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * 在shell中运行主程序
     */
    private fun runShell() {
        shellThread = Thread(Runnable {
            try {
                val command = "cd " + getFilesDir().getAbsolutePath() + ";pwd;" + applicationInfo.nativeLibraryDir + "/libWorkdayAlarmClock.so app"
                val process = ProcessBuilder("sh")
                    .redirectErrorStream(true)
                    .start()

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                writer = PrintWriter(process.outputStream)
                writer?.println(command)
                writer?.flush()

                // Shell输入框
                findViewById<EditText>(R.id.shellInput).setOnEditorActionListener { _, actionId, ketEvent ->
                    if (ketEvent != null && ketEvent.keyCode == KeyEvent.KEYCODE_ENTER){
                        try {
                            val cmd = findViewById<EditText>(R.id.shellInput).text.toString()
                            findViewById<android.widget.EditText>(com.zyyme.workdayalarmclock.R.id.shellInput).setText("")
                            print2LogView("> $cmd")
                            writer?.println(cmd)
                            writer?.flush()
                        } catch (e: Exception) {
                            e.printStackTrace()
                            print2LogView("Shell执行出错 $e")
                        }
                        true
                    }
                    false
                }

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
        shellThread?.start()
    }

    /**
     * 初始化媒体按键监听
     * 是的，除了写个监听器还要超麻烦的初始化
     */
    private fun mediaButtonReceiverInit() {
        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        mediaComponentName = ComponentName(packageName, MediaButtonReceiver::class.java.name).apply {
            packageManager.setComponentEnabledSetting(
                this,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
            )
            // Android 5.0
            if (Build.VERSION.SDK_INT >= 21) {
                mediaSessionCompat =
                    MediaSessionCompat(this@MainActivity, "WorkdayAlarmClock", this, null).apply {
                        //指明支持的按键信息类型
                        setFlags(
                            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
                        )

                        setCallback(object : MediaSessionCompat.Callback() {
                            override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                                MediaButtonReceiver().onReceive(this@MainActivity, mediaButtonEvent)
                                return true
                            }
                        }, Handler(Looper.getMainLooper()))
                        isActive = true
                    }

            } else {
                audioManager.registerMediaButtonEventReceiver(this)
            }
        }

    }

    /**
     * 销毁媒体按键监听
     */
    private fun mediaButtonReceiverDestroy() {
        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        // Android 5.0
        if (Build.VERSION.SDK_INT >= 21) {
            mediaSessionCompat?.let {
                it.setCallback(null)
                it.release()
            }
        } else {
            mediaComponentName?.let {
                audioManager.unregisterMediaButtonEventReceiver(it)
            }

        }
    }


    /**
     * 响应按键
     * 重写了监听器里的接口
     */
    fun keyHandle(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                Toast.makeText(this, "${this.getString(R.string.app_name)} 在后台运行", Toast.LENGTH_SHORT).show()
                moveTaskToBack(false)
                return true
            }
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
        var pendingIntent:PendingIntent;
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