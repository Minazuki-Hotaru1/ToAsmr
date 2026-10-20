package com.asmrone.app.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.asmrone.app.MainActivity
import com.asmrone.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 把 [AudioController] 接到**系统媒体播放框架**上。
 *
 * ## 它解决什么
 *
 * 之前播放只在 App 界面里存在：退到后台没有通知，锁屏没有卡片，耳机线控和蓝牙
 * 上一曲/下一曲按了没反应，来电也不会让位。这里补上三件事：
 *
 *  1. **MediaSession** —— 系统的"媒体会话"。锁屏、系统媒体控制中心（下拉后的
 *     媒体卡片）、Android 13+ 的快捷设置、蓝牙与耳机线控，全部通过它和本 App 通信。
 *  2. **前台服务** —— 带 `mediaPlayback` 类型的前台服务是"后台持续出声"的
 *     唯一正规做法；没有它，App 一进后台就会被系统掐掉或被低内存回收。
 *  3. **媒体通知** —— `MediaStyle` + `setMediaSession(token)`，通知上的按钮、
 *     进度、封面都来自会话状态，而不是另写一套。
 *
 * ## 为什么是平台 API 而不是 Media3 的 MediaSessionService
 *
 * 视频播放已经用了 Media3（见 `VideoPlayerDialog`），但它的 `media3-session`
 * 要求把播放内核换成 ExoPlayer 才能拿到完整能力。音频内核（MediaPlayer）是本工程
 * 最稳定的一块，为了通知去重写它，回归面远大于收益。
 * 而 `MediaSession` / `MediaStyle` / `AudioFocusRequest` 都是 **API 26 以下就有的
 * 平台能力**（本工程 minSdk 28），零新增依赖 —— 与工程"能不加依赖就不加"的约定一致。
 *
 * ## 生命周期
 *
 * 只在"有播放内容"时存在：[AudioController] 在开始播放时 [start]，停止时 [stop]。
 * 暂停**不**销毁它（通知要留着，用户可能随时从锁屏续播），只退出前台态。
 */
class PlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MediaSession
    private lateinit var notificationManager: NotificationManager

    private var observing: Job? = null
    private var coverJob: Job? = null

    /** 当前是否处于前台服务态（暂停时退出前台，但通知保留） */
    private var foreground = false

    private var coverUrl: String? = null
    private var coverBitmap: Bitmap? = null

    /** 上一次真正提交给系统的元数据 / 通知特征，用来避免每 500ms 一次的无意义刷新 */
    private var lastMetadataKey: String? = null
    private var lastNotificationKey: String? = null

    /**
     * 耳机拔出 / 蓝牙断开。
     *
     * 没有它的话，插着耳机睡着时把耳机拔掉，声音会直接外放 —— 对这类内容来说
     * 是相当糟糕的意外。系统的这条广播就是为这个场景设计的。
     */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            if (AudioController.state.value.isPlaying) AudioController.pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannel()
        session = MediaSession(this, SESSION_TAG).apply {
            // 不设 setFlags：FLAG_HANDLES_MEDIA_BUTTONS / FLAG_HANDLES_TRANSPORT_CONTROLS
            // 从 API 26 起被系统忽略（会话一律接管媒体键与传输控制），本工程 minSdk 28，
            // 设了只会得到一条 deprecation 警告。
            // 锁屏 / 系统媒体卡片点一下就能回到 App（并且直接展开播放器）
            setSessionActivity(openPlayerIntent())
            setCallback(sessionCallback)
            isActive = true
        }
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // 必须在 onCreate 里同步调用：startForegroundService 之后 5s 内不进入前台会抛异常
        val state = AudioController.state.value
        startForeground(NOTIFICATION_ID, buildNotification(state))
        foreground = true

        observing = scope.launch {
            AudioController.state.collect { current ->
                if (!current.isActive) {
                    // 播放被彻底清空（用户关闭 / 收藏夹切歌失败等）：服务没有存在意义了
                    stopSelf()
                    return@collect
                }
                updateMetadata(current)
                updatePlaybackState(current)
                syncForeground(current)
                ensureCover(current.coverUrl)
                postNotification(current)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> AudioController.togglePlayPause()
            ACTION_NEXT -> AudioController.next()
            ACTION_PREV -> AudioController.prev()
            ACTION_STOP -> AudioController.stop()
            else -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 从最近任务里划掉 App 时**继续播放** —— 这是音乐类 App 的默认预期 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!AudioController.state.value.isPlaying) stopSelf()
    }

    override fun onDestroy() {
        observing?.cancel()
        coverJob?.cancel()
        scope.cancel()
        runCatching { unregisterReceiver(noisyReceiver) }
        session.isActive = false
        session.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        super.onDestroy()
    }

    // ─────────────────────────── 系统 → 播放器 ───────────────────────────

    /**
     * 系统媒体按键、锁屏、蓝牙、其他 App 的媒体控制器的入口。
     *
     * 注意这里**没有**重写 `onMediaButtonEvent`：`MediaSession.Callback` 的默认实现
     * 已经把 KEYCODE_MEDIA_PLAY_PAUSE / NEXT / PREVIOUS 翻译成下面这些回调了。
     */
    private val sessionCallback = object : MediaSession.Callback() {
        override fun onPlay() = AudioController.resume()

        override fun onPause() = AudioController.pause()

        override fun onSkipToNext() = AudioController.next()

        override fun onSkipToPrevious() = AudioController.prev()

        override fun onSeekTo(pos: Long) = AudioController.seekTo(pos.toInt())

        override fun onStop() = AudioController.stop()
    }

    // ─────────────────────────── 播放器 → 系统 ───────────────────────────

    private fun updatePlaybackState(state: PlayerState) {
        val playbackState = PlaybackState.Builder()
            .setActions(PlaybackSessionModel.actionsOf(state))
            .setState(
                PlaybackSessionModel.stateOf(state),
                state.positionMs.toLong(),
                PlaybackSessionModel.speedOf(state)
            )
            .apply { state.error?.let { setErrorMessage(it) } }
            .build()
        session.setPlaybackState(playbackState)
    }

    private fun updateMetadata(state: PlayerState) {
        val key = "${state.trackTitle}|${state.workTitle}|${state.durationMs}|${state.coverUrl}"
        if (key == lastMetadataKey) return
        lastMetadataKey = key
        val builder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, state.trackTitle.ifBlank { state.workTitle })
            .putString(MediaMetadata.METADATA_KEY_ARTIST, state.workTitle)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "ToAsmr")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs.toLong())
        coverBitmap?.let { builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(builder.build())
    }

    /**
     * 前台态跟随播放态。
     *
     * 暂停时用 `STOP_FOREGROUND_DETACH` 而不是 `REMOVE`：退出前台，但**保留通知**。
     * 用 REMOVE 会把通知一起收走，用户就再也点不到"继续播放"了。
     */
    private fun syncForeground(state: PlayerState) {
        if (state.isPlaying && !foreground) {
            startForeground(NOTIFICATION_ID, buildNotification(state))
            foreground = true
            lastNotificationKey = null
        } else if (!state.isPlaying && foreground) {
            stopForeground(STOP_FOREGROUND_DETACH)
            foreground = false
        }
    }

    private fun postNotification(state: PlayerState) {
        val key = listOf(
            state.trackTitle,
            state.workTitle,
            state.isPlaying,
            state.hasNext,
            state.hasPrev,
            coverBitmap != null
        ).joinToString("|")
        if (key == lastNotificationKey) return
        lastNotificationKey = key
        notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: PlayerState): Notification {
        val title = state.trackTitle.ifBlank { state.workTitle }.ifBlank { "ToAsmr" }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_headphones_fluent)
            .setContentTitle(title)
            .setContentText(state.workTitle)
            .setContentIntent(openPlayerIntent())
            .setDeleteIntent(serviceIntent(ACTION_STOP, REQUEST_STOP))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(state.isPlaying)
            .setLargeIcon(coverBitmap)
            .addAction(
                action(
                    R.drawable.ic_skip_previous_fluent,
                    "上一首",
                    ACTION_PREV,
                    REQUEST_PREV,
                    enabled = state.hasPrev
                )
            )
            .addAction(
                action(
                    if (state.isPlaying) R.drawable.ic_pause_fluent else R.drawable.ic_play_fluent,
                    if (state.isPlaying) "暂停" else "播放",
                    ACTION_TOGGLE,
                    REQUEST_TOGGLE
                )
            )
            .addAction(
                action(
                    R.drawable.ic_skip_next_fluent,
                    "下一首",
                    ACTION_NEXT,
                    REQUEST_NEXT,
                    enabled = state.hasNext
                )
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    // 收起状态下只放三个按钮（0=上一首 1=播放/暂停 2=下一首）
                    .setShowActionsInCompactView(0, 1, 2)
            )
        return builder.build()
    }

    private fun action(
        iconRes: Int,
        title: String,
        action: String,
        requestCode: Int,
        enabled: Boolean = true
    ): Notification.Action {
        val pending = serviceIntent(action, requestCode)
        // 做不到的操作给一个"空"的 PendingIntent：系统会把它画成不可用
        val target = if (enabled) pending else null
        return Notification.Action.Builder(
            Icon.createWithResource(this, iconRes),
            title,
            target
        ).build()
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun openPlayerIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
        }
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // ─────────────────────────── 封面 ───────────────────────────

    /**
     * 封面只在真的换了作品时才重新取。
     *
     * 走 Coil 的 imageLoader 而不是自己开连接：播放器界面本来就通过 Coil 加载同一张图，
     * 内存缓存能直接命中，等于"顺手拿一份"，不多一次网络。
     */
    private fun ensureCover(url: String?) {
        if (url == coverUrl) return
        coverUrl = url
        coverBitmap = null
        lastMetadataKey = null
        lastNotificationKey = null
        coverJob?.cancel()
        if (url.isNullOrBlank()) return
        coverJob = scope.launch {
            val bitmap = loadCover(url)
            if (bitmap == null || coverUrl != url) return@launch
            coverBitmap = bitmap
            val current = AudioController.state.value
            updateMetadata(current)
            postNotification(current)
        }
    }

    private suspend fun loadCover(url: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val request = ImageRequest.Builder(this@PlaybackService)
                .data(url)
                // 通知上的大图标最大也就百来像素，没必要解整张封面
                .size(COVER_PX)
                .allowHardware(false)
                .build()
            val result = imageLoader.execute(request)
            (result as? SuccessResult)?.drawable?.toBitmap()
        }.getOrNull()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "播放控制",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "播放器通知：当前曲目、上一首/播放/下一首"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ToAsmrPlayback"
        private const val SESSION_TAG = "ToAsmr"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1001
        private const val COVER_PX = 256

        private const val ACTION_TOGGLE = "com.asmrone.app.action.TOGGLE"
        private const val ACTION_NEXT = "com.asmrone.app.action.NEXT"
        private const val ACTION_PREV = "com.asmrone.app.action.PREV"
        private const val ACTION_STOP = "com.asmrone.app.action.STOP"

        private const val REQUEST_OPEN = 10
        private const val REQUEST_TOGGLE = 11
        private const val REQUEST_NEXT = 12
        private const val REQUEST_PREV = 13
        private const val REQUEST_STOP = 14

        /** 开始播放时拉起服务（已在运行就是一次普通的 `onStartCommand`） */
        fun start(context: Context) {
            if (!AudioController.state.value.isActive) return
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, PlaybackService::class.java)
                )
            }.onFailure {
                // 后台被限制等情况下会拒绝启动前台服务：播放本身不受影响，只是没有通知
                Log.w(TAG, "无法启动播放服务：${it.message}")
            }
        }

        /** 播放被彻底清空时收起服务与通知 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
        }
    }
}
