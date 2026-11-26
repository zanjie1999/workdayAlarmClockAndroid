package com.zyyme.workdayalarmclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import android.view.KeyEvent

/**
 * 媒体按键监听器
 * 这传入参数是什么逆天写法
 */
class MeMediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {

        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_MEDIA_BUTTON -> {
                val keyEvent =intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent ?: return
                when (keyEvent.action) {
                    KeyEvent.ACTION_DOWN -> {
                        MeService.me?.keyHandle(keyEvent.keyCode, true)
                    }
                    KeyEvent.ACTION_UP -> {
                        MeService.me?.keyHandle(keyEvent.keyCode, false)
                        Log.d("logView MediaButton", "code: $keyEvent.keyCode")
                    }
                }

            }
            MeService.ACTION_PLAY -> {
                MeService.me?.keyHandleAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                Log.d("logView MediaButton", "ACTION_PLAY")
            }
            MeService.ACTION_NEXT -> {
                MeService.me?.keyHandleAction(2147483645)
                Log.d("logView MediaButton", "ACTION_NEXT")
            }
            MeService.ACTION_PREVIOUS -> {
                MeService.me?.keyHandleAction(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                Log.d("logView MediaButton", "ACTION_PREVIOUS")
            }
            MeService.ACTION_STOP -> {
                MeService.me?.keyHandleAction(KeyEvent.KEYCODE_MEDIA_STOP)
                Log.d("logView MediaButton", "ACTION_STOP")
            }
            MeService.ACTION_FORWARD -> {
                MeService.me?.keyHandleAction(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
                Log.d("logView MediaButton", "ACTION_FORWARD")
            }
            MeService.ACTION_WAKE -> {
                val ctx = context ?: return
                // 不管服务还在不在，先把 CPU 锁拿住，给启动服务留出时间
                MeService.acquireNextWakeLock(ctx)
                val service = MeService.me
                if (service != null) {
                    service.toGo("wake")
                } else {
                    // APP被系统杀掉了，重新启动服务，Go会跑一遍定时器
                    startWakeService(ctx, MeService.ACTION_WAKE)
                }
                Log.d("logView MediaButton", "ACTION_WAKE running=${service != null}")
            }
            MeService.ACTION_PREWAKE -> {
                val ctx = context ?: return
                MeService.acquireNextWakeLock(ctx)
                if (MeService.me == null) {
                    startWakeService(ctx, MeService.ACTION_PREWAKE)
                }
                Log.d("logView MediaButton", "ACTION_PREWAKE running=${MeService.me != null}")
            }
            else -> {
                Log.d("logView MediaButton", "未知action $action")
            }
        }

    }

    /**
     * 闹钟到点时如果服务已经不在了，用这个把 MeService 拉起来。
     * Go 启动后会自己跑一次定时器，这里不用再补 wake。
     */
    private fun startWakeService(context: Context, action: String) {
        val intent = Intent(context, MeService::class.java).setAction(action)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.d("logView MediaButton", "$action 服务不在，已请求启动")
        } catch (e: Exception) {
            Log.d("logView MediaButton", "$action 启动服务失败 $e")
        }
    }
}
