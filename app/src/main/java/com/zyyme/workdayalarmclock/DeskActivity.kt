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
import android.provider.MediaStore
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
import kotlin.math.min
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
        private const val CONTENT_LYRICS = 3

    }

    private val handler = Handler(Looper.getMainLooper())
    private val slotKeys = arrayOf(
        MeSettings.KEY_DESK_SLOT_TOP_LEFT,
        MeSettings.KEY_DESK_SLOT_TOP_RIGHT,
        MeSettings.KEY_DESK_SLOT_BOTTOM_LEFT,
        MeSettings.KEY_DESK_SLOT_BOTTOM_RIGHT
    )
    private val defaultSlots = intArrayOf(CONTENT_LYRICS, CONTENT_NONE, CONTENT_TIME, CONTENT_PLAYER)
    private val slotValues = defaultSlots.copyOf()
    private val baseIconDrawables = mutableMapOf<Int, Drawable>()

    private lateinit var wallpaperView: ImageView
    private lateinit var maskView: View
    private lateinit var grid: LinearLayout
    private lateinit var timePanel: LinearLayout
    private lateinit var playerPanel: LinearLayout
    private lateinit var lyricsView: TextView
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
    private lateinit var alarmStopButton: Button

    private var wallpaperBitmap: Bitmap? = null
    private var pendingWallpaperUri: Uri? = null
    private var isUserSeeking = false
    private var isUserAdjustingVolume = false
    private var alarmMode = false
    var isKeepScreenOn = false

    private var timeFormat = SimpleDateFormat("h:mm:ss", Locale.CHINA)
    private val dateFormat = SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA)
    private val weatherDateFormat = SimpleDateFormat("M月d日 E", Locale.CHINA)

    private val refreshRunnable = object : Runnable {
        override fun run() {
            val now = Date()
            val service = MeService.me
            service?.requestWeatherIfNeeded()
            timeView.text = timeFormat.format(now)
            val weather = service?.weatherText.orEmpty()
            dateView.text = if (weather.isEmpty()) {
                dateFormat.format(now)
            } else {
                "${weatherDateFormat.format(now)} $weather"
            }

            service?.lastEcho?.let {
                if (echoView.text.toString() != it) echoView.text = it
            }
            val position = service?.getPlaybackPosition()
            val duration = service?.getPlaybackDuration() ?: 0
            updateProgress(position, duration)
            updateVolumeControl()

            if (MeSettings.isEnabled(this@DeskActivity, MeSettings.KEY_LYRICS)) {
                val lyric = formatLyricForTwoLines(service?.getCurrentLyric(position).orEmpty())
                if (lyricsView.text.toString() != lyric) lyricsView.text = lyric
            } else if (lyricsView.text.isNotEmpty()) {
                lyricsView.text = ""
            }

            val delay = if (position != null && MeSettings.isEnabled(this@DeskActivity, MeSettings.KEY_LYRICS)) {
                250L
            } else {
                1000L - System.currentTimeMillis() % 1000L
            }
            handler.postDelayed(this, delay)
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

        grid.post {
            sizePanels()
            applyConfiguredLayout()
            loadWallpaper()
            handleIntent(intent)
        }
        handler.post(refreshRunnable)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            grid.post { handleIntent(intent) }
        }
    }

    override fun onResume() {
        super.onResume()
        configureClockFormat()
        loadSlotSettings()
        applyMask()
        applyTextStyle()
        grid.post {
            sizePanels()
            applyConfiguredLayout()
            if (alarmMode) showAlarmControls(true)
        }
        MeService.me?.syncLyricsSetting()
        MeService.me?.requestWeatherIfNeeded()
        applyDefaultKeepScreenOn()
        setFullscreen()
    }

    fun showEcho(message: String) {
        runOnUiThread {
            if (::echoView.isInitialized) echoView.text = message
        }
    }

    private fun bindViews() {
        wallpaperView = findViewById(R.id.desk_wallpaper)
        maskView = findViewById(R.id.desk_mask)
        grid = findViewById(R.id.desk_grid)
        timePanel = findViewById(R.id.desk_time_panel)
        playerPanel = findViewById(R.id.desk_player_panel)
        lyricsView = findViewById(R.id.desk_lyrics)
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
        alarmStopButton = findViewById(R.id.desk_alarm_stop)
    }

    private fun bindActions() {
        findViewById<ImageButton>(R.id.desk_prev).setOnClickListener {
            MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_PREVIOUS, true)
        }
        findViewById<ImageButton>(R.id.desk_play).setOnClickListener {
            MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, true)
        }
        findViewById<ImageButton>(R.id.desk_next).setOnClickListener {
            MeService.me?.keyHandle(2147483645, true)
        }
        findViewById<ImageButton>(R.id.desk_stop).setOnClickListener {
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
        val contentNames = arrayOf("不显示", "时间日期", "播放控制", "歌词")
        val maskEnabled = MeSettings.isEnabled(this, MeSettings.KEY_DESK_MASK)
        val lightText = MeSettings.isEnabled(this, MeSettings.KEY_DESK_LIGHT_TEXT, true)
        val keepScreenOn = MeSettings.isEnabled(this, MeSettings.KEY_DESK_KEEP_SCREEN_ON)
        val items = mutableListOf(
            "应用列表",
            "设置壁纸",
            "深色遮罩：${if (maskEnabled) "开" else "关"}",
            "文字颜色：${if (lightText) "白色" else "黑色"}",
            "屏幕常亮：${if (keepScreenOn) "开" else "关"}",
        )
        slotNames.forEachIndexed { slot, name ->
            items += "$name：${contentNames[slotValues[slot]]}"
        }
        items += "返回"

        val dialog = AlertDialog.Builder(this)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, AppListActivity::class.java))
                    1 -> chooseWallpaper()
                    2 -> {
                        MeSettings.setEnabled(this, MeSettings.KEY_DESK_MASK, !maskEnabled)
                        applyMask()
                    }
                    3 -> {
                        MeSettings.setEnabled(this, MeSettings.KEY_DESK_LIGHT_TEXT, !lightText)
                        applyTextStyle()
                    }
                    4 -> {
                        val enabled = !keepScreenOn
                        MeSettings.setEnabled(this, MeSettings.KEY_DESK_KEEP_SCREEN_ON, enabled)
                        applyKeepScreenOnState(enabled)
                    }
                    in 6..9 -> showSlotContentDialog(which - 6, slotNames[which - 6], contentNames)
                    10 -> returnToMain()
                }
            }
            .create()
        dialog.setCanceledOnTouchOutside(true)
        showImmersiveDialog(dialog)
    }

    private fun showSlotContentDialog(slot: Int, slotName: String, contentNames: Array<String>) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(slotName)
            .setSingleChoiceItems(contentNames, slotValues[slot]) { choiceDialog, content ->
                setSlotContent(slot, content)
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
        if (slot !in slotValues.indices || content !in CONTENT_NONE..CONTENT_LYRICS) return

        if (content != CONTENT_NONE) {
            slotValues.indices.filter { it != slot && slotValues[it] == content }
                .forEach { slotValues[it] = CONTENT_NONE }
        }
        if (content == CONTENT_LYRICS) {
            slotValues[pairedSlot(slot)] = CONTENT_NONE
        } else if (content != CONTENT_NONE && slotValues[pairedSlot(slot)] == CONTENT_LYRICS) {
            slotValues[pairedSlot(slot)] = CONTENT_NONE
        }
        slotValues[slot] = content
        saveSlotSettings()
        applyConfiguredLayout()
    }

    private fun pairedSlot(slot: Int): Int = if (slot % 2 == 0) slot + 1 else slot - 1

    private fun loadSlotSettings() {
        slotKeys.indices.forEach { index ->
            slotValues[index] = MeSettings.getInt(this, slotKeys[index], defaultSlots[index])
                .coerceIn(CONTENT_NONE, CONTENT_LYRICS)
        }
        normalizeSlots()
    }

    private fun normalizeSlots() {
        for (content in CONTENT_TIME..CONTENT_LYRICS) {
            val matches = slotValues.indices.filter { slotValues[it] == content }
            matches.drop(1).forEach { slotValues[it] = CONTENT_NONE }
        }
        val lyricSlot = slotValues.indexOf(CONTENT_LYRICS)
        if (lyricSlot >= 0) slotValues[pairedSlot(lyricSlot)] = CONTENT_NONE
    }

    private fun saveSlotSettings() {
        slotKeys.indices.forEach { MeSettings.setInt(this, slotKeys[it], slotValues[it]) }
    }

    private fun applyConfiguredLayout() {
        if (alarmMode) return
        removeFromParent(timePanel)
        removeFromParent(playerPanel)
        removeFromParent(lyricsView)

        slotValues.forEachIndexed { slot, content ->
            when (content) {
                CONTENT_TIME -> addPanelToSlot(timePanel, slot, false)
                CONTENT_PLAYER -> addPanelToSlot(playerPanel, slot, true)
                CONTENT_LYRICS -> addLyricsToRow(slot / 2)
            }
        }
    }

    private fun addPanelToSlot(panel: View, slot: Int, player: Boolean) {
        val width = (resources.displayMetrics.widthPixels * 0.4f).toInt()
        val heightFraction = if (player) 0.24f else 0.30f
        val height = (resources.displayMetrics.heightPixels * heightFraction).toInt()
        val horizontalGravity = if (slot % 2 == 0) Gravity.START else Gravity.END
        val gravity = horizontalGravity or
            (if (slot < 2) Gravity.TOP else Gravity.BOTTOM)
        if (!player) {
            timePanel.gravity = horizontalGravity or Gravity.BOTTOM
            timeView.gravity = horizontalGravity or Gravity.BOTTOM
            dateView.gravity = horizontalGravity or Gravity.TOP
        }
        panel.setPadding(0, 0, 0, 0)
        slotFrames()[slot].addView(panel, FrameLayout.LayoutParams(width, height, gravity))
    }

    private fun addLyricsToRow(row: Int) {
        val rowView = if (row == 0) {
            findViewById<FrameLayout>(R.id.desk_top_row)
        } else {
            findViewById<FrameLayout>(R.id.desk_bottom_row)
        }
        val height = min(
            (resources.displayMetrics.heightPixels * 0.1875f).toInt(),
            rowView.height.takeIf { it > 0 } ?: Int.MAX_VALUE
        )
        val gravity = if (row == 0) Gravity.TOP else Gravity.BOTTOM
        rowView.addView(
            lyricsView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height, gravity)
        )
        lyricsView.visibility = if (MeSettings.isEnabled(this, MeSettings.KEY_LYRICS)) {
            View.VISIBLE
        } else {
            View.GONE
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

    private fun showAlarmControls(enabled: Boolean) {
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
            playerPanel.setPadding(0, 0, 0, 0)
            findViewById<FrameLayout>(R.id.desk_bottom_row).addView(
                playerPanel,
                FrameLayout.LayoutParams(width, height, Gravity.END or Gravity.BOTTOM)
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

    private fun loadWallpaper() {
        val width = wallpaperView.width.coerceAtLeast(resources.displayMetrics.widthPixels)
        val height = wallpaperView.height.coerceAtLeast(resources.displayMetrics.heightPixels)
        val custom = File(filesDir, "desk.jpg")
        try {
            val bitmap = if (custom.exists() && custom.length() > 0L) {
                decodeSampledFile(custom, width, height)
            } else {
                decodeSampledResource(R.drawable.desk, width, height)
            }
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
        listOf(R.id.desk_prev, R.id.desk_play, R.id.desk_next, R.id.desk_stop).forEach {
            val button = findViewById<ImageButton>(it)
            val base = baseIconDrawables.getOrPut(it) {
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

    private fun formatLyricForTwoLines(lyric: String): String {
        return if (lyric.isNotEmpty() && '\n' !in lyric) "$lyric\n" else lyric
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
        volumePercentView.text = "$percent%"
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
            progressView.max = 0
            progressView.progress = 0
            progressView.isEnabled = false
            positionView.text = formatMusicTime(0)
            durationView.text = formatMusicTime(0)
            return
        }
        progressView.isEnabled = true
        if (progressView.max != duration) progressView.max = duration
        if (!isUserSeeking) {
            progressView.progress = position.coerceIn(0, duration)
            positionView.text = formatMusicTime(position)
        }
        durationView.text = formatMusicTime(duration)
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
        handler.removeCallbacks(refreshRunnable)
        wallpaperBitmap?.let { if (!it.isRecycled) it.recycle() }
        wallpaperBitmap = null
        if (me === this) me = null
        super.onDestroy()
    }
}
