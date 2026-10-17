package com.asmrone.app.data.audio

import java.time.LocalDate
import java.time.ZoneId

/**
 * 音频流缓存的一个候选项 —— **快照**，不含任何文件句柄。
 *
 * 刻意只带"做淘汰决策需要的信息"，这样 [AudioCachePolicy] 的每条规则
 * 都能用普通单元测试覆盖（不需要设备、不需要真实文件系统）。
 */
data class AudioCacheEntry(
    /** 资源标识（由上游 URL 派生），同时也是文件名 */
    val id: String,
    /** 已落盘的字节数（`.part` 是已缓存的部分，`.audio` 是完整文件） */
    val cachedBytes: Long,
    /** 该资源的总字节数；未知为 0（`Content-Range` 没给出来时） */
    val totalBytes: Long,
    /** 是否已完整（`.audio`） */
    val complete: Boolean,
    /** 文件最后修改时间 —— 也是"是否还在增长"的判据 */
    val lastModifiedMs: Long,
    /** 最后被访问（播放）的时间，用于 LRU 淘汰 */
    val lastAccessedMs: Long
) {
    /**
     * **`.part` 永远不可能靠 LRU 之外的规则清掉的那种垃圾**：
     * 未完成 + 长期未增长。
     *
     * 典型来源是"试听 5 秒就划走"——那个 `.part` 既不会有人再回来补完，
     * 也不会自己消失。参考实现只靠 30 天 TTL 兜底，空间利用率很差。
     */
    val isStalePartial: Boolean get() = !complete

    /** 展示用的占用（未完成时就是已缓存的那部分） */
    val displayBytes: Long get() = cachedBytes
}

/** 一次清理要做的事 */
data class CacheCleanupPlan(
    /** 要删除的条目 */
    val delete: List<AudioCacheEntry>,
    /** 删除后释放的字节数 */
    val freedBytes: Long,
    /** 触发原因（用于提示与排查） */
    val reasons: List<String>
) {
    val isEmpty: Boolean get() = delete.isEmpty()
}

/**
 * 音频缓存的**淘汰策略** —— 纯函数，便于单测。
 *
 * ## 三条规则的顺序（顺序本身是有意的）
 *
 * ```
 * ① 过期未完成的 .part（7 天未增长）→ 删
 *    这类文件永远不会完成：不会有人再从头把它播完，它也不会自己消失。
 *    "试听几秒就划走"是音声场景里最常见的用法，所以这条最该先跑。
 *
 * ② 过期已完成的缓存（TTL）→ 删
 *    服务端**没有 ETag / Last-Modified**（实测），所以只能靠
 *    "字节数精确匹配 + TTL"判断新鲜度。TTL 是唯一的失效兜底。
 *
 * ③ 仍超上限 → 按 LRU 删（最久没被访问的先删）
 *    只在还有"已完成"的缓存时删；`.part` 留到 ① 才处理 ——
 *    因为一个正在增长的 `.part` 是有用的（下次接着播就能命中）。
 * ```
 *
 * 为什么 ① 用"未增长时长"而不是"创建时长"：一个正在被慢慢听的
 * 长音频（比如 2 小时的音声分几天听）其 `.part` 会持续增长，
 * 按创建时间会被误删。
 */
object AudioCachePolicy {

    /** 未完成的 `.part` 多久没增长就删（用户确认的口径） */
    const val STALE_PARTIAL_DAYS = 7L

    /** 已完成的缓存多久过期 */
    const val COMPLETE_TTL_DAYS = 30L

    /** 默认上限 1GB（参考实现的默认值；用户可在设置里改） */
    const val DEFAULT_LIMIT_MB = 1024

    const val MIN_LIMIT_MB = 128
    const val MAX_LIMIT_MB = 10240

