package com.zyyme.workdayalarmclock.camera

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.net.Socket

/**
 * Ampe会走路的音箱 底盘（双轮）控制。
 *
 * 协议来自厂测 APK com.sunchip.hardwaretest#sendCmdToMachine(int)：
 * 把十进制数字直接写入 /sys/class/action/mx612e/workup，不追加换行。
 *
 *   0 = 停止（原厂字段名 ACTION_TINGWARD）
 *   1 = 前进
 *   2 = 后退
 *   5 = 动作
 *   3 / 4 = 左转 / 右转
 *
 * 注意：节点是锁存的，写 1 之后轮子会一直转，必须再写 0 才停。
 * 所以除松手发 0 之外还有 2 秒看门狗：超时没收到指令就自动归零。
 */
internal class MeAmpeWheelCtrl(context: Context) {
    private val powerManager = context.applicationContext
        .getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val writeMethod = powerManager?.javaClass?.methods?.firstOrNull {
        it.name == "setSysClassStringValue" &&
            it.parameterTypes.contentEquals(arrayOf(String::class.java, String::class.java))
    }

    private val lock = Any()
    @Volatile private var lastCmd = 0
    @Volatile private var lastAt = 0L
    @Volatile private var channel = "none"
    @Volatile private var handler: Handler? = null
    @Volatile private var thread: HandlerThread? = null

    private val tick = object : Runnable {
        override fun run() {
            synchronized(lock) {
                if (lastCmd != 0 && SystemClock.elapsedRealtime() - lastAt >= WATCHDOG_MS) {
                    lastCmd = 0
                    writeNode("0")
                }
            }
            handler?.postDelayed(this, TICK_MS)
        }
    }

    /** 有 sysfs 节点才认为这台机器能控制 */
    val enabled: Boolean = File(WORKUP).exists()

    /** 唯一对外接口：传一个数字 0..9，返回是否写成功 */
    fun send(cmd: Int): Boolean {
        if (!enabled || cmd !in 0..9) return false
        synchronized(lock) {
            lastCmd = cmd
            lastAt = SystemClock.elapsedRealtime()
            return writeNode(cmd.toString())
        }
    }

    /** 服务启动时调用：先归零，防止上次残留的指令让车自己跑 */
    fun onServerStart() {
        if (!enabled) return
        try {
            send(0)
            startWatchdog()
        } catch (_: Exception) {
            // 底盘出问题不能连累 HTTP 服务启动
        }
    }

    /** 服务关闭前调用，必须早于关 socket，否则车会带着最后一条指令继续跑 */
    fun shutdown() {
        if (!enabled) return
        try {
            send(0)
            stopWatchdog()
        } catch (_: Exception) {
        }
    }

    fun matches(path: String): Boolean = path == "/ampe/wheel"

    fun handle(socket: Socket, method: String, uri: Uri) {
        if (!enabled) {
            respond(socket, 404, "Not Found", JSONObject().put("error", "unsupported device"))
            return
        }
        if (method != "GET") {
            respond(socket, 405, "Method Not Allowed", JSONObject().put("error", "GET required"))
            return
        }
        if (uri.path != "/ampe/wheel") {
            respond(socket, 404, "Not Found", JSONObject().put("error", "unknown ampe endpoint"))
            return
        }
        val cmd = uri.getQueryParameter("cmd")?.toIntOrNull()
        if (cmd == null || cmd !in 0..9) {
            respond(socket, 400, "Bad Request", JSONObject().put("error", "cmd must be 0..9"))
            return
        }
        val ok = send(cmd)
        respond(socket, 200, "OK", JSONObject().put("ok", ok).put("cmd", cmd).put("channel", channel))
    }

