package com.zyyme.workdayalarmclock.xiaozhi

import org.json.JSONArray
import org.json.JSONObject

/**
 * 设备侧 MCP 服务端：给 AI 暴露三个工具
 * - `self.player.command` 播放器/闹钟命令（白名单校验后写 Go 命令管道）
 * - `self.get_device_status` 设备状态，只读
 * - `self.camera.take_photo` 拍照，只在「允许拍照」开关打开时才出现在 tools/list 里
 *
 * 交互全部跑在小智的 WebSocket 上，报文是 `{"type":"mcp","payload":{jsonrpc,id,method|result}}`。
 * 这里只做 JSON 拼装和命令校验，不碰网络与线程（便于 JVM 单测）。
 *
 * **两版都支持**：网关先发 `server/discover`，响应里列出两个版本，它挑新版；
 * 老网关不发发现、直接 `initialize`，那就还是旧版流程。回包形状按**每条请求**里的
 * `params._meta` 判断（新版每条请求都带版本标记），不依赖连接级状态，重连也不会串。
 *
 * 拍照走官方那套：声明 `me.xiaozhi/vision` 扩展 → 网关下发 vision 地址（含 token）
 * → 设备把 JPEG 以 multipart POST 上去 → 拿回文字。**不直接把图片塞进工具结果**，
 * 网关不认 image content（会回 "Image not supported"）。
 */
object XiaozhiMcp {

    /** 工具名，`module.action` 风格，和官方固件保持一致 */
    const val TOOL_NAME = "self.player.command"
    const val TOOL_STATUS = "self.get_device_status"
    const val TOOL_PHOTO = "self.camera.take_photo"

    private const val SERVER_NAME = "workday-alarm-clock"

    /** 工具目录缓存时长，网关按这个 TTL 失效重取 */
    private const val CACHE_TTL_MS = 60000

    /** 网关下发的视觉服务地址，空串表示还没下发 */
    @Volatile
    private var visionEndpoint = ""

    @Volatile
    private var visionAccessToken = ""

