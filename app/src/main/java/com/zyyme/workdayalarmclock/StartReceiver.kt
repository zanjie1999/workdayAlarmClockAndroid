package com.zyyme.workdayalarmclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import java.io.File

/**
 * 开机启动监听器
 */
class StartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getAction().toString();

        Log.v("workdayAlarmClock", "startAtBooted")
        if (File(context.filesDir.absolutePath + "/disable").exists()) {
            Log.v("workdayAlarmClock", "disabledisabledisable 开机不启动")
            return
        }
        Toast.makeText(context, "开机启动咯~", Toast.LENGTH_LONG).show()
        val intent = Intent(context, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}