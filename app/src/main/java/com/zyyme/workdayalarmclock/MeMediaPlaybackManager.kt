package com.zyyme.workdayalarmclock

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent

/**
 * 响应系统的音乐播放控件
 * 系统会显示在锁屏上
 */
class MeMediaPlaybackManager(
    private val context: Context
) {

    private var mediaSession: MediaSessionCompat? = null
    private lateinit var playbackStateBuilder: PlaybackStateCompat.Builder
    private var currentMediaSessionActiveState: Boolean = false
    var lastTitle = ""

    companion object {
        const val MEDIA_SESSION_TAG = "WorkdayAlarmClockMediaSession"
    }

    init {
        initializeMediaSession()
    }

    private fun print2LogView(s: String) {
//        MainActivity.me?.print2LogView("MediaManager: $message")
        Log.d("logView MediaPlayback", s)
    }

    private fun initializeMediaSession() {
        val applicationContext = context.applicationContext
        // 这用的是我自己的MediaButtonReceiver不是Android自带的，响应Android4的锁屏按钮
        val meMediaButtonReceiver = ComponentName(applicationContext, MeMediaButtonReceiver::class.java)

        mediaSession = MediaSessionCompat(
            applicationContext,
            MEDIA_SESSION_TAG,
            meMediaButtonReceiver,
            null
        )

        val sessionActivityIntent = Intent(applicationContext, ClockActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            sessionActivityIntent,
            flags
        )
        mediaSession?.setSessionActivity(sessionActivityPendingIntent)

        mediaSession?.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                print2LogView("onPlay called")
                MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_PLAY, 0)
            }

            override fun onPause() {
                print2LogView("onPause called")
                MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_PAUSE, 0)
            }

            override fun onStop() {
                print2LogView("onStop called")
                MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_STOP, 0)
            }

            override fun onSkipToNext() {
                print2LogView("onSkipToNext called")
                MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_NEXT, 0)
            }

            override fun onSkipToPrevious() {
                print2LogView("onSkipToPrevious called")
                MeService.me?.keyHandle(KeyEvent.KEYCODE_MEDIA_PREVIOUS, 0)
            }
        })

        mediaSession?.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )

        playbackStateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
            )

        // 默认STATE_CONNECTING让Android4把控件显示出来
        mediaSession?.setPlaybackState(playbackStateBuilder.setState(PlaybackStateCompat.STATE_CONNECTING, 0, 1.0f).build())
        mediaSession?.isActive = true
        currentMediaSessionActiveState = false
        // 显示默认的标题
        updateMediaMetadata(0, null, null, null)
        print2LogView("MediaSession initialized")
    }

    /**
     * 更新播放状态
     * @param state 播放状态
     * @param currentPosition 当前播放位置
     * @param errorMsg 错误信息
     */
    fun updatePlaybackState(state: Int, currentPosition: Long, errorMsg: String? = null) {
        if (mediaSession == null) {
            print2LogView("updatePlaybackState called but mediaSession is null")
            return
        }

        val playbackSpeed = if (state == PlaybackStateCompat.STATE_PLAYING) 1.0f else 0.0f

        var availableActions = PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO
        when (state) {
            PlaybackStateCompat.STATE_PLAYING -> {
                availableActions = availableActions or PlaybackStateCompat.ACTION_PAUSE
            }
            PlaybackStateCompat.STATE_PAUSED, PlaybackStateCompat.STATE_STOPPED, PlaybackStateCompat.STATE_NONE -> {
                availableActions = availableActions or PlaybackStateCompat.ACTION_PLAY
            }
        }

        playbackStateBuilder.setActions(availableActions)
        playbackStateBuilder.setState(state, currentPosition, playbackSpeed, SystemClock.elapsedRealtime())

        if (state == PlaybackStateCompat.STATE_ERROR && errorMsg != null) {
            playbackStateBuilder.setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, errorMsg)
        }
        mediaSession?.setPlaybackState(playbackStateBuilder.build())
        print2LogView("PlaybackState updated. State: $state, Position: $currentPosition")


//        val shouldBeActive = state == PlaybackStateCompat.STATE_PLAYING ||
//                state == PlaybackStateCompat.STATE_PAUSED ||
//                state == PlaybackStateCompat.STATE_BUFFERING ||
//                state == PlaybackStateCompat.STATE_CONNECTING
//
//        if (currentMediaSessionActiveState != shouldBeActive) {
//            mediaSession?.isActive = shouldBeActive
//            currentMediaSessionActiveState = shouldBeActive
//            print2LogView("MediaSession active state changed to: $shouldBeActive (due to playback state: $state)")
//        }
        mediaSession?.isActive = true

    }

    /**
     * 更新播放媒体信息
     * @param duration 媒体时长
     * @param title 媒体标题
     * @param artist 媒体艺术家
     * @param albumArt 媒体专辑封面
     */
    fun updateMediaMetadata(duration: Long, title: String?, artist: String?, albumArt: Bitmap?) {
        if (mediaSession == null) {
            print2LogView("updateMediaMetadata called but mediaSession is null")
            return
        }

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title ?: "工作咩闹钟")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist ?: "咩咩")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration) // 使用从 Service 获取的时长

        if (albumArt != null) {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
        }

        mediaSession?.setMetadata(metadataBuilder.build())
        lastTitle = title ?: "工作咩闹钟"
        print2LogView("MediaMetadata updated. Title: $title, Artist: $artist, Duration: $duration")
    }

    fun getSessionToken(): MediaSessionCompat.Token? {
        return mediaSession?.sessionToken
    }

    fun release() {
        print2LogView("Releasing MediaSession...")
        mediaSession?.apply {
            isActive = false
            release()
        }
        mediaSession = null
        print2LogView("MediaSession released.")
    }
}
