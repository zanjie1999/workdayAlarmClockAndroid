package com.zyyme.workdayalarmclock

import android.Manifest
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.*
import android.provider.Settings
import android.support.v4.media.session.MediaSessionCompat
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import kotlin.system.exitProcess
import androidx.core.net.toUri


class MainActivity : AppCompatActivity() {
    companion object {
        var print2LogViewHandler: Handler? = null
        var me: MainActivity? = null
        var startService = true
        private const val MENU_EXIT = 1
        private const val OPEN_WEB = 2
        private const val OPEN_CLOCK = 3
        private const val OPEN_APPLIST = 4
        private const val OPEN_DEVICE_ADMIN = 5
        private const val OPEN_ACCESSIBILITY_SETTINGS = 6
        private const val OPEN_NOTIFICATION_FORWARD_URL = 7
        private const val MENU_SETTING_START = 100
    }

    var mediaSessionCompat: MediaSessionCompat? = null
    var mediaComponentName: ComponentName? = null
    var isActivityVisible = false

    // 时钟模式
    var useClockMode = false

    private data class SettingMenuItem(val key: String, val label: String)

    private val settingsMenuItems = listOf(
        SettingMenuItem(MeSettings.KEY_DISABLE, "开机不启动"),
        SettingMenuItem(MeSettings.KEY_CLOCK, "时钟模式"),
        SettingMenuItem(MeSettings.KEY_AUTO_BACK_CLOCK, "自动回到时钟"),
        SettingMenuItem(MeSettings.KEY_TSS, "时钟不显示秒"),
        SettingMenuItem(MeSettings.KEY_T24, "时钟24小时制"),
        SettingMenuItem(MeSettings.KEY_WHITE, "时钟亮色主题"),
        SettingMenuItem(MeSettings.KEY_LANDSCAPE, "锁定横屏"),
        SettingMenuItem(MeSettings.KEY_VERTICAL, "强制竖屏布局"),
        SettingMenuItem(MeSettings.KEY_ROUND, "强制圆屏布局"),
        SettingMenuItem(MeSettings.KEY_AP, "开机开热点(辅助)"),
    )

    override fun onResume() {
        super.onResume()
        isActivityVisible = true
        show2LogView()
    }
    override fun onPause() {
        super.onPause()
        isActivityVisible = false
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // 当作为桌面并且已启动时，再次按下Home键
        if (intent?.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            val appListIntent = Intent(this, AppListActivity::class.java)
            startActivity(appListIntent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 作为启动器启动，也需要运行开机启动app
        if (StartupAppHelper.tryHandleLauncherBootActivity(this, getIntent())) {
            val startupAppCount = StartupAppHelper.getStartupAppPackageNames(this).size
            Thread.sleep(startupAppCount * StartupAppHelper.STARTUP_APP_DELAY_MILLIS)
        }
        me = this
        useClockMode = MeService.clockModeModel.contains(Build.MANUFACTURER + Build.MODEL) || MeSettings.isEnabled(this, MeSettings.KEY_CLOCK)

        // am start -n com.zyyme.workdayalarmclock/.MainActivity -d http://...
        val amUrl = getIntent().getDataString();
        if (amUrl != null) {
            startService = false
            if (MeService.me == null) {
                startService(Intent(this, MeService::class.java))
            } else {
                print2LogView("服务已经在运行了")
            }
            print2LogView("收到外部播放链接 将不启动服务 放完自动退出\n" + amUrl)
            Thread(Runnable {
                while (MeService.me == null) {
                    print2LogView("等待服务启动...")
                    Thread.sleep(1000)
                }
                MeService.me!!.playUrl(amUrl!!, true)
            }).start()
            return
        }

        // 启动服务 如果Activity不是重载的话
        if (MeService.me == null) {
            startService(Intent(this, MeService::class.java))
            if (useClockMode) {
                // 初次启动，切换到时钟模式 有clock文件的也切换      亮色主题（闹钟屏幕出线白色没那么明显）
                applyClockTheme()
                val intent = Intent(this, ClockActivity::class.java)
                intent.putExtra("clockMode", true)
                startActivity(intent)
            }
        }

        // 换一下循序，避免时钟模式设备多次渲染
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar));

        // 存储空间权限 Android11
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            Toast.makeText(this,"请允许权限\n用于本地音乐播放", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${BuildConfig.APPLICATION_ID}".toUri()))
        } else if (Build.VERSION.SDK_INT < 30 && ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE ) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this,"请允许权限\n用于本地音乐播放", Toast.LENGTH_LONG).show()
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
        }

        // 检查二进制文件更新  lib不可写入 写入了重启也会恢复 于是没有热更新了
