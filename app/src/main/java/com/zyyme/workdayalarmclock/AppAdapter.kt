package com.zyyme.workdayalarmclock

import android.graphics.Typeface
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private var allApps: MutableList<AppInfo>,
    private val onItemClick: (AppInfo) -> Unit,
    private val onTogglePin: (AppInfo) -> Unit,
    private val onOpenAppInfo: (AppInfo) -> Unit,
    private val onItemLongClick: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    private var filteredApps: MutableList<AppInfo> = allApps.toMutableList()
    private var currentQuery = ""

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.iv_app_icon)
        val tvName: TextView = view.findViewById(R.id.tv_app_name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = filteredApps[position]
        holder.tvName.text = app.name
        holder.ivIcon.setImageDrawable(app.icon)
        
        // 置顶应用加粗
        if (app.isPinned) {
            holder.tvName.setTypeface(null, Typeface.BOLD)
        } else {
            holder.tvName.setTypeface(null, Typeface.NORMAL)
        }

        holder.itemView.setOnClickListener { onItemClick(app) }

        // Android6修复了事件分发机制
        // 整体长按
        holder.itemView.setOnLongClickListener {
            onItemLongClick(app)
            true
        }

        // 向左置顶，向右打开应用详情
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onTogglePin(app)
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onOpenAppInfo(app)
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }
    }

    override fun getItemCount() = filteredApps.size

    fun filter(query: String) {
        currentQuery = query
        applyFilter()
    }

    fun updateData(newApps: List<AppInfo>) {
        allApps = newApps.toMutableList()
        applyFilter()
    }

    private fun applyFilter() {
        filteredApps = if (currentQuery.isEmpty()) {
            allApps.toMutableList()
        } else {
            allApps.filter { it.name.contains(currentQuery, ignoreCase = true) }.toMutableList()
        }
        notifyDataSetChanged()
    }
}
