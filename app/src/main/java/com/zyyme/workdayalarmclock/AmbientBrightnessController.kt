package com.zyyme.workdayalarmclock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Window
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * 实现使用前摄像头估算亮度，控制屏幕亮度
 */
internal class AmbientBrightnessController(
    context: Context,
    private val log: (String) -> Unit
) {
    companion object {
        private val DEFAULT_BRIGHTNESS = intArrayOf(0, 8, 32, 96, 255)
        @Volatile private var latestLevel = 4

        fun applyLatestTo(window: Window) {
            if (!MeSettings.isEnabled(window.context, MeSettings.KEY_CAMERA_AUTO_BRIGHTNESS)) {
                val attributes = window.attributes
                attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = attributes
                return
            }
            val level = latestLevel.coerceIn(0, 4)
            val value = if (level == 0) 0 else MeSettings.getInt(
                window.context,
                brightnessKey(level),
                DEFAULT_BRIGHTNESS[level]
            ).coerceIn(0, 255)
            val attributes = window.attributes
            attributes.screenBrightness = value / 255f
            window.attributes = attributes
        }

        private fun brightnessKey(level: Int): String = when (level) {
            1 -> MeSettings.KEY_CAMERA_BRIGHTNESS_1
            2 -> MeSettings.KEY_CAMERA_BRIGHTNESS_2
            3 -> MeSettings.KEY_CAMERA_BRIGHTNESS_3
            4 -> MeSettings.KEY_CAMERA_BRIGHTNESS_4
            else -> MeSettings.KEY_CAMERA_BRIGHTNESS_0
        }
    }

    private val appContext = context.applicationContext
    private val levelValue = AtomicInteger(4)
    private val controlThread = HandlerThread("ambient-brightness-control").apply { start() }
    private val controlHandler = Handler(controlThread.looper)
    private val sampler = AmbientCameraSampler(::updateLuma, log)
    @Volatile private var enabled = false
    @Volatile private var ipCameraActive = false
    @Volatile private var previewActive = false
    private var pendingLevel = -1
    private var pendingCount = 0
    private var ignoreUntil = 0L
    private var lastLumaAt = 0L
    private var lastAppliedLevel = -1

    val level: Int
        get() = levelValue.get()

    private val periodicSample: Runnable = object : Runnable {
        override fun run() {
            if (!enabled || ipCameraActive || intervalMillis() <= 0L) return
            beginCameraWarmup()
            sampler.start()
            controlHandler.removeCallbacks(stopPeriodicSample)
            controlHandler.postDelayed(stopPeriodicSample, 3_000L)
        }
    }

    private val stopPeriodicSample: Runnable = Runnable {
        sampler.stop()
        if (enabled && !ipCameraActive) {
            val interval = intervalMillis()
            if (interval > 0L) controlHandler.postDelayed(periodicSample, interval)
        }
    }

    fun syncSettings() {
        enabled = MeSettings.isEnabled(appContext, MeSettings.KEY_CAMERA_AUTO_BRIGHTNESS)
        controlHandler.removeCallbacks(periodicSample)
        controlHandler.removeCallbacks(stopPeriodicSample)
        sampler.stop()
        if (!enabled && !previewActive) {
            if (levelValue.getAndSet(4) != 4) latestLevel = 4
            Handler(appContext.mainLooper).post { applyToVisibleWindows() }
            return
        }
        if (previewActive || ipCameraActive) return
        controlHandler.post {
            if (enabled && !ipCameraActive) {
                if (intervalMillis() == 0L) {
                    beginCameraWarmup()
                    sampler.start()
                } else {
                    controlHandler.post(periodicSample)
                }
            }
        }
    }

    fun onIpCameraStarting() {
        ipCameraActive = true
        controlHandler.removeCallbacks(periodicSample)
        controlHandler.removeCallbacks(stopPeriodicSample)
        sampler.stop()
        beginCameraWarmup()
    }

    fun onIpCameraStopped() {
        ipCameraActive = false
        if (!enabled && !previewActive) return
        if (previewActive || intervalMillis() == 0L) {
            beginCameraWarmup()
            sampler.start()
        } else {
            controlHandler.postDelayed(periodicSample, intervalMillis())
        }
    }

    @Synchronized
    fun updateLuma(luma: Int) {
        if (!enabled && !previewActive) return
        val now = SystemClock.elapsedRealtime()
        if (now < ignoreUntil || now - lastLumaAt < 500L) return
        lastLumaAt = now
        val candidate = when {
            luma < 20 -> 0
            luma < 55 -> 1
            luma < 110 -> 2
            luma < 190 -> 3
            else -> 4
        }
        if (candidate == level) {
            pendingLevel = -1
            pendingCount = 0
            return
        }
        if (pendingLevel == candidate) pendingCount++ else {
            pendingLevel = candidate
            pendingCount = 1
        }
        if (pendingCount >= 3) {
            pendingLevel = -1
            pendingCount = 0
            if (levelValue.getAndSet(candidate) != candidate) {
                latestLevel = candidate
                if (!previewActive) {
                    Handler(appContext.mainLooper).post { applyLevel(candidate) }
                }
            }
        }
    }

    fun shutdown() {
        enabled = false
        previewActive = false
        controlHandler.removeCallbacksAndMessages(null)
        sampler.stop()
        controlThread.quit()
    }

    @Synchronized
    fun beginCameraWarmup() {
        pendingLevel = -1
        pendingCount = 0
        lastLumaAt = 0L
        ignoreUntil = SystemClock.elapsedRealtime() + 1_200L
    }

    fun beginLivePreview() {
        previewActive = true
        lastAppliedLevel = -1
        controlHandler.removeCallbacks(periodicSample)
        controlHandler.removeCallbacks(stopPeriodicSample)
        sampler.stop()
        if (!ipCameraActive) {
            beginCameraWarmup()
            sampler.start()
        }
    }

    fun endLivePreview() {
        if (!previewActive) return
        previewActive = false
        sampler.stop()
        if (!enabled) {
            if (levelValue.getAndSet(4) != 4) latestLevel = 4
            Handler(appContext.mainLooper).post { applyToVisibleWindows() }
            return
        }
        if (ipCameraActive) return
        Handler(appContext.mainLooper).post { applyLevel(level) }
        if (intervalMillis() == 0L) {
            beginCameraWarmup()
            sampler.start()
        } else {
            controlHandler.postDelayed(periodicSample, intervalMillis())
        }
    }

    fun brightnessValueForLevel(level: Int = this.level): Int {
        val safeLevel = level.coerceIn(0, 4)
        return if (safeLevel == 0) 0 else MeSettings.getInt(
            appContext,
            brightnessKey(safeLevel),
            DEFAULT_BRIGHTNESS[safeLevel]
        ).coerceIn(0, 255)
    }

    fun currentSystemBrightness(): Int {
        return try {
            Settings.System.getInt(
                appContext.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            ).coerceIn(0, 255)
        } catch (_: Exception) {
            0
        }
    }

    private fun intervalMillis(): Long {
        val text = appContext.getSharedPreferences("me_settings", Context.MODE_PRIVATE)
            .getString(MeSettings.KEY_CAMERA_BRIGHTNESS_INTERVAL, "0")
            .orEmpty()
        val minutes = text.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
        return (minutes * 60_000.0).toLong()
    }

    private fun applyLevel(newLevel: Int) {
        if (lastAppliedLevel == newLevel) return
        lastAppliedLevel = newLevel
        val brightness = if (newLevel == 0) 0 else MeSettings.getInt(
            appContext,
            brightnessKey(newLevel),
            DEFAULT_BRIGHTNESS[newLevel]
        ).coerceIn(0, 255)
        try {
            Settings.System.putInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
        } catch (e: Exception) {
            log("设置系统亮度失败：${e.message}")
        }
        applyToVisibleWindows()
        val closeScreenEnabled = when (newLevel) {
            0 -> MeSettings.isEnabled(appContext, MeSettings.KEY_CAMERA_CLOSE_SCREEN)
            1 -> MeSettings.isEnabled(appContext, MeSettings.KEY_CAMERA_CLOSE_SCREEN_LEVEL_1)
            else -> false
        }
        if (closeScreenEnabled) {
            closeScreen()
            return
        }
        val wakeLevel = MeSettings.getInt(appContext, MeSettings.KEY_CAMERA_AUTO_WAKE_LEVEL, 0).coerceIn(0, 4)
        if (wakeLevel > 0 && newLevel >= wakeLevel) {
            val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val screenOn = if (android.os.Build.VERSION.SDK_INT >= 20) powerManager.isInteractive else powerManager.isScreenOn
            if (!screenOn) MeService.me?.wakeScreenForAmbient()
        }
    }

    private fun applyToVisibleWindows() {
        MainActivity.me?.let { applyLatestTo(it.window) }
        ClockActivity.me?.let { applyLatestTo(it.window) }
        DeskActivity.me?.let { applyLatestTo(it.window) }
    }

    private fun closeScreen() {
        if (ClockActivity.me?.isKeepScreenOn == true || DeskActivity.me?.isKeepScreenOn == true) {
            log("摄像头自动亮度跳过熄屏：当前设置了保持亮屏")
            return
        }
        val manager = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(appContext, MeDeviceAdminReceiver::class.java)
        if (!manager.isAdminActive(admin)) {
            log("摄像头自动亮度无法熄屏：设备管理员未激活")
            return
        }
        try {
            ClockActivity.me?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            DeskActivity.me?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            manager.lockNow()
        } catch (e: Exception) {
            log("摄像头自动亮度熄屏失败：${e.message}")
        }
    }
}
