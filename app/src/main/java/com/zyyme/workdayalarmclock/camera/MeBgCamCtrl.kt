package com.zyyme.workdayalarmclock.camera

import android.content.Context
import android.os.PowerManager
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.net.Socket

/**
 * 老王家卖的BodyGuargz监控控制 19块钱的MT6739 1+8GB Android9.0
 */
internal class MeBgCamCtrl(context: Context) {
    private val powerManager = context.applicationContext
        .getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val writeMethod = powerManager?.javaClass?.methods?.firstOrNull {
        it.name == "setSysClassStringValue" &&
            it.parameterTypes.contentEquals(arrayOf(String::class.java, String::class.java))
    }
    private val rotationDirectionMethod = powerManager?.javaClass?.methods?.firstOrNull {
        it.name == "setRotationAngleDir" && it.parameterTypes.size == 1 &&
            it.parameterTypes[0] == Integer.TYPE
    }
    private val rotationEnableMethod = powerManager?.javaClass?.methods?.firstOrNull {
        it.name == "setRotationAngleEnable" && it.parameterTypes.size == 1 &&
            it.parameterTypes[0] == java.lang.Boolean.TYPE
    }

    // 根据系统系统接口自动开关
    val enabled: Boolean = writeMethod != null

    fun matches(path: String): Boolean = path == "/bgcam" || path.startsWith("/bgcam/")

    fun handle(socket: Socket, method: String, uri: Uri) {
        if (!enabled) {
            respond(socket, 404, "Not Found", JSONObject().put("error", "unsupported device"))
            return
        }
        if (method == "GET" && (uri.path == "/bgcam" || uri.path == "/bgcam/" || uri.path == "/bgcam/status")) {
            respond(socket, 200, "OK", status())
            return
        }
        if (method != "GET") {
            respond(socket, 405, "Method Not Allowed", JSONObject().put("error", "GET required"))
            return
        }
        val result = when (uri.path) {
            "/bgcam/pan" -> {
                val direction = uri.getQueryParameter("dir")?.toIntOrNull()
                if (direction == null || direction !in 0..3) {
                    respond(socket, 400, "Bad Request", JSONObject().put("error", "dir must be 0..3"))
                    return
                }
                val ok = invoke(rotationDirectionMethod, direction) && invoke(rotationEnableMethod, true)
                JSONObject().put("ok", ok).put("direction", direction)
            }
            "/bgcam/stop" -> JSONObject().put("ok", invoke(rotationEnableMethod, false))
            "/bgcam/led" -> {
                val on = uri.getQueryParameter("on")?.toBooleanStrictOrNull()
                if (on == null) {
                    respond(socket, 400, "Bad Request", JSONObject().put("error", "on must be true or false"))
                    return
                }
                val value1 = if (on) "2 1" else "2 3"
                val value2 = if (on) "4 1" else "4 3"
                val ok1 = write(LED_ONE, value1)
                val ok2 = write(LED_TWO, value2)
                JSONObject().put("ok", ok1 && ok2).put("on", on)
            }
            "/bgcam/infrared" -> setNode(uri, INFRARED, "on")
            "/bgcam/infrared-sub" -> setNode(uri, INFRARED_SUB, "on")
            "/bgcam/light-sensor" -> setNode(uri, ALS_ENABLE, "on")
            "/bgcam/sound-sensor" -> setNode(uri, SOUND_SENSOR, "on")
            else -> {
                respond(socket, 404, "Not Found", JSONObject().put("error", "unknown bgcam endpoint"))
                return
            }
        }
        respond(socket, 200, "OK", result)
    }

    private fun setNode(uri: Uri, path: String, parameter: String): JSONObject {
        val on = uri.getQueryParameter(parameter)?.toBooleanStrictOrNull()
            ?: return JSONObject().put("ok", false).put("error", "$parameter must be true or false")
        return JSONObject().put("ok", write(path, if (on) "1" else "0")).put("on", on)
    }

    private fun status(): JSONObject = JSONObject()
        .put("supported", enabled)
        .put("infrared", read(INFRARED) ?: JSONObject.NULL)
        .put("infraredSub", read(INFRARED_SUB) ?: JSONObject.NULL)
        .put("alsEnabled", read(ALS_ENABLE) ?: JSONObject.NULL)
        .put("alsData", read(ALS_DATA) ?: JSONObject.NULL)
        .put("led1", read(LED_ONE) ?: JSONObject.NULL)
        .put("led2", read(LED_TWO) ?: JSONObject.NULL)
        .put("soundSensor", read(SOUND_SENSOR) ?: JSONObject.NULL)

