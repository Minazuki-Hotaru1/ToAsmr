package com.asmrone.app.data.prefs

import org.json.JSONObject

import com.asmrone.app.data.audio.AudioCachePolicy

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局偏好设置（SharedPreferences + StateFlow）。
 *
 * 与 comicApp 的 ThemePrefs/HomePrefs 同思路：单例 + `MutableStateFlow`，
 * Compose 里 `collectAsState()` 直接消费，写入立即持久化。
 *
 * 服务器相关项（[host]）是本 App 能用的前提：
 * asmr.one 的 api 域名在部分地区不可直连；App 内**不再提供代理配置**，
 * 需要在系统层使用 VPN/代理，本页只负责切换 API 主机。
 *
 * ⚠️ 登录令牌**不在这里** —— 它由 [com.asmrone.app.data.auth.AuthManager] 统一持有，
 * 因为令牌与"当前登录的是哪个账号"是绑定的，拆开放会出现两边不一致的状态。
 */
object AppPrefs {

    private const val FILE = "asmrone_prefs"

    private lateinit var sp: SharedPreferences

    /** asmr.one 官方常见主机（国内可直连顺序靠前） */
    val HOST_PRESETS = listOf(
        "https://api.asmr-200.com",
        "https://api.asmr.one",
        "https://api.asmr-100.com",
        "https://api.asmr-300.com"
    )

    private val _host = MutableStateFlow(HOST_PRESETS.first())
    val host: StateFlow<String> = _host.asStateFlow()

    /** 音质偏好：mp3 / m4a / wav / flac / 空字符串（全部） */
    private val _audioFormat = MutableStateFlow("mp3")
    val audioFormat: StateFlow<String> = _audioFormat.asStateFlow()

    /** 首页是否加载榜单（关掉可以省流量 / 避免无网络时一直转圈） */
    /** 首页是否自动加载（关掉后首页显示提示，不发起请求） */
    private val _homeEnabled = MutableStateFlow(true)
    val homeEnabled: StateFlow<Boolean> = _homeEnabled.asStateFlow()

    /** 推荐板块用的匿名设备标识（首次启动生成并持久化） */
    private val _recommenderUuid = MutableStateFlow("")
    val recommenderUuid: StateFlow<String> = _recommenderUuid.asStateFlow()

    /**
     * 搜索字典是否已经做过首次初始化。
     *
     * ## 它只解决一件事：不要每次启动都去拉一遍
     *
     * 三本字典合计约 830KB（标签 65KB / 声优 447KB / 社团 302KB），
     * 每次冷启动都拉一遍既费流量又让启动受网络影响。所以第一次进入 App 拉一次，
     * 之后靠这个标记跳过。
     *
     * ⚠️ **它不代表"数据一定在"**。真正的判断是数据本身
     * （[com.asmrone.app.data.repo.AsmrRepository.dictionariesReady]），
     * 因为换了 API 主机、或用户在设置里清过某一本之后，标记仍是 true 但数据没了。
     * 两者的分工：
     *  - 标记 → 决定"要不要尝试拉"
     *  - 数据 → 决定"拉完算不算完整"
     *
     * ⚠️ **失败也会置 true**。否则没网的用户每次冷启动都会被拦一次、
     * 每次都要等一次超时 —— 那比"少了字典"更烦人。补救入口是设置页的
     * 「更新搜索字典」，那里随时可以手动重试。
     */
    private val _dictionariesInitialized = MutableStateFlow(false)
    val dictionariesInitialized: StateFlow<Boolean> = _dictionariesInitialized.asStateFlow()

    /** 底部导航栏是否随滚动收起 */
    private val _collapseBottomBar = MutableStateFlow(true)
    val collapseBottomBar: StateFlow<Boolean> = _collapseBottomBar.asStateFlow()

    /** 仅 Wi-Fi 下播放 / 下载 */
    private val _wifiOnly = MutableStateFlow(false)
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    /** 低音质省流量（接口支持的场景下会带参数） */
    private val _lowBitrate = MutableStateFlow(false)
    val lowBitrate: StateFlow<Boolean> = _lowBitrate.asStateFlow()

