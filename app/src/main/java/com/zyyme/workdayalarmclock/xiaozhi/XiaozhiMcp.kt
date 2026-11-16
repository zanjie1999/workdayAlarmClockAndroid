package com.zyyme.workdayalarmclock.xiaozhi

import org.json.JSONArray
import org.json.JSONObject

/**
 * 设备侧 MCP 服务端：只给 AI 暴露一个"执行播放器命令"的工具。
 *
 * 交互全部跑在小智的 WebSocket 上，报文是 `{"type":"mcp","payload":{jsonrpc,id,method|result}}`。
 * 这里只做 JSON 拼装和命令校验，不碰网络与线程（便于 JVM 单测）。
 *
 * 只实现 legacy（2024-11-05）流程；收到新版的 `server/discover` 一律回 -32601，
 * 网关就会退回 `initialize` + `tools/list`，这是官方文档给老固件的兼容路径。
 */
object XiaozhiMcp {

    /** 工具名，`module.action` 风格，和官方固件保持一致 */
    const val TOOL_NAME = "self.player.command"

    private const val PROTOCOL_VERSION = "2024-11-05"
    private const val SERVER_NAME = "workday-alarm-clock"

    /** 给模型看的工具说明：命令清单 */
    private const val TOOL_DESCRIPTION = """控制本机的音乐播放器和闹钟，优先使用，只能原样发送下面这些命令：
播放默认歌单：1key
播放歌曲：playmusic {歌曲id}
收到明确的歌曲id时直接执行 playmusic {歌曲id}，不要先查询或搜索歌曲信息，接口不互通
播放歌单：playlist {歌单id}
播放链接：play {音频url}
暂停播放：pause
继续播放：resume
下一首：next
上一首：prev
停止播放：stop
定时停止播放：stop {分钟}
音量加：volp
音量减：volm
设置音量：vol {1-100}
查询闹钟：alarmlist（返回一行json，格式 {"alarms":[{"time":"0730","types":["1","6"]}]}）
添加闹钟：alarmadd {HHmm} {类型,类型}
删除闹钟：alarmdel {HHmm} {类型,类型}（只删掉对得上的类型）
闹钟时间用24小时制4位数字，例如0730
闹钟类型用半角逗号分隔：1法定工作日 2法定休息日 3仅一次 4每天 5周日 6周一 7周二 8周三 9周四 10周五 11周六，4位数字表示某月某日如0101
删除前先用alarmlist查一下，类型要和已有闹钟对得上才删"""

    /**
     * 处理一条 MCP 请求，返回要发回去的 JSON；返回 null 表示不用回（通知类消息）。
     *
     * @param runCommand 真正去执行命令（已通过白名单校验），返回一句给模型看的结果
     */
    fun handle(
        request: XiaozhiProtocol.ServerEvent.Mcp,
        serverVersion: String,
        runCommand: (String) -> String
    ): String? {
        val id = request.id
        val sessionId = request.sessionId
        return when (request.method) {
            "initialize" -> XiaozhiProtocol.mcpResultJson(
                id, initializeResult(serverVersion), sessionId
            )
            "tools/list" -> XiaozhiProtocol.mcpResultJson(id, toolsResult(), sessionId)
            "tools/call" -> XiaozhiProtocol.mcpResultJson(
                id, callResult(request.params, runCommand), sessionId
            )
            "ping" -> XiaozhiProtocol.mcpResultJson(id, JSONObject(), sessionId)
            else -> {
                // notifications/* 没有 id，不能回包
                if (request.method.startsWith("notifications/")) {
                    null
                } else {
                    XiaozhiProtocol.mcpErrorJson(
                        id, -32601, "Method not found: ${request.method}", sessionId
                    )
                }
            }
        }
    }

    private fun initializeResult(serverVersion: String): JSONObject = JSONObject()
        .put("protocolVersion", PROTOCOL_VERSION)
        .put("capabilities", JSONObject().put("tools", JSONObject()))
        .put("serverInfo", JSONObject()
            .put("name", SERVER_NAME)
            .put("version", serverVersion))

    private fun toolsResult(): JSONObject {
        val commandProperty = JSONObject()
            .put("type", "string")
            .put("description", "完整命令原文，例如 1key、playmusic 1234、stop 30、vol 40、alarmlist、alarmadd 0730 1,6")
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("command", commandProperty))
            .put("required", JSONArray().put("command"))
        val tool = JSONObject()
            .put("name", TOOL_NAME)
            .put("description", TOOL_DESCRIPTION)
            .put("inputSchema", schema)
        return JSONObject().put("tools", JSONArray().put(tool))
    }

    private fun callResult(params: JSONObject?, runCommand: (String) -> String): JSONObject {
        val name = params?.optString("name").orEmpty()
        if (name != TOOL_NAME) return errorResult("未知工具：$name")
        val raw = params?.optJSONObject("arguments")?.optString("command").orEmpty()
        val command = XiaozhiPlayerCommands.parse(raw)
            ?: return errorResult("不支持的播放器命令：$raw")
        return textResult(runCommand(command), isError = false)
    }

    private fun textResult(text: String, isError: Boolean): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        .put("isError", isError)

    private fun errorResult(message: String): JSONObject = textResult(message, isError = true)
}
