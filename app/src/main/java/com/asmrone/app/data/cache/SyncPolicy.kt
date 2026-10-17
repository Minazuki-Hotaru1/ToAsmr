package com.asmrone.app.data.cache

import com.asmrone.app.data.prefs.AppPrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 为什么要跟服务器说话 —— **封闭的三个理由**。
 *
 * ## 目标：把"每天只自动匹配一次"编在代码里
 *
 * 用户的目的是**减少服务器压力**，所以自动匹配的机会是：
 *
 * ```
 * 每天第一次进 App  → 「标记」与「列表」各自对齐一次
 * 当天之后           → 一个请求都不发（只读本地）
 * ```
 *
 * 而用户随时可以用**主动刷新**覆盖这个限制。
 *
 * ## 为什么需要"理由"这个显式参数
 *
 * 早先的版本用 `SyncScope`（DAILY / MANUAL）+ 调用方各自传 `daily` 布尔值。
 * 那套设计的毛病是**判断散落且可被静默绕过**：
 * 任何一处写成 `daily = false` 就绕过了额度，而**编译通过、单测全绿**，
 * 只有真机抓包才看得出来 —— 这个错犯过两次（一次在仓库层，一次在 UI 层）。
 *
 * 现在只有三个理由，全部在 [SyncTrigger] 里封闭声明；
 * 「每天一次」由 [SyncGate] 用**落盘的按类别日期标记**保证。
 */
enum class SyncTrigger {

    /**
     * **每天第一次自动对齐** —— 唯一的一条自动路径。
     *
     * 每个 `(数据类别, host, 账号)` **每个本地自然日**只放行一次。
     * 「标记」与「列表」是两份独立的数据、两个独立的接口，
     * 所以它们**各自**拥有一次机会（共用一个额度是早期版本的 bug：
     * 谁先进谁用掉，另一个当天永远拿不到云端更新）。
     *
     * ## 为什么必须落盘（[com.asmrone.app.data.prefs.AppPrefs.autoSyncDay]）
     *
     * 目的就是减少服务器压力。如果只记在内存里，杀掉 App 重开又会对齐一次，
     * 达不到"当天之后不再自动匹配"。
     */
    DAILY_AUTO,

    /**
     * **用户明确要求**：下拉刷新、点刷新按钮、点卡片上的「待同步」重试。
     *
     * 不受任何限制 —— 用户想要最新的就给他。仍受单飞约束，所以连点只发一次。
     */
    USER_REFRESH,

    /**
     * **换了账号或服务器**。
     *
     * 必须单独放行，不能归进 [DAILY_AUTO]：缓存键含 host + 账号
     * （见 `SyncCacheStore.SyncKeys`），换了账号就是**另一份缓存**，
     * 本地为空；而当前会话的自动机会可能已被上一个账号用掉 ——
     * 不单独放行就会一整天空白。
     */
    ACCOUNT_CHANGED
}

/**
 * 同步闸门 —— 决定"这次该不该真的去问服务器"，并保证同一份数据不并发重复请求。
 *
 * ## 规则（就这么几条，都在这里）
 *
 * | 理由 | 放行条件 |
 * |---|---|
 * | [SyncTrigger.DAILY_AUTO] | 本会话内**这份数据**还没自动对齐过 |
 * | [SyncTrigger.USER_REFRESH] | 总是放行 |
 * | [SyncTrigger.ACCOUNT_CHANGED] | 总是放行 |
 *
 * 放行 = 真正发请求；被挡下 = **一个请求都不发**，调用方照常用本地 JSON。
 *
 * ## 失败也算"对齐过"
 *
 * 自动路径在**请求之前**就记下"今天已对齐"，而不是等结果回来。
 * 两个原因：
 *  1. 服务端持续故障时不能每进一次页面就重打一次（那正是要避免的压力源）
 *  2. 请求超时（45 秒）期间用户来回切页签，不能反复触发新请求
 *
 * 代价（明确接受）：自动对齐失败后**不会自动重试**，只能靠用户手动刷新，
 * 或明天。这也**不需要在界面上报告** —— 本地 JSON 就是主数据源，
 * 没对齐上不影响使用。真正要提醒的是"某次改动没送到云端"，
 * 那由卡片上的「待同步」标识表达。
 */
object SyncGate {

    /** key → 在途请求（单飞用） */
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Any?>>()

    /** 保护"检查 + 登记"这一步，避免两个协程同时通过检查 */
    private val gate = Mutex()

    // ─────────────────── 每日标记的读写（可替换，便于单测）───────────────────
    //
    // 生产环境走 AppPrefs（SharedPreferences）；单元测试里换成内存实现，
    // 这样"每天一次"的判定可以在纯 JVM 上验证，不必起 Android 环境。

    private var dayRead: ((String) -> String)? = null
    private var dayWrite: ((String, String) -> Unit)? = null

    /** 仅供测试：注入内存日期实现 */
    fun installDayStoreForTest(read: (String) -> String, write: (String, String) -> Unit) {
        dayRead = read
        dayWrite = write
    }

    /** 仅供测试：撤销注入 */
    fun uninstallDayStoreForTest() {
        dayRead = null
        dayWrite = null
    }

    private fun readDay(domain: String): String =
        dayRead?.invoke(domain) ?: AppPrefs.autoSyncDay(domain)

