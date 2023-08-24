package com.zyyme.workdayalarmclock

import android.R.attr
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaPlayer.OnBufferingUpdateListener
import android.media.MediaPlayer.OnPreparedListener
import android.os.Bundle
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.util.Log
import android.view.KeyEvent
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.URL


class MainActivity : AppCompatActivity() {

    var player : MediaPlayer? = null
    var writer : PrintWriter? = null
    var lastUrl : String? = null

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
                player?.release()
                player = null
            } else if (s == "PAUSE") {
                print2LogView("暂停播放")
                player?.pause()
            } else if (s == "RESUME") {
                print2LogView("恢复播放")
                if (player != null) {
                    player?.start()
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
            player?.release()
//            player = MediaPlayer().apply {
//                setAudioStreamType(AudioManager.STREAM_MUSIC)
//                setOnCompletionListener({mediaPlayer ->
//                    //播放完成监听
//                    print2LogView("播放完成")
//                    toGo("next")
//                })
//                setDataSource(url)
//                prepare()
//                start()
//            }
            player = MediaPlayer()
            player?.setDataSource(url)
            player?.setOnCompletionListener({mediaPlayer ->
                //播放完成监听
                player?.release()
                player = null
                print2LogView("播放完成")
                toGo("next")
            })
            player?.setOnPreparedListener(OnPreparedListener { mediaPlayer ->
                //异步准备监听
                print2LogView("加载完成 时长"+(mediaPlayer.duration / 1000).toString())
                mediaPlayer.start()
            })
            player?.setOnBufferingUpdateListener(OnBufferingUpdateListener { mediaPlayer, i ->
                //文件缓冲监听
                if (i != 100) {
                    print2LogView("加载音频 $i%")
                    if (i > 10) {
                        // 其实是支持边缓冲边放的 得让他先冲一会再调播放
                        mediaPlayer.start()
                    }
                }
            })
            player?.prepareAsync()
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
        Thread(Runnable {
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
                        val cmd = findViewById<EditText>(R.id.shellInput).text.toString()
                            findViewById<android.widget.EditText>(com.zyyme.workdayalarmclock.R.id.shellInput).setText("")
                            print2LogView("> $cmd")
                            writer?.println(cmd)
                            writer?.flush()
                        true
                    }
                    false
                }

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    checkAction(line)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                print2LogView(e.toString())
            }
        }).start()
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
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_ONE_SHOT
        )
        val manager = getSystemService(ALARM_SERVICE) as AlarmManager
        manager[AlarmManager.RTC, System.currentTimeMillis() + 1000] = pendingIntent
        System.exit(0)
    }
}