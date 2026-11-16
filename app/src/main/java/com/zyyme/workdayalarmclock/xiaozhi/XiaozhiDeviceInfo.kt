package com.zyyme.workdayalarmclock.xiaozhi

import android.content.Context
import android.os.Build
import com.zyyme.workdayalarmclock.MeSettings
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

/**
 * 设备标识：小智服务端用 Device-Id（MAC）认设备，用 Client-Id 认客户端。
 * 两者都必须稳定，否则每次都会被当成一台新设备。
 *
 * MAC 是本机随机生成的，不去读真网卡：6.0 之后读不到（一律 02:00:00:00:00:00），
 * 而且同一台机器刷两个包也会撞车。第一次用到时生成并保存，之后一直复用，
 * 只有用户点「重新生成设备ID」才会换。
 */
object XiaozhiDeviceInfo {

    private val random = SecureRandom()

    /** 有保存值就用；没有就生成一个随机 MAC 并写回设置。全小写、冒号分隔。 */
    fun deviceMac(context: Context): String {
        val saved = MeSettings.getXiaozhiDeviceMac(context)
        if (saved.isNotBlank()) return normalizeMac(saved)
        val generated = generateMac()
        MeSettings.setXiaozhiDeviceMac(context, generated)
        return generated
    }

    /** 只生成不保存，给输入框的「随机」按钮用，点保存才生效 */
    fun newMac(): String = generateMac()

    fun clientId(context: Context): String {
        val saved = MeSettings.getXiaozhiClientId(context)
        if (saved.isNotBlank()) return saved
        val generated = UUID.randomUUID().toString()
        MeSettings.setXiaozhiClientId(context, generated)
        return generated
    }

    /** 小智 OTA 的请求体，字段照 esp32 版打，缺了服务端也能宽容处理。 */
    fun otaRequestBody(context: Context, versionName: String): String {
        val mac = deviceMac(context)
        val application = JSONObject()
            .put("name", "xiaozhi")
            .put("version", versionName)
        val board = JSONObject()
            .put("type", "generic")
            .put("name", Build.MANUFACTURER + Build.MODEL)
            .put("manufacturer", "")
            .put("mac", mac)
        val protocol = JSONObject()
            .put("features", JSONObject().put("mcp", true))
        return JSONObject()
            .put("version", 2)
            // 官方固件在这里上报 Lang::CODE，服务端据此决定系统播报用哪种语言
            .put("language", XiaozhiProtocol.DEVICE_LANGUAGE)
            .put("mac_address", mac)
            .put("uuid", clientId(context))
            .put("application", application)
            .put("board", board)
            .put("protocol", protocol)
            .toString()
    }

    fun normalizeMac(raw: String): String {
        val cleaned = raw.trim().lowercase().replace("-", ":")
        return if (cleaned.isBlank()) generateMac() else cleaned
    }

    /**
     * 随机 MAC：首字节保留本地管理位（0x02）、清掉组播位，保证是合法的单播本地地址。
     * 用 SecureRandom 是因为这个值就是设备身份，别用可预测的种子。
     */
    private fun generateMac(): String {
        val bytes = ByteArray(6)
        random.nextBytes(bytes)
        bytes[0] = ((bytes[0].toInt() and 0xFC) or 0x02).toByte()
        val sb = StringBuilder(17)
        for (i in 0 until 6) {
            if (i > 0) sb.append(':')
            sb.append(String.format(Locale.US, "%02x", bytes[i]))
        }
        return sb.toString()
    }
}
