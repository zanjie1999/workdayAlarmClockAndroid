package com.zyyme.workdayalarmclock

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
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

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

    override fun onDestroy() {
        isActivityDestroyed = true
        appLoadExecutor.shutdownNow()
        super.onDestroy()
    }
}
