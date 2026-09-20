package com.zyyme.workdayalarmclock.applist

import android.content.pm.ResolveInfo

data class AppInfo(
    val name: String,
    val packageName: String,
    val resolveInfo: ResolveInfo,
    val isPinned: Boolean = false
)
