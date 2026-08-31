package com.zyyme.workdayalarmclock

import android.annotation.SuppressLint
import android.app.Notification
import android.os.Build
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.ArrayDeque
import java.util.HashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@SuppressLint("OverrideAbstract")
@RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
class MeNotificationListenerService : NotificationListenerService() {
    companion object {
        private const val TAG = "MeNotificationForward"
        private const val CONNECT_TIMEOUT_MILLIS = 5000
        private const val READ_TIMEOUT_MILLIS = 5000
        private const val DEDUP_WINDOW_MILLIS = 1000L
        private const val FORWARD_INTERVAL_MILLIS = 3000L
        private val URL_PLACEHOLDERS = listOf("{title}", "{msg}", "{pkg}", "{app}")
    }

    private data class PendingForward(
        val baseUrl: String,
        val title: String,
        val content: String,
        val packageName: String
    )

    private val forwardingExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val pendingForwards = ArrayDeque<PendingForward>()
    private val recentForwardKeys = HashMap<String, Long>()
    private var scheduledForward: ScheduledFuture<*>? = null

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

        enqueueForward(PendingForward(forwardUrl, title, content, sbn.packageName))
    }

    override fun onDestroy() {
        synchronized(pendingForwards) {
            scheduledForward?.cancel(false)
            scheduledForward = null
        }
        forwardingExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun enqueueForward(forward: PendingForward) {
        val now = SystemClock.elapsedRealtime()
        synchronized(pendingForwards) {
            val iterator = recentForwardKeys.entries.iterator()
            while (iterator.hasNext()) {
                if (now - iterator.next().value >= DEDUP_WINDOW_MILLIS) {
                    iterator.remove()
                }
            }
            val dedupKey = "${forward.packageName}\u0000${forward.title}\u0000${forward.content}"
            val lastForwardAt = recentForwardKeys[dedupKey]
            if (lastForwardAt != null && now - lastForwardAt < DEDUP_WINDOW_MILLIS) {
                return
            }
            recentForwardKeys[dedupKey] = now
            pendingForwards.addLast(forward)
            if (scheduledForward == null) {
                scheduledForward = forwardingExecutor.schedule(
                    { flushPendingForwards() },
                    FORWARD_INTERVAL_MILLIS,
                    TimeUnit.MILLISECONDS
                )
            }
        }
    }

    private fun flushPendingForwards() {
        val forwards = synchronized(pendingForwards) {
            scheduledForward = null
            if (pendingForwards.isEmpty()) {
                emptyList()
            } else {
                val result = pendingForwards.toList()
                pendingForwards.clear()
                result
            }
        }

        if (forwards.isNotEmpty()) {
            sendForwardRequest(forwards)
        }

        synchronized(pendingForwards) {
            if (pendingForwards.isNotEmpty() && scheduledForward == null && !forwardingExecutor.isShutdown) {
                scheduledForward = forwardingExecutor.schedule(
                    { flushPendingForwards() },
                    FORWARD_INTERVAL_MILLIS,
                    TimeUnit.MILLISECONDS
                )
            }
        }
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

    private fun sendForwardRequest(forwards: List<PendingForward>) {
        val first = forwards.first()
        var connection: HttpURLConnection? = null
        try {
            connection = URL(buildForwardUrl(first.baseUrl, forwards)).openConnection() as HttpURLConnection
            UnsafeHttps.configure(connection)
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
    }

    private fun buildForwardUrl(baseUrl: String, forwards: List<PendingForward>): String {
        val title = forwards.joinToString("\n") { it.title }
        val content = forwards.joinToString("\n") { it.content }
        val packageName = forwards.joinToString("\n") { it.packageName }
        if (!hasUrlPlaceholder(baseUrl)) {
            return baseUrl + urlEncode(forwards.joinToString("\n") { "${it.title}：${it.content}" })
        }

        var forwardUrl = baseUrl
            .replace("{title}", urlEncode(title))
            .replace("{msg}", urlEncode(content))
            .replace("{pkg}", urlEncode(packageName))

        if (forwardUrl.contains("{app}")) {
            forwardUrl = forwardUrl.replace(
                "{app}",
                urlEncode(forwards.joinToString("\n") { getAppName(it.packageName) })
            )
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
