package com.asmrone.app.data.cache

import android.content.Context
import coil.Coil
import coil.annotation.ExperimentalCoilApi
import com.asmrone.app.data.audio.AudioCacheMaintenance
import com.asmrone.app.data.lyrics.LyricsStore
import com.asmrone.app.data.prefs.AppPrefs

/**
 * 「本地缓存」的总入口：统计各类缓存占用、按类清除。
 *
 * ## 为什么需要这样一个汇总层
 *
 * 缓存散在各处：Coil 的图片盘、音频代理的分片目录、详情元数据、文件树、歌词、
 * 收藏镜像……每个模块只知道自己那一份。设置页要"看到各类缓存分别占多少"，
 * 就必须有一个地方把它们列在一起，并且**按同一套口径**给出字节数 ——
 * 否则用户看到的数字加起来会对不上。
 *
 * ## 绝对不能删的两样东西
 *
 * 1. **[PendingWriteStore] 的待补写文件**。它们和收藏镜像存在同一个目录
 *    （[CacheFiles]），内容却是"用户已经点过、云端还没收到"的操作。删掉不是
 *    "下次重新拉取"，而是**用户的操作凭空消失**。所以清收藏镜像走的是
 *    [clearSyncMirrors]：按 key 前缀 `pending|` 精确排除，**不用**
 *    `SyncCacheStore.clearAll()`（那是整目录清，会在切换服务器以外的场景误伤）。
 * 2. **已下载的作品**（`DownloadStore`）。它们是用户主动下载的离线内容，
 *    不在这份清单里，[clearAll] 也不会碰。
 */
object CacheManager {

    /** 可清除的缓存种类；界面据此展示标题与说明 */
    enum class Kind {
        IMAGES,
        AUDIO,
        WORK_META,
        TRACK_TREE,
        FOLDER_STATE,
        LYRICS,
        SYNC_MIRRORS,
        EXTERNAL_FILES
    }

    /**
     * 一类缓存的当前占用。
     *
     * [count] 只在"条数有意义"的种类上填（作品信息、文件树、镜像文件），
     * 其余保持 0 —— 界面上也不显示它。
     */
    data class Usage(val kind: Kind, val bytes: Long, val count: Int = 0)

    /**
     * 待补写文件的 key 前缀（见 `SyncCacheStore.SyncKeys.pendingWrites`）。
     *
     * 清镜像时用它做**白名单**：命中即跳过，不是黑名单。
     */
    private const val PENDING_PREFIX = "pending|"

    /**
     * 收藏镜像的 key 前缀（见 `SyncCacheStore.SyncKeys`）。
     *
     * 必须**逐个列出来**，不能写成"除了 pending 之外都算镜像"：
     * 同一个目录里还躺着搜索字典（`tag|` / `voiceActor|` / `circle|`），
     * 它们删掉要重新走一遍更新流程，并不是"下次浏览就会回来"的缓存。
     */
    private val MIRROR_PREFIXES = listOf(
        "collection|",
        "playlists|",
        "playlistWorks|",
        "playlistMeta|"
    )

    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /**
     * 统计全部缓存；**会读盘**，请在 IO 线程调用。
     *
     * 每次调用都重新扫一遍：缓存是播放/浏览过程中不断变化的，
     * 记住上一次的快照反而会让"清完了数字还在"显得像没生效。
     */
    @OptIn(ExperimentalCoilApi::class)
    fun usages(): List<Usage> {
        val context = appContext
        val imageBytes = if (context == null) {
            0L
        } else {
            // Coil 2 的单例盘缓存；内存缓存不占磁盘，这里只报盘上的
            runCatching { Coil.imageLoader(context).diskCache?.size ?: 0L }.getOrDefault(0L)
        }
        val audio = AudioCacheMaintenance.status()
        val mirrors = syncMirrors()
        return listOf(
            Usage(Kind.IMAGES, imageBytes),
            Usage(Kind.AUDIO, audio.usedBytes, audio.totalCount),
            Usage(Kind.WORK_META, WorkMetaStore.sizeBytes(), WorkMetaStore.count()),
            Usage(Kind.TRACK_TREE, TrackTreeStore.sizeBytes(), TrackTreeStore.count()),
            Usage(Kind.FOLDER_STATE, FolderStateStore.sizeBytes()),
            Usage(Kind.LYRICS, LyricsStore.sizeBytes()),
            Usage(Kind.SYNC_MIRRORS, mirrors.sumOf { it.bytes }, mirrors.size),
            Usage(
                Kind.EXTERNAL_FILES,
                context?.let { ExternalOpenFiles.sizeBytes(it) } ?: 0L
            )
        )
    }

    /** 待补写的条数（界面用它提示"这些不会被清除"） */
    fun pendingWriteCount(): Int {
        val context = appContext ?: return 0
        return CacheFiles.entries(context).count { it.key.startsWith(PENDING_PREFIX) }
    }

    /**
     * 这条缓存属于"可清的镜像"还是"必须留的其它数据"。
     *
     * 拆成纯函数是为了能单测：判定一旦写错，后果是**用户的操作被删掉**
     * （待补写）或**字典被清空**，比一般"清理不彻底"严重得多。
     */
    internal fun isMirrorKey(key: String): Boolean = MIRROR_PREFIXES.any { key.startsWith(it) }

    /** 清除一类缓存 */
    fun clear(kind: Kind) {
        val context = appContext ?: return
        when (kind) {
            Kind.IMAGES -> runCatching {
                val loader = Coil.imageLoader(context)
                // 内存里的缩略图也要清：只清盘的话，返回列表仍是旧图，用户会以为没起作用
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
            }
            Kind.AUDIO -> AudioCacheMaintenance.clearAll()
            Kind.WORK_META -> WorkMetaStore.clearAll()
            Kind.TRACK_TREE -> TrackTreeStore.clearAll()
            Kind.FOLDER_STATE -> FolderStateStore.clearAll()
            Kind.LYRICS -> LyricsStore.clearAll()
            Kind.SYNC_MIRRORS -> clearSyncMirrors(context)
            Kind.EXTERNAL_FILES -> ExternalOpenFiles.clearAll(context)
        }
    }

    /** 清除全部（保留待补写与已下载作品） */
    fun clearAll() {
        Kind.entries.forEach(::clear)
    }

    /** 收藏 / 收藏夹 / 待补写同处一个目录 → 只取镜像部分 */
    private fun syncMirrors(): List<CacheFiles.CachedFile> {
        val context = appContext ?: return emptyList()
        return CacheFiles.entries(context).filter { isMirrorKey(it.key) }
    }

    private fun clearSyncMirrors(context: Context) {
        syncMirrors().forEach { it.file.delete() }
        // 与 CacheFiles.ensureSchema 同一个理由：镜像没了、"今天已自动对齐"的记账还在，
        // 当天就不会再拉一次，收藏页会一直空到第二天。
        AppPrefs.clearAutoSyncDays()
    }
}
