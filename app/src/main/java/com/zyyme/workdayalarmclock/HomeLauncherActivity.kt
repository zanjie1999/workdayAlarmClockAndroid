package com.zyyme.workdayalarmclock

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle

class HomeLauncherActivity : Activity() {
    companion object {
        const val EXTRA_RETURN_PACKAGE = "homeReturnPackage"
        const val EXTRA_IS_HOME_OPEN = "isHomeOpen"

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

        if (runStartupApps && StartupAppHelper.tryHandleLauncherBootActivity(this, intent)) {
            val startupAppCount = StartupAppHelper.getStartupAppPackageNames(this).size
            Thread.sleep(startupAppCount * StartupAppHelper.STARTUP_APP_DELAY_MILLIS)
        }

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
        startActivity(destination)
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
