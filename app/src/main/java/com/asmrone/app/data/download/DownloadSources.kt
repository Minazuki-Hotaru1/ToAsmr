package com.asmrone.app.data.download

import java.io.File

/**
 * 两个下载仓库的统一入口。
 *
 * 内容可能落在 App 私有目录（[DownloadStore]）或公共目录（[LocalDownloadStore]），
 * 读取方（详情页、播放器、歌词、图片查看）不关心是哪一种，问这里就行：
 * 先查 App 内那份，没有再查本地那份。
 *
 * 两边都存过同一部作品时以 App 内那份为准——它是"完整下载"的原始记录，
 * 本地那份往往是"另存出去"的副本。
 */
object DownloadSources {

    suspend fun manifest(workId: Int): DownloadManifest? =
        DownloadStore.load(workId) ?: LocalDownloadStore.load(workId)

    fun localFileFor(workId: Int, path: String): File? =
        DownloadStore.localFileFor(workId, path) ?: LocalDownloadStore.localFileFor(workId, path)

    fun isDownloaded(workId: Int): Boolean =
        DownloadStore.isDownloaded(workId) || LocalDownloadStore.isDownloaded(workId)

    /**
     * 已下载的树内路径（两边合并）。
     *
     * 详情页靠它决定哪些文件显示"已下载"、哪些可以离线播放；
     * 两个目录各下了一部分时也要合起来，否则会出现"明明下过却标成未下载"。
     */
    suspend fun paths(workId: Int): Set<String> =
        DownloadStore.load(workId)?.paths.orEmpty() + LocalDownloadStore.load(workId)?.paths.orEmpty()

    /** 同上，但只读内存缓存（首帧用，不碰磁盘） */
    fun cachedPaths(workId: Int): Set<String> =
        DownloadStore.cached(workId)?.paths.orEmpty() + LocalDownloadStore.cached(workId)?.paths.orEmpty()
}
