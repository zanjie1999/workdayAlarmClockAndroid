package com.zyyme.workdayalarmclock

import android.annotation.SuppressLint
import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

@SuppressLint("OverrideAbstract")
@RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
class MeNotificationListenerService : NotificationListenerService() {
    companion object {
        private const val TAG = "MeNotificationForward"
        private const val CONNECT_TIMEOUT_MILLIS = 5000
        private const val READ_TIMEOUT_MILLIS = 5000
    }

    private data class OngoingNotificationState(
        var firstForwarded: Boolean,
        var latestPayload: String
    )

    private val ongoingNotificationStates = mutableMapOf<String, OngoingNotificationState>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) {
            return
        }

        val forwardUrl = MeSettings.getNotificationForwardUrl(this)
        if (forwardUrl.isEmpty()) {
            return
        }

        val notification = sbn.notification ?: return
        val (title, content) = getNotificationTitleAndContent(notification)
        if (title.isEmpty() && content.isEmpty()) {
            return
        }

        val payload = "$title：$content"
        if (isOngoingNotification(notification)) {
            if (shouldForwardOngoingNotification(sbn, payload)) {
                sendForwardRequest(forwardUrl, payload)
            }
            return
        }

        sendForwardRequest(forwardUrl, payload)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) {
            return
        }

        val payload = synchronized(ongoingNotificationStates) {
            ongoingNotificationStates.remove(getNotificationKey(sbn))?.latestPayload
        } ?: return

        val forwardUrl = MeSettings.getNotificationForwardUrl(this)
        if (forwardUrl.isEmpty()) {
            return
        }

        sendForwardRequest(forwardUrl, payload)
    }

    private fun shouldForwardOngoingNotification(sbn: StatusBarNotification, payload: String): Boolean {
        return synchronized(ongoingNotificationStates) {
            val key = getNotificationKey(sbn)
            val state = ongoingNotificationStates[key]
            if (state == null) {
                ongoingNotificationStates[key] = OngoingNotificationState(true, payload)
                true
            } else {
                state.latestPayload = payload
                if (state.firstForwarded) {
                    false
                } else {
                    state.firstForwarded = true
                    true
                }
            }
        }
    }

    private fun isOngoingNotification(notification: Notification): Boolean {
        return notification.flags and Notification.FLAG_ONGOING_EVENT != 0
    }

    private fun getNotificationKey(sbn: StatusBarNotification): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return sbn.key
        }

        return "${sbn.packageName}:${sbn.id}:${sbn.tag.orEmpty()}"
    }

    private fun getNotificationTitleAndContent(notification: Notification): Pair<String, String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val extras = notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val content = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            return Pair(title, content)
        }

        return Pair("", notification.tickerText?.toString().orEmpty())
    }

    private fun sendForwardRequest(baseUrl: String, payload: String) {
        Thread(Runnable {
            var connection: HttpURLConnection? = null
            try {
                val encodedPayload = URLEncoder.encode(payload, "UTF-8")
                connection = URL(baseUrl + encodedPayload).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS

                val responseCode = connection.responseCode
                val responseStream = if (responseCode >= HttpURLConnection.HTTP_BAD_REQUEST) {
                    connection.errorStream
                } else {
                    connection.inputStream
                }
                responseStream?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).use { reader ->
                        while (reader.readLine() != null) {
                            // Consume the response so the connection can finish cleanly.
                        }
                    }
                }
                log("通知转发完成 HTTP $responseCode")
            } catch (e: Exception) {
                Log.e(TAG, "通知转发失败", e)
                log("通知转发失败 ${e.message ?: e.toString()}")
            } finally {
                connection?.disconnect()
            }
        }).start()
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        MeService.me?.print2LogView(message)
    }
}
