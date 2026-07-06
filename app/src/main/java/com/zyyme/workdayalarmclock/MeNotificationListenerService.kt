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
        private val URL_PLACEHOLDERS = listOf("{title}", "{msg}", "{pkg}", "{app}")
    }

    private val forwardedOngoingNotificationIds = mutableSetOf<String>()

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

        if (isOngoingNotification(notification)) {
            if (shouldForwardOngoingNotification(sbn)) {
                sendForwardRequest(forwardUrl, title, content, sbn.packageName)
            }
            return
        }

        sendForwardRequest(forwardUrl, title, content, sbn.packageName)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) {
            return
        }

        synchronized(forwardedOngoingNotificationIds) {
            forwardedOngoingNotificationIds.remove(getNotificationId(sbn))
        }
    }

    private fun shouldForwardOngoingNotification(sbn: StatusBarNotification): Boolean {
        return synchronized(forwardedOngoingNotificationIds) {
            forwardedOngoingNotificationIds.add(getNotificationId(sbn))
        }
    }

    private fun isOngoingNotification(notification: Notification): Boolean {
        return notification.flags and Notification.FLAG_ONGOING_EVENT != 0
    }

    private fun getNotificationId(sbn: StatusBarNotification): String {
        return "${sbn.packageName}:${sbn.id}"
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

    private fun sendForwardRequest(baseUrl: String, title: String, content: String, packageName: String) {
        Thread(Runnable {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(buildForwardUrl(baseUrl, title, content, packageName)).openConnection() as HttpURLConnection
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

    private fun buildForwardUrl(baseUrl: String, title: String, content: String, packageName: String): String {
        if (!hasUrlPlaceholder(baseUrl)) {
            return baseUrl + urlEncode("$title：$content")
        }

        var forwardUrl = baseUrl
            .replace("{title}", urlEncode(title))
            .replace("{msg}", urlEncode(content))
            .replace("{pkg}", urlEncode(packageName))

        if (forwardUrl.contains("{app}")) {
            forwardUrl = forwardUrl.replace("{app}", urlEncode(getAppName(packageName)))
        }

        return forwardUrl
    }

    private fun hasUrlPlaceholder(url: String): Boolean {
        return URL_PLACEHOLDERS.any { url.contains(it) }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val packageInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(packageInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        MeService.me?.print2LogView(message)
    }
}
