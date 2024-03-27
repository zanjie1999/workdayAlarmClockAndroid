package com.zyyme.workdayalarmclock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.*
import android.os.PowerManager.WakeLock
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import android.view.KeyEvent
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity


class MainActivity : AppCompatActivity() {
    companion object {
        var print2LogViewHandler: Handler? = null
        var me: MainActivity? = null
        var startService = true
    }

    var mediaSessionCompat: MediaSessionCompat? = null
    var mediaComponentName: ComponentName? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        me = this
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar));


        // am start -n com.zyyme.workdayalarmclock/.MainActivity -d http://...
        val amUrl = getIntent().getDataString();
        if (amUrl != null) {
            startService = false
            if (MeService.me == null) {
                startService(Intent(this, MeService::class.java))
            } else {
                print2LogView("服务已经在运行了")
            }
            print2LogView("收到外部播放链接 将不启动服务 放完自动退出\n" + amUrl)
            Thread(Runnable {
                while (MeService.me == null) {
                    print2LogView("等待服务启动...")
                    Thread.sleep(1000)
                }
                MeService.me!!.playUrl(amUrl!!)
            }).start()
            return
        }

        // 启动服务 如果Activity不是重载的话
        if (MeService.me == null) {
            startService(Intent(this, MeService::class.java))
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

        // Shell输入框
        findViewById<EditText>(R.id.shellInput).setOnEditorActionListener { _, actionId, ketEvent ->
            if (ketEvent != null && ketEvent.keyCode == KeyEvent.KEYCODE_ENTER){
                val shellInput = findViewById<EditText>(R.id.shellInput)
                val cmd = shellInput.text.toString()
                shellInput.setText("")
                print2LogView("> $cmd")
                MeService.me?.send2Shell(cmd)
                shellInput.requestFocus()
                true
            }
            false
        }

        print2LogViewHandler = Handler(Looper.getMainLooper()) { msg ->
            print2LogView(msg.obj as String)
            true
        }

        mediaButtonReceiverInit()

        // toolbar的控制按钮
        findViewById<ImageView>(R.id.iconPrev).setOnClickListener {
            MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }
        findViewById<ImageView>(R.id.iconPlay).setOnClickListener {
            MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
        findViewById<ImageView>(R.id.iconNext).setOnClickListener {
            MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_NEXT)
        }
        findViewById<ImageView>(R.id.iconStop).setOnClickListener {
            MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_STOP)
        }
        findViewById<ImageView>(R.id.iconExit).setOnClickListener {
            Toast.makeText(this, "${this.getString(R.string.app_name)} 服务已停止", Toast.LENGTH_SHORT).show()
            onDestroy()
            MeService.me?.stopSelf()
        }

    }

    override fun onDestroy() {
        print2LogView("即将退出...")
        mediaButtonReceiverDestroy()
//        stopService(Intent(this, MeService::class.java))
        Toast.makeText(this, "${this.getString(R.string.app_name)} 在后台运行", Toast.LENGTH_SHORT).show()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // MeService允许无头运行 所以不需要将Activity放后台了 可以直接销毁
//        if (keyCode == KeyEvent.KEYCODE_BACK) {
//            Toast.makeText(this, "${this.getString(R.string.app_name)} 在后台运行", Toast.LENGTH_SHORT).show()
//            moveTaskToBack(false)
//        } else
        if (MeService.me?.keyHandle(keyCode) == true) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * 将日志打印到logView
     */
    fun print2LogView(s:String) {
        Log.d("logView", s)
        runOnUiThread(Runnable {
            findViewById<TextView>(R.id.logView).append(s + "\n")
            val scrollView = findViewById<ScrollView>(R.id.scrollView)
            scrollView.post(Runnable { scrollView.fullScroll(ScrollView.FOCUS_DOWN) })
        })
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

}