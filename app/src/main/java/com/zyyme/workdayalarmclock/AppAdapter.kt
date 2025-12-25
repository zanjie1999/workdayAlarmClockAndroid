package com.zyyme.workdayalarmclock

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private var allApps: MutableList<AppInfo>,
    private val onItemClick: (AppInfo) -> Unit,
    private val onIconLongClick: (AppInfo) -> Unit,
    private val onNameLongClick: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    private var filteredApps: MutableList<AppInfo> = allApps.toMutableList()

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
        
        // 图标长按 置顶
        holder.ivIcon.setOnLongClickListener {
            onIconLongClick(app)
            true
        }
        
        // 名称/整体长按 详情
        holder.itemView.setOnLongClickListener {
            onNameLongClick(app)
            true
        }
    }

    override fun getItemCount() = filteredApps.size

    fun filter(query: String) {
        filteredApps = if (query.isEmpty()) {
            allApps.toMutableList()
        } else {
            allApps.filter { it.name.contains(query, ignoreCase = true) }.toMutableList()
        }
        notifyDataSetChanged()
    }

    fun updateData(newApps: List<AppInfo>) {
        allApps = newApps.toMutableList()
        filteredApps = newApps.toMutableList()
        notifyDataSetChanged()
    }
}
