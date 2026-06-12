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
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.widget.TextViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

import java.text.SimpleDateFormat
import java.util.Date

/**
 * 时钟
 */
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
    
    var showMsgTime = 0

    var clockMode = false

    var enableTop = false

    private var isUserSeeking = false

    fun showMsg(msg: String) {
        runOnUiThread {
            showMsgTime = 3
            if (enableTop) {
                findViewById<TextView>(R.id.tv_top).text = msg
            } else {
                findViewById<TextView>(R.id.tv_date).text = msg
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        me = this

        super.onCreate(savedInstanceState)

        // 启动Go服务 如果App以特殊方式启动
        if (MeService.me == null) {
            startService(Intent(this, MeService::class.java))
        }

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

        // 横屏flag 会根据重力自动选择时钟模式的横屏方向
        if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE && MeSettings.isEnabled(this, MeSettings.KEY_LANDSCAPE)) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        // 不显示秒的flag  24小时制flag
        val flag24 = MeSettings.isEnabled(this, MeSettings.KEY_T24)
        if (MeSettings.isEnabled(this, MeSettings.KEY_TSS)) {
            if (flag24) {
                sdfHmsmde = SimpleDateFormat("H:mm.M月d日 E")
            } else {
                sdfHmsmde = SimpleDateFormat("h:mm.M月d日 E")
            }
        } else if (flag24) {
            sdfHmsmde = SimpleDateFormat("H:mm:ss.M月d日 E")
        }


        setFullscreen()

        setContentView(R.layout.activity_clock)
//        mediaButtonReceiverInit()

        val tvTop = findViewById<TextView>(R.id.tv_top)
        val tvTime = findViewById<TextView>(R.id.tv_time)
        val tvDate = findViewById<TextView>(R.id.tv_date)
        val circularMusicProgress = findViewById<CircularMusicProgressView>(R.id.circular_music_progress)
        setupClockTextAutoSize(tvTop, tvTime, tvDate)

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
        findViewById<Button>(R.id.btn_app).setOnClickListener {
            val intent = Intent(this, AppListActivity::class.java)
            startActivity(intent)
        }
        tvTop.setOnClickListener {
            val intent = Intent(this, AppListActivity::class.java)
            startActivity(intent)
        }
        findViewById<Button>(R.id.btn_prev).setOnClickListener {
            MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_PREVIOUS, true)
        }
        findViewById<Button>(R.id.btn_play).setOnClickListener {
            MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, true)
        }
        findViewById<Button>(R.id.btn_next).setOnClickListener {
            MeService.me?.keyHandle(2147483645, true)
        }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_STOP, true)
        }
        findViewById<Button>(R.id.btn_volm).setOnClickListener {
            MeService.me?.keyHandle(2147483646, true)
        }
        findViewById<Button>(R.id.btn_volp).setOnClickListener {
            MeService.me?.keyHandle(2147483647, true)
        }
        findViewById<Button>(R.id.btn_forward).setOnClickListener {
            MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, true)
        }

        val musicSeekBar = findViewById<SeekBar>(R.id.sb_music_progress)
        val tvMusicPosition = findViewById<TextView>(R.id.tv_music_position)
        val tvMusicDuration = findViewById<TextView>(R.id.tv_music_duration)
        musicSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvMusicPosition.text = formatMusicTime(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val player = MeService.me?.player
                val target = seekBar?.progress ?: 0
                try {
                    val duration = player?.duration ?: 0
                    if (player != null && duration > 0) {
                        player.seekTo(target.coerceIn(0, duration))
                    }
                } catch (e: IllegalStateException) {
                    Log.w("ClockActivity", "seek music progress failed", e)
                }
                isUserSeeking = false
            }
        })

        val tvGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (clockMode) {
                    MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, true)
                } else {
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
                return true
            }

            override fun onLongPress(e: MotionEvent?) {
                // 一键 让go决定是要放还是要停
                showMsg("一键")
                MeService.me?.toGo("1key")
                true
            }

            override fun onFling(
                e1: MotionEvent,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                if (Math.abs(diffX) > Math.abs(diffY)) {
                    // 水平
                    if (Math.abs(diffX) > 100 && Math.abs(velocityX) > 100) {
                        if (diffX > 0) {
                            // 右
                            MeService.me?.keyHandle(2147483645, true)
                        } else {
                            // 左
                            MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_PREVIOUS, true)
                        }
                        return true
                    }
                } else {
                    // 垂直
                    if (Math.abs(diffY) > 100 && Math.abs(velocityY) > 100) {
                        if (diffY > 0) {
                            // 下
                            MeService.me?.keyHandle(2147483646, true)
                        } else {
                            // 上
                            MeService.me?.keyHandle(2147483647, true)
                        }
                        return true
                    }
                }
                return false
            }
        })
        tvTime.setOnTouchListener { v, event ->
            tvGestureDetector.onTouchEvent(event)
        }

        tvDate.setOnClickListener {
            if (clockMode) {
                recreate()
            } else {
                // 切换暗色模式
                if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                } else {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                }
            }
        }
        tvDate.setOnLongClickListener {
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
                Log.d("intent", "收到clockMode")
                // 直接进入全屏时钟模式
                setFullScreenClock()
            }
            // 保持亮屏flag
            if (intent.getBooleanExtra("keepOn", false)) {
                Log.d("intent", "收到keepOn")
                isKeepScreenOn = true
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            // 清空
            intent.replaceExtras(Intent())
        }



        // 时间显示线程 1秒一次
        runnable = object : Runnable {
            override fun run() {
                val hmsmde = sdfHmsmde.format(Date()).split(".")
                tvTime.text = hmsmde[0]
                val player = MeService.me?.player
                var millis: Int? = null
                var duration = 0
                try {
                    if (player != null) {
                        millis = player.currentPosition
                        duration = player.duration
                    }
                } catch (e: IllegalStateException) {
                    millis = null
                    duration = 0
                }
                updateMusicProgress(musicSeekBar, tvMusicPosition, tvMusicDuration, millis, duration)
                circularMusicProgress.setProgress(millis, duration)
                if (showMsgTime > 0) {
                    showMsgTime--
                } else {
                    if (enableTop) {
                        if (millis != null) {
                            tvTop.text = MeService.me!!.batInfo + "▷" + String.format("%2d:%02d", millis / 60000, (millis % 60000) / 1000)
                            tvDate.text = hmsmde[1]
                        } else {
                            tvTop.text = MeService.me!!.batInfo
                            tvDate.text = hmsmde[1]
                        }
                    } else {
                        if (millis != null) {
                            tvDate.text = MeService.me!!.batInfo + hmsmde[1] + " ▷" + String.format("%2d:%02d", millis / 60000, (millis % 60000) / 1000)
                        } else {
                            tvDate.text = MeService.me!!.batInfo + hmsmde[1]
                        }
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
        findViewById<LinearLayout>(R.id.music_progress_layout).visibility = View.GONE

        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        val tvTop = findViewById<TextView>(R.id.tv_top)
        val tvTime = findViewById<TextView>(R.id.tv_time)
        val tvDate = findViewById<TextView>(R.id.tv_date)
        val circularMusicProgress = findViewById<CircularMusicProgressView>(R.id.circular_music_progress)
        val rootLayout = findViewById<LinearLayout>(R.id.root_layout)
        var realHeightPixels = rootLayout.height
        val isVertical = displayMetrics.heightPixels / displayMetrics.widthPixels.toFloat() > 1.15 ||  MeSettings.isEnabled(this, MeSettings.KEY_VERTICAL)
        val isRound = rootLayout.height == rootLayout.width || MeSettings.isEnabled(this, MeSettings.KEY_ROUND)
        circularMusicProgress.visibility = if (isRound) View.VISIBLE else View.GONE
        Log.d("ClockActivity", "realHeight: $realHeightPixels realWidth: ${rootLayout.width} height: ${displayMetrics.heightPixels} width: ${displayMetrics.widthPixels} density: ${displayMetrics.density} 比例:${displayMetrics.heightPixels / displayMetrics.widthPixels.toFloat()}")
        if (isRound) {
            // 圆形屏幕 增加上下边距
            Log.d("ClockActivity", "圆形屏幕")
            val padding = realHeightPixels / 5
            findViewById<LinearLayout>(R.id.root_layout).setPadding(0, padding, 0, padding)
            // 自动计算大小 搞个左右的边距
            val textHorizontalPadding = 10
            tvTop.setPadding(textHorizontalPadding, tvTop.paddingTop, textHorizontalPadding, tvTop.paddingBottom)
            tvTime.setPadding(textHorizontalPadding, tvTime.paddingTop, textHorizontalPadding, tvTime.paddingBottom)
            tvDate.setPadding(textHorizontalPadding, tvDate.paddingTop, textHorizontalPadding, tvDate.paddingBottom)
            realHeightPixels -= padding * 2
        }
        if (isRound || isVertical) {
            // 启用顶部框 圆形 竖屏
            tvTime.layoutParams.height = (realHeightPixels * 0.6).toInt()
            tvTop.layoutParams.height = (realHeightPixels * 0.2).toInt()
            enableTop = true
        } else {
            tvTime.layoutParams.height = (realHeightPixels * 0.8).toInt()
        }
        tvDate.layoutParams.height = (realHeightPixels * 0.2).toInt()

        if (isVertical) {
            // 竖屏秒换行
            if (!MeSettings.isEnabled(this, MeSettings.KEY_TSS)) {
                if (MeSettings.isEnabled(this, MeSettings.KEY_T24)) {
                    sdfHmsmde = SimpleDateFormat("H:mm\nss.M月d日 E")
                } else {
                    sdfHmsmde = SimpleDateFormat("h:mm\nss.M月d日 E")
                }
            }
            tvTime.maxLines = 2
        }
        setupClockTextAutoSize(tvTop, tvTime, tvDate)
    }

    private fun setupClockTextAutoSize(tvTop: TextView, tvTime: TextView, tvDate: TextView) {
        setUniformAutoSize(tvTop, 8, 50)
        setUniformAutoSize(tvTime, 18, 200)
        setUniformAutoSize(tvDate, 8, 50)
    }

    private fun setUniformAutoSize(textView: TextView, minSp: Int, maxSp: Int) {
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            textView,
            minSp,
            maxSp,
            1,
            TypedValue.COMPLEX_UNIT_SP
        )
    }

    private fun updateMusicProgress(
        seekBar: SeekBar,
        positionView: TextView,
        durationView: TextView,
        position: Int?,
        duration: Int
    ) {
        if (position == null || duration <= 0) {
            seekBar.max = 0
            seekBar.progress = 0
            seekBar.isEnabled = false
            positionView.text = formatMusicTime(0)
            durationView.text = formatMusicTime(0)
            return
        }

        seekBar.isEnabled = true
        if (seekBar.max != duration) {
            seekBar.max = duration
        }
        if (!isUserSeeking) {
            seekBar.progress = position.coerceIn(0, duration)
            positionView.text = formatMusicTime(position)
        }
        durationView.text = formatMusicTime(duration)
    }

    private fun formatMusicTime(millis: Int): String {
        val totalSeconds = millis.coerceAtLeast(0) / 1000
        val hours = totalSeconds / 3600
        val minutes = totalSeconds % 3600 / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
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
        me = null
        super.onDestroy()
    }

    override fun dispatchKeyEvent(keyEvent: KeyEvent?): Boolean {
        if (clockMode) {
            when (keyEvent?.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (MeService.me?.keyHandle(keyEvent.keyCode, true) == true) {
                        return true
                    }
                }
                KeyEvent.ACTION_UP -> {
                    if (MeService.me?.keyHandle(keyEvent.keyCode, false) == true) {
                        return true
                    }
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
