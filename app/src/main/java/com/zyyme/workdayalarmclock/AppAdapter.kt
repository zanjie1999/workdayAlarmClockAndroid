package com.zyyme.workdayalarmclock

import android.graphics.Typeface
import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor

class AppAdapter(
    private var allApps: MutableList<AppInfo>,
    private val onItemClick: (AppInfo) -> Unit,
    private val onTogglePin: (AppInfo) -> Unit,
    private val onOpenAppInfo: (AppInfo) -> Unit,
    private val onItemLongClick: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    private var filteredApps: MutableList<AppInfo> = allApps.toMutableList()
    private var currentQuery = ""
    private val iconCache = LruCache<String, Drawable>(48)
    private val iconExecutor = Executors.newFixedThreadPool(2) as ThreadPoolExecutor
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var released = false

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.iv_app_icon)
        val tvName: TextView = view.findViewById(R.id.tv_app_name)
        var iconRequest: Future<*>? = null
        var iconBinding: Any? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = filteredApps[position]
        holder.tvName.text = app.name
        bindIcon(holder, app)
        
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

    private fun bindIcon(holder: ViewHolder, app: AppInfo) {
        clearIconRequest(holder)
        val info = app.resolveInfo
        val key = ComponentName(info.activityInfo.packageName, info.activityInfo.name).flattenToString()
        val cached = iconCache.get(key)
        holder.ivIcon.setImageDrawable(cached)
        if (cached != null || released) return

        val binding = Any()
        holder.iconBinding = binding
        val pm = holder.itemView.context.applicationContext.packageManager
        holder.iconRequest = iconExecutor.submit {
            val icon = try {
                info.loadIcon(pm)
            } catch (_: RuntimeException) {
                pm.defaultActivityIcon
            }
            if (!released && !Thread.currentThread().isInterrupted) {
                mainHandler.post {
                    if (!released) {
                        iconCache.put(key, icon)
                        // A recycled or rebound row must never receive an old request's icon.
                        if (holder.iconBinding === binding) {
                            holder.ivIcon.setImageDrawable(icon)
                            holder.iconRequest = null
                        }
                    }
                }
            }
        }
    }

    private fun clearIconRequest(holder: ViewHolder) {
        holder.iconBinding = null
        holder.iconRequest?.cancel(true)
        holder.iconRequest = null
        iconExecutor.purge()
    }

    override fun onViewRecycled(holder: ViewHolder) {
        clearIconRequest(holder)
        holder.ivIcon.setImageDrawable(null)
        super.onViewRecycled(holder)
    }

    fun release() {
        released = true
        iconExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        iconCache.evictAll()
    }

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