    /** 播放器音量增益（0.0 ~ 2.0，1.0 为原始） */
    private val _gain = MutableStateFlow(1.0f)
    val gain: StateFlow<Float> = _gain.asStateFlow()

    /**
     * **缓存总上限**（MB）—— 所有已缓存音频占用的磁盘上限。
     *
     * 默认 1GB：音声单轨实测可到 92MB，整部作品几个 GB，
     * 上限太小会一直淘汰（缓存等于白做），太大又会占满用户手机。
     * 1GB 大约能装 10–20 轨，是一个能明显感觉到"第二次听不用流量"的量级。
     */
    private val _audioCacheLimitMb = MutableStateFlow(AudioCachePolicy.DEFAULT_LIMIT_MB)
    val audioCacheLimitMb: StateFlow<Int> = _audioCacheLimitMb.asStateFlow()

    /**
     * **单曲缓存上限**（MB）—— 一次播放一首曲子期间，最多往后缓存多少。
     *
     * 与 [audioCacheLimitMb]（缓存总上限）是**两个不同的量**，设置页里分开显示：
     *  - 这个：[单曲]一次能缓存多少 → 决定大体积音轨（WAV/FLAC，实测到 92MB）
     *    能否在一次播放里缓存完整
     *  - 那个：所有缓存占用的磁盘总量
     *
     * 之所以让用户调：格式与流量套餐因人而异。上限小于单曲体积时，
     * 那首曲子**永远缓存不完整**（次次重新走网络）；调大则意味着在一次连续
     * 播放里多花流量。没有普适最优值。
     */
    private val _perTrackCacheLimitMb = MutableStateFlow(AudioCachePolicy.DEFAULT_PER_TRACK_MB)
    val perTrackCacheLimitMb: StateFlow<Int> = _perTrackCacheLimitMb.asStateFlow()

    /** 睡眠定时器默认分钟数（0 = 不启用） */
    private val _sleepTimerMinutes = MutableStateFlow(0)
    val sleepTimerMinutes: StateFlow<Int> = _sleepTimerMinutes.asStateFlow()

    /** 主题模式：0 = 跟随系统，1 = 强制亮色，2 = 强制暗色 */
    private val _themeMode = MutableStateFlow(0)
    val themeMode: StateFlow<Int> = _themeMode.asStateFlow()

    /**
     * 上次选择的收藏夹 id。
     *
     * 点收藏按钮时默认选它；第一次（空）则回退到系统的「我标记的」收藏夹。
     * 对应需求："默认选择上一次选择的收藏夹，第一次则选择我标记的"。
     */
    private val _lastSelectedPlaylistId = MutableStateFlow("")
    val lastSelectedPlaylistId: StateFlow<String> = _lastSelectedPlaylistId.asStateFlow()

    fun setLastSelectedPlaylistId(value: String) {
        _lastSelectedPlaylistId.value = value
        sp.edit().putString(KEY_LAST_PLAYLIST, value).apply()
    }

    // ─────────────────── 每日自动对齐的"已对齐"标记 ───────────────────
    //
    // ## 为什么要持久化，以及为什么必须按数据类别分开
    //
    // 需求是"每天第一次进 App 才自动与云端匹配，当天之后不再自动匹配"。
    // 这个"当天已经匹配过"的事实必须**跨进程存活**（否则杀进程重开就又匹配一次，
    // 达不到"减少服务器压力"的目的），所以只能落盘。
    //
    // ⚠️ 持久化的是 **每个数据类别各自的日期**，不是一个全局日期。
    // 早先的版本只有一个全局 `last_auto_sync_day`，后果是：
    // 「标记」与「列表」是两份不同的数据、两个不同的接口，
    // 但**共用一个额度** —— 谁先进谁用掉，另一个当天永远拿不到云端更新。
    //
    // 存储形状是 `{"collection|host|user":"2026-10-17", …}`。
    // 只保留"今天"的条目（写入时顺带剪掉过期的），所以不会无限增长。

