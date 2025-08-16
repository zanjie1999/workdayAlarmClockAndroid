package com.zyyme.workdayalarmclock

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zyyme.workdayalarmclock.MainActivity.Companion.keyDownTime
import java.text.SimpleDateFormat
import java.util.Date


class ClockActivity : AppCompatActivity() {

    var mediaSessionCompat: MediaSessionCompat? = null
    var mediaComponentName: ComponentName? = null

    private var timeHandler: Handler = Handler()
    private var runnable: Runnable? = null
    var sdfHmsmde = SimpleDateFormat("h:mm:ss.M月d日 E")

    var isKeepScreenOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 去除状态栏
        val decorView = getWindow().getDecorView()
        decorView.setSystemUiVisibility(
            (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION // 隐藏导航栏的关键标志
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)
        )

        setContentView(R.layout.activity_clock)
        mediaButtonReceiverInit()

        // 按钮控制
//        findViewById<Button>(R.id.btn_back).setOnClickListener {
//            Toast.makeText(this, "${this.getString(R.string.app_name)} 服务已停止", Toast.LENGTH_SHORT).show()
//            onDestroy()
//            MeService.me?.stopSelf()
//        }
        findViewById<Button>(R.id.btn_back).setOnClickListener {
            val intent: Intent = Intent(this, MainActivity::class.java)
            // 清除任务栈并创建新任务
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
        }
        findViewById<Button>(R.id.btn_prev).setOnClickListener {
            MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_PREVIOUS, 0)
        }
        findViewById<Button>(R.id.btn_play).setOnClickListener {
            MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
        }
        findViewById<Button>(R.id.btn_next).setOnClickListener {
            MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_NEXT, 0)
        }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_STOP, 0)
        }
        findViewById<Button>(R.id.btn_volm).setOnClickListener {
            MeService.me?.keyHandle(2147483646, 0)
        }
        findViewById<Button>(R.id.btn_volp).setOnClickListener {
            MeService.me?.keyHandle(2147483647, 0)
        }
        findViewById<TextView>(R.id.tv_time).setOnClickListener {
            isKeepScreenOn = !isKeepScreenOn
            if (isKeepScreenOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                Toast.makeText(this, "保持亮屏打开", Toast.LENGTH_SHORT).show()
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                Toast.makeText(this, "关闭保持亮屏", Toast.LENGTH_SHORT).show()
            }
        }


        // 时间显示线程 1秒一次
        runnable = object : Runnable {
            override fun run() {
                val hmsmde = sdfHmsmde.format(Date()).split(".")
                findViewById<TextView>(R.id.tv_time).text = hmsmde[0]
                findViewById<TextView>(R.id.tv_date).text = hmsmde[1]

                timeHandler.postDelayed(this, 1000)
            }
        }
        timeHandler.postDelayed(runnable as Runnable, 1000)

    }

    override fun onDestroy() {
        mediaButtonReceiverDestroy()
        // 时间计时器回收
        if (runnable != null) {
            timeHandler.removeCallbacks(runnable!!)
        }
//        stopService(Intent(this, MeService::class.java))
        Toast.makeText(this, "${this.getString(R.string.app_name)} 在后台运行", Toast.LENGTH_SHORT).show()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(keyEvent: KeyEvent?): Boolean {
        when (keyEvent?.action) {
            KeyEvent.ACTION_DOWN -> {
                keyDownTime = System.currentTimeMillis()
            }
            KeyEvent.ACTION_UP -> {
                if (MeService.me?.keyHandle(keyEvent.keyCode, System.currentTimeMillis() - keyDownTime) == true) {
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(keyEvent)
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
                    MediaSessionCompat(this@ClockActivity, "WorkdayAlarmClock", this, null).apply {
                        //指明支持的按键信息类型
                        setFlags(
                            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
                        )

                        setCallback(object : MediaSessionCompat.Callback() {
                            override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                                MediaButtonReceiver().onReceive(this@ClockActivity, mediaButtonEvent)
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