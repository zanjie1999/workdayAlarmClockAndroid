package com.zyyme.workdayalarmclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.io.File

/**
 * 开机启动逻辑
 */
object StartupAppHelper {
    const val PREFS_NAME = "app_list"
    const val KEY_PINNED_APPS = "pinned_apps"
    private const val KEY_STARTUP_APP = "startup_app"
    private const val STARTUP_APP_DELAY_MILLIS = 10_000L
    private const val EXTRA_SKIP_STARTUP_APP = "skip_startup_app"

    private var startupAppLaunching = false

    fun getStartupAppPackageName(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STARTUP_APP, null)
    }

    fun setStartupAppPackageName(context: Context, packageName: String?) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (packageName.isNullOrEmpty()) {
            editor.remove(KEY_STARTUP_APP)
        } else {
            editor.putString(KEY_STARTUP_APP, packageName)
        }
        editor.apply()
    }

    fun startAtBooted(
        context: Context,
        accessibility: Boolean = false,
        pendingResult: BroadcastReceiver.PendingResult? = null
    ) {
        val appContext = context.applicationContext

        fun finishPending() {
            pendingResult?.finish()
        }

        fun continueOriginalLogic() {
            try {
                startupAppLaunching = false
                startOriginalBootLogic(appContext, accessibility)
            } finally {
                finishPending()
            }
        }

        val startupPackageName = getStartupAppPackageName(appContext)
        if (startupPackageName.isNullOrEmpty()) {
            continueOriginalLogic()
            return
        }

        if (startupAppLaunching) {
            Log.v("workdayAlarmClock", "开机启动应用正在处理，跳过重复启动")
            finishPending()
            return
        }

        startupAppLaunching = true
        if (!launchStartupApp(appContext, startupPackageName)) {
            startupAppLaunching = false
            continueOriginalLogic()
            return
        }

        Handler(Looper.getMainLooper()).postDelayed({
            continueOriginalLogic()
        }, STARTUP_APP_DELAY_MILLIS)
    }

    fun tryHandleLauncherBootActivity(context: Context, intent: Intent?): Boolean {
        if (intent?.getBooleanExtra(EXTRA_SKIP_STARTUP_APP, false) == true) {
            return false
        }
        if (intent?.action != Intent.ACTION_MAIN || !intent.hasCategory(Intent.CATEGORY_HOME)) {
            return false
        }

        val appContext = context.applicationContext
        val startupPackageName = getStartupAppPackageName(appContext)
        if (startupPackageName.isNullOrEmpty()) {
            return false
        }

        if (startupAppLaunching) {
            Log.v("workdayAlarmClock", "开机启动应用正在处理，跳过重复启动")
            return true
        }

        startupAppLaunching = true
        if (!launchStartupApp(appContext, startupPackageName)) {
            startupAppLaunching = false
            return false
        }
        return true
    }

    private fun launchStartupApp(context: Context, packageName: String): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            Log.v("workdayAlarmClock", "开机启动应用不可启动：$packageName")
            return false
        }

        return try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            true
        } catch (e: Exception) {
            Log.v("workdayAlarmClock", "开机启动应用启动失败：$packageName", e)
            false
        }
    }

    private fun startOriginalBootLogic(context: Context, accessibility: Boolean) {
        Log.v("workdayAlarmClock", "startAtBooted")
        if (File(context.filesDir.absolutePath + "/disable").exists()) {
            Log.v("workdayAlarmClock", "disabledisabledisable 开机不启动")
            return
        }
        if (MeService.me != null) {
            // 已手动启动
            return
        }

        if (accessibility) {
            // 辅助功能服务 开机启动
            Log.d("workdayAlarmClock", "MyAccessibilityService onServiceConnected startAtBooted")
            Toast.makeText(context, "使用无障碍服务开机启动咯~", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "开机启动咯~", Toast.LENGTH_LONG).show()
        }

        val intent = Intent(context, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!accessibility) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
    }
}