    /** 某份数据上次自动对齐的日期（`yyyy-MM-dd`）；从未对齐过返回空串 */
    fun autoSyncDay(domain: String): String =
        readAutoSyncDays()[domain].orEmpty()

    /**
     * 记下"这份数据今天已经自动对齐过了"。
     *
     * 顺带剪掉不是今天的条目：那些数据今天已经没机会了，
     * 明天它们的日期自然会过期，留着只会让这个 map 越积越多。
     */
    fun setAutoSyncDay(domain: String, day: String) {
        val current = readAutoSyncDays()
        val pruned = current.filterValues { it == day }.toMutableMap()
        pruned[domain] = day
        val json = JSONObject().apply {
            pruned.forEach { (k, v) -> put(k, v) }
        }
        sp.edit().putString(KEY_AUTO_SYNC_DAYS, json.toString()).apply()
    }

    private fun readAutoSyncDays(): Map<String, String> {
        val raw = sp.getString(KEY_AUTO_SYNC_DAYS, null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            buildMap {
                o.keys().forEach { k ->
                    val v = o.optString(k)
                    if (v.isNotBlank()) put(k, v)
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun setThemeMode(value: Int) {
        _themeMode.value = value
        sp.edit().putInt(KEY_THEME_MODE, value).apply()
    }

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _host.value = sp.getString(KEY_HOST, HOST_PRESETS.first()) ?: HOST_PRESETS.first()
        _audioFormat.value = sp.getString(KEY_AUDIO_FORMAT, "mp3") ?: "mp3"
        _homeEnabled.value = sp.getBoolean(KEY_HOME_ENABLED, true)
        _recommenderUuid.value = sp.getString(KEY_RECOMMENDER_UUID, null)
            ?: java.util.UUID.randomUUID().toString().also {
                // 本机生成一次并持久化：推荐板块用它作为「这台设备」的匿名标识
                sp.edit().putString(KEY_RECOMMENDER_UUID, it).apply()
            }
        // 默认 false：老版本升级上来的用户没这个键，应当走一次首次字典初始化
        _dictionariesInitialized.value = sp.getBoolean(KEY_DICTIONARIES_INITIALIZED, false)
        _collapseBottomBar.value = sp.getBoolean(KEY_COLLAPSE_BOTTOM_BAR, true)
        _wifiOnly.value = sp.getBoolean(KEY_WIFI_ONLY, false)
        _lowBitrate.value = sp.getBoolean(KEY_LOW_BITRATE, false)
        _gain.value = sp.getFloat(KEY_GAIN, 1.0f)
        _audioCacheLimitMb.value = sp.getInt(KEY_AUDIO_CACHE_LIMIT_MB, AudioCachePolicy.DEFAULT_LIMIT_MB)
            .coerceIn(AudioCachePolicy.MIN_LIMIT_MB, AudioCachePolicy.MAX_LIMIT_MB)
        _perTrackCacheLimitMb.value = sp.getInt(
            KEY_PER_TRACK_CACHE_LIMIT_MB,
            AudioCachePolicy.DEFAULT_PER_TRACK_MB
        ).coerceIn(AudioCachePolicy.MIN_PER_TRACK_MB, AudioCachePolicy.MAX_PER_TRACK_MB)
        _sleepTimerMinutes.value = sp.getInt(KEY_SLEEP_TIMER, 0)
        _themeMode.value = sp.getInt(KEY_THEME_MODE, 0)
        _lastSelectedPlaylistId.value = sp.getString(KEY_LAST_PLAYLIST, "") ?: ""
    }

    fun setHost(value: String) {
        val normalized = value.trim().trimEnd('/')
        _host.value = normalized
        sp.edit().putString(KEY_HOST, normalized).apply()
    }

    fun setAudioFormat(value: String) {
        _audioFormat.value = value
        sp.edit().putString(KEY_AUDIO_FORMAT, value).apply()
    }

    fun setHomeEnabled(value: Boolean) {
        _homeEnabled.value = value
        sp.edit().putBoolean(KEY_HOME_ENABLED, value).apply()
    }

    /**
     * 记下"搜索字典已做过首次初始化"。
     *
     * ⚠️ 无论成功还是失败都要调 —— 理由见
     * [AppPrefs.dictionariesInitialized] 的说明（避免离线用户每次启动都被拦）。
     */
    fun setDictionariesInitialized(value: Boolean) {
        _dictionariesInitialized.value = value
        sp.edit().putBoolean(KEY_DICTIONARIES_INITIALIZED, value).apply()
    }

    fun setCollapseBottomBar(value: Boolean) {
        _collapseBottomBar.value = value
        sp.edit().putBoolean(KEY_COLLAPSE_BOTTOM_BAR, value).apply()
    }

    fun setWifiOnly(value: Boolean) {
        _wifiOnly.value = value
        sp.edit().putBoolean(KEY_WIFI_ONLY, value).apply()
    }

    fun setLowBitrate(value: Boolean) {
        _lowBitrate.value = value
        sp.edit().putBoolean(KEY_LOW_BITRATE, value).apply()
    }

    fun setGain(value: Float) {
        val v = value.coerceIn(0f, 2f)
        _gain.value = v
        sp.edit().putFloat(KEY_GAIN, v).apply()
    }

    /**
     * 设置**缓存总上限**（MB）。
     *
     * 在这里就夹到 [AudioCachePolicy] 允许的区间：用户可能通过
     * 恢复备份之类的方式得到一个荒谬的值（比如 0 或 100000），
     * 0 会让每次播放都立刻淘汰刚缓存的东西。
     */
    fun setAudioCacheLimitMb(value: Int) {
        val v = value.coerceIn(AudioCachePolicy.MIN_LIMIT_MB, AudioCachePolicy.MAX_LIMIT_MB)
        _audioCacheLimitMb.value = v
        sp.edit().putInt(KEY_AUDIO_CACHE_LIMIT_MB, v).apply()
    }

    /**
     * 设置**单曲缓存上限**（MB）。
     *
     * 同样在这里夹到允许区间：上限小于单曲体积本身没有意义
     * （只会让每首曲子都缓存不完整），设成 0 更是直接废掉缓存。
     */
    fun setPerTrackCacheLimitMb(value: Int) {
        val v = value.coerceIn(AudioCachePolicy.MIN_PER_TRACK_MB, AudioCachePolicy.MAX_PER_TRACK_MB)
        _perTrackCacheLimitMb.value = v
        sp.edit().putInt(KEY_PER_TRACK_CACHE_LIMIT_MB, v).apply()
    }

    fun setSleepTimerMinutes(value: Int) {
        _sleepTimerMinutes.value = value
        sp.edit().putInt(KEY_SLEEP_TIMER, value).apply()
    }

    private const val KEY_HOST = "host"
    private const val KEY_AUDIO_FORMAT = "audio_format"
    private const val KEY_HOME_ENABLED = "home_enabled"
    private const val KEY_RECOMMENDER_UUID = "recommender_uuid"
    private const val KEY_DICTIONARIES_INITIALIZED = "dictionaries_initialized"
    private const val KEY_COLLAPSE_BOTTOM_BAR = "collapse_bottom_bar"
    private const val KEY_WIFI_ONLY = "wifi_only"
    private const val KEY_LOW_BITRATE = "low_bitrate"
    private const val KEY_GAIN = "gain"
    private const val KEY_AUDIO_CACHE_LIMIT_MB = "audio_cache_limit_mb"
    private const val KEY_PER_TRACK_CACHE_LIMIT_MB = "per_track_cache_limit_mb"
    private const val KEY_SLEEP_TIMER = "sleep_timer"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_LAST_PLAYLIST = "last_selected_playlist"
    private const val KEY_AUTO_SYNC_DAYS = "auto_sync_days"
}
