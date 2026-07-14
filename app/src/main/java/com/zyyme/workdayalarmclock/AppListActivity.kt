package com.zyyme.workdayalarmclock

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/**
 * 应用列表
 * 实现一个简单的启动器功能，目的是代替启动器实现开机启动
 */
class AppListActivity : AppCompatActivity() {

    private lateinit var adapter: AppAdapter
    private lateinit var etSearch: EditText
    private val mainHandler = Handler(Looper.getMainLooper())
    private val appLoadExecutor = Executors.newSingleThreadExecutor()
    @Volatile
    private var isActivityDestroyed = false
    private val PREFS_NAME = "app_list"
    private val KEY_PINNED_APPS = "pinned_apps"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_list)

        val rootView = findViewById<View>(R.id.root_app_list)
        val btnBack = findViewById<ImageButton>(R.id.btn_back)
        etSearch = findViewById(R.id.et_search)
        val rvApps = findViewById<RecyclerView>(R.id.rv_apps)
        // 列表
        rvApps.layoutManager = LinearLayoutManager(this)
        // 网格
        // rvApps.layoutManager = GridLayoutManager(this, 4)
        rootView.requestFocus()

        btnBack.setOnClickListener {
            finish()
        }

        adapter = AppAdapter(mutableListOf(), { appInfo ->
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

        disableSearchKeyboardOnFocus()
        etSearch.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                showKeyboard()
            }
            false
        }

        etSearch.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                showKeyboard()
                return@setOnKeyListener true
            }
            false
        }

        loadAppsAsync()
    }

    private fun showAppMenu(appInfo: AppInfo) {
        val isStartupApp = StartupAppHelper.isStartupApp(this, appInfo.packageName)
        val items = arrayOf(
            if (appInfo.isPinned) "取消置顶" else "置顶",
            if (isStartupApp) "取消开机启动" else "加入开机启动",
            "应用信息",
            "复制包名",
            "返回"
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
                        val enabled = StartupAppHelper.toggleStartupApp(this, appInfo.packageName)
                        Toast.makeText(
                            this,
                            if (enabled) "已加入开机序列" else "已从开机序列移除",
                            Toast.LENGTH_SHORT
                        ).show()
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

    private fun refreshAppList() {
        loadAppsAsync()
    }

    private fun loadAppsAsync() {
        appLoadExecutor.execute {
            val apps = getLaunchableApps()
            mainHandler.post {
                if (!isActivityDestroyed) {
                    adapter.updateData(apps)
                }
            }
        }
    }

    private fun showKeyboard() {
        enableSearchKeyboardOnFocus()
        etSearch.requestFocus()
        etSearch.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
            etSearch.postDelayed({
                if (!isActivityDestroyed) {
                    disableSearchKeyboardOnFocus()
                }
            }, 200)
        }
    }

    private fun enableSearchKeyboardOnFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            etSearch.showSoftInputOnFocus = true
        }
    }

    private fun disableSearchKeyboardOnFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            etSearch.showSoftInputOnFocus = false
        }
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

    override fun onBackPressed() {
        // 默认时钟模式的设备 返回退到全屏时钟
        if (MeService.clockModeModel.contains(Build.MANUFACTURER + Build.MODEL) || MeSettings.isEnabled(this, MeSettings.KEY_CLOCK)) {
            if (MeSettings.isEnabled(this, MeSettings.KEY_WHITE)) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            }
            val intent: Intent = Intent(this, ClockActivity::class.java)
            intent.putExtra("clockMode", true)
            startActivity(intent)
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        isActivityDestroyed = true
        appLoadExecutor.shutdownNow()
        super.onDestroy()
    }
}
