package com.zyyme.workdayalarmclock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

class MeAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {

    }

    override fun onInterrupt() {
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 辅助功能服务 开机启动
        Log.d("workdayAlarmClock", "MyAccessibilityService onServiceConnected startAtBooted")
        Toast.makeText(this, "使用无障碍服务开机启动咯~", Toast.LENGTH_LONG).show()
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }
}