    fun playerUi(): String {
        if (!enabled) return ""
        return """
<section id="ampewheel"><h3>轮子控制</h3>
<div class="ampewheel-row"><button id="ampeWheelLeft" type="button">左转</button><button id="ampeWheelForward" type="button">前进</button><button id="ampeWheelBack" type="button">后退</button><button id="ampeWheelRight" type="button">右转</button></div>
<style>#ampewheel{margin-top:22px;padding:14px;border:1px solid #444;border-radius:8px}#ampewheel h3{margin:0 0 12px}.ampewheel-row{display:flex;flex-wrap:nowrap;gap:8px}#ampewheel button{flex:1 1 0;width:auto;min-width:0;min-height:56px;padding:0 4px;white-space:nowrap;font-size:16px;border-radius:6px;touch-action:none;user-select:none;-webkit-user-select:none;-webkit-touch-callout:none;-webkit-tap-highlight-color:transparent}#ampewheel button.on{background:#1769aa;border-color:#278bd2}</style>
<script>
(function(){
  var beat=null,cmd=0;
  function send(c){new Image().src='/ampe/wheel?cmd='+c+'&t='+Date.now()}
  function mark(node){var all=document.querySelectorAll('#ampewheel button');for(var i=0;i<all.length;i++){all[i].className=(all[i]===node)?'on':''}}
  function press(node,c){cmd=c;mark(node);send(c);if(beat)clearInterval(beat);beat=setInterval(function(){send(c)},1000)}
  function release(){if(beat){clearInterval(beat);beat=null}mark(null);if(cmd){cmd=0;send(0)}}
  var map=[['ampeWheelLeft',3],['ampeWheelForward',1],['ampeWheelBack',2],['ampeWheelRight',4]];
  for(var i=0;i<map.length;i++){(function(node,c){
    function down(e){if(e&&e.cancelable)e.preventDefault();press(node,c)}
    function up(e){if(e&&e.cancelable)e.preventDefault();release()}
    if(!node)return;
    if(window.PointerEvent){
      node.addEventListener('pointerdown',down);
      node.addEventListener('pointerup',up);
      node.addEventListener('pointercancel',up);
      node.addEventListener('pointerleave',up);
    }else if('ontouchstart' in window){
      node.addEventListener('touchstart',down);
      node.addEventListener('touchend',up);
      node.addEventListener('touchcancel',up);
    }else{
      node.addEventListener('mousedown',down);
      node.addEventListener('mouseup',up);
      node.addEventListener('mouseleave',up);
    }
  })(document.getElementById(map[i][0]),map[i][1])}
  window.addEventListener('blur',release);
  window.addEventListener('pagehide',release);
  window.addEventListener('beforeunload',release);
  document.addEventListener('visibilitychange',function(){if(document.hidden)release()});
})();
</script>
</section>
""".trimIndent()
    }

    private fun startWatchdog() {
        synchronized(lock) {
            if (thread != null) return
            val t = HandlerThread("ampewheel")
            t.start()
            thread = t
            val h = Handler(t.looper)
            handler = h
            h.postDelayed(tick, TICK_MS)
        }
    }

    private fun stopWatchdog() {
        var h: Handler? = null
        var t: HandlerThread? = null
        synchronized(lock) {
            h = handler
            t = thread
            handler = null
            thread = null
        }
        h?.removeCallbacksAndMessages(null)
        @Suppress("DEPRECATION")
        t?.quit()
    }

    /** 先直接写文件（厂测 APK 就是这么写的），失败再退回 ROM 藏起来的 PowerManager 接口 */
    private fun writeNode(value: String): Boolean {
        val direct = try {
            FileOutputStream(WORKUP).use {
                it.write(value.toByteArray())
                it.flush()
            }
            true
        } catch (_: Exception) {
            false
        }
        if (direct) {
            channel = "direct"
            return true
        }
        val reflected = invoke(writeMethod, WORKUP, value)
        channel = if (reflected) "reflect" else "none"
        return reflected
    }

    private fun invoke(method: Method?, vararg args: Any): Boolean = try {
        if (method == null) false else {
            method.invoke(powerManager, *args)
            true
        }
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

    companion object {
        private const val WORKUP = "/sys/class/action/mx612e/workup"
        private const val WATCHDOG_MS = 1500L
        private const val TICK_MS = 1000L
    }
}