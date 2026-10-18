package com.asmrone.app.player

import com.asmrone.app.data.diag.HttpLog
import com.asmrone.app.data.prefs.AppPrefs
import com.asmrone.app.data.prefs.PlaybackSessionStore
import com.asmrone.app.data.audio.LocalAudioProxy
import com.asmrone.app.data.audio.TrackCacheLedger
import com.asmrone.app.data.audio.AudioCacheStore
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.SystemClock
import com.asmrone.app.data.model.Track
import com.asmrone.app.data.model.Work
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 播放器对外暴露的状态（供迷你播放器与全屏播放器共同消费） */
data class PlayerState(
    val workId: Int = 0,
    /**
     * 作品的 RJ 号（DLsite 作品号，如 `RJ243448`）。
     *
     * 与 [workId] 一起带在状态里，是为了让**文件树与歌词**能用它做本地缓存的键：
     * 冷启动恢复会话后内存里没有作品元数据，只有这个快照，拿不到 RJ 号就只能
     * 退回按 workId 找缓存（可能落空）。详见 `TrackTreeStore`。
     */
    val workSourceId: String? = null,
    val workTitle: String = "",
    val coverUrl: String? = null,
    val tracks: List<Track> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    val isPrepared: Boolean = false,
    val isBuffering: Boolean = false,
    val durationMs: Int = 0,
    val positionMs: Int = 0,
    val error: String? = null,
    /** 睡眠定时器剩余毫秒；0 表示未启用 */
    val sleepTimerRemainingMs: Long = 0L
) {
    val currentTrack: Track? get() = tracks.getOrNull(index)
    val trackTitle: String get() = currentTrack?.title.orEmpty()
    val hasNext: Boolean get() = index in 0 until tracks.lastIndex
    val hasPrev: Boolean get() = index > 0
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val isActive: Boolean get() = tracks.isNotEmpty()
}

/**
 * 播放内核（`android.media.MediaPlayer` 封装）——**音频专用**。
 *
 * ## 关于"零新增依赖"这条约定
 *
 * 原先的说明是"本工程刻意保持零新增依赖（离线可构建）“。这条约定**已经**
 * 因为视频播放被打破过一次：详情页文件树里的 `.mp4` 实测是**真视频**
 * （单个可达 1.8GB、码率 4.5Mbps），必须流式播放，所以引入了 Media3
 * （见 `ui/component/viewer/VideoPlayerDialog`）。
 *
 * 但**音频内核刻意没有跟着改**，理由：
 *  - MediaPlayer 已能满足音声这类「顺序播放 + 后台播放 + 跳转」的全部诉求
 *  - 音频是主场景、播放极其频繁，换内核的回归风险远大于收益
 *  - 两者互不干扰：本类只服务音频，视频走 Media3，各自独立
 *
 * 所以现在是"一处例外"，而不是"约定作废"。后续若要继续加依赖，
 * 应当按同样的标准衡量：**这个能力是否真的无法用平台自带的部件做到**。
 *
 * 说明：MediaPlayer 的流地址直接交给播放器内核，**不走我们在 API 层配置的 HTTP 代理**；
 * 若 asmr.one 的媒体域名不可达，需要系统级代理（如 Clash 的 TUN/全局模式）。
 */
