package com.zyyme.workdayalarmclock

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

class MeDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, "设备管理员权限已启用", Toast.LENGTH_SHORT).show()
        Log.d("MyDeviceAdminReceiver", "Device Admin: Enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(context, "设备管理员权限已禁用", Toast.LENGTH_SHORT).show()
        Log.d("MyDeviceAdminReceiver", "Device Admin: Disabled")
    }

    override fun onPasswordChanged(context: Context, intent: Intent) {
        super.onPasswordChanged(context, intent)
        Log.d("MyDeviceAdminReceiver", "Device Admin: Password Changed")
    }

    // 你可以根据需要覆盖其他回调方法
}