    private fun read(path: String): String? = try {
        File(path).takeIf { it.canRead() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    private fun write(path: String, value: String): Boolean = invoke(writeMethod, path, value)

    private fun invoke(method: java.lang.reflect.Method?, vararg args: Any): Boolean = try {
        method?.invoke(powerManager, *args)
        method != null
    } catch (_: Exception) {
        false
    }

    private fun respond(socket: Socket, code: Int, reason: String, json: JSONObject) {
        val body = json.toString().toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.0 $code $reason\r\n" +
            "Connection: close\r\nCache-Control: no-cache, no-store\r\n" +
            "Content-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\n\r\n"
        socket.getOutputStream().apply {
            write(header.toByteArray(Charsets.US_ASCII))
            write(body)
            flush()
        }
    }

    fun playerUi(): String = if (!enabled) "" else """
<section id="bgcam"><h3>摄像头云台与传感器</h3>
<div class="bgcam-pad"><button data-dir="0">↑</button><button data-dir="1">↓</button><button data-dir="2">←</button><button data-dir="3">→</button><button id="bgcamStop">停止</button></div>
<div class="bgcam-actions"><button id="bgcamLed">补光灯</button><button id="bgcamIr">红外灯</button><button id="bgcamIrSub">副红外灯</button><button id="bgcamAls">光线传感器</button><button id="bgcamSound">声音传感器</button></div>
<pre id="bgcamStatus">正在读取设备状态…</pre></section>
<style>#bgcam{margin-top:22px;padding:14px;border:1px solid #444;border-radius:8px}#bgcam h3{margin:0 0 12px}.bgcam-pad,.bgcam-actions{display:flex;flex-wrap:wrap;gap:8px;margin:8px 0}.bgcam-pad button{flex:1;min-width:58px}.bgcam-actions button{flex:1 1 130px}#bgcamStatus{white-space:pre-wrap;color:#bbb}</style>
<script>
(()=>{const base='/bgcam',out=document.getElementById('bgcamStatus');let state={};async function call(path){const r=await fetch(base+path,{cache:'no-store'});const j=await r.json();if(!r.ok||j.ok===false)throw Error(j.error||('HTTP '+r.status));return j}function render(){out.textContent='红外灯: '+state.infrared+' / 副灯: '+state.infraredSub+'\n光线传感器: '+state.alsEnabled+'，读数: '+state.alsData+'\nLED1: '+state.led1+' / LED2: '+state.led2+'\n声音传感器: '+state.soundSensor}async function refresh(){try{state=await call('/status');render()}catch(e){out.textContent='读取设备状态失败: '+e.message}}async function action(path){try{await call(path);await refresh()}catch(e){out.textContent='控制失败: '+e.message}}document.querySelectorAll('#bgcam [data-dir]').forEach(b=>{b.addEventListener('pointerdown',e=>{e.preventDefault();action('/pan?dir='+b.dataset.dir)});b.addEventListener('pointerup',()=>action('/stop'));b.addEventListener('pointerleave',()=>action('/stop'))});document.getElementById('bgcamStop').onclick=()=>action('/stop');document.getElementById('bgcamLed').onclick=()=>action('/led?on='+!(String(state.led1||'').includes('1')));document.getElementById('bgcamIr').onclick=()=>action('/infrared?on='+(String(state.infrared||'0').trim()==='0'));document.getElementById('bgcamIrSub').onclick=()=>action('/infrared-sub?on='+(String(state.infraredSub||'0').trim()==='0'));document.getElementById('bgcamAls').onclick=()=>action('/light-sensor?on='+(String(state.alsEnabled||'0').trim()==='0'));document.getElementById('bgcamSound').onclick=()=>action('/sound-sensor?on='+(String(state.soundSensor||'0').trim()==='0'));refresh();setInterval(refresh,3000)})();
</script>
""".trimIndent()

    companion object {
        private const val INFRARED = "/sys/devices/platform/infrared/infrared_debug"
        private const val INFRARED_SUB = "/sys/devices/platform/infrared/infraredsub_debug"
        private const val ALS_ENABLE = "/sys/bus/i2c/drivers/mn25713_main/als_enable"
        private const val ALS_DATA = "/sys/bus/i2c/drivers/mn25713_main/als_data"
        private const val LED_ONE = "/sys/bus/platform/drivers/aw9963e/aw9963e/aw9963eled1"
        private const val LED_TWO = "/sys/bus/platform/drivers/aw9963e/aw9963e/aw9963eled2"
        private const val SOUND_SENSOR = "/sys/devices/platform/ln4120/ln4120_status"
    }
}
