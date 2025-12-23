package com.zyyme.workdayalarmclock

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private var allApps: List<AppInfo>,
    private val onItemClick: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    private var filteredApps: List<AppInfo> = allApps

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
        holder.itemView.setOnClickListener { onItemClick(app) }
    }

    override fun getItemCount() = filteredApps.size

    fun filter(query: String) {
        filteredApps = if (query.isEmpty()) {
            allApps
        } else {
            allApps.filter { it.name.contains(query, ignoreCase = true) }
        }
        notifyDataSetChanged()
    }

    fun updateData(newApps: List<AppInfo>) {
        allApps = newApps
        filteredApps = newApps
        notifyDataSetChanged()
    }
}
