package com.zyyme.workdayalarmclock.notification

internal class RecentPackageList(private val maxSize: Int) {
    private val packageNames = LinkedHashSet<String>()

    init {
        require(maxSize > 0) { "maxSize must be greater than zero" }
    }

    @Synchronized
    fun record(packageName: String) {
        packageNames.remove(packageName)
        packageNames.add(packageName)
        while (packageNames.size > maxSize) {
            val iterator = packageNames.iterator()
            iterator.next()
            iterator.remove()
        }
    }

    @Synchronized
    fun snapshotNewestFirst(): List<String> = packageNames.toList().asReversed()

    @Synchronized
    fun clear() {
        packageNames.clear()
    }
}
