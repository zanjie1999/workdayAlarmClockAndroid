package com.zyyme.workdayalarmclock

import android.content.pm.ResolveInfo

data class AppInfo(
    val name: String,
    val packageName: String,
    val resolveInfo: ResolveInfo,
    val isPinned: Boolean = false
)