    private fun writeDay(domain: String, day: String) {
        val write = dayWrite
        if (write != null) write(domain, day) else AppPrefs.setAutoSyncDay(domain, day)
    }

    /**
     * 该不该为 [trigger] 发这次请求？
     *
     * 对 [SyncTrigger.DAILY_AUTO] 是**判定 + 记账**一步完成（先记账再让调用方去请求），
     * 所以调用方不需要自己记住"今天已经问过了"。
     *
     * @param domainKey 数据类别（形如 `collection|host|用户`），不含筛选与页码
     */
    fun shouldSend(trigger: SyncTrigger, domainKey: String): Boolean = when (trigger) {
        SyncTrigger.USER_REFRESH, SyncTrigger.ACCOUNT_CHANGED -> true
        SyncTrigger.DAILY_AUTO -> {
            val today = dayStamp()
            if (readDay(domainKey) == today) {
                false
            } else {
                // ⚠️ 先记账再让调用方去请求：若等到请求返回再记，
                // 一次卡住的请求（超时 45 秒）期间用户来回切页签会不断触发新请求
                writeDay(domainKey, today)
                true
            }
        }
    }

    /**
     * 把当前时刻格式化成"本地自然日"的日期串。
     *
     * 用日历日而不是"间隔 24 小时"：需求是"每天第一次"，日历日更符合直觉，
     * 也不会因为夏令时/时区导致某天被跳过或重复。
     */
    fun dayStamp(nowMs: Long = System.currentTimeMillis()): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(nowMs))

    /**
     * 执行一次对齐 —— 统一套用上面的规则与单飞。
     *
     * 调用方只管把"网络动作"传进来；**失败时异常照原样抛出**，
     * 由调用方决定要不要处理（收藏页的选择是：什么都不做，本地内容照常展示）。
     *
     * @param key 缓存键（含 host + 账号 + 维度，见 `SyncCacheStore.SyncKeys`）
     * @param trigger 见 [SyncTrigger]
     * @param fetch 真正的网络动作（通常是条件请求）；调用方自己写缓存
     * @return 是否由本次调用真正发起了请求
     */
    suspend fun sync(
        key: String,
        trigger: SyncTrigger,
        fetch: suspend () -> Unit
    ): Boolean {
        // domain = 缓存键去掉"页/筛选"后缀，这样同一份数据的多个页共享一个自动机会
        if (!shouldSend(trigger, domainOf(key))) return false

        // ── 单飞：登记在途请求 ──────────────────────────────
        val (mine, pending) = gate.withLock {
            val existing = inFlight[key]
            if (existing != null) {
                false to existing
            } else {
                val d = CompletableDeferred<Any?>()
                inFlight[key] = d
                true to d
            }
        }

        if (!mine) {
            // 别人正在拉同一个 key —— 等它的结果，不自己发请求
            runCatching { pending.await() }
            return false
        }

        return try {
            fetch()
            true
        } finally {
            gate.withLock { inFlight.remove(key) }
            pending.complete(null)
        }
    }

    /**
     * 缓存键 → 数据类别（每日机会的粒度）。
     *
     * ## 各类数据的"一份"是什么
     *
     * `SyncCacheStore.SyncKeys` 的实际形状：
     *
     * ```
     * collection|host|用户                      ← 「标记」只有这一份（筛选本地派生）
     * playlists|host|用户                       ← 「列表」只有这一份
     * playlistWorks|host|用户|收藏夹id          ← **每个收藏夹各是一份**
     * ```
     *
     * 页码后缀只在翻页时才拼上（`"$key|page$n"`），不属于类别。
     *
     * ## 为什么不能一律取前三段
     *
     * 前两类取前三段就够了，而且**必须**这样：「标记」的 4 个状态筛选
     * 共用同一份本地 JSON（各筛选是读取时按 `progress` 派生的），
     * 若把筛选也算进类别，切筛选就会各打一次接口。
     *
     * 但收藏夹内容不一样：`playlistWorks|host|user|<收藏夹id>` 的第 4 段
     * 是**收藏夹 id**，两个不同的收藏夹是两份不同的数据 ——
     * 一律取前三段会让它们共用一个每日机会（今天打开 A、B 就拿不到更新），
     * 这是单测抓出来的（"期望 1 实际 0"）。
     *
     * 所以按维度区分：`playlistWorks` 取四段，其余取三段。
     */
    private fun domainOf(key: String): String {
        val parts = key.split('|')
        // 去掉尾部的 `pageN`（只可能出现在最后一段）
        val head = if (parts.isNotEmpty() && parts.last().startsWith("page")) {
            parts.dropLast(1)
        } else {
            parts
        }
        val take = when (head.firstOrNull()) {
            "collection", "playlists" -> 3
            else -> 4
        }
        return head.take(take).joinToString("|")
    }

    /**
     * 换账号 / 换服务器时调用。
     *
     * **只清在途登记，不清"已自动对齐"标记** —— 恰恰相反：
     * 新账号自己的类别（含账号名）本来就没被标记过，所以它天然拥有
     * 一次完整的自动机会；而旧账号的标记留着不影响任何事
     * （旧账号的数据在本次会话里确实已经对齐过了）。
     */
    fun reset() {
        inFlight.clear()
    }

    /** 排查用：当前有多少个 key 正在请求 */
    fun inFlightCount(): Int = inFlight.size
}