object AudioController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private var sleepJob: Job? = null
    private var gain: Float = 1.0f
    /** 上次写播放快照的时间（uptimeMillis），避免每 500ms tick 都写盘 */
    private var lastPersistAt = 0L

    /**
     * 音频缓存：存储层 + 本地代理（"边播边缓存"的实现，见 LocalAudioProxy）。
     *
     * 对外可见是为了让「设置 → 数据设置」能显示占用、改上限、手动清理 ——
     * 缓存状态的唯一来源是这里，不该在设置页另开一份。
     */
    val cacheStore = AudioCacheStore()
    private val proxy = LocalAudioProxy(cacheStore)

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** 初始化音频属性（只做一次即可，重复调用无副作用） */
    fun init(context: Context) {
        // 音频缓存的存储层与本地代理：一起初始化。
        // 用 applicationContext 的 cacheDir —— 可以再生的数据，系统空间紧张时
        // 允许它被清理（清掉只是下次重新缓存）。
        cacheStore.init(context.applicationContext.cacheDir)
        proxy.start()
        HttpLog.request(
            "音频缓存代理已启动：127.0.0.1:${proxy.port}  已缓存 ${cacheStore.snapshot(System.currentTimeMillis()).totalCount} 首"
        )

        if (player != null) return
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
        }
    }

    /**
     * 冷启动时恢复上次退出前的播放会话。
     *
     * 只恢复展示与位置，不自动播放；用户点播放时才按 [PlayerState.positionMs] 继续。
     */
    fun restoreSession() {
        if (_state.value.isActive) return
        val saved = PlaybackSessionStore.load() ?: return
        _state.value = PlayerState(
            workId = saved.workId,
            workSourceId = saved.workSourceId,
            workTitle = saved.workTitle,
            coverUrl = saved.coverUrl,
            tracks = saved.tracks,
            index = saved.index,
            isPlaying = false,
            isPrepared = false,
            isBuffering = false,
            durationMs = saved.durationMs,
            positionMs = saved.positionMs
        )
    }

    /** 主动写一次快照（Activity onStop / 用户暂停、拖动进度时调用） */
    fun persistSession(synchronous: Boolean = false) {
        val s = _state.value
        if (!s.isActive || s.workId <= 0) return
        PlaybackSessionStore.save(s, synchronous = synchronous)
        lastPersistAt = SystemClock.uptimeMillis()
    }

    /**
     * 把上游地址换成"经过本地代理"的地址，从而获得边播边缓存。
     *
     * 失败时**回退到直连**：代理只是优化，不该成为播放的必要条件 ——
     * 端口占用、回环被限制等情况下必须还能正常听。
     */
    private fun playbackUrlFor(upstreamUrl: String): String {
        if (!proxy.isRunning) return upstreamUrl
        return runCatching { proxy.localUrlFor(upstreamUrl) }
            .getOrElse {
                HttpLog.error("生成本地代理地址失败，回退直连：${it.message}")
                upstreamUrl
            }
    }

    /**
     * 播放整部作品。
     *
     * [tracks] 由调用方给出 —— 详情页会先把文件树按「音质分支」拆开
     * （mp3 / wav / 全部…），只把选中的那条分支交给播放器，
     * 避免同一段音声的 mp3 与 wav 版本被混在一起连播。
     */
    fun playWork(work: Work, coverUrl: String?, tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) {
            _state.value = _state.value.copy(error = "这部作品没有可播放的音轨")
            return
        }
        _state.value = PlayerState(
            workId = work.id,
            workSourceId = work.sourceId,
            workTitle = work.title,
            coverUrl = coverUrl,
            tracks = tracks,
            index = startIndex.coerceIn(0, tracks.lastIndex),
            isBuffering = true
        )
        persistSession()
        startCurrent()
    }

    /** 直接播放一首曲目（曲目列表面板点击时用） */
    fun playTrackAt(index: Int) {
        val s = _state.value
        if (index !in s.tracks.indices) return
        _state.value = s.copy(index = index, positionMs = 0, isBuffering = true, error = null)
        persistSession()
        startCurrent()
    }

    fun togglePlayPause() {
        val p = player ?: return
        val s = _state.value
        if (!s.isPrepared) {
            if (s.isBuffering) return
            if (s.tracks.isNotEmpty()) {
                _state.value = s.copy(isBuffering = true, error = null)
                startCurrent()
            }
            return
        }
        if (p.isPlaying) {
            p.pause()
            stopTicker()
            _state.value = s.copy(isPlaying = false)
        } else {
            p.start()
            applyGain()
            startTicker()
            _state.value = s.copy(isPlaying = true)
        }
        persistSession()
    }

    fun next() {
        val s = _state.value
        if (s.hasNext) playTrackAt(s.index + 1)
    }

    fun prev() {
        val s = _state.value
        if (s.hasPrev) playTrackAt(s.index - 1) else seekTo(0)
    }

    fun seekTo(positionMs: Int) {
        val s = _state.value
        val clamped = positionMs.coerceIn(0, s.durationMs.coerceAtLeast(0))
        if (!s.isPrepared) {
            // 冷启动恢复后还没 prepare：允许先拖动进度，点播放时从这个位置开始。
            _state.value = s.copy(positionMs = clamped)
            persistSession()
            return
        }
        val p = player ?: return
        p.seekTo(clamped)
        _state.value = s.copy(positionMs = clamped)
        persistSession()
    }

    fun seekToFraction(fraction: Float) {
        val s = _state.value
        if (s.durationMs <= 0) return
        seekTo((s.durationMs * fraction.coerceIn(0f, 1f)).toInt())
    }

    /** 快进 / 快退（秒） */
    fun skipBy(seconds: Int) {
        val s = _state.value
        seekTo(s.positionMs + seconds * 1000)
    }

    fun setGain(value: Float) {
        gain = value.coerceIn(0f, 2f)
        applyGain()
    }

    /** 睡眠定时器：到点自动暂停 */
    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            _state.value = _state.value.copy(sleepTimerRemainingMs = 0L)
            return
        }
        sleepJob = scope.launch {
            var remaining = minutes * 60_000L
            while (remaining > 0) {
                _state.value = _state.value.copy(sleepTimerRemainingMs = remaining)
                delay(1_000)
                remaining -= 1_000
            }
            _state.value = _state.value.copy(sleepTimerRemainingMs = 0L)
            player?.let { if (it.isPlaying) { it.pause(); stopTicker() } }
            _state.value = _state.value.copy(isPlaying = false)
            persistSession()
        }
    }

    /** 停止并清空（退出播放页 / 切换作品时调用） */
    fun stop() {
        ticker?.cancel()
        sleepJob?.cancel()
        player?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.reset() }
        }
        PlaybackSessionStore.clear()
        _state.value = PlayerState()
    }

    fun release() {
        stop()
        player?.release()
        player = null
    }

    // ─────────────────────────── 内部实现 ───────────────────────────

    private fun startCurrent() {
        val p = player ?: return
        val s = _state.value
        val track = s.currentTrack ?: return
        val url = track.mediaStreamUrl
        if (url.isNullOrBlank()) {
            _state.value = s.copy(
                isBuffering = false,
                error = "该音轨没有可用的播放地址（可能需要登录或更换主机）"
            )
            return
        }
        runCatching {
            p.reset()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // 记下实际交给播放内核的地址：验证"边播边缓存"是否生效，
            // 最直接的证据就是它究竟是 127.0.0.1 还是上游域名
            val playbackUrl = playbackUrlFor(url)
            // 开始新的一轨 → 新建缓存记账。
            //
            // ⚠️ 必须重置：复用上一轨的记账会让这一轨一上来就被判定
            // "已达单曲上限"而**完全不再缓存**（表现为第二首起缓存永远是 0）。
            // 这里同时重新读一次用户配置的单曲上限，所以改完设置下一首就生效。
            TrackCacheLedger.startFor(
                AudioCacheStore.idFor(url),
                AppPrefs.perTrackCacheLimitMb.value.toLong() * 1024L * 1024L
            )
            HttpLog.request(
                "开始播放：${track.title}  地址=" +
                    if (playbackUrl.contains("127.0.0.1")) "本地代理(${proxy.port})" else "直连上游"
            )
            p.setDataSource(playbackUrl)
            p.setOnPreparedListener { mp ->
                val duration = mp.duration.coerceAtLeast(0)
                // 用「当前 state 的位置」而不是调用 startCurrent 时的旧值：
                // 冷启动恢复后用户可能在 prepare 完成前先拖了进度条。
                val start = if (duration > 0) {
                    _state.value.positionMs.coerceAtMost(duration)
                } else {
                    0
                }
                if (start > 0) runCatching { mp.seekTo(start) }
                _state.value = _state.value.copy(
                    isPrepared = true,
                    isBuffering = false,
                    isPlaying = true,
                    durationMs = duration,
                    positionMs = start,
                    error = null
                )
                applyGain()
                mp.start()
                startTicker()
                persistSession()
            }
            p.setOnCompletionListener {
                val cur = _state.value
                if (cur.hasNext) {
                    playTrackAt(cur.index + 1)
                } else {
                    stopTicker()
                    _state.value = cur.copy(isPlaying = false, positionMs = cur.durationMs)
                    persistSession()
                }
            }
            p.setOnErrorListener { _, what, extra ->
                stopTicker()
                _state.value = _state.value.copy(
                    isPlaying = false,
                    isBuffering = false,
                    isPrepared = false,
                    error = "播放失败（$what/$extra）：请检查网络或代理设置"
                )
                persistSession()
                true
            }
            p.prepareAsync()
        }.onFailure { e ->
            _state.value = s.copy(
                isBuffering = false,
                error = "无法打开音频流：${e.message ?: "未知错误"}"
            )
            persistSession()
        }
    }

    private fun applyGain() {
        player?.let { runCatching { it.setVolume(gain, gain) } }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                val p = player ?: break
                val s = _state.value
                if (s.isPrepared) {
                    val pos = runCatching { p.currentPosition }.getOrDefault(s.positionMs)
                    val dur = runCatching { p.duration }.getOrDefault(s.durationMs)
                    _state.value = s.copy(
                        positionMs = pos,
                        durationMs = if (dur > 0) dur else s.durationMs
                    )
                    // 播放中每 2 秒落一次快照；被杀进程也能最多丢 2 秒。
                    if (SystemClock.uptimeMillis() - lastPersistAt >= 2_000L) {
                        persistSession()
                    }
                }
                delay(500)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }
}