    // ─────────────────── 单曲缓存上限 ───────────────────
    //
    // 语义见 [TrackCacheLedger]：一次连续播放期间，最多往后缓存多少。
    //
    // 限速靠**播放器自身**（它读多少才跟着写多少），所以这个上限
    // 就是唯一的封顶 —— 没有额外的"预读额度"层（原先有，已删除）。
    //
    // 为什么要让用户选：音声格式跨度极大（MP3 常见十几 MB，
    // 而 **WAV / FLAC 单曲实测可到 92 MB**）。上限小于单曲体积时，
    // 那首曲子永远缓存不完整 → 次次重新走网络。
    // 但调大意味着在一次播放里多花流量，所以取决于用户的曲库与套餐。

    /** 默认 128 MB：覆盖实测最大单曲（92MB WAV），又不至于让一次异常拉太多 */
    const val DEFAULT_PER_TRACK_MB = 128

    const val MIN_PER_TRACK_MB = 32
    const val MAX_PER_TRACK_MB = 1024

    /** 单曲上限的可选项。量级跳跃而非细粒度："一次愿意为单曲花多少流量"是量级决策 */
    val PER_TRACK_CHOICES_MB = listOf(32, 64, 128, 256, 512, 1024)

    /** 缓存总上限的可选项 */
    val TOTAL_LIMIT_CHOICES_MB = listOf(256, 512, 1024, 2048)

    fun stalePartialCutoffMs(nowMs: Long): Long = nowMs - STALE_PARTIAL_DAYS * DAY_MS

    fun completeTtlCutoffMs(nowMs: Long): Long = nowMs - COMPLETE_TTL_DAYS * DAY_MS

    /**
     * 生成清理计划。
     *
     * @param usedBytes 当前总占用（由调用方给出，避免这里再数一遍文件）
     * @param limitBytes 用户设定的上限（字节）
     */
    fun plan(
        entries: List<AudioCacheEntry>,
        limitBytes: Long,
        nowMs: Long,
        usedBytes: Long
    ): CacheCleanupPlan {
        val toDelete = LinkedHashMap<String, AudioCacheEntry>()
        val reasons = mutableListOf<String>()

        // ① 过期未完成的 .part
        val staleCutoff = stalePartialCutoffMs(nowMs)
        val stale = entries.filter { it.isStalePartial && it.lastModifiedMs < staleCutoff }
        if (stale.isNotEmpty()) {
            stale.forEach { toDelete[it.id] = it }
            reasons += "${stale.size} 个未完成的缓存 ${STALE_PARTIAL_DAYS} 天没有增长"
        }

        // ② 过期已完成的
        val ttlCutoff = completeTtlCutoffMs(nowMs)
        val expired = entries.filter {
            it.complete && it.id !in toDelete && it.lastModifiedMs < ttlCutoff
        }
        if (expired.isNotEmpty()) {
            expired.forEach { toDelete[it.id] = it }
            reasons += "${expired.size} 个缓存已超过 ${COMPLETE_TTL_DAYS} 天"
        }

        // ③ 仍超上限 → LRU。先算前面两步能释放多少
        val afterExpiry = usedBytes - toDelete.values.sumOf { it.cachedBytes }
        if (afterExpiry > limitBytes) {
            // 只淘汰"已完成"的：正在增长的部分缓存留着（下次接着播就命中）
            val lru = entries
                .filter { it.complete && it.id !in toDelete }
                .sortedBy { it.lastAccessedMs }
            var over = afterExpiry - limitBytes
            for (e in lru) {
                if (over <= 0) break
                toDelete[e.id] = e
                over -= e.cachedBytes
            }
            val evicted = toDelete.values.count { it.complete && it.id !in stale.map { s -> s.id } }
            if (evicted > 0) {
                reasons += "超出上限 ${limitBytes / MB} MB，按最久未播放淘汰了 $evicted 个"
            }
        }

        val list = toDelete.values.toList()
        return CacheCleanupPlan(
            delete = list,
            freedBytes = list.sumOf { it.cachedBytes },
            reasons = reasons
        )
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val MB = 1024L * 1024L

    /** 今天（本地时区）—— 用于"上次清理时间"这类按天判断 */
    fun today(zone: ZoneId = ZoneId.systemDefault()): LocalDate = LocalDate.now(zone)
}
