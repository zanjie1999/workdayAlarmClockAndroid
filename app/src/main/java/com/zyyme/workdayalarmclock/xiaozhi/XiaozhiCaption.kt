package com.zyyme.workdayalarmclock.xiaozhi

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 会话字幕的全局状态位。
 * 界面（clock / desk）只是读它，不参与会话逻辑。
 */
object XiaozhiCaption {

    @Volatile
    var active: Boolean = false
        private set

    /** 当前要显示的一行文字（状态或识别/回复内容） */
    @Volatile
    var text: String = ""
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun set(value: String) {
        active = true
        if (text == value) return
        text = value
        notifyChanged()
    }

    fun clear() {
        if (!active && text.isEmpty()) return
        active = false
        text = ""
        notifyChanged()
    }

    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        for (listener in listeners) {
            try {
                listener()
            } catch (ignored: Throwable) {
            }
        }
    }
}