//        try {
//            val updateFile = File(Environment.getExternalStorageDirectory().absolutePath + "/libWorkdayAlarmClock.so")
//            if (updateFile.exists()) {
//                print2LogView("找到 /sdcard/libWorkdayAlarmClock.so 开始更新")
//                val libdir = File(applicationInfo.nativeLibraryDir)
//                if (!libdir.exists()) {
//                    libdir.mkdirs()
//                }
//                val inputStream: InputStream = FileInputStream(updateFile)
//                val outputFile = File(libdir, "libWorkdayAlarmClock.so")
//                val outputStream: OutputStream = FileOutputStream(outputFile)
//                val buffer = ByteArray(1024)
//                var length: Int
//                while (inputStream.read(buffer).also { length = it } > 0) {
//                    outputStream.write(buffer, 0, length)
//                }
//                outputStream.close()
//                inputStream.close()
//                print2LogView("更新完成")
//            } else {
//                print2LogView("没有找到 /sdcard/libWorkdayAlarmClock.so 不更新")
//            }
//        } catch (e: IOException) {
//            e.printStackTrace()
//            print2LogView(e.toString())
//        }

        // Shell输入框
        findViewById<EditText>(R.id.shellInput).setOnEditorActionListener { _, actionId, keyEvent ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_NULL ||
                (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN && (keyEvent.keyCode == KeyEvent.KEYCODE_ENTER || keyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER))) {
                shellOnEnter()
                true
            } else {
                Log.d("shellInputEditText", "actionId: $actionId keyEvent: $keyEvent")
            }
            false
        }

        print2LogViewHandler = Handler(Looper.getMainLooper()) { msg ->
            print2LogView(msg.obj as String)
            true
        }