    /** 当前视觉地址，给宿主打日志/排查用 */
    fun visionUrl(): String = visionEndpoint

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
查询正在播放：nowplay（返回一行json，格式 {"nowPlaying":"歌名"}，没在播时为空）
闹钟时间用24小时制4位数字，例如0730
闹钟类型用半角逗号分隔：1法定工作日 2法定休息日 3仅一次 4每天 5周日 6周一 7周二 8周三 9周四 10周五 11周六，4位数字表示某月某日如0101
删除前先用alarmlist查一下，类型要和已有闹钟对得上才删"""

    /** 设备状态工具说明 */
    private const val STATUS_DESCRIPTION = """查询本机的实时状态，用户问电量、有没有在充电、音量多大、IP地址是多少、屏幕亮没亮、亮度几档、还剩多少存储和内存、现在几点几分几号星期几时都用它。
不需要参数，返回一行json，例如 {"battery":87,"charging":true,"volume":40,"screen":"on","brightness":3,"ip":"192.168.1.23","storageFreeMB":1024,"memFreeMB":210,"time":"2026-11-17 03:04 周二","playing":false,"version":"1.4.2"}
电量或音量读不到时对应字段是 -1"""

    /** 拍照工具说明 */
    private const val PHOTO_DESCRIPTION = """用本机摄像头拍一张当前画面。用户问「你看到什么」「我手上拿的是什么」「前面有人吗」这类需要看画面的问题时用它。
把想问的问题放在 question 里，拍完会返回对画面的文字描述。
camera 是摄像头编号，默认 0；0 通常是后置，1 通常是前置，用户说「看看我」「看你自己」时用 1"""

    /**
     * 处理一条 MCP 请求，返回要发回去的 JSON；返回 null 表示不用回（通知类消息）。
     *
     * @param allowPhoto 「允许拍照」开关，关着时工具列表里不出现拍照工具，也不声明视觉扩展
     * @param runCommand 执行一条已通过白名单校验的播放器命令，返回一句给模型看的结果
     * @param deviceStatus 读设备状态，返回一行 json
     * @param capturePhoto (问题, 摄像头编号, 视觉地址, token) → 画面描述；失败返回 null
     */
    fun handle(
        request: XiaozhiProtocol.ServerEvent.Mcp,
        serverVersion: String,
        allowPhoto: Boolean,
        runCommand: (String) -> String,
        deviceStatus: () -> String,
        capturePhoto: (String, Int, String, String) -> String?
    ): String? {
        val id = request.id
        val sessionId = request.sessionId
        val params = request.params
        val modern = XiaozhiProtocol.isModernMcp(params)

        return when (request.method) {
            // 新版第一步：探测支持的版本与能力，不再走 initialize
            "server/discover" -> reply(
                id, sessionId, discoverResult(allowPhoto), true, serverVersion
            )

            // 旧版第一步；新版不会发这个
            "initialize" -> {
                applyVisionConfig(params?.optJSONObject("capabilities")?.optJSONObject("vision"))
                reply(id, sessionId, initializeResult(serverVersion), false, serverVersion)
            }

            "tools/list" -> reply(
                id, sessionId, toolsResult(params, allowPhoto, modern), modern, serverVersion
            )

            "tools/call" -> {
                // 新版每次调用都带 vision 配置
                applyVisionConfig(visionFromMeta(params))
                reply(
                    id, sessionId,
                    callResult(params, modern, runCommand, deviceStatus, capturePhoto),
                    modern, serverVersion
                )
            }

            "ping" -> reply(id, sessionId, JSONObject(), modern, serverVersion)

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

    /** 从新版 `params._meta…clientCapabilities.extensions["me.xiaozhi/vision"]` 取视觉配置 */
    private fun visionFromMeta(params: JSONObject?): JSONObject? {
        val capabilities = params?.optJSONObject("_meta")
            ?.optJSONObject(XiaozhiProtocol.META_CLIENT_CAPABILITIES) ?: return null
        return capabilities.optJSONObject("extensions")
            ?.optJSONObject(XiaozhiProtocol.META_VISION_EXTENSION)
    }

    private fun applyVisionConfig(json: JSONObject?) {
        val url = json?.optString("url").orEmpty()
        if (url.isEmpty()) return
        visionEndpoint = url
        visionAccessToken = json?.optString("token").orEmpty()
    }

    /**
     * 回包。新版结果要带 `resultType: "complete"` 和 `_meta.serverInfo`，旧版什么都不加。
     */
    private fun reply(
        id: Any?,
        sessionId: String,
        result: JSONObject,
        modern: Boolean,
        serverVersion: String
    ): String {
        val body = if (modern) {
            result
                .put("resultType", "complete")
                .put(
                    "_meta",
                    JSONObject().put(
                        XiaozhiProtocol.META_SERVER_INFO,
                        JSONObject().put("name", SERVER_NAME).put("version", serverVersion)
                    )
                )
        } else {
            result
        }
        return XiaozhiProtocol.mcpResultJson(id, body, sessionId)
    }

    /**
     * `server/discover` 的响应：列出两个版本让网关挑，能力只声明 tools。
     * 有拍照工具时额外声明 `me.xiaozhi/vision`，网关才会下发视觉地址。
     */
    private fun discoverResult(allowPhoto: Boolean): JSONObject {
        val capabilities = JSONObject().put("tools", JSONObject())
        if (allowPhoto) {
            capabilities.put(
                "extensions",
                JSONObject().put(XiaozhiProtocol.META_VISION_EXTENSION, JSONObject())
            )
        }
        return JSONObject()
            .put(
                "supportedVersions",
                JSONArray()
                    .put(XiaozhiProtocol.MCP_VERSION_MODERN)
                    .put(XiaozhiProtocol.MCP_VERSION_LEGACY)
            )
            .put("capabilities", capabilities)
            .put("ttlMs", CACHE_TTL_MS)
            .put("cacheScope", "private")
    }

    private fun initializeResult(serverVersion: String): JSONObject = JSONObject()
        .put("protocolVersion", XiaozhiProtocol.MCP_VERSION_LEGACY)
        .put("capabilities", JSONObject().put("tools", JSONObject()))
        .put("serverInfo", JSONObject()
            .put("name", SERVER_NAME)
            .put("version", serverVersion))

    private fun toolsResult(
        params: JSONObject?,
        allowPhoto: Boolean,
        modern: Boolean
    ): JSONObject {
        // 带 cursor 说明网关在翻页；我们不分页，第二页直接给空表
        if (modern && !params?.optString("cursor").isNullOrEmpty()) {
            return JSONObject().put("tools", JSONArray())
        }
        val tools = JSONArray().put(playerTool()).put(statusTool())
        if (allowPhoto) tools.put(photoTool())
        val result = JSONObject().put("tools", tools)
        if (modern) {
            result.put("ttlMs", CACHE_TTL_MS).put("cacheScope", "private")
        }
        return result
    }

    private fun playerTool(): JSONObject {
        val commandProperty = JSONObject()
            .put("type", "string")
            .put("description", "完整命令原文，例如 1key、playmusic 1234、stop 30、vol 40、alarmlist、alarmadd 0730 1,6")
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("command", commandProperty))
            .put("required", JSONArray().put("command"))
        return JSONObject()
            .put("name", TOOL_NAME)
            .put("description", TOOL_DESCRIPTION)
            .put("inputSchema", schema)
    }

    private fun statusTool(): JSONObject = JSONObject()
        .put("name", TOOL_STATUS)
        .put("description", STATUS_DESCRIPTION)
        .put("inputSchema", JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()))

    private fun photoTool(): JSONObject {
        val questionProperty = JSONObject()
            .put("type", "string")
            .put("description", "想问的画面问题，例如「你看到了什么」")
        val cameraProperty = JSONObject()
            .put("type", "integer")
            .put("description", "摄像头编号，默认 0（后置），1 一般是前置")
        return JSONObject()
            .put("name", TOOL_PHOTO)
            .put("description", PHOTO_DESCRIPTION)
            .put("inputSchema", JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("question", questionProperty)
                    .put("camera", cameraProperty))
                .put("required", JSONArray().put("question")))
    }

    private fun callResult(
        params: JSONObject?,
        modern: Boolean,
        runCommand: (String) -> String,
        deviceStatus: () -> String,
        capturePhoto: (String, Int, String, String) -> String?
    ): JSONObject {
        val name = params?.optString("name").orEmpty()
        return when (name) {
            TOOL_NAME -> {
                val raw = params?.optJSONObject("arguments")?.optString("command").orEmpty()
                val command = XiaozhiPlayerCommands.parse(raw)
                    ?: return errorResult("不支持的播放器命令：$raw")
                textResult(runCommand(command), isError = false)
            }

            TOOL_STATUS -> textResult(deviceStatus(), isError = false)

            TOOL_PHOTO -> {
                val arguments = params?.optJSONObject("arguments")
                val question = arguments?.optString("question").orEmpty()
                val cameraIndex = arguments?.optInt("camera", 0) ?: 0
                val description =
                    capturePhoto(question, cameraIndex, visionEndpoint, visionAccessToken)
                        ?: return errorResult("拍照失败，相机或视觉服务不可用")
                textResult(description, isError = false)
            }

            else -> errorResult("未知工具：$name")
        }
    }

    private fun textResult(text: String, isError: Boolean): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        .put("isError", isError)

    private fun errorResult(message: String): JSONObject = textResult(message, isError = true)
}
