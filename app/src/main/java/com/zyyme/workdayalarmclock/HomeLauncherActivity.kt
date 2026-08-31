package com.zyyme.workdayalarmclock

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class HomeLauncherActivity : Activity() {
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

        val clockIsVisible = ClockActivity.me?.isActivityStarted == true ||
                DeskActivity.me?.isActivityStarted == true

        if (runStartupApps && StartupAppHelper.tryHandleLauncherBootActivity(this, intent)) {
            val startupAppCount = StartupAppHelper.getStartupAppPackageNames(this).size
            Thread.sleep(startupAppCount * StartupAppHelper.STARTUP_APP_DELAY_MILLIS)
        }

        val destination = if (clockIsVisible) {
            Intent(this, AppListActivity::class.java).apply {
                putExtra(AppListActivity.EXTRA_OPENED_FROM_HOME, true)
            }
        } else {
            MeSettings.createClockIntent(this).apply {
                putExtra("clockMode", true)
            }
        }
        startActivity(destination)
        finish()
    }
}
