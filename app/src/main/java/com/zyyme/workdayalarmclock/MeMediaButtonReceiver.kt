package com.zyyme.workdayalarmclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
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
                MeService.me?.toGo("wake")
                Log.d("logView MediaButton", "ACTION_WAKE")
            }
            else -> {
                Log.d("logView MediaButton", "未知action $action")
            }
        }

    }
}
