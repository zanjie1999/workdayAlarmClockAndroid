package com.zyyme.workdayalarmclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.json.JSONArray

/**
 * 开机启动逻辑
 */
object StartupAppHelper {
    const val PREFS_NAME = "app_list"
    const val KEY_PINNED_APPS = "pinned_apps"
    private const val KEY_STARTUP_APPS = "startup_apps"
    const val STARTUP_APP_DELAY_MILLIS = 5_000L
    private const val EXTRA_SKIP_STARTUP_APP = "skip_startup_app"

    private var startupAppLaunching = false

    fun getStartupAppPackageNames(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val storedApps = prefs.getString(KEY_STARTUP_APPS, null).orEmpty().trim()
        if (storedApps.isNotEmpty()) {
            parseStartupApps(storedApps).let { parsed ->
                if (parsed.isNotEmpty()) {
                    return parsed
                }
            }
        }
        return emptyList()
    }

    fun setStartupAppPackageName(context: Context, packageName: String?) {
        val currentApps = getStartupAppPackageNames(context).toMutableList()
        if (packageName.isNullOrBlank()) {
            currentApps.clear()
        } else if (!currentApps.contains(packageName)) {
            currentApps.add(packageName)
        }
        setStartupAppPackageNames(context, currentApps)
    }

    fun setStartupAppPackageNames(context: Context, packageNames: List<String>) {
        val normalized = packageNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (normalized.isEmpty()) {
            editor.remove(KEY_STARTUP_APPS)
        } else {
            editor.putString(KEY_STARTUP_APPS, JSONArray(normalized).toString())
        }
        editor.apply()
    }

    fun isStartupApp(context: Context, packageName: String): Boolean {
        return getStartupAppPackageNames(context).contains(packageName)
    }

    fun toggleStartupApp(context: Context, packageName: String): Boolean {
        val currentApps = getStartupAppPackageNames(context).toMutableList()
        if (currentApps.contains(packageName)) {
            currentApps.remove(packageName)
        } else {
            currentApps.add(packageName)
        }
        setStartupAppPackageNames(context, currentApps)
        return currentApps.contains(packageName)
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

        val startupPackageNames = getStartupAppPackageNames(appContext)
        if (startupPackageNames.isEmpty()) {
            continueOriginalLogic()
            return
        }

        if (startupAppLaunching) {
            Log.v("workdayAlarmClock", "开机启动应用正在处理，跳过重复启动")
            finishPending()
            return
        }

        startupAppLaunching = true
        if (!launchStartupApps(appContext, startupPackageNames)) {
            startupAppLaunching = false
            continueOriginalLogic()
            return
        }

        Handler(Looper.getMainLooper()).postDelayed({
            continueOriginalLogic()
        }, startupPackageNames.size * STARTUP_APP_DELAY_MILLIS)
    }

    fun tryHandleLauncherBootActivity(context: Context, intent: Intent?): Boolean {
        if (intent?.getBooleanExtra(EXTRA_SKIP_STARTUP_APP, false) == true) {
            return false
        }
        if (intent?.action != Intent.ACTION_MAIN || !intent.hasCategory(Intent.CATEGORY_HOME)) {
            return false
        }

        val appContext = context.applicationContext
        val startupPackageNames = getStartupAppPackageNames(appContext)
        if (startupPackageNames.isEmpty()) {
            return false
        }

        if (startupAppLaunching) {
            Log.v("workdayAlarmClock", "开机启动应用正在处理，跳过重复启动")
            return true
        }

        startupAppLaunching = true
        if (!launchStartupApps(appContext, startupPackageNames)) {
            startupAppLaunching = false
            return false
        }
        return true
    }

    private fun launchStartupApps(context: Context, packageNames: List<String>): Boolean {
        var hasAnyLaunched = false
        packageNames.forEachIndexed { index, packageName ->
            if (launchStartupApp(context, packageName)) {
                hasAnyLaunched = true
            }
            if (index < packageNames.lastIndex) {
                Thread.sleep(STARTUP_APP_DELAY_MILLIS)
            }
        }
        return hasAnyLaunched
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
        if (MeSettings.isEnabled(context, MeSettings.KEY_DISABLE)) {
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

    private fun parseStartupApps(raw: String): List<String> {
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val value = array.optString(i).trim()
                    if (value.isNotEmpty() && !contains(value)) {
                        add(value)
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