//        mediaButtonReceiverInit()

        // toolbar的控制按钮
        findViewById<ImageView>(R.id.iconPrev).setOnClickListener {
            MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_PREVIOUS, true)
        }
        findViewById<ImageView>(R.id.iconPlay).setOnClickListener {
            MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, true)
        }
        findViewById<ImageView>(R.id.iconNext).setOnClickListener {
            MeService.me?.keyHandle(2147483645, true)
        }
        findViewById<ImageView>(R.id.iconStop).setOnClickListener {
            MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_STOP, true)
        }
        findViewById<ImageView>(R.id.iconForward).setOnClickListener {
            MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, true)
        }
        findViewById<ImageView>(R.id.iconMenu).setOnClickListener {
            showSettingsMenu(it as ImageView)
        }
        findViewById<Toolbar>(R.id.toolbar).setOnClickListener {
            val intent: Intent = Intent(this, ClockActivity::class.java)
            startActivity(intent)
        }
        findViewById<Toolbar>(R.id.toolbar).setOnLongClickListener {
            val intent = Intent(this, AppListActivity::class.java)
            startActivity(intent)
            true
        }

    }

    private fun showSettingsMenu(anchor: ImageView) {
        val popupMenu = PopupMenu(this, anchor)
        popupMenu.menu.add(Menu.NONE, MENU_EXIT, 0, "退出${getString(R.string.app_name)}")
        popupMenu.menu.add(Menu.NONE, OPEN_WEB, 1, "打开Web控制台")
        popupMenu.menu.add(Menu.NONE, OPEN_CLOCK, 3, "打开时钟模式")
        popupMenu.menu.add(Menu.NONE, OPEN_APPLIST, 4, "打开应用列表")
        settingsMenuItems.forEachIndexed { index, item ->
            popupMenu.menu.add(Menu.NONE, MENU_SETTING_START + index, index + 4, item.label).apply {
                isCheckable = true
                isChecked = MeSettings.isEnabled(this@MainActivity, item.key)
            }
        }
        popupMenu.menu.add(Menu.NONE, OPEN_DEVICE_ADMIN, MENU_SETTING_START + settingsMenuItems.size, "授权熄屏权限")
        popupMenu.menu.add(Menu.NONE, OPEN_ACCESSIBILITY_SETTINGS, MENU_SETTING_START + settingsMenuItems.size + 1, "辅助功能设置")
        popupMenu.menu.add(Menu.NONE, OPEN_NOTIFICATION_FORWARD_URL, MENU_SETTING_START + settingsMenuItems.size + 2, "通知转发URL")

        popupMenu.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == MENU_EXIT) {
                exitApp()
                return@setOnMenuItemClickListener true
            } else if (menuItem.itemId == OPEN_WEB) {
                startActivity(Intent(this, WebActivity::class.java))
                return@setOnMenuItemClickListener true
            } else if (menuItem.itemId == OPEN_CLOCK) {
                startActivity(Intent(this, ClockActivity::class.java))
                return@setOnMenuItemClickListener true
            } else if (menuItem.itemId == OPEN_APPLIST) {
                startActivity(Intent(this, AppListActivity::class.java))
                return@setOnMenuItemClickListener true
            } else if (menuItem.itemId == OPEN_DEVICE_ADMIN) {
                openDeviceAdminSettings()
                return@setOnMenuItemClickListener true
            } else if (menuItem.itemId == OPEN_ACCESSIBILITY_SETTINGS) {
                openAccessibilitySettings()
                return@setOnMenuItemClickListener true
            } else if (menuItem.itemId == OPEN_NOTIFICATION_FORWARD_URL) {
                showNotificationForwardUrlDialog()
                return@setOnMenuItemClickListener true
            }


            val settingItem = settingsMenuItems.getOrNull(menuItem.itemId - MENU_SETTING_START)
            if (settingItem != null) {
                val enabled = !MeSettings.isEnabled(this, settingItem.key)
                MeSettings.setEnabled(this, settingItem.key, enabled)
                menuItem.isChecked = enabled
                true
            } else {
                false
            }
        }
        popupMenu.show()
    }

    private fun openDeviceAdminSettings() {
        val devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponentName = ComponentName(this, MeDeviceAdminReceiver::class.java)
        if (devicePolicyManager.isAdminActive(adminComponentName)) {
            try {
                startActivity(Intent("android.settings.DEVICE_ADMIN_SETTINGS"))
                Toast.makeText(this, "熄屏权限已授权", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                    Toast.makeText(this, "请在安全设置中打开设备管理应用", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "打开设备管理员设置失败", Toast.LENGTH_LONG).show()
                }
            }
            return
        }

        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponentName)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "远程锁屏需要这个权限"
            )
        }
        startActivity(intent)
        Toast.makeText(this,"请激活设备管理员权限\n远程锁屏需要这个权限\n卸载app需要在这里卸载", Toast.LENGTH_LONG).show()
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, "请开启工作咩闹钟辅助功能", Toast.LENGTH_LONG).show()
    }

    private fun showNotificationForwardUrlDialog() {
        val input = EditText(this).apply {
            hint = "通知内容将拼在URL末端推送"
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_URI or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            setText(MeSettings.getNotificationForwardUrl(this@MainActivity))
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle("通知转发URL")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val url = input.text.toString().trim()
                MeSettings.setNotificationForwardUrl(this, url)
                if (url.isEmpty()) {
                    Toast.makeText(this, "通知转发功能已关闭", Toast.LENGTH_SHORT).show()
                } else {
                    requestNotificationListenerPermissionIfNeeded()
                }
            }
            .show()
    }

    private fun requestNotificationListenerPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
            Toast.makeText(this, "当前系统不支持通知读取权限", Toast.LENGTH_LONG).show()
            return
        }

        if (isNotificationListenerEnabled()) {
            Toast.makeText(this, "通知读取权限已授权", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            Toast.makeText(this, "请开启${getString(R.string.app_name)}通知读取权限", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
                Toast.makeText(this, "请在系统设置中开启通知读取权限", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "打开通知读取权限设置失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
            return false
        }
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        val expectedComponent = ComponentName(this, MeNotificationListenerService::class.java)
        return enabledListeners.split(":").any { listener ->
            val component = ComponentName.unflattenFromString(listener)
            component?.packageName == expectedComponent.packageName &&
                    component.className == expectedComponent.className
        }
    }

    private fun applyClockTheme() {
        if (MeSettings.isEnabled(this, MeSettings.KEY_WHITE)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
    }

    private fun exitApp() {
        Toast.makeText(this, "${this.getString(R.string.app_name)} 服务已停止", Toast.LENGTH_SHORT).show()
        onDestroy()
        MeService.me?.stopSelf()
        exitProcess(0)
    }

    fun shellOnEnter() {
        val shellInput = findViewById<EditText>(R.id.shellInput)
        val cmd = shellInput.text.toString()
        shellInput.setText("")
        print2LogView("> $cmd")
        MeService.me?.send2Shell(cmd)
        shellInput.requestFocus()
    }

    override fun onDestroy() {
        print2LogView("控制台退出...")
//        mediaButtonReceiverDestroy()
//        stopService(Intent(this, MeService::class.java))
//        Toast.makeText(this, "${this.getString(R.string.app_name)} 在后台运行", Toast.LENGTH_SHORT).show()
        me = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        // 默认时钟模式的设备 返回退到全屏时钟
        if (useClockMode) {
            applyClockTheme()
            val intent: Intent = Intent(this, ClockActivity::class.java)
            intent.putExtra("clockMode", true)
            startActivity(intent)
        } else {
            super.onBackPressed()
        }
    }

    override fun dispatchKeyEvent(keyEvent: KeyEvent?): Boolean {
        // 方便按键机操作
        if (findViewById<EditText>(R.id.shellInput).text.toString() == "") {
            when (keyEvent?.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (MeService.me?.keyHandle(keyEvent.keyCode, true) == true) {
                        return true
                    }
                }
                KeyEvent.ACTION_UP -> {
                    if (keyEvent.keyCode == KeyEvent.KEYCODE_CALL) {
                        exitApp()
                    } else if (MeService.me?.keyHandle(keyEvent.keyCode, false) == true) {
                        return true
                    }
                }
            }
        } else if (keyEvent?.action == KeyEvent.ACTION_UP && keyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            // 多亲这个系统不知道怎么回事不会调用setOnEditorActionListener
            shellOnEnter()
        }
        return super.dispatchKeyEvent(keyEvent)
    }

    /**
     * 将日志打印到logView
     */
    fun print2LogView(s:String) {
        MeService.me?.print2LogView(s)
    }

    fun show2LogView() {
        if (isActivityVisible) {
            runOnUiThread {
                findViewById<TextView>(R.id.logView).text = MeService.logBuilder.toString()
                val scrollView = findViewById<ScrollView>(R.id.scrollView)
                scrollView.post(Runnable { scrollView.fullScroll(ScrollView.FOCUS_DOWN) })
            }
        }
    }

    /**
     * 初始化媒体按键监听
     * 是的，除了写个监听器还要超麻烦的初始化
     */
    private fun mediaButtonReceiverInit() {
        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        mediaComponentName = ComponentName(packageName, MeMediaButtonReceiver::class.java.name).apply {
            packageManager.setComponentEnabledSetting(
                this,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
            )
            // Android 5.0
            if (Build.VERSION.SDK_INT >= 21) {
                mediaSessionCompat =
                    MediaSessionCompat(this@MainActivity, "WorkdayAlarmClock", this, null).apply {
                        //指明支持的按键信息类型
                        setFlags(
                            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
                        )

                        setCallback(object : MediaSessionCompat.Callback() {
                            override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                                MeMediaButtonReceiver().onReceive(this@MainActivity, mediaButtonEvent)
                                return true
                            }
                        }, Handler(Looper.getMainLooper()))
                        isActive = true
                    }

            } else {
                audioManager.registerMediaButtonEventReceiver(this)
            }
        }

    }

    /**
     * 销毁媒体按键监听
     */
    private fun mediaButtonReceiverDestroy() {
        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        // Android 5.0
        if (Build.VERSION.SDK_INT >= 21) {
            mediaSessionCompat?.let {
                it.setCallback(null)
                it.release()
            }
        } else {
            mediaComponentName?.let {
                audioManager.unregisterMediaButtonEventReceiver(it)
            }

        }
    }

}
