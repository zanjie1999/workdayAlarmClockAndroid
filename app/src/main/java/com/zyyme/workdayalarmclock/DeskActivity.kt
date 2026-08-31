package com.zyyme.workdayalarmclock

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 大屏时钟模式
 * 触屏音箱桌面时钟模式
 */
class DeskActivity : AppCompatActivity() {
    companion object {
        var me: DeskActivity? = null

        const val EXTRA_ALARM_MODE = "deskAlarmMode"

        private const val REQUEST_PICK_WALLPAPER = 201
        private const val REQUEST_CROP_WALLPAPER = 202

        private const val CONTENT_NONE = 0
        private const val CONTENT_TIME = 1
        private const val CONTENT_PLAYER = 2
        private const val LEGACY_CONTENT_LYRICS = 3
        private const val CONTENT_TODO = 4

        private const val LYRICS_TOP = 0
        private const val LYRICS_BOTTOM = 1
        private const val LYRICS_NONE = 2
        private const val HOUR_MILLIS = 60L * 60L * 1000L

    }

    private val handler = Handler(Looper.getMainLooper())
    private val fullscreenRestoreRunnable = Runnable {
        if (!isFinishing && !isDestroyed) setFullscreen()
    }
    var isActivityStarted = false
    private val slotKeys = arrayOf(
        MeSettings.KEY_DESK_SLOT_TOP_LEFT,
        MeSettings.KEY_DESK_SLOT_TOP_RIGHT,
        MeSettings.KEY_DESK_SLOT_BOTTOM_LEFT,
        MeSettings.KEY_DESK_SLOT_BOTTOM_RIGHT
    )
    private val defaultSlots = intArrayOf(CONTENT_NONE, CONTENT_NONE, CONTENT_TIME, CONTENT_PLAYER)
    private val slotValues = defaultSlots.copyOf()
    private val baseIconDrawables = mutableMapOf<Int, Drawable>()

    private lateinit var wallpaperView: ImageView
    private lateinit var maskView: View
    private lateinit var grid: LinearLayout
    private lateinit var timePanel: LinearLayout
    private lateinit var playerPanel: LinearLayout
    lateinit var lyricsView: TextView
    private lateinit var todoView: TextView
    private lateinit var timeView: TextView
    private lateinit var dateView: TextView
    private lateinit var echoRowView: View
    private lateinit var echoView: TextView
    private lateinit var volumeControlView: View
    private lateinit var volumeIconView: ImageView
    private lateinit var volumeProgressView: SeekBar
    private lateinit var volumePercentView: TextView
    private lateinit var progressView: SeekBar
    private lateinit var positionView: TextView
    private lateinit var durationView: TextView
    private lateinit var controlsView: View
    private lateinit var progressTimesView: View
    private lateinit var prevButton: ImageButton
    private lateinit var playButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var stopButton: ImageButton
    private lateinit var alarmStopButton: Button

    private var wallpaperBitmap: Bitmap? = null
    private var pendingWallpaperUri: Uri? = null
    private val autoWallpaperDirectory = File("/sdcard/zyymeWallpaper")
    private var autoWallpaperFiles = emptyList<File>()
    private var autoWallpaperIndex = -1
    private var currentAutoWallpaper: File? = null
    private var isUserSeeking = false
    private var isUserAdjustingVolume = false
    private var lyricsRefreshScheduled = false
    private var lyricsEnabled = false
    var alarmMode = false
    var isKeepScreenOn = false

