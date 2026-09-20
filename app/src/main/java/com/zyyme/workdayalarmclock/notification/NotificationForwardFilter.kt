package com.zyyme.workdayalarmclock.notification

internal object NotificationForwardFilter {
    fun isBlockedByScreen(screenOffOnly: Boolean, screenInteractive: Boolean): Boolean {
        return screenOffOnly && screenInteractive
    }

    fun isBlacklisted(packageName: String, blacklist: Set<String>): Boolean {
        return packageName in blacklist
    }
}
