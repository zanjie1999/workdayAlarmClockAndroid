package com.zyyme.workdayalarmclock

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class AppListActivity : AppCompatActivity() {

    private lateinit var adapter: AppAdapter
    private val PREFS_NAME = "app_list"
    private val KEY_PINNED_APPS = "pinned_apps"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_list)

        val btnBack = findViewById<ImageButton>(R.id.btn_back)
        val etSearch = findViewById<EditText>(R.id.et_search)
        val rvApps = findViewById<RecyclerView>(R.id.rv_apps)
        // 列表
        rvApps.layoutManager = LinearLayoutManager(this)
        // 网格
        // rvApps.layoutManager = GridLayoutManager(this, 4)

        btnBack.setOnClickListener {
            finish()
        }

        val apps = getLaunchableApps().toMutableList()
        adapter = AppAdapter(apps, { appInfo ->
            // 点击 打开应用
            val launchIntent = packageManager.getLaunchIntentForPackage(appInfo.packageName)
            if (launchIntent != null) {
                startActivity(launchIntent)
            }
        }, { appInfo ->
            // 图标长按 置顶
            togglePinApp(appInfo.packageName)
            refreshAppList()
//            rvApps.scrollToPosition(0)
        }, { appInfo ->
            // 名称长按 打开应用详情
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", appInfo.packageName, null)
            }
            startActivity(intent)
        })
        rvApps.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun getPinnedApps(): Set<String> {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_PINNED_APPS, emptySet()) ?: emptySet()
    }

    private fun togglePinApp(packageName: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pinnedApps = getPinnedApps().toMutableSet()
        if (pinnedApps.contains(packageName)) {
            pinnedApps.remove(packageName)
        } else {
            pinnedApps.add(packageName)
        }
        prefs.edit().putStringSet(KEY_PINNED_APPS, pinnedApps).apply()
    }

    private fun refreshAppList() {
        val apps = getLaunchableApps()
        adapter.updateData(apps)
    }

    private fun getLaunchableApps(): List<AppInfo> {
        val appList = mutableListOf<AppInfo>()
        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = packageManager.queryIntentActivities(intent, 0)
        val pinnedApps = getPinnedApps()

        for (resolveInfo in resolveInfos) {
            val name = resolveInfo.loadLabel(packageManager).toString()
            val packageName = resolveInfo.activityInfo.packageName
            val icon = resolveInfo.loadIcon(packageManager)
            val isPinned = pinnedApps.contains(packageName)
            appList.add(AppInfo(name, packageName, icon, isPinned))
        }

        // 置顶应用在前，其余名称排序
        return appList.sortedWith(compareByDescending<AppInfo> { it.isPinned } .thenBy { it.name })
    }
}
