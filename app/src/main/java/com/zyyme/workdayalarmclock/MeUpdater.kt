package com.zyyme.workdayalarmclock

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 从 GitHub Releases 检查新版本、加速下载 apk 并调起安装
 * 进度全部输出到控制台(logView)
 */
object MeUpdater {
    private const val REPO = "zanjie1999/workdayAlarmClockAndroid"
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPO/releases/latest"
    // 加速下载，拼在完整的 github 地址前面
    private const val ACCELERATE_PREFIX = "https://ghproxy.net/"
    // 下载用浏览器的UA，部分镜像会按UA分流
    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    private const val CONNECT_TIMEOUT_MS = 15000
    private const val READ_TIMEOUT_MS = 30000
    private const val BUFFER_SIZE = 64 * 1024
    private const val PROGRESS_MIN_INTERVAL_MS = 500L

    // root 静默更新相关
    private const val KEY_ROOT_GRANTED = "root_granted"
    private const val ROOT_CHECK_TIMEOUT_MS = 60_000L
    private const val ROOT_INSTALL_TIMEOUT_MS = 120_000L
    private const val SILENT_INSTALL_TMP_PATH = "/data/local/tmp/workdayalarmclock_update.apk"
    private const val APP_MAIN_ACTIVITY = "com.zyyme.workdayalarmclock/.MainActivity"

    private data class ReleaseInfo(val version: String, val apkUrl: String, val apkName: String)

