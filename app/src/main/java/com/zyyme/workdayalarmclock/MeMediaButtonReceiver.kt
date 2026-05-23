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
                MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, true)
                Log.d("logView MediaButton", "ACTION_PLAY")
            }
            MeService.ACTION_NEXT -> {
                MeService.me?.keyHandle(2147483645, true)
                Log.d("logView MediaButton", "ACTION_NEXT")
            }
            MeService.ACTION_PREVIOUS -> {
                MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_PREVIOUS, true)
                Log.d("logView MediaButton", "ACTION_PREVIOUS")
            }
            MeService.ACTION_STOP -> {
                MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_STOP, true)
                Log.d("logView MediaButton", "ACTION_STOP")
            }
            MeService.ACTION_FORWARD -> {
                MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, true)
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
