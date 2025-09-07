package com.zyyme.workdayalarmclock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.util.DisplayMetrics
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.Date


class ClockActivity : AppCompatActivity() {
    companion object {
        var me: ClockActivity? = null
    }

    var mediaSessionCompat: MediaSessionCompat? = null
    var mediaComponentName: ComponentName? = null

    private var timeHandler: Handler = Handler()
    private var runnable: Runnable? = null
    var sdfHmsmde = SimpleDateFormat("h:mm:ss.M月d日 E")

    var isKeepScreenOn = false
    
    var showMsgFlag = false

    var clockMode = false
    fun showMsg(msg: String) {
        showMsgFlag = true
        findViewById<TextView>(R.id.tv_date).text = msg
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        me = this
        super.onCreate(savedInstanceState)

        // 可以显示在锁屏上
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }


        setFullscreen()

        setContentView(R.layout.activity_clock)
//        mediaButtonReceiverInit()

        // 按钮控制
//        findViewById<Button>(R.id.btn_back).setOnClickListener {
//            Toast.makeText(this, "${this.getString(R.string.app_name)} 服务已停止", Toast.LENGTH_SHORT).show()
//            onDestroy()
//            MeService.me?.stopSelf()
//        }
        findViewById<Button>(R.id.btn_back).setOnClickListener {
            val intent: Intent = Intent(this, MainActivity::class.java)
            // 清除任务栈并创建新任务
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
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
//                Toast.makeText(this, "保持亮屏打开", Toast.LENGTH_SHORT).show()
                showMsg("保持亮屏 打开")
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
//                Toast.makeText(this, "关闭保持亮屏", Toast.LENGTH_SHORT).show()
                showMsg("关闭 保持亮屏")
            }
        }
        findViewById<TextView>(R.id.tv_time).setOnLongClickListener {
            // 一键 让go决定是要放还是要停
            showMsg("一键")
            MeService.me?.toGo("1key")
            true
        }
        findViewById<TextView>(R.id.tv_date).setOnClickListener {
            // 切换暗色模式
            if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            }
        }
        findViewById<TextView>(R.id.tv_date).setOnLongClickListener {
            val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponentName = ComponentName(this, MeDeviceAdminReceiver::class.java)
            if (devicePolicyManager.isAdminActive(adminComponentName)) {
                try {
                    devicePolicyManager.lockNow()
                } catch (e: Exception) {
                    showMsg("锁屏失败: ${e.message}")
                }
            }
            true
        }
        findViewById<Button>(R.id.btn_minsize).setOnClickListener {
            setFullScreenClock()
        }
        var screenReverseFlag = false
        findViewById<Button>(R.id.btn_rotation).setOnClickListener {
            requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT && !screenReverseFlag) {
                screenReverseFlag = true
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            } else if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && !screenReverseFlag) {
                screenReverseFlag = true
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            } else if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT && screenReverseFlag) {
                // 重载会自动 screenReverseFlag = false
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                // 重载会自动 screenReverseFlag = false
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
        val rootLaout = findViewById<LinearLayout>(R.id.root_layout)
        rootLaout.post {
            // 延迟进行字体大小调整  初始化完后延时执行
            if (intent.getBooleanExtra("clockMode", false)) {
                intent.removeExtra("clockMode")
                // 直接进入全屏时钟模式
                setFullScreenClock()
            } else {
                // 给Android8以下设备设置字体大小（无法自动缩放）
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    val height =  rootLaout.height / resources.displayMetrics.density
                    val width = rootLaout.width / resources.displayMetrics.density
                    Log.d("ClockActivity", "height: $height width: $width density: ${resources.displayMetrics.density}")
                    if (height > width) {
                        // 竖屏
                        if (resources.displayMetrics.density < 1) {
                            findViewById<TextView>(R.id.tv_time).textSize = width * 0.2f
                        } else {
                            findViewById<TextView>(R.id.tv_time).textSize = width * 0.25f
                        }
                    } else {
                        if (resources.displayMetrics.density < 1) {
                            findViewById<TextView>(R.id.tv_time).textSize = height * 0.25f
                        } else {
                            findViewById<TextView>(R.id.tv_time).textSize = height * 0.3f
                        }
                    }
                }
            }
            // 保持亮屏flag
            if (intent.getBooleanExtra("keepOn", false)) {
                intent.removeExtra("keepOn")
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }



        // 时间显示线程 1秒一次
        runnable = object : Runnable {
            override fun run() {
                val hmsmde = sdfHmsmde.format(Date()).split(".")
                findViewById<TextView>(R.id.tv_time).text = hmsmde[0]
                if (showMsgFlag) {
                    showMsgFlag = false
                } else {
                    val millis = MeService.me?.player?.currentPosition;
                    if (millis != null) {
                        findViewById<TextView>(R.id.tv_date).text = hmsmde[1] + " ▷" + String.format("%2d:%02d", millis / 60000, (millis % 60000) / 1000)
                    } else {
                        findViewById<TextView>(R.id.tv_date).text = hmsmde[1]
                    }
                }
                if ((System.currentTimeMillis() / 1000) % 10 == 0L) {
                    timeHandler.postDelayed(this, 1000 - System.currentTimeMillis() % 1000)
                } else {
                    timeHandler.postDelayed(this, 1000)
                }
            }
        }
        timeHandler.postDelayed(runnable as Runnable, 1000 - System.currentTimeMillis() % 1000)

    }

    override fun onResume() {
        super.onResume()
        setFullscreen()
    }

    private fun setFullScreenClock() {
        clockMode = true
        findViewById<LinearLayout>(R.id.btm_layout1).visibility = View.GONE
        findViewById<LinearLayout>(R.id.btm_layout2).visibility = View.GONE
        findViewById<LinearLayout>(R.id.btm_layout3).visibility = View.GONE
        findViewById<LinearLayout>(R.id.btm_layout4).visibility = View.GONE

        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        val tvTime = findViewById<TextView>(R.id.tv_time)
        val tvDate = findViewById<TextView>(R.id.tv_date)
        val realHeightPixels = findViewById<LinearLayout>(R.id.root_layout).height
        Log.d("ClockActivity", "realHeightPixels: $realHeightPixels")
        tvTime.layoutParams.height = (realHeightPixels * 0.8).toInt()
        tvDate.layoutParams.height = (realHeightPixels * 0.2).toInt()

        Log.d("btn_minsize", "height: ${displayMetrics.heightPixels} width: ${displayMetrics.widthPixels} 比例:${displayMetrics.heightPixels / displayMetrics.widthPixels.toFloat()}")
        if (displayMetrics.heightPixels / displayMetrics.widthPixels.toFloat() > 1.15) {
            // 竖屏秒换行
            sdfHmsmde = SimpleDateFormat("h:mm\nss.M月d日 E")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                tvTime.textSize = displayMetrics.heightPixels / resources.displayMetrics.density * 0.25f
            }
            tvTime.maxLines = 2
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            tvTime.textSize = displayMetrics.widthPixels / resources.displayMetrics.density * 0.25f
            if (tvDate.textSize < displayMetrics.widthPixels / resources.displayMetrics.density * 0.05f) {
                tvDate.textSize = displayMetrics.widthPixels / resources.displayMetrics.density * 0.05f
            }
        }
    }

    private fun setFullscreen() {
        // 这个音箱刚需overscan，为了这个特殊处理一下
        if (Build.MODEL == "HPN_XH") {
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        } else {
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION         // 添加此行以隐藏导航栏
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)


            // 新版Android
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            // false时按钮会显示到挖孔的区域
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).let { controller ->
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    override fun onDestroy() {
//        mediaButtonReceiverDestroy()
        // 时间计时器回收
        if (runnable != null) {
            timeHandler.removeCallbacks(runnable!!)
        }
//        stopService(Intent(this, MeService::class.java))
//        Toast.makeText(this, "${this.getString(R.string.app_name)} 在后台运行", Toast.LENGTH_SHORT).show()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(keyEvent: KeyEvent?): Boolean {
        if (clockMode) {
            when (keyEvent?.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (MeService.me?.keyHandle(keyEvent.keyCode, 0) == true) {
                        return true
                    }
                }
                KeyEvent.ACTION_UP -> {

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
        mediaComponentName = ComponentName(packageName, MeMediaButtonReceiver::class.java.name).apply {
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
                                MeMediaButtonReceiver().onReceive(this@ClockActivity, mediaButtonEvent)
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