    // 常见 su 路径，存在即认为可能已 root（文件检测不弹框）
    private val SU_PATHS = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/data/adb/magisk"
    )

    private data class RootResult(val exitCode: Int, val output: String)

    @Volatile
    private var updating = false

    // go 发了 HASUPDATE 说明有新版本，只用来决定菜单里「立即更新」是否显示
    @Volatile
    var hasUpdate = false
        private set

    // 等用户授权「安装未知应用」的安装包，授权返回后自动继续装
    @Volatile
    private var pendingApk: File? = null

    /**
     * go 侧检测到有新版本，这里只记标志，不打日志
     */
    fun onHasUpdate() {
        hasUpdate = true
    }

    /**
     * 文件检测 su 是否存在（不执行 su，不弹授权框）
     */
    fun hasSuBinary(): Boolean = SU_PATHS.any { File(it).exists() }

    /**
     * 是否已成功申请到 root 授权（持久化标记）
     */
    fun isRootGranted(context: Context): Boolean =
        MeSettings.isEnabled(context, KEY_ROOT_GRANTED)

    /**
     * 执行一次 su 申请 root 授权，成功后写标记；未授权设备会弹授权框
     */
    fun requestRootPermission(context: Context): Boolean {
        val result = runAsRoot("id", ROOT_CHECK_TIMEOUT_MS)
        if (result.exitCode == 0) {
            MeSettings.setEnabled(context, KEY_ROOT_GRANTED, true)
            return true
        }
        return false
    }

    /**
     * 用 su 执行命令，返回退出码与输出；带超时（用轮询避免依赖 Java8 的 waitFor(timeout)，兼容 Android4.0）
     */
    private fun runAsRoot(command: String, timeoutMs: Long): RootResult {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().use { br ->
                        br.forEachLine { output.appendLine(it) }
                    }
                } catch (_: Exception) {
                }
            }
            reader.start()

            var exitCode = -1
            var exited = false
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    exitCode = process.exitValue()
                    exited = true
                    break
                } catch (_: IllegalThreadStateException) {
                    Thread.sleep(50)
                }
            }
            if (!exited) {
                process.destroy()
            }
            try {
                reader.join(2000)
            } catch (_: InterruptedException) {
            }
            RootResult(if (exited) exitCode else -1, output.toString())
        } catch (e: Exception) {
            RootResult(-1, e.message ?: "")
        }
    }

    /**
     * 复制到 /data/local/tmp 后静默安装，成功后拉起 MainActivity
     */
    private fun silentInstallAndRestart(apk: File): Boolean {
        val command = "cp '${apk.absolutePath}' $SILENT_INSTALL_TMP_PATH" +
            " && chmod 644 $SILENT_INSTALL_TMP_PATH" +
            " && pm install -r -d -g $SILENT_INSTALL_TMP_PATH" +
            " && am start -n $APP_MAIN_ACTIVITY"
        log("开始静默安装 ${apk.name}")
        val result = runAsRoot(command, ROOT_INSTALL_TIMEOUT_MS)
        val success = result.exitCode == 0
        log(if (success) "静默安装成功，正在重启" else "静默安装失败：${result.output.trim()}")
        return success
    }

    fun log(msg: String) {
        val service = MeService.me
        if (service != null) {
            service.print2LogView(msg)
            return
        }
        Log.d("logView", msg)
        MainActivity.print2LogViewHandler?.obtainMessage(0, msg)?.sendToTarget()
    }

    /**
     * 检查并更新 会开线程跑，日志输出到控制台
     */
    fun checkAndUpdate(context: Context) {
        if (updating) {
            log("正在更新中，请稍候…")
            return
        }
        updating = true
        val appContext = context.applicationContext
        Thread {
            try {
                doUpdate(appContext)
            } catch (e: Exception) {
                log("更新出错：$e")
                e.printStackTrace()
            } finally {
                updating = false
            }
        }.start()
    }

    private fun doUpdate(context: Context, silent: Boolean = false) {
        val currentVersion = versionName(context)
        val info = resolveReleaseInfo(currentVersion) ?: return
        val apk = download(context, info) ?: return
        log("下载完成 ${apk.absolutePath}")
        if (silent) {
            silentInstallAndRestart(apk)
        } else {
            install(context, apk)
        }
    }

    /**
     * root 设备收到 HASUPDATE 后自动静默更新：下载→静默安装→重启，无需用户操作
     */
    fun autoSilentUpdate(context: Context) {
        if (!hasSuBinary()) return
        if (updating) {
            log("正在更新中，请稍候…")
            return
        }
        updating = true
        val appContext = context.applicationContext
        Thread {
            try {
                doUpdate(appContext, silent = true)
            } catch (e: Exception) {
                log("静默更新出错：$e")
                e.printStackTrace()
            } finally {
                updating = false
            }
        }.start()
    }

    /**
     * 一律以 GitHub API 的最新 release 为准
     */
    private fun resolveReleaseInfo(currentVersion: String): ReleaseInfo? {
        log("当前版本 $currentVersion，正在检查新版本…")
        val latest = fetchLatestRelease()
        if (!isNewer(latest.version, currentVersion)) {
            log("已是最新版本 ${latest.version}，无需更新")
            return null
        }
        log("发现新版本 ${latest.version}（当前 $currentVersion）")
        return latest
    }

    /**
     * 获取最新的release信息
     */
    private fun fetchLatestRelease(): ReleaseInfo {
        val json = httpGetString(LATEST_RELEASE_API)
        val obj = JSONObject(json)
        val version = obj.optString("tag_name").trim()
            .ifEmpty { obj.optString("name").trim() }
            .removePrefix("v")
        val assets = obj.optJSONArray("assets")
        val picked = if (assets == null) null else pickAsset(assets)
        val apkName = picked?.second ?: "app-${apkFlavor()}-release.apk"
        val apkUrl = picked?.first
            ?: "https://github.com/$REPO/releases/download/$version/$apkName"
        return ReleaseInfo(version, apkUrl, apkName)
    }

    /**
     * 按设备架构挑对应的apk x86/x86_64用x86，arm32用arm7，其他(arm64等)用full
     */
    private fun pickAsset(assets: JSONArray): Pair<String, String>? {
        val flavor = apkFlavor()
        var fallback: Pair<String, String>? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (!name.endsWith(".apk") || url.isEmpty()) continue
            if (name.contains("-${flavor}-")) return url to name
            if (fallback == null) fallback = url to name
        }
        return fallback
    }

    private fun apkFlavor(): String {
        val abis = if (Build.VERSION.SDK_INT >= 21) {
            Build.SUPPORTED_ABIS
        } else {
            arrayOf(Build.CPU_ABI ?: "")
        }
        return when {
            abis.any { it.startsWith("x86") } -> "x86"
            abis.any { it.startsWith("arm64") } -> "full"
            abis.any { it.startsWith("arm") } -> "arm7"
            else -> "full"
        }
    }

    /**
     * 先走加速地址，失败再用github原始地址
     */
    private fun download(context: Context, info: ReleaseInfo): File? {
        val dir = updateDir(context)
        val file = File(dir, info.apkName)
        if (file.exists() && !file.delete()) {
            log("删除旧安装包失败 ${file.absolutePath}")
        }
        val urls = ArrayList<String>(2)
        // 已经是加速地址或非github地址就不要重复加前缀
        if (info.apkUrl.contains("github.com/") && !info.apkUrl.startsWith(ACCELERATE_PREFIX)) {
            urls.add(ACCELERATE_PREFIX + info.apkUrl)
        }
        urls.add(info.apkUrl)
        urls.forEach { url ->
            log("开始下载${if (url.startsWith(ACCELERATE_PREFIX)) "（加速）" else "（直连）"} $url")
            try {
                if (downloadTo(url, file)) return file
            } catch (e: Exception) {
                log("下载出错：${e.message}")
            }
        }
        log("下载失败，请稍后再试")
        return null
    }

    private fun downloadTo(urlStr: String, file: File): Boolean {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", BROWSER_USER_AGENT)
            setRequestProperty("Accept", "*/*")
        }
        UnsafeHttps.configure(conn)
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                log("下载返回 HTTP $code")
                return false
            }
            val total = conn.contentLength.toLong()
            log("安装包大小 ${if (total > 0) sizeText(total) else "未知"}")
            var done = 0L
            var lastLog = 0L
            val buffer = ByteArray(BUFFER_SIZE)
            conn.inputStream.use { input ->
                FileOutputStream(file).use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        done += read
                        val now = System.currentTimeMillis()
                        if (now - lastLog >= PROGRESS_MIN_INTERVAL_MS) {
                            lastLog = now
                            if (total > 0) {
                                val percent = (done * 100 / total).toInt().coerceIn(0, 100)
                                log("下载进度 $percent% ${sizeText(done)}/${sizeText(total)}")
                            } else {
                                log("已下载 ${sizeText(done)}")
                            }
                        }
                    }
                }
            }
            if (total > 0 && file.length() != total) {
                log("下载不完整 ${file.length()}/$total")
                return false
            }
            log("下载进度 100% ${sizeText(file.length())}")
            return true
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 优先放到 /sdcard/Download，没有存储权限就放到应用自己的目录
     */
    private fun updateDir(context: Context): File {
        if (canWritePublicStorage(context)) {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (ensureDir(downloadDir)) return downloadDir
        }
        val externalDir = context.getExternalFilesDir("update")
        if (externalDir != null && ensureDir(externalDir)) return externalDir
        val cacheDir = File(context.cacheDir, "update")
        ensureDir(cacheDir)
        return cacheDir
    }

    private fun ensureDir(dir: File): Boolean = dir.exists() || dir.mkdirs()

    private fun canWritePublicStorage(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun install(context: Context, apk: File) {
        if (!canInstallPackages(context)) {
            // 记下来，用户授权完回到前台自动继续装
            pendingApk = apk
            log("请允许「安装未知应用」权限，授权返回后自动继续安装")
            openInstallPermissionSettings(context)
            return
        }
        pendingApk = null
        try {
            val uri: Uri = if (Build.VERSION.SDK_INT >= 24) {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            } else {
                Uri.fromFile(apk)
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            log("开始安装 ${apk.name}")
            context.startActivity(intent)
        } catch (e: Exception) {
            log("调起安装失败：${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 回到前台时调用，用户刚授权「安装未知应用」的话继续装
     */
    fun resumePendingInstall(context: Context) {
        val apk = pendingApk ?: return
        if (!apk.exists()) {
            pendingApk = null
            log("安装包不存在了 ${apk.absolutePath}")
            return
        }
        if (!canInstallPackages(context)) return
        install(context, apk)
    }

    private fun canInstallPackages(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    private fun openInstallPermissionSettings(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri()
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            // 部分rom没有这个页面，退回到应用详情
            log("打开安装权限设置失败：${e.message}")
            try {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "package:${context.packageName}".toUri()
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e2: Exception) {
                log("打开应用详情失败：${e2.message}")
            }
        }
    }

    private fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    /**
     * 版本号比较 latest 比 current 新返回true
     */
    fun isNewer(latest: String, current: String): Boolean {
        val l = parseVersion(latest)
        val c = parseVersion(current)
        val size = maxOf(l.size, c.size)
        for (i in 0 until size) {
            val a = l.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun parseVersion(version: String): List<Int> =
        version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .split('.', '-', '_', '+')
            .map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }

    private fun httpGetString(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "workdayAlarmClockAndroid")
        }
        UnsafeHttps.configure(conn)
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun sizeText(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1fMB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format(Locale.US, "%.0fKB", bytes / 1024.0)
        else -> "${bytes}B"
    }
}
