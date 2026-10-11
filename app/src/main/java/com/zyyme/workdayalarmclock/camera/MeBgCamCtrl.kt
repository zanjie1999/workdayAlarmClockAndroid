package com.zyyme.workdayalarmclock.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

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
    private val rotationStatusMethod = powerManager?.javaClass?.methods?.firstOrNull {
        it.name == "getRotationAngleStatus" && it.parameterTypes.isEmpty()
    }
    private val lastWrittenValues = ConcurrentHashMap<String, String>()
    private val angleHandler = Handler(Looper.getMainLooper())
    private val angleLock = Any()
    private val sensorStateLock = Any()
    @Volatile private var currentAngleDegrees = 0
    @Volatile private var personDetected = false
    @Volatile private var soundDetected = false
    private val clearPersonDetected = Runnable {
        synchronized(sensorStateLock) { personDetected = false }
    }
    private val clearSoundDetected = Runnable {
        synchronized(sensorStateLock) { soundDetected = false }
    }
    private var pendingAngleDegrees: Int? = null
    private val applyPendingAngle = object : Runnable {
        override fun run() {
            if ((callResult(rotationStatusMethod) as? Int) == 1) {
                angleHandler.postDelayed(this, 200L)
                return
            }
            val target = synchronized(angleLock) {
                pendingAngleDegrees.also { pendingAngleDegrees = null }
            } ?: return
            val delta = ((target - currentAngleDegrees) * 1.3846154f).toInt()
            currentAngleDegrees = target
            if (delta != 0) {
                invoke(rotationDirectionMethod, delta)
                invoke(rotationEnableMethod, true)
            }
        }
    }

    // 根据系统系统接口自动开关
    val enabled: Boolean = writeMethod != null

    /** Consume device-generated sensor key events only on supported camera hardware. */
    fun handleSensorKeyEvent(keyCode: Int, isDown: Boolean): Boolean {
        if (!enabled) return false
        val clearRunnable = when (keyCode) {
            android.view.KeyEvent.KEYCODE_F11 -> clearPersonDetected
            android.view.KeyEvent.KEYCODE_CAMERA -> clearSoundDetected
            else -> return false
        }
        if (isDown) {
            synchronized(sensorStateLock) {
                if (keyCode == android.view.KeyEvent.KEYCODE_F11) {
                    personDetected = true
                    angleHandler.removeCallbacks(clearRunnable)
                    angleHandler.postDelayed(clearRunnable, 3000)

                } else {
                    soundDetected = true
                    angleHandler.removeCallbacks(clearRunnable)
                    angleHandler.postDelayed(clearRunnable, 30000)
                }
            }
        }
        return true
    }

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
            "/bgcam/angle" -> {
                val degrees = uri.getQueryParameter("degrees")?.toIntOrNull()
                if (degrees == null || degrees !in 0..360) {
                    respond(socket, 400, "Bad Request", JSONObject().put("error", "degrees must be 0..360"))
                    return
                }
                if (rotationDirectionMethod == null || rotationEnableMethod == null || rotationStatusMethod == null) {
                    JSONObject().put("ok", false).put("error", "rotation methods are unavailable")
                } else {
                    synchronized(angleLock) { pendingAngleDegrees = degrees }
                    angleHandler.removeCallbacks(applyPendingAngle)
                    angleHandler.post(applyPendingAngle)
                    JSONObject().put("ok", true).put("degrees", degrees)
                }
            }
            "/bgcam/led" -> {
                val id = uri.getQueryParameter("id")?.toIntOrNull()
                if (id == null || id !in 1..4) {
                    respond(socket, 400, "Bad Request", JSONObject().put("error", "id must be 1..4"))
                    return
                }
                val on = uri.getQueryParameter("on")?.toBooleanStrictOrNull()
                if (on == null) {
                    respond(socket, 400, "Bad Request", JSONObject().put("error", "on must be true or false"))
                    return
                }
                val ledPath = ledPath(id)
                val value = "${id * 2} ${if (on) 1 else 3}"
                JSONObject().put("ok", write(ledPath, value)).put("id", id).put("on", on)
            }
            "/bgcam/infrared" -> setNode(uri, INFRARED, "on")
            "/bgcam/person-sensor" -> setNode(uri, PERSON_SENSOR, "on")
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
        .put("rotationDegrees", currentAngleDegrees)
        .put("rotationBusy", (callResult(rotationStatusMethod) as? Int) == 1)
        .put("infrared", read(INFRARED) ?: JSONObject.NULL)
        .put("personSensor", read(PERSON_SENSOR) ?: JSONObject.NULL)
        .put("personDetected", personDetected)
        .put("led1", read(LED_ONE) ?: JSONObject.NULL)
        .put("led2", read(LED_TWO) ?: JSONObject.NULL)
        .put("led3", read(LED_THREE) ?: JSONObject.NULL)
        .put("led4", read(LED_FOUR) ?: JSONObject.NULL)
        .put("soundSensor", read(SOUND_SENSOR) ?: JSONObject.NULL)
        .put("soundDetected", soundDetected)

    private fun read(path: String): String? = try {
        File(path).readText().trim().takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    } ?: lastWrittenValues[path]

    private fun writeAndRemember(path: String, value: String): Boolean {
        val success = invoke(writeMethod, path, value)
        if (success) lastWrittenValues[path] = value
        return success
    }

    private fun write(path: String, value: String): Boolean = writeAndRemember(path, value)

    private fun invoke(method: java.lang.reflect.Method?, vararg args: Any): Boolean = try {
        if (method == null) false else {
            method.invoke(powerManager, *args)
            true
        }
    } catch (_: Exception) {
        false
    }

    private fun callResult(method: java.lang.reflect.Method?): Any? = try {
        method?.invoke(powerManager)
    } catch (_: Exception) {
        null
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
<section id="bgcam"><h3>摄像头控制</h3>
<label class="bgcam-angle">云台角度 <span id="bgcamDegrees">0°</span><input id="bgcamAngle" type="range" min="0" max="360" step="1" value="0"></label>
<div class="bgcam-actions"><button id="bgcamLed">下方补光灯</button><button id="bgcamTopLed">顶部补光灯</button><button id="bgcamIr">红外灯</button></div>
<div class="bgcam-actions"><button id="bgcamPerson">人体传感器</button><button id="bgcamSound">声音传感器</button></div>
<pre id="bgcamStatus">正在读取设备状态…</pre></section>
<style>#bgcam{margin-top:22px;padding:14px;border:1px solid #444;border-radius:8px}#bgcam h3{margin:0 0 12px}.bgcam-angle{display:flex;align-items:center;gap:12px;color:#ddd}.bgcam-angle span{min-width:48px;text-align:right}.bgcam-angle input{flex:1;min-width:0}.bgcam-actions{display:flex;flex-wrap:wrap;gap:8px;margin:8px 0}.bgcam-actions button{flex:1 1 130px}#bgcamStatus{white-space:pre-wrap;color:#bbb}</style>
<script>
(()=>{
  const base='/bgcam',out=document.getElementById('bgcamStatus'),slider=document.getElementById('bgcamAngle'),degrees=document.getElementById('bgcamDegrees');
  let state={},last={};
  async function call(path){const r=await fetch(base+path,{cache:'no-store'});const j=await r.json();if(!r.ok||j.ok===false)throw Error(j.error||('HTTP '+r.status));return j}
  function val(key){return state[key]??last[key]??'不可读取'}
  function on(key){const v=String(val(key)).trim();return v==='1'||v.includes(' 1')}
  function render(){out.textContent='红外灯: '+val('infrared')+'\n人体传感器开关: '+val('personSensor')+'\n人体检测: '+(state.personDetected?'有人':'无人')+'\n下方补光: '+val('led1')+'\n顶部补光: '+val('led2')+'\n声音传感器开关: '+val('soundSensor')+'\n声音触发: '+(state.soundDetected?'触发中':'无触发');if(document.activeElement!==slider){slider.value=state.rotationDegrees||0;degrees.textContent=slider.value+'°'}}
  async function refresh(){try{state=await call('/status');render()}catch(e){out.textContent='读取设备状态失败: '+e.message}}
  async function action(path,key,value){try{await call(path);if(key)last[key]=value?'1':'0';await refresh()}catch(e){out.textContent='控制失败: '+e.message}}
  async function setLed(id,value){try{await call('/led?id='+id+'&on='+value);last['led'+id]=(id*2)+' '+(value?1:3);await refresh()}catch(e){out.textContent='补光灯控制失败: '+e.message}}
  slider.addEventListener('input',()=>degrees.textContent=slider.value+'°');
  slider.addEventListener('change',()=>action('/angle?degrees='+slider.value));
  document.getElementById('bgcamLed').onclick=()=>setLed(1,!on('led1'));
  document.getElementById('bgcamTopLed').onclick=()=>setLed(2,!on('led2'));
  document.getElementById('bgcamIr').onclick=()=>action('/infrared?on='+(!on('infrared')),'infrared',!on('infrared'));
  document.getElementById('bgcamPerson').onclick=()=>action('/person-sensor?on='+(!on('personSensor')),'personSensor',!on('personSensor'));
  document.getElementById('bgcamSound').onclick=()=>action('/sound-sensor?on='+(!on('soundSensor')),'soundSensor',!on('soundSensor'));
  refresh();setInterval(refresh,3000)
})();
</script>
""".trimIndent()

    companion object {
        private const val INFRARED = "/sys/devices/platform/infrared/infrared_debug"
        private const val PERSON_SENSOR = "/sys/devices/platform/pir/elmos_debug"
        private const val LED_ONE = "/sys/bus/platform/drivers/aw9963e/aw9963e/aw9963eled1"
        private const val LED_TWO = "/sys/bus/platform/drivers/aw9963e/aw9963e/aw9963eled2"
        private const val LED_THREE = "/sys/bus/platform/drivers/aw9963e/aw9963e/aw9963eled3"
        private const val LED_FOUR = "/sys/bus/platform/drivers/aw9963e/aw9963e/aw9963eled4"
        private const val SOUND_SENSOR = "/sys/devices/platform/ln4120/ln4120_status"
    }

    private fun ledPath(id: Int): String = when (id) {
        1 -> LED_ONE
        2 -> LED_TWO
        3 -> LED_THREE
        else -> LED_FOUR
    }
}
