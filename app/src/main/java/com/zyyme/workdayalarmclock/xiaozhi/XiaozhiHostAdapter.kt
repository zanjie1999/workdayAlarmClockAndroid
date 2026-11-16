package com.zyyme.workdayalarmclock.xiaozhi

/**
 * 小智模块与宿主之间唯一的接口。
 * 由 MeService 实现；xiaozhi 包里其余类不得直接碰 MeService / MeSettings。
 */
interface XiaozhiHost {

    /** 写进现有日志页 */
    fun log(message: String)

    /** 短暂提示（消息条），类似宿主里的 showMsg */
    fun showStatus(message: String)

    fun appVersion(): String

    /** 录音期间压低音乐音量（类似 AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK） */
    fun setVoiceDuck(on: Boolean)

    /** 会话开始暂停音乐；返回 true 表示这次确实暂停了（结束时需要恢复） */
    fun pauseMediaForVoice(): Boolean

    /** 会话结束恢复被 [pauseMediaForVoice] 暂停的音乐 */
    fun resumeMediaAfterVoice()

    /**
     * 执行一条音乐命令（已由 [XiaozhiPlayerCommands] 校验过），返回一句给模型看的结果。
     * 宿主内部走 Go 的命令管道。
     */
    fun runPlayerCommand(command: String): String

    fun isMediaPlaying(): Boolean

    /** 设备状态，一行紧凑 JSON，直接当 MCP 工具结果返回 */
    fun deviceStatusJson(): String

    /**
     * 拍一张照片，按网关下发的视觉地址上传，返回画面描述。
     * 没权限、相机不可用、上传失败都返回 null。
     *
     * @param cameraIndex 摄像头编号，和 `Camera.open` 的一致（0 后置、1 前置是常见情况）
     */
    fun capturePhoto(question: String, cameraIndex: Int, visionUrl: String, visionToken: String): String?

    /** 抢占麦克风（摄像头 /aac 推流等） */
    fun claimMicrophone()

    fun releaseMicrophone()

    /** Android 10+ 后台录音需要有 microphone 前台服务类型 */
    fun ensureForegroundMicType()
}
