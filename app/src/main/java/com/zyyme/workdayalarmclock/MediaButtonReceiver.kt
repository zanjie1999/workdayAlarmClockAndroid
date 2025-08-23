package com.zyyme.workdayalarmclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent

/**
 * 媒体按键监听器
 * 这传入参数是什么逆天写法
 */
class MediaButtonReceiver : BroadcastReceiver() {
    companion object {
        var keyDownTime = 0L
    }
    override fun onReceive(context: Context?, intent: Intent?) {

        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_MEDIA_BUTTON -> {
                val keyEvent =intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent ?: return
                when (keyEvent.action) {
                    KeyEvent.ACTION_DOWN -> {
                        keyDownTime = System.currentTimeMillis()
                    }
                    KeyEvent.ACTION_UP -> {
                        MeService.me?.keyHandle(keyEvent.keyCode, System.currentTimeMillis() - MainActivity.keyDownTime)
                        Log.d("logView MediaButton", "mbrHandler: $MainActivity.mbrHandler code: $keyEvent.keyCode")
                    }
                }

            }
            MeService.ACTION_PLAY -> {
                MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
                Log.d("logView MediaButton", "ACTION_PLAY")
            }
            MeService.ACTION_NEXT -> {
                MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_NEXT, 0)
                Log.d("logView MediaButton", "ACTION_NEXT")
            }
            MeService.ACTION_PREVIOUS -> {
                MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_PREVIOUS, 0)
                Log.d("logView MediaButton", "ACTION_PREVIOUS")
            }
            MeService.ACTION_STOP -> {
                MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_STOP, 0)
                Log.d("logView MediaButton", "ACTION_STOP")
            }
            else -> {
                Log.d("logView MediaButton", "未知action $action")
            }
        }

    }
}
