package com.zyyme.workdayalarmclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机启动监听器
 */
class StartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getAction().toString();
        StartupAppHelper.startAtBooted(context, pendingResult = goAsync())
    }
}
