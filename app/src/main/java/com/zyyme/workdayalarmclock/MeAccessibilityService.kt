package com.zyyme.workdayalarmclock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.annotation.RequiresApi

class MeAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 窗口切换
        if (event == null) return
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d("MeAccessibilityService", "包名：${event.packageName}类名：${event.className}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                if (event.packageName != "com.android.settings") return
                var rootNode = rootInActiveWindow
//                if (rootNode == null) {
//                    Thread.sleep(200)
//                    rootNode = rootInActiveWindow
//                }
                if (rootNode == null) return
                else {
//                    Log.d("MeAccessibilityService", "获取到根节点")
                }
                var nodes = rootNode.findAccessibilityNodeInfosByText("热点")
                if (nodes.isNotEmpty()) {
                    if (nodes.size == 1 && nodes[0].text == "热点和网络共享") {
                        nodes[0].parent.parent.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Thread.sleep(1000)
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        return
                    }
//                    Log.d("MeAccessibilityService", "获取到子节点")
                    for (node1 in nodes) {
                        val box = node1.parent.parent
                        if (box.childCount > 1) {
                            val node2 = node1.parent.parent.getChild(1)
                            if (node2.childCount > 0) {
                                val node = node2.getChild(0)
                                if (node.className == "android.widget.Switch") {
                                    Log.d("MeAccessibilityService", "找到开关")
                                    if (!node.isChecked) {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                                            Log.d("MeAccessibilityService", "点击开关")
                                            box.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                            Thread.sleep(500)
                                            performGlobalAction(GLOBAL_ACTION_BACK)
                                            return
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                rootInActiveWindow ?: return
            }
        }
    }

    override fun onInterrupt() {
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (MeService.me != null) {
            // 已手动启动
            return
        }
        // 辅助功能服务 开机启动
        Log.d("workdayAlarmClock", "MyAccessibilityService onServiceConnected startAtBooted")
        Toast.makeText(this, "使用无障碍服务开机启动咯~", Toast.LENGTH_LONG).show()
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }
}