    private var timeFormat = SimpleDateFormat("h:mm:ss", Locale.CHINA)
    private val dateFormat = SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA)
    private val weatherDateFormat = SimpleDateFormat("M月d日 E", Locale.CHINA)

    private val refreshRunnable = object : Runnable {
        override fun run() {
            val service = MeService.me
            val position = service?.getPlaybackPosition()
            if (lyricsEnabled && position != null) {
                // 双语歌词在服务解析歌词文本时已经用换行拼接；单语歌词保持原样。
                val lyric = service?.getCurrentLyric(position).orEmpty()
                if (lyricsView.text.toString() != lyric) lyricsView.text = lyric
                handler.postDelayed(this, 250L)
            } else {
                lyricsRefreshScheduled = false
                if (lyricsView.text.isNotEmpty()) {
                    lyricsView.text = ""
                }
            }
        }
    }

    private fun ensureLyricsRefresh(position: Int?) {
        if (position != null && lyricsEnabled) {
            if (!lyricsRefreshScheduled) {
                lyricsRefreshScheduled = true
                handler.post(refreshRunnable)
            }
        } else if (lyricsRefreshScheduled) {
            lyricsRefreshScheduled = false
            handler.removeCallbacks(refreshRunnable)
            if (lyricsView.text.isNotEmpty()) {
                lyricsView.text = ""
            }
        }
    }

    private val nonLyricsRefreshRunnable = object : Runnable {
        override fun run() {
            val now = Date()
            val service = MeService.me
            // 保留天气请求对服务启动时 writer 尚未就绪的重试；壁纸轮换由整点任务单独调度。
            service?.requestWeatherIfNeeded()
            service?.requestTodoIfNeeded()
            setTextIfChanged(timeView, timeFormat.format(now))
            val weather = service?.weatherText.orEmpty()
            val date = if (weather.isEmpty()) {
                dateFormat.format(now)
            } else {
                "${weatherDateFormat.format(now)} $weather"
            }
            setTextIfChanged(dateView, date)
            service?.lastEcho?.let {
                setTextIfChanged(echoView, it)
            }
            val position = service?.getPlaybackPosition()
            ensureLyricsRefresh(position)
            updateProgress(position, service?.getPlaybackDuration() ?: 0)
            updateVolumeControl()
            handler.postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }

    private val hourlyUpdateRunnable = object : Runnable {
        override fun run() {
            if (isActivityStarted && isScreenInteractive()) {
                MeService.me?.requestWeatherIfNeeded()
                MeService.me?.requestTodoIfNeeded()
                advanceAutoWallpaper()
            }
            scheduleHourlyUpdates()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        me = this
        super.onCreate(savedInstanceState)

        if (MeService.me == null) {
            startService(Intent(this, MeService::class.java))
        }
        showAboveLockScreen()
        if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE &&
            MeSettings.isEnabled(this, MeSettings.KEY_LANDSCAPE)
        ) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        setFullscreen()
        setContentView(R.layout.activity_desk)
        bindViews()
        configureClockFormat()
        loadSlotSettings()
        bindActions()
        applyMask()
        applyTextStyle()
        lyricsEnabled = MeSettings.isEnabled(this, MeSettings.KEY_LYRICS)

        grid.post {
            sizePanels()
            applyConfiguredLayout()
            loadWallpaper()
            handleIntent(intent)
        }
        handler.post(nonLyricsRefreshRunnable)
        scheduleHourlyUpdates()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            grid.post { handleIntent(intent) }
        }
        restoreFullscreenAfterTransition()
    }

    override fun onStart() {
        super.onStart()
        isActivityStarted = true
    }

    override fun onStop() {
        isActivityStarted = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        lyricsEnabled = MeSettings.isEnabled(this, MeSettings.KEY_LYRICS)
        todoView.text = MeService.me?.todoText.orEmpty()
        configureClockFormat()
        loadSlotSettings()
        applyMask()
        applyTextStyle()
        updatePlaybackButton(MeService.me?.shouldShowPauseIcon() == true)
        grid.post {
            sizePanels()
            applyConfiguredLayout()
            if (alarmMode) showAlarmControls(true)
        }
        MeService.me?.syncLyricsSetting()
        MeService.me?.requestWeatherIfNeeded()
        MeService.me?.requestTodoIfNeeded()
        scheduleHourlyUpdates()
        applyDefaultKeepScreenOn()
        restoreFullscreenAfterTransition()
        AmbientBrightnessController.applyLatestTo(window)
    }

    fun showEcho(message: String) {
        runOnUiThread {
            if (::echoView.isInitialized) echoView.text = message
        }
    }

    fun updateTodoText(message: String) {
        runOnUiThread {
            if (::todoView.isInitialized && todoView.text.toString() != message) {
                todoView.text = message
                todoView.scrollTo(0, 0)
            }
        }
    }

    private fun bindViews() {
        wallpaperView = findViewById(R.id.desk_wallpaper)
        maskView = findViewById(R.id.desk_mask)
        grid = findViewById(R.id.desk_grid)
        timePanel = findViewById(R.id.desk_time_panel)
        playerPanel = findViewById(R.id.desk_player_panel)
        lyricsView = findViewById(R.id.desk_lyrics)
        todoView = findViewById(R.id.desk_todo)
        todoView.movementMethod = ScrollingMovementMethod()
        lyricsView.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                updateLyricsLayout()
            }
        })
        timeView = findViewById(R.id.desk_time)
        dateView = findViewById(R.id.desk_date)
        echoRowView = findViewById(R.id.desk_echo_row)
        echoView = findViewById(R.id.desk_echo)
        volumeControlView = findViewById(R.id.desk_volume_control)
        volumeIconView = findViewById(R.id.desk_volume_icon)
        volumeProgressView = findViewById(R.id.desk_volume_progress)
        volumePercentView = findViewById(R.id.desk_volume_percent)
        progressView = findViewById(R.id.desk_progress)
        positionView = findViewById(R.id.desk_position)
        durationView = findViewById(R.id.desk_duration)
        controlsView = findViewById(R.id.desk_controls)
        progressTimesView = findViewById(R.id.desk_progress_times)
        prevButton = findViewById(R.id.desk_prev)
        playButton = findViewById(R.id.desk_play)
        nextButton = findViewById(R.id.desk_next)
        stopButton = findViewById(R.id.desk_stop)
        alarmStopButton = findViewById(R.id.desk_alarm_stop)
    }

    private fun bindActions() {
        prevButton.setOnClickListener {
            MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_PREVIOUS, true)
        }
        playButton.setOnClickListener {
            MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, true)
        }
        nextButton.setOnClickListener {
            MeService.me?.keyHandle(2147483645, true)
        }
        stopButton.setOnClickListener {
            MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_STOP, true)
        }
        alarmStopButton.setOnClickListener {
            MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_STOP, true)
            showAlarmControls(false)
        }

        progressView.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) positionView.text = formatMusicTime(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                MeService.me?.seekPlaybackTo(seekBar?.progress ?: 0)
                isUserSeeking = false
            }
        })
        progressTimesView.setOnTouchListener { _, event -> forwardTouchToProgress(event) }
        progressView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            progressView.post { alignProgressTimesToTrack() }
        }
        progressView.post { alignProgressTimesToTrack() }

        volumeProgressView.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    volumePercentView.text = "$progress%"
                    setMediaVolumePercent(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserAdjustingVolume = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserAdjustingVolume = false
                updateVolumeControl()
            }
        })
        volumeControlView.setOnTouchListener { _, event -> forwardTouchToVolume(event) }
        updateVolumeControl()

        todoView.setOnClickListener {
            todoView.text = "正在刷新..."
            todoView.scrollTo(0, 0)
            MeService.me?.refreshTodoNow()
        }

        val longClickListener = View.OnLongClickListener {
            showDeskMenu()
            true
        }
        val menuTrigger = findViewById<View>(R.id.desk_menu_trigger)
        val menuGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                lockScreenIfPermitted()
                return true
            }
        })
        menuTrigger.setOnLongClickListener(longClickListener)
        menuTrigger.setOnTouchListener { _, event ->
            menuGestureDetector.onTouchEvent(event)
            false
        }
    }

    private fun showDeskMenu() {
        val slotNames = arrayOf("↖左上角", "↗右上角", "↙左下角", "↘右下角")
        val slotContentNames = arrayOf("不显示", "时间日期", "播放控制", "待办事项")
        val lyricsPositionNames = arrayOf("顶部", "底部", "不显示")
        val lyricsPosition = loadLyricsPosition()
        val maskEnabled = MeSettings.isEnabled(this, MeSettings.KEY_DESK_MASK)
        val lightText = MeSettings.isEnabled(this, MeSettings.KEY_DESK_LIGHT_TEXT, true)
        val keepScreenOn = MeSettings.isEnabled(this, MeSettings.KEY_DESK_KEEP_SCREEN_ON)
        val items = mutableListOf(
            "应用列表",
            "设置壁纸",
            "深色遮罩：${if (maskEnabled) "开" else "关"}",
            "文字颜色：${if (lightText) "白色" else "黑色"}",
            "屏幕常亮：${if (keepScreenOn) "开" else "关"}",
            "歌词/信息显示：${lyricsPositionNames[lyricsPosition]}",
        )
        slotNames.forEachIndexed { slot, name ->
            items += "$name：${slotContentName(slotValues[slot], slotContentNames)}"
        }
        val hasNextWallpaper = listAutoWallpaperFiles().size > 1
        if (hasNextWallpaper) {
            items += "下一张壁纸"
        }
        items += "返回"
        val returnItemIndex = if (hasNextWallpaper) 11 else 10

        val dialog = AlertDialog.Builder(this)
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which == 0 -> startActivity(Intent(this, AppListActivity::class.java))
                    which == 1 -> chooseWallpaper()
                    which == 2 -> {
                        MeSettings.setEnabled(this, MeSettings.KEY_DESK_MASK, !maskEnabled)
                        applyMask()
                    }
                    which == 3 -> {
                        MeSettings.setEnabled(this, MeSettings.KEY_DESK_LIGHT_TEXT, !lightText)
                        applyTextStyle()
                    }
                    which == 4 -> {
                        val enabled = !keepScreenOn
                        MeSettings.setEnabled(this, MeSettings.KEY_DESK_KEEP_SCREEN_ON, enabled)
                        applyKeepScreenOnState(enabled)
                    }
                    which == 5 -> showLyricsPositionDialog(lyricsPositionNames, lyricsPosition)
                    which in 6..9 -> showSlotContentDialog(which - 6, slotNames[which - 6], slotContentNames)
                    hasNextWallpaper && which == 10 -> advanceAutoWallpaper()
                    which == returnItemIndex -> returnToMain()
                }
            }
            .create()
        dialog.setCanceledOnTouchOutside(true)
        showImmersiveDialog(dialog)
    }

    private fun showLyricsPositionDialog(positionNames: Array<String>, position: Int) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("歌词/信息显示")
            .setSingleChoiceItems(positionNames, position) { choiceDialog, selected ->
                MeSettings.setInt(this, MeSettings.KEY_DESK_LYRICS_POSITION, selected)
                applyConfiguredLayout()
                choiceDialog.dismiss()
            }
            .create()
        dialog.setCanceledOnTouchOutside(true)
        showImmersiveDialog(dialog)
    }

    private fun showSlotContentDialog(slot: Int, slotName: String, contentNames: Array<String>) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(slotName)
            .setSingleChoiceItems(contentNames, slotContentChoice(slotValues[slot])) { choiceDialog, content ->
                setSlotContent(slot, slotContentValue(content))
                choiceDialog.dismiss()
            }
            .create()
        dialog.setCanceledOnTouchOutside(true)
        showImmersiveDialog(dialog)
    }

    private fun returnToMain() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
    }

    private fun setSlotContent(slot: Int, content: Int) {
        if (slot !in slotValues.indices || content !in setOf(CONTENT_NONE, CONTENT_TIME, CONTENT_PLAYER, CONTENT_TODO)) return

        if (content != CONTENT_NONE) {
            slotValues.indices.filter { it != slot && slotValues[it] == content }
                .forEach { slotValues[it] = CONTENT_NONE }
        }
        slotValues[slot] = content
        saveSlotSettings()
        applyConfiguredLayout()
    }

    private fun loadSlotSettings() {
        slotKeys.indices.forEach { index ->
            val stored = MeSettings.getInt(this, slotKeys[index], defaultSlots[index])
            // 3 was the old four-corner "歌词" value; it is now represented by the
            // independent desk lyrics position setting.
            slotValues[index] = if (stored == LEGACY_CONTENT_LYRICS) {
                CONTENT_NONE
            } else {
                stored.coerceIn(CONTENT_NONE, CONTENT_TODO)
            }
        }
        normalizeSlots()
    }

    private fun normalizeSlots() {
        for (content in listOf(CONTENT_TIME, CONTENT_PLAYER, CONTENT_TODO)) {
            val matches = slotValues.indices.filter { slotValues[it] == content }
            matches.drop(1).forEach { slotValues[it] = CONTENT_NONE }
        }
    }

    private fun saveSlotSettings() {
        slotKeys.indices.forEach { MeSettings.setInt(this, slotKeys[it], slotValues[it]) }
    }

    private fun applyConfiguredLayout() {
        if (alarmMode) return
        removeFromParent(timePanel)
        removeFromParent(playerPanel)
        removeFromParent(lyricsView)
        removeFromParent(todoView)

        applyLyricsLayout()

        slotValues.forEachIndexed { slot, content ->
            when (content) {
                CONTENT_TIME -> addPanelToSlot(timePanel, slot, false, true)
                CONTENT_PLAYER -> addPanelToSlot(playerPanel, slot, true)
                CONTENT_TODO -> addPanelToSlot(todoView, slot, false)
            }
        }
    }

    private fun slotContentName(content: Int, names: Array<String>): String {
        return names.getOrElse(slotContentChoice(content)) { names[0] }
    }

    private fun slotContentChoice(content: Int): Int {
        return when (content) {
            CONTENT_TIME -> 1
            CONTENT_PLAYER -> 2
            CONTENT_TODO -> 3
            else -> 0
        }
    }

    private fun slotContentValue(choice: Int): Int {
        return when (choice) {
            1 -> CONTENT_TIME
            2 -> CONTENT_PLAYER
            3 -> CONTENT_TODO
            else -> CONTENT_NONE
        }
    }

    private fun addPanelToSlot(panel: View, slot: Int, player: Boolean, alignTime: Boolean = false) {
        val width = (resources.displayMetrics.widthPixels * 0.4f).toInt()
        val heightFraction = if (player) 0.24f else 0.30f
        val height = (resources.displayMetrics.heightPixels * heightFraction).toInt()
        val horizontalGravity = if (slot % 2 == 0) Gravity.START else Gravity.END
        val gravity = panelGravity(slot)
        if (alignTime) {
            timePanel.gravity = horizontalGravity or Gravity.BOTTOM
            timeView.gravity = horizontalGravity or Gravity.BOTTOM
            dateView.gravity = horizontalGravity or Gravity.TOP
        } else if (panel === todoView) {
            todoView.gravity = horizontalGravity or (if (slot < 2) Gravity.TOP else Gravity.BOTTOM)
        }
        panel.setPadding(0, 0, 0, 0)
        slotFrames()[slot].addView(panel, FrameLayout.LayoutParams(width, height, gravity))
    }

    private fun panelGravity(slot: Int): Int {
        val horizontalGravity = if (slot % 2 == 0) Gravity.START else Gravity.END
        return horizontalGravity or (if (slot < 2) Gravity.TOP else Gravity.BOTTOM)
    }

    private fun loadLyricsPosition(): Int {
        return MeSettings.getInt(this, MeSettings.KEY_DESK_LYRICS_POSITION, LYRICS_TOP)
            .coerceIn(LYRICS_TOP, LYRICS_NONE)
    }

    private fun applyLyricsLayout() {
        val position = loadLyricsPosition()
        if (position == LYRICS_NONE) {
            lyricsView.visibility = View.GONE
            return
        }

        val height = if (lyricsView.text.isEmpty()) {
            0
        } else {
            (resources.displayMetrics.heightPixels * 0.1875f).toInt()
        }
        lyricsView.visibility = if (height > 0) View.VISIBLE else View.GONE
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
        val index = if (position == LYRICS_TOP) 0 else grid.childCount
        grid.addView(lyricsView, index, params)
    }

    private fun updateLyricsLayout() {
        if (!::grid.isInitialized || !::lyricsView.isInitialized) return
        val parent = lyricsView.parent
        if (parent == null) {
            if (loadLyricsPosition() != LYRICS_NONE && lyricsView.text.isNotEmpty()) {
                applyConfiguredLayout()
            }
            return
        }
        val hasText = lyricsView.text.isNotEmpty()
        lyricsView.visibility = if (hasText) View.VISIBLE else View.GONE
        val params = lyricsView.layoutParams as? LinearLayout.LayoutParams ?: return
        val desiredHeight = if (hasText) {
            (resources.displayMetrics.heightPixels * 0.1875f).toInt()
        } else {
            0
        }
        if (params.height != desiredHeight) {
            params.height = desiredHeight
            lyricsView.layoutParams = params
        }
    }

    private fun slotFrames(): List<FrameLayout> = listOf(
        findViewById(R.id.desk_top_left),
        findViewById(R.id.desk_top_right),
        findViewById(R.id.desk_bottom_left),
        findViewById(R.id.desk_bottom_right)
    )

    private fun removeFromParent(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun sizePanels() {
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        grid.setPadding(
            (width * 0.04f).toInt(),
            (height * 0.0267f).toInt(),
            (width * 0.04f).toInt(),
            (height * 0.0267f).toInt()
        )
    }

    fun showAlarmControls(enabled: Boolean) {
        alarmMode = enabled
        echoRowView.visibility = if (enabled) View.GONE else View.VISIBLE
        controlsView.visibility = if (enabled) View.GONE else View.VISIBLE
        progressView.visibility = if (enabled) View.GONE else View.VISIBLE
        progressTimesView.visibility = if (enabled) View.GONE else View.VISIBLE
        alarmStopButton.visibility = if (enabled) View.VISIBLE else View.GONE

        removeFromParent(playerPanel)
        if (enabled) {
            val width = (resources.displayMetrics.widthPixels * 0.4f).toInt()
            val height = (resources.displayMetrics.heightPixels * 0.32f).toInt()
            val configuredPlayerSlot = slotValues.indexOf(CONTENT_PLAYER)
            val alarmSlot = if (configuredPlayerSlot >= 0) configuredPlayerSlot else 3
            playerPanel.setPadding(0, 0, 0, 0)
            slotFrames()[alarmSlot].addView(
                playerPanel,
                FrameLayout.LayoutParams(width, height, panelGravity(alarmSlot))
            )
        } else {
            applyConfiguredLayout()
        }
    }

    private fun handleIntent(source: Intent) {
        if (source.getBooleanExtra("keepOn", false)) {
            applyKeepScreenOnState(true)
        }
        if (source.getBooleanExtra(EXTRA_ALARM_MODE, false)) {
            showAlarmControls(true)
        }
    }

    private fun applyDefaultKeepScreenOn() {
        if (MeSettings.isEnabled(this, MeSettings.KEY_DESK_KEEP_SCREEN_ON)) {
            applyKeepScreenOnState(true)
        }
    }

    private fun applyKeepScreenOnState(enabled: Boolean) {
        isKeepScreenOn = enabled
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun chooseWallpaper() {
        Toast.makeText(this, "将图片放到内置存储的zyymeWallpaper中可每小时随机轮换", Toast.LENGTH_LONG).show()
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            Intent.ACTION_OPEN_DOCUMENT
        } else {
            Intent.ACTION_GET_CONTENT
        }
        val intent = Intent(action).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            type = "image/*"
        }
        startActivityForResult(intent, REQUEST_PICK_WALLPAPER)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_WALLPAPER && resultCode == Activity.RESULT_OK) {
            val source = data?.data ?: return
            pendingWallpaperUri = source
            tryCropWallpaper(source)
        } else if (requestCode == REQUEST_CROP_WALLPAPER && resultCode == Activity.RESULT_OK) {
            val cropped = File(cacheDir, "desk_crop.jpg")
            if (cropped.exists() && cropped.length() > 0L) {
                saveWallpaperFile(cropped)
            } else {
                pendingWallpaperUri?.let { saveWallpaperUri(it) }
            }
        }
    }

    private fun tryCropWallpaper(source: Uri) {
        val cropped = File(cacheDir, "desk_crop.jpg")
        if (cropped.exists()) cropped.delete()
        val output = FileProvider.getUriForFile(this, "$packageName.fileprovider", cropped)
        val cropIntent = Intent("com.android.camera.action.CROP").apply {
            setDataAndType(source, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            putExtra("crop", "true")
            putExtra("aspectX", resources.displayMetrics.widthPixels)
            putExtra("aspectY", resources.displayMetrics.heightPixels)
            putExtra("scale", true)
            putExtra("return-data", false)
            putExtra(MediaStore.EXTRA_OUTPUT, output)
            putExtra("outputFormat", "JPEG")
        }
        val handlers = packageManager.queryIntentActivities(cropIntent, 0)
        if (handlers.isEmpty()) {
            saveWallpaperUri(source)
            return
        }
        handlers.forEach {
            grantUriPermission(
                it.activityInfo.packageName,
                output,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        try {
            startActivityForResult(cropIntent, REQUEST_CROP_WALLPAPER)
        } catch (_: Exception) {
            saveWallpaperUri(source)
        }
    }

    private fun saveWallpaperUri(source: Uri) {
        Thread {
            try {
                val target = File(filesDir, "desk.jpg")
                val input = contentResolver.openInputStream(source)
                    ?: throw IllegalStateException("无法读取选择的图片")
                input.use {
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
                handler.post { onWallpaperSaved() }
            } catch (e: Exception) {
                handler.post { Toast.makeText(this, "壁纸设置失败：${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun saveWallpaperFile(source: File) {
        Thread {
            try {
                val target = File(filesDir, "desk.jpg")
                source.inputStream().use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
                handler.post { onWallpaperSaved() }
            } catch (e: Exception) {
                handler.post { Toast.makeText(this, "壁纸设置失败：${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun onWallpaperSaved() {
        pendingWallpaperUri = null
        loadWallpaper()
        Toast.makeText(this, "壁纸已设置", Toast.LENGTH_SHORT).show()
    }

    private fun scheduleHourlyUpdates() {
        handler.removeCallbacks(hourlyUpdateRunnable)
        val remainder = System.currentTimeMillis() % HOUR_MILLIS
        val delay = (HOUR_MILLIS - remainder).coerceAtLeast(1000L)
        handler.postDelayed(hourlyUpdateRunnable, delay)
    }

    private fun isScreenInteractive(): Boolean {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            powerManager.isInteractive
        } else {
            powerManager.isScreenOn
        }
    }

    private fun listAutoWallpaperFiles(): List<File> {
        return try {
            if (!autoWallpaperDirectory.exists()) {
                autoWallpaperDirectory.mkdirs()
            }
            if (!autoWallpaperDirectory.isDirectory) {
                emptyList()
            } else {
                autoWallpaperDirectory.listFiles()
                    ?.filter { it.isFile && isAutoWallpaperFile(it) }
                    ?.sortedBy { it.name.lowercase(Locale.ROOT) }
                    .orEmpty()
            }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private fun isAutoWallpaperFile(file: File): Boolean {
        return when (file.extension.lowercase(Locale.ROOT)) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif" -> true
            else -> false
        }
    }

    private fun hasSameAutoWallpaperFiles(files: List<File>): Boolean {
        return files.map { it.absolutePath }.toSet() ==
                autoWallpaperFiles.map { it.absolutePath }.toSet()
    }

    private fun shuffleAutoWallpaperFiles(files: List<File>, avoid: File? = null): List<File> {
        val shuffled = files.shuffled().toMutableList()
        if (avoid != null && shuffled.size > 1 && shuffled.first().absolutePath == avoid.absolutePath) {
            shuffled[0] = shuffled[1].also { shuffled[1] = shuffled[0] }
        }
        return shuffled
    }

    private fun advanceAutoWallpaper() {
        val files = listAutoWallpaperFiles()
        if (files.isEmpty()) {
            autoWallpaperFiles = emptyList()
            autoWallpaperIndex = -1
            currentAutoWallpaper = null
            loadWallpaper()
            return
        }

        val previous = currentAutoWallpaper
        if (!hasSameAutoWallpaperFiles(files) || autoWallpaperIndex !in autoWallpaperFiles.indices) {
            autoWallpaperFiles = shuffleAutoWallpaperFiles(files, previous)
            autoWallpaperIndex = 0
        } else if (autoWallpaperIndex == autoWallpaperFiles.lastIndex) {
            autoWallpaperFiles = shuffleAutoWallpaperFiles(files, previous)
            autoWallpaperIndex = 0
        } else {
            autoWallpaperIndex++
        }
        currentAutoWallpaper = autoWallpaperFiles[autoWallpaperIndex]
        loadWallpaper()
    }

    private fun selectAutoWallpaper(): File? {
        val files = listAutoWallpaperFiles()
        if (files.isEmpty()) {
            autoWallpaperFiles = emptyList()
            autoWallpaperIndex = -1
            currentAutoWallpaper = null
            return null
        }
        if (!hasSameAutoWallpaperFiles(files) ||
            currentAutoWallpaper == null || !currentAutoWallpaper!!.isFile
        ) {
            autoWallpaperFiles = shuffleAutoWallpaperFiles(files)
            autoWallpaperIndex = 0
            currentAutoWallpaper = autoWallpaperFiles[autoWallpaperIndex]
        }
        return currentAutoWallpaper
    }

    private fun loadWallpaper() {
        val width = wallpaperView.width.coerceAtLeast(resources.displayMetrics.widthPixels)
        val height = wallpaperView.height.coerceAtLeast(resources.displayMetrics.heightPixels)
        val auto = selectAutoWallpaper()
        val custom = auto ?: File(filesDir, "desk.jpg")
        try {
            val bitmap = decodeSampledFileSafely(custom, width, height)
                ?: if (auto != null) {
                    decodeSampledFileSafely(File(filesDir, "desk.jpg"), width, height)
                } else {
                    null
                }
                ?: decodeSampledResource(R.drawable.desk, width, height)
            if (bitmap != null) {
                val old = wallpaperBitmap
                wallpaperBitmap = bitmap
                wallpaperView.setImageBitmap(bitmap)
                if (old != null && old !== bitmap && !old.isRecycled) old.recycle()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "壁纸读取失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun decodeSampledFileSafely(file: File, width: Int, height: Int): Bitmap? {
        return if (file.exists() && file.length() > 0L) {
            try {
                decodeSampledFile(file, width, height)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
    }

    private fun decodeSampledFile(file: File, width: Int, height: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, width, height)
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun decodeSampledResource(resourceId: Int, width: Int, height: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(resources, resourceId, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, width, height)
        }
        return BitmapFactory.decodeResource(resources, resourceId, options)
    }

    private fun calculateSampleSize(sourceWidth: Int, sourceHeight: Int, width: Int, height: Int): Int {
        var sample = 1
        while (sourceWidth / (sample * 2) >= width && sourceHeight / (sample * 2) >= height) {
            sample *= 2
        }
        return sample
    }

    private fun applyMask() {
        maskView.visibility = if (MeSettings.isEnabled(this, MeSettings.KEY_DESK_MASK)) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun applyTextStyle() {
        val light = MeSettings.isEnabled(this, MeSettings.KEY_DESK_LIGHT_TEXT, true)
        val color = if (light) Color.WHITE else Color.BLACK
        val shadow = Color.argb(210, 0, 0, 0)
        listOf(
            timeView,
            dateView,
            lyricsView,
            todoView,
            echoView,
            volumePercentView,
            positionView,
            durationView
        ).forEach {
            it.setTextColor(color)
            if (light) {
                it.setShadowLayer(4f, 1f, 1f, shadow)
            } else {
                it.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            }
        }
        volumeIconView.drawable?.mutate()?.let { icon ->
            DrawableCompat.setTint(icon, color)
            volumeIconView.setImageDrawable(icon)
        }
        listOf(
            R.id.desk_prev to prevButton,
            R.id.desk_play to playButton,
            R.id.desk_next to nextButton,
            R.id.desk_stop to stopButton
        ).forEach { (id, button) ->
            val base = baseIconDrawables.getOrPut(id) {
                button.drawable.constantState?.newDrawable()?.mutate() ?: button.drawable.mutate()
            }
            val foreground = base.constantState?.newDrawable()?.mutate() ?: base.mutate()
            DrawableCompat.setTint(foreground, color)
            if (light) {
                val shadowDrawable = base.constantState?.newDrawable()?.mutate() ?: base.mutate()
                DrawableCompat.setTint(shadowDrawable, shadow)
                val layers = LayerDrawable(arrayOf(shadowDrawable, foreground))
                val offset = (resources.displayMetrics.density * 2f).toInt().coerceAtLeast(1)
                layers.setLayerInset(0, offset, offset, 0, 0)
                button.setImageDrawable(layers)
            } else {
                button.setImageDrawable(foreground)
            }
        }
        applyProgressFillColor(color)
        alarmStopButton.setTextColor(color)
        if (light) {
            alarmStopButton.setShadowLayer(4f, 1f, 1f, shadow)
        } else {
            alarmStopButton.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
    }

    fun updatePlaybackButton(showPause: Boolean) {
        runOnUiThread {
            if (::playButton.isInitialized) {
                if (playButton.isSelected != showPause) {
                    playButton.isSelected = showPause
                }
                val description = if (showPause) "暂停" else "播放"
                if (playButton.contentDescription?.toString() != description) {
                    playButton.contentDescription = description
                }
            }
        }
    }

    private fun applyProgressFillColor(color: Int) {
        listOf(progressView, volumeProgressView).forEach { seekBar ->
            val drawable = (seekBar.progressDrawable?.mutate() as? LayerDrawable) ?: return@forEach
            drawable.findDrawableByLayerId(android.R.id.progress)?.let { progressLayer ->
                DrawableCompat.setTint(progressLayer, color)
            }
            seekBar.progressDrawable = drawable
        }
    }

    private fun configureClockFormat() {
        val showSeconds = !MeSettings.isEnabled(this, MeSettings.KEY_TSS)
        val hour = if (MeSettings.isEnabled(this, MeSettings.KEY_T24)) "H:mm" else "h:mm"
        timeFormat = SimpleDateFormat(if (showSeconds) "$hour:ss" else hour, Locale.CHINA)
    }

    private fun updateVolumeControl() {
        if (!::volumeControlView.isInitialized || volumeControlView.visibility != View.VISIBLE) return
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val percent = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max)
            .roundToInt()
            .coerceIn(0, 100)
        if (!isUserAdjustingVolume && volumeProgressView.progress != percent) {
            volumeProgressView.progress = percent
        }
        setTextIfChanged(volumePercentView, "$percent%")
    }

    private fun setMediaVolumePercent(percent: Int) {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val volume = (max * percent.coerceIn(0, 100) / 100f).roundToInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
    }

    private fun forwardTouchToVolume(event: MotionEvent): Boolean {
        val forwardedEvent = MotionEvent.obtain(event)
        val sourceLocation = IntArray(2)
        val targetLocation = IntArray(2)
        volumeControlView.getLocationOnScreen(sourceLocation)
        volumeProgressView.getLocationOnScreen(targetLocation)
        forwardedEvent.offsetLocation(
            (sourceLocation[0] - targetLocation[0]).toFloat(),
            (sourceLocation[1] - targetLocation[1]).toFloat()
        )
        return try {
            volumeProgressView.dispatchTouchEvent(forwardedEvent)
        } finally {
            forwardedEvent.recycle()
        }
    }

    private fun lockScreenIfPermitted() {
        val devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, MeDeviceAdminReceiver::class.java)
        if (!devicePolicyManager.isAdminActive(adminComponent)) return
        try {
            devicePolicyManager.lockNow()
        } catch (_: Exception) {
            // Double-tap lock is intentionally silent when the device rejects the request.
        }
    }

    private fun forwardTouchToProgress(event: MotionEvent): Boolean {
        if (!progressView.isEnabled || progressView.max <= 0) return false

        val forwardedEvent = MotionEvent.obtain(event)
        forwardedEvent.offsetLocation(
            (progressTimesView.left - progressView.left).toFloat(),
            (progressTimesView.top - progressView.top).toFloat()
        )
        return try {
            progressView.dispatchTouchEvent(forwardedEvent)
        } finally {
            forwardedEvent.recycle()
        }
    }

    private fun alignProgressTimesToTrack() {
        val trackBounds = progressView.progressDrawable?.bounds ?: return
        val trackStart = progressView.left + progressView.paddingLeft + trackBounds.left
        val trackEnd = progressView.left + progressView.paddingLeft + trackBounds.right
        progressTimesView.setPadding(
            (trackStart - progressTimesView.left).coerceAtLeast(0),
            progressTimesView.paddingTop,
            (progressTimesView.right - trackEnd).coerceAtLeast(0),
            progressTimesView.paddingBottom
        )
    }

    private fun updateProgress(position: Int?, duration: Int) {
        if (position == null || duration <= 0) {
            if (progressView.max != 0) progressView.max = 0
            if (progressView.progress != 0) progressView.progress = 0
            if (progressView.isEnabled) progressView.isEnabled = false
            val emptyTime = formatMusicTime(0)
            setTextIfChanged(positionView, emptyTime)
            setTextIfChanged(durationView, emptyTime)
            return
        }
        if (!progressView.isEnabled) progressView.isEnabled = true
        if (progressView.max != duration) progressView.max = duration
        if (!isUserSeeking) {
            val progress = position.coerceIn(0, duration)
            if (progressView.progress != progress) progressView.progress = progress
            setTextIfChanged(positionView, formatMusicTime(position))
        }
        setTextIfChanged(durationView, formatMusicTime(duration))
    }

    private fun setTextIfChanged(view: TextView, text: String) {
        if (view.text.toString() != text) view.text = text
    }

    private fun formatMusicTime(millis: Int): String {
        val totalSeconds = millis.coerceAtLeast(0) / 1000
        val hours = totalSeconds / 3600
        val minutes = totalSeconds % 3600 / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.CHINA, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.CHINA, "%d:%02d", minutes, seconds)
        }
    }

    private fun showAboveLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    private fun fullscreenSystemUiVisibility(): Int = if (Build.MODEL == "HPN_XH") {
        View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    } else {
        View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    private fun hideSystemBars(targetWindow: Window) {
        targetWindow.decorView.systemUiVisibility = fullscreenSystemUiVisibility()
        WindowCompat.setDecorFitsSystemWindows(targetWindow, false)
        WindowInsetsControllerCompat(targetWindow, targetWindow.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showImmersiveDialog(dialog: AlertDialog) {
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        dialog.setOnDismissListener { setFullscreen() }
        dialog.show()
        dialog.window?.let { dialogWindow ->
            hideSystemBars(dialogWindow)
            dialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            dialogWindow.decorView.post { hideSystemBars(dialogWindow) }
        }
    }

    private fun setFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideSystemBars(window)
    }

    private fun restoreFullscreenAfterTransition() {
        setFullscreen()
        window.decorView.removeCallbacks(fullscreenRestoreRunnable)
        window.decorView.post(fullscreenRestoreRunnable)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setFullscreen()
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        when (event?.action) {
            KeyEvent.ACTION_DOWN -> if (MeService.me?.keyHandle(event.keyCode, true) == true) return true
            KeyEvent.ACTION_UP -> if (MeService.me?.keyHandle(event.keyCode, false) == true) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        if (MeService.clockModeModel.contains(Build.MANUFACTURER + Build.MODEL) ||
            MeSettings.isEnabled(this, MeSettings.KEY_CLOCK)
        ) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        window.decorView.removeCallbacks(fullscreenRestoreRunnable)
        handler.removeCallbacks(refreshRunnable)
        handler.removeCallbacks(nonLyricsRefreshRunnable)
        handler.removeCallbacks(hourlyUpdateRunnable)
        wallpaperBitmap?.let { if (!it.isRecycled) it.recycle() }
        wallpaperBitmap = null
        if (me === this) me = null
        super.onDestroy()
    }
}
