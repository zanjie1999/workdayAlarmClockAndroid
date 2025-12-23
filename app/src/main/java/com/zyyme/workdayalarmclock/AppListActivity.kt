package com.zyyme.workdayalarmclock

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
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

        val apps = getLaunchableApps()
        adapter = AppAdapter(apps) { appInfo ->
            val launchIntent = packageManager.getLaunchIntentForPackage(appInfo.packageName)
            if (launchIntent != null) {
                startActivity(launchIntent)
            }
        }
        rvApps.adapter = adapter

        // 实时搜索功能
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun getLaunchableApps(): List<AppInfo> {
        val appList = mutableListOf<AppInfo>()
        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = packageManager.queryIntentActivities(intent, 0)

        for (resolveInfo in resolveInfos) {
            val name = resolveInfo.loadLabel(packageManager).toString()
            val packageName = resolveInfo.activityInfo.packageName
            val icon = resolveInfo.loadIcon(packageManager)
            appList.add(AppInfo(name, packageName, icon))
        }

        return appList.sortedBy { it.name }
    }
}
