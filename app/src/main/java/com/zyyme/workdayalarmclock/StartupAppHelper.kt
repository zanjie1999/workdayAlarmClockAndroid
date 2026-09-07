package com.zyyme.workdayalarmclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
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
    private val startupLock = Any()
    private var startupAppLaunching = false
    private var startupAppsHandled = false

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
        pendingResult: BroadcastReceiver.PendingResult? = null,
        onFinished: (() -> Unit)? = null
    ) {
        val appContext = context.applicationContext

        fun finishPending() {
            pendingResult?.finish()
        }

        fun continueOriginalLogic() {
            try {
                synchronized(startupLock) {
                    startupAppLaunching = false
                    startupAppsHandled = true
                }
                if (onFinished != null) {
                    onFinished()
                } else {
                    startOriginalBootLogic(appContext, accessibility)
                }
            } finally {
                finishPending()
            }
        }

        val startupPackageNames = getStartupAppPackageNames(appContext)
        synchronized(startupLock) {
            if (startupAppLaunching) {
                Log.v("workdayAlarmClock", "开机启动应用正在处理，跳过重复启动")
                finishPending()
                return
            }
            if (startupAppsHandled) {
                Log.v("workdayAlarmClock", "开机启动应用已经处理，跳过重复启动")
                try {
                    onFinished?.invoke()
                } finally {
                    finishPending()
                }
                return
            }
            startupAppLaunching = true
        }

        if (startupPackageNames.isEmpty()) {
            continueOriginalLogic()
            return
        }

        val launchedAppCount = launchStartupApps(appContext, startupPackageNames)
        if (launchedAppCount == 0) {
            continueOriginalLogic()
            return
        }

        Handler(Looper.getMainLooper()).postDelayed({
            continueOriginalLogic()
        }, launchedAppCount * STARTUP_APP_DELAY_MILLIS)
    }

    private fun launchStartupApps(context: Context, packageNames: List<String>): Int {
        val launchablePackages = packageNames.filter { packageName ->
            context.packageManager.getLaunchIntentForPackage(packageName) != null
        }
        if (launchablePackages.isEmpty()) {
            packageNames.forEach { packageName ->
                Log.v("workdayAlarmClock", "开机启动应用不可启动：$packageName")
            }
            return 0
        }

        val mainHandler = Handler(Looper.getMainLooper())
        launchablePackages.forEachIndexed { index, packageName ->
            mainHandler.postDelayed({
                launchStartupApp(context, packageName)
            }, index * STARTUP_APP_DELAY_MILLIS)
        }
        return launchablePackages.size
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

        if (accessibility) {
            // 辅助功能服务 开机启动
            Log.d("workdayAlarmClock", "MyAccessibilityService onServiceConnected startAtBooted")
            Toast.makeText(context, "使用无障碍服务开机启动咯~", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "开机启动咯~", Toast.LENGTH_LONG).show()
        }

        launchInitialDestination(context, accessibility)
    }

    /**
     * Starts the activity that should be visible after the boot/startup sequence.
     * Clock mode is enabled for the configured setting or for one of the built-in
     * device model allow-list entries; otherwise the normal control console opens.
     */
    fun launchInitialDestination(context: Context, accessibility: Boolean = false) {
        val intent = if (
            MeService.clockModeModel.contains(Build.MANUFACTURER + Build.MODEL) ||
            MeSettings.isEnabled(context, MeSettings.KEY_CLOCK)
        ) {
            MeSettings.applyClockTheme(context)
            MeSettings.createClockIntent(context).apply {
                putExtra("clockMode", true)
            }
        } else {
            Intent(context, MainActivity::class.java)
        }
        launchInitialDestination(context, intent, accessibility)
    }

    private fun launchInitialDestination(
        context: Context,
        intent: Intent,
        accessibility: Boolean
    ) {
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
