package com.asmrone.app.data.audio

import com.asmrone.app.data.diag.HttpLog
import com.asmrone.app.data.prefs.AppPrefs
import com.asmrone.app.player.AudioController

/**
 * 音频缓存的**维护入口** —— 启动时自动跑一次，设置页也会调。
 *
 * ## 为什么放在 data 层而不是设置页
 *
 * 有两个调用方（App 启动、设置页手动清理），而它们必须是**同一套行为** ——
 * 否则"启动时清了什么"与"点按钮清了什么"会不一样，用户看到占用数字
 * 对不上就会怀疑缓存没生效。
 *
 * ## 清理与上限的关系
 *
 * [run] 做三件事，顺序有意：
 * 1. 先按策略清掉过期的（7 天未增长的未完成 + 30 天前的已完成）
 * 2. 再看是否超上限 → 按 LRU 淘汰
 * 3. 把结果记进网络日志（排查"为什么缓存没留下"时有用）
 *
 * 上限从 [AppPrefs.audioCacheLimitMb] 读，所以用户改完立刻生效。
 */
object AudioCacheMaintenance {

    /** 启动时最多多久跑一次（避免每次冷启动都去数文件） */
    private const val MIN_INTERVAL_MS = 10 * 60 * 1000L

    @Volatile
    private var lastRunAt = 0L

    /**
     * 跑一次维护。
     *
     * @param force 忽略时间间隔（设置页手动清理、或用户刚改上限时用）
     * @return 本次释放的字节数
     */
    fun run(force: Boolean = false): Long {
        val store = AudioController.cacheStore
        if (!store.isReady) return 0L

        val now = System.currentTimeMillis()
        if (!force && now - lastRunAt < MIN_INTERVAL_MS) return 0L
        lastRunAt = now

        return runCatching {
            val limitBytes = AppPrefs.audioCacheLimitMb.value.toLong() * 1024L * 1024L
            val plan = store.cleanupPlan(limitBytes, now)
            if (plan.isEmpty) return@runCatching 0L

            val freed = store.executePlan(plan)
            HttpLog.request(
                "音频缓存清理：释放 ${AudioCacheStatus.formatBytes(freed)}" +
                    "（${plan.reasons.joinToString("；")}）"
            )
            freed
        }.getOrElse {
            HttpLog.error("音频缓存清理失败：${it.message}")
            0L
        }
    }

    /** 当前状态（设置页展示用） */
    fun status(): AudioCacheStatus {
        val store = AudioController.cacheStore
        if (!store.isReady) return AudioCacheStatus()
        return store.snapshot(System.currentTimeMillis())
    }

    /** 清空全部音频缓存；返回释放的字节数 */
    fun clearAll(): Long {
        val store = AudioController.cacheStore
        if (!store.isReady) return 0L
        val freed = store.clearAll()
        HttpLog.request("音频缓存已清空：释放 ${AudioCacheStatus.formatBytes(freed)}")
        return freed
    }

    /** 上限改了之后立刻按新上限收敛一次 */
    fun applyLimit(limitMb: Int) {
        AppPrefs.setAudioCacheLimitMb(limitMb)
        run(force = true)
    }

    /**
     * 自检报告（调试用）。
     *
     * 为什么要它：判断"边播边缓存"到底有没有生效，需要知道
     * **代理实际收到了什么 Range 请求**、缓存文件长到多少了 ——
     * 这些只发生在播放的那一刻，靠事后推演代码很容易得出错误结论
     * （我在别处已经因此犯错两次）。所以把事实写成一份可读报告。
     *
     * 报告进 [HttpLog]，配合 `adb` 取出的日志文件就能在**没有界面**的情况下
     * 确认整条链路。也可以直接在「设置 → 关于 → 网络日志」里看。
     */
    fun selfCheck(): String {
        val store = AudioController.cacheStore
        val status = status()
        val report = buildString {
            append("音频缓存自检：")
            append(if (store.isReady) "存储已就绪" else "存储未就绪")
            append("；已缓存 ").append(status.completeCount).append(" 首")
            append("、未完成 ").append(status.partialCount).append(" 首")
            append("；占用 ").append(status.usedText)
            append("；上限 ").append(AppPrefs.audioCacheLimitMb.value).append(" MB")
        }
        HttpLog.request(report)
        HttpLog.flushToFile()
        return report
    }
}
