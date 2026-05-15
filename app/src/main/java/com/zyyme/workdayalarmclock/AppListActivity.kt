package com.zyyme.workdayalarmclock

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class AppListActivity : AppCompatActivity() {

    private lateinit var adapter: AppAdapter

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
            openApp(appInfo)
        }, { appInfo ->
            // 置顶应用
            togglePinApp(appInfo.packageName)
            refreshAppList()
//            rvApps.scrollToPosition(0)
        }, { appInfo ->
            // 打开应用详情
            openAppInfo(appInfo.packageName)
        }, { appInfo ->
            // 整体长按显示菜单
            showAppMenu(appInfo)
        })
        rvApps.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // 关掉自动打开的键盘
        etSearch.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(etSearch.windowToken, 0)
        }, 100)
    }

    private fun showAppMenu(appInfo: AppInfo) {
        val isStartupApp = getStartupAppPackageName() == appInfo.packageName
        val items = arrayOf(
            if (appInfo.isPinned) "取消置顶" else "置顶",
            if (isStartupApp) "取消开机启动" else "设为开机启动",
            "应用信息",
            "复制包名",
            "关闭"
        )

        AlertDialog.Builder(this)
            .setTitle(appInfo.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        togglePinApp(appInfo.packageName)
                        refreshAppList()
                    }
                    1 -> {
                        if (isStartupApp) {
                            setStartupAppPackageName(null)
                            Toast.makeText(this, "已取消开机启动", Toast.LENGTH_SHORT).show()
                        } else {
                            setStartupAppPackageName(appInfo.packageName)
                            Toast.makeText(this, "已设为开机启动", Toast.LENGTH_SHORT).show()
                        }
                    }
                    2 -> openAppInfo(appInfo.packageName)
                    3 -> copyPackageName(appInfo.packageName)
                }
            }
            .show()
    }

    private fun openApp(appInfo: AppInfo) {
        val launchIntent = packageManager.getLaunchIntentForPackage(appInfo.packageName)
        if (launchIntent != null) {
            startActivity(launchIntent)
        }
    }

    private fun openAppInfo(packageName: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun copyPackageName(packageName: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("packageName", packageName))
        Toast.makeText(this, "已复制包名", Toast.LENGTH_SHORT).show()
    }

    private fun getPinnedApps(): Set<String> {
        val prefs = getSharedPreferences(StartupAppHelper.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(StartupAppHelper.KEY_PINNED_APPS, emptySet()) ?: emptySet()
    }

    private fun togglePinApp(packageName: String) {
        val prefs = getSharedPreferences(StartupAppHelper.PREFS_NAME, Context.MODE_PRIVATE)
        val pinnedApps = getPinnedApps().toMutableSet()
        if (pinnedApps.contains(packageName)) {
            pinnedApps.remove(packageName)
        } else {
            pinnedApps.add(packageName)
        }
        prefs.edit().putStringSet(StartupAppHelper.KEY_PINNED_APPS, pinnedApps).apply()
    }

    private fun getStartupAppPackageName(): String? {
        return StartupAppHelper.getStartupAppPackageName(this)
    }

    private fun setStartupAppPackageName(packageName: String?) {
        StartupAppHelper.setStartupAppPackageName(this, packageName)
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
