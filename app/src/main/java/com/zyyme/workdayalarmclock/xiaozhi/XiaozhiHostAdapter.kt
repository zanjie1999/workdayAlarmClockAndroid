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

    fun isMediaPlaying(): Boolean

    /** 抢占麦克风（摄像头 /aac 推流等） */
    fun claimMicrophone()

    fun releaseMicrophone()

    /** Android 10+ 后台录音需要有 microphone 前台服务类型 */
    fun ensureForegroundMicType()
}
