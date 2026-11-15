package com.zyyme.workdayalarmclock.xiaozhi

/**
 * DPAD Center 的「单击 / 双击」消歧，纯时间判断，不碰 Handler。
 *
 * 只有小智模块启用时才需要消歧：
 * - 未启用：单击立刻执行，和改造前一模一样；
 * - 启用：单击延后 [windowMs] 执行，期间来了第二击就判为双击。
 *
 * 长按不在本类判定，宿主的 900ms 长按逻辑原样保留，只是把 `wasLongPress` 传进来。
 */
class DoubleClickGate(private val windowMs: Long = 300L) {

    enum class DownResult {
        /** 普通按下，宿主照旧启动长按计时 */
        NONE,

        /** 判定为双击，宿主应取消待执行的单击并取消长按计时 */
        DOUBLE
    }

    enum class UpResult {
        /** 立刻执行原单击逻辑（未启用小智 / 长按抬起） */
        IMMEDIATE,

        /** 延后 [windowMs] 再执行原单击逻辑，等第二击 */
        DEFER,

        /** 双击之后的多余抬起，直接丢弃（避免三连击被拆成双击+单击） */
        IGNORE
    }

    private var lastUpAt = 0L
    private var singlePending = false
    private var suppressSingleUntil = 0L

    fun onDown(nowMs: Long, enabled: Boolean): DownResult {
        if (enabled && singlePending && nowMs - lastUpAt <= windowMs) {
            singlePending = false
            suppressSingleUntil = nowMs + windowMs
            return DownResult.DOUBLE
        }
        // 新的一次按下，之前的单击已经执行或已被判定
        singlePending = false
        return DownResult.NONE
    }

    fun onUp(nowMs: Long, enabled: Boolean, wasLongPress: Boolean): UpResult {
        if (!enabled || wasLongPress) {
            singlePending = false
            return UpResult.IMMEDIATE
        }
        if (nowMs < suppressSingleUntil) {
            return UpResult.IGNORE
        }
        lastUpAt = nowMs
        singlePending = true
        return UpResult.DEFER
    }

    /** 仍有待执行的单击 */
    fun isSinglePending(): Boolean = singlePending

    /** 取走待执行的单击（返回 true 表示这一次单击该由宿主执行） */
    fun consumeSingle(): Boolean {
        if (!singlePending) return false
        singlePending = false
        return true
    }

    /** 清空所有中间状态（长按触发、页面销毁、弹窗打开时调用） */
    fun reset() {
        singlePending = false
        suppressSingleUntil = 0L
    }
}
