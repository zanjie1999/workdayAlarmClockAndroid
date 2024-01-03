package com.zyyme.workdayalarmclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.KeyEvent

/**
 * 媒体按键监听器
 * 这传入参数是什么逆天写法
 */
class MediaButtonReceiver : BroadcastReceiver() {
    companion object {
        // 逆天 系统每调用一次new一个 只能放这
        var lastT: Long = 0
    }
    override fun onReceive(context: Context?, intent: Intent?) {

        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_MEDIA_BUTTON -> {
                val keyEvent =intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent ?: return
                when (keyEvent.action) {
                    KeyEvent.ACTION_DOWN -> {
                        val t = System.currentTimeMillis()
                        // 息屏收到的会重复 过滤掉重复的
                        if (t - lastT < 600) {
                            Log.d("MediaButtonReceiver", "跳过重复按键 ${keyEvent.keyCode}")
                            return
//                        } else {
//                            MainActivity.me?.print2LogView((t - lastT).toString())
                        }
                        lastT = t


                        Log.d("MediaButtonReceiver", "mbrHandler: $MainActivity.mbrHandler code: $keyEvent.keyCode")
                        // 回调
//                        val message = Message.obtain()
//                        message.obj = keyEvent.keyCode
//                        MeService.mediaButtonReceiverHandler?.sendMessage(message)
                        MeService.me?.keyHandle(keyEvent.keyCode)
                    }
                }

            }
        }

    }
}
