package com.zyyme.workdayalarmclock

import com.zyyme.workdayalarmclock.notification.NotificationForwardFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationForwardFilterTest {
    @Test
    fun screenFilterOnlyBlocksInteractiveScreenWhenEnabled() {
        assertFalse(NotificationForwardFilter.isBlockedByScreen(false, false))
        assertFalse(NotificationForwardFilter.isBlockedByScreen(false, true))
        assertFalse(NotificationForwardFilter.isBlockedByScreen(true, false))
        assertTrue(NotificationForwardFilter.isBlockedByScreen(true, true))
    }

    @Test
    fun blacklistMatchesExactPackageName() {
        val blacklist = setOf("app.blocked")

        assertTrue(NotificationForwardFilter.isBlacklisted("app.blocked", blacklist))
        assertFalse(NotificationForwardFilter.isBlacklisted("app.allowed", blacklist))
    }
}
