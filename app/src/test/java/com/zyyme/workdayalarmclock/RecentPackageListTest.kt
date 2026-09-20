package com.zyyme.workdayalarmclock

import com.zyyme.workdayalarmclock.notification.RecentPackageList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentPackageListTest {
    @Test
    fun snapshotReturnsUniquePackagesNewestFirst() {
        val packages = RecentPackageList(3)

        packages.record("app.one")
        packages.record("app.two")
        packages.record("app.one")

        assertEquals(listOf("app.one", "app.two"), packages.snapshotNewestFirst())
    }

    @Test
    fun recordDropsOldestPackageAtLimit() {
        val packages = RecentPackageList(3)

        packages.record("app.one")
        packages.record("app.two")
        packages.record("app.three")
        packages.record("app.four")

        assertEquals(
            listOf("app.four", "app.three", "app.two"),
            packages.snapshotNewestFirst()
        )
    }

    @Test
    fun clearRemovesAllPackages() {
        val packages = RecentPackageList(3)
        packages.record("app.one")

        packages.clear()

        assertTrue(packages.snapshotNewestFirst().isEmpty())
    }
}
