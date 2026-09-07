package com.zyyme.workdayalarmclock

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper

class HomeLauncherActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        const val EXTRA_RETURN_PACKAGE = "homeReturnPackage"
        const val EXTRA_IS_HOME_OPEN = "isHomeOpen"

        /**
         * Home 启动器覆盖在原应用任务上时，优先把自己的任务移到后台，
         * 让系统直接显示下面原本的任务，避免重新启动应用入口页面。
         */
        fun revealPreviousTask(activity: Activity, packageName: String?): Boolean {
            if (packageName.isNullOrBlank() || packageName == activity.packageName) return false
            return try {
                activity.moveTaskToBack(true)
                true
            } catch (_: RuntimeException) {
                false
            }
        }

        fun returnToPreviousApp(activity: Activity, packageName: String?): Boolean {
            if (packageName.isNullOrBlank() || packageName == activity.packageName) return false
            val launchIntent = activity.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            return try {
                activity.startActivity(launchIntent)
                activity.finish()
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        openHomeDestination(runStartupApps = true)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        openHomeDestination(runStartupApps = false)
    }

    private fun openHomeDestination(runStartupApps: Boolean) {
        val returnPackage = findPreviousPackage()

        val clockIsVisible = ClockActivity.me?.isActivityStarted == true ||
                DeskActivity.me?.isActivityStarted == true

        val destination = if (clockIsVisible) {
            Intent(this, AppListActivity::class.java).apply {
                putExtra(EXTRA_RETURN_PACKAGE, returnPackage)
                putExtra(EXTRA_IS_HOME_OPEN, true)
            }
        } else {
            MeSettings.applyClockTheme(this)
            MeSettings.createClockIntent(this).apply {
                putExtra("clockMode", true)
                putExtra(EXTRA_RETURN_PACKAGE, returnPackage)
            }
        }

        if (runStartupApps && StartupAppHelper.tryHandleLauncherBootActivity(this, intent)) {
            // HomeLauncherActivity uses Theme.NoDisplay. It must finish before its first
            // onResume completes, otherwise Android throws "did not call finish()".
            // Startup apps and the clock are therefore launched asynchronously while this
            // transparent trampoline exits immediately.
            val startupAppCount = StartupAppHelper.getStartupAppPackageNames(this).size
            val destinationContext = applicationContext
            destination.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            mainHandler.postDelayed({
                try {
                    destinationContext.startActivity(destination)
                } catch (_: Exception) {
                    // The launcher may have been disabled or the task may have gone away;
                    // there is no visible activity here from which to recover.
                }
            }, startupAppCount * StartupAppHelper.STARTUP_APP_DELAY_MILLIS)
            finish()
            return
        }

        startActivity(destination)
        finish()
    }

    private fun findPreviousPackage(): String? {
        return try {
            val activityManager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
            activityManager?.getRunningTasks(8)
                ?.asSequence()
                ?.mapNotNull { it.topActivity?.packageName }
                ?.firstOrNull { it != packageName && it != "com.android.systemui" }
        } catch (_: SecurityException) {
            null
        }
    }
}
