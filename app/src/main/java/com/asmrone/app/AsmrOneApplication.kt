package com.asmrone.app

import android.app.Application
import android.content.pm.ApplicationInfo
import com.asmrone.app.data.audio.AudioCacheMaintenance
import com.asmrone.app.data.cache.CacheFiles
import com.asmrone.app.data.cache.CacheManager
import com.asmrone.app.data.cache.CircleDictionary
import com.asmrone.app.data.cache.FolderStateStore
import com.asmrone.app.data.diag.HttpLog
import com.asmrone.app.data.cache.PendingWriteStore
import com.asmrone.app.data.cache.TagDictionary
import com.asmrone.app.data.cache.TrackTreeStore
import com.asmrone.app.data.cache.WorkMetaStore
import com.asmrone.app.data.lyrics.LyricsStore
import com.asmrone.app.data.cache.SyncCacheStore
import com.asmrone.app.data.cache.VoiceActorDictionary
import com.asmrone.app.data.download.DownloadManager
import com.asmrone.app.data.download.DownloadStore
import com.asmrone.app.data.prefs.AccountStore
import com.asmrone.app.data.prefs.AppPrefs
import com.asmrone.app.data.prefs.LibraryStore
import com.asmrone.app.data.prefs.PlaybackSessionStore
import com.asmrone.app.data.prefs.SearchHistoryStore
import com.asmrone.app.player.AudioController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AsmrOneApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 偏好与本地收藏必须在任何 Compose 组合之前初始化
        AppPrefs.init(this)
        LibraryStore.init(this)
        AccountStore.init(this)
        // 搜索历史（SharedPreferences）；搜索页会直接读它，所以要早于首次组合
        SearchHistoryStore.init(this)
        // 缓存结构升级：版本不一致就作废旧 JSON 并重置"今天已对齐"的记账。
        // 必须在 SyncCacheStore 之前 —— 否则会先读到旧结构、又因为 304 永不重写。
        CacheFiles.ensureSchema(this)
        // 收藏类数据的本地镜像（JSON 文件）；必须在第一次读取收藏之前就绪
        SyncCacheStore.init(this)
        // 缓存总览（设置 → 数据 → 缓存管理）：统计 / 清除各类缓存。
        // 它自己不写盘，只是把散在各处的缓存索引到一起，早初始化只是为了拿到 applicationContext
        CacheManager.init(this)
        // 待补写队列：装的是"用户已表达、但还没写到云端"的操作，同样要早于首次读取
        PendingWriteStore.init(this)
        // 文件树的本地持久化（按 RJ 号）：进入作品时写入，冷启动后播放器/歌词页直接读
        TrackTreeStore.init(this)
        // 详情页元数据的本地持久化：从收藏页点进作品时不再等一次网络往返
        WorkMetaStore.init(this)
        // 每部作品"展开了哪些文件夹"：再进同一部作品时自动恢复
        FolderStateStore.init(this)
        // 已解析歌词的本地持久化：冷启动直接读盘，不再为同一首歌词重复联网
        LyricsStore.init(this)
        // 已下载作品的磁盘根目录（filesDir/downloads）：详情页、播放器、歌词都直接读它，
        // 所以要早于首次组合 —— 否则"打开已下载作品"的第一帧会以为本地没有而联网
        DownloadStore.init(this)
        // 搜索用的三本字典（本地 JSON）：标签 / 声优 / 社团。
        // 搜索界面会直接读它们，所以要早于首次组合。
        TagDictionary.init(this)
        VoiceActorDictionary.init(this)
        CircleDictionary.init(this)
        // 播放会话快照：必须早于 AudioController 恢复
        PlaybackSessionStore.init(this)
        AudioController.init(this)
        AudioController.restoreSession()
        AudioController.setGain(AppPrefs.gain.value)
        AppPrefs.sleepTimerMinutes.value.takeIf { it > 0 }?.let { AudioController.setSleepTimer(it) }

        // 音频缓存的清理放到后台线程：它要遍历缓存目录数文件，
        // 放在 onCreate 里会拖慢冷启动的首帧。
        // 内部有 10 分钟的最小间隔，所以频繁冷启动不会反复扫盘。
        CoroutineScope(Dispatchers.IO).launch {
            // 旧版本只按 RJ 落盘、没有"workId → RJ"指针。补一次，
            // 否则那些**已经缓存过**的作品还要再拉一遍树才读得到。
            TrackTreeStore.backfillPointers()
            AudioCacheMaintenance.run()
            // 下载页的列表要等清单解析完才有内容，而它有可能是几十部作品：
            // 放进后台预热，用户切到下载页时就是直接出图
            DownloadManager.init(this@AsmrOneApplication)
        }

        // 调试期把网络日志落盘，便于用 adb 取出分析。
        //
        // 用 FLAG_DEBUGGABLE 而不是 BuildConfig.DEBUG：本工程没开
        // `buildFeatures { buildConfig = true }`，BuildConfig 并不生成；
        // 而这个判断只是"别在正式包里留日志文件"，用系统标志就够。
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) {
            HttpLog.enableFileDump(java.io.File(filesDir, "http_log.txt"))
            // 立刻刷一次：否则要等到第一次播放（代理收到请求）才有日志文件，
            // 而排查"代理到底起没起来"恰恰是在播放之前
            HttpLog.flushToFile()
        }
    }
}
