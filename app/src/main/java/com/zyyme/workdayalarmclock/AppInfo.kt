package com.zyyme.workdayalarmclock

import android.graphics.drawable.Drawable

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable,
    val isPinned: Boolean = false
)
