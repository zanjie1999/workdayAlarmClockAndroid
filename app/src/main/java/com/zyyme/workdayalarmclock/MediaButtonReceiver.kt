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
    var itf : MeMBRInterface? = null
        set(value) {
            field = value
        }
    override fun onReceive(context: Context?, intent: Intent?) {

        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_MEDIA_BUTTON -> {
                val keyEvent =intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent ?: return
                when (keyEvent.action) {
                    KeyEvent.ACTION_DOWN -> {
                        Log.d("MediaButtonReceiver", "itf: $itf code: $keyEvent.keyCode")
                        // 回调实现的方法
//                        itf?.keyHandle(keyEvent.keyCode)
                        // TODO: interface传不进来 因为只能静态注册监听器 无法进行交互
                    }
                }

            }
        }

    }

    /**
     * 用于实现回调的接口
     */
    interface MeMBRInterface {
        fun keyHandle(keyCode: Int): Boolean
    }

}
