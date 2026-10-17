package com.asmrone.app.data.audio

/**
 * 单曲缓存记账 —— **只回答一句话：这一首还能再缓存多少**。
 *
 * ## 为什么这么短
 *
 * 这里先后有过两版更复杂的东西，都删掉了：
 *
 * 1. **"预读额度"（2MB）**：本意是会话开头提前多读一小段让起播快。
 *    收益在多数网络下感受不到，却要求两段分别记账、跨段时头尾拆记、
 *    换曲必须重置、非顺序读取不能花额度 —— 一串约束换不来任何改善。
 * 2. **"起始阶段块长截断"**：连带的产物，纯属多余（见下）。
 *
 * 用户的判断更直接：曲目之间的等待本来就只有 5 秒左右，
 * 为"下一首"预留数据没有意义，为"当前这一首"多读一小段同样没有意义。
 *
 * ## 现在只剩两件事
 *
 * ```
 * ① 缓存由播放器驱动 —— 不需要任何额度去模拟"别一次拉整首"
 *    代理喂给播放器的 output.write 会阻塞，直到播放器把数据取走。
 *    它读多少，代理才继续读多少，所以代理**不可能**抢在播放器前面
 *    把整首拉完。这是天然的限速，不用代码去实现。
 *
 * ② 唯一的封顶是用户可配的**单曲上限**
 *    一部作品里单曲体积跨度极大（MP3 十几 MB，WAV/FLAC 实测到 92MB），
 *    上限太小会让大曲子永远缓存不完整（次次重新走网络），
 *    太大则意味着在一次播放里多花流量。没有普适最优值，所以交给用户。
 * ```
 *
 * ## 为什么"块长截断"也删了
 *
 * 它原本是为了"在起始阶段按剩余量截断 `read()` 的 64KB 块"。
 * 删掉它是安全的：`appendPart` 是**追加写**，写多少字节就长多少 ——
 * 多写一两块（最多 64KB 级别）不会越过上限，也不会破坏 `.part` 的连续性。
 * 为此保留一个"阶段"概念不划算。
 *
 * ## 为什么用 object
 *
 * 同时只会播一首，且"当前这一首"是唯一的上下文 ——
 * 传一个记账对象给代理只会多一层间接，没有收益。
 */
internal object TrackCacheLedger {

    private var id: String? = null
    private var cap: Long = 0L
    private var spent: Long = 0L

    /**
     * 开始一首曲子的记账。
     *
     * ⚠️ 换曲时**必须**调用：不重置的话上一首的已写量会算到这一首头上，
     * 这一首会一上来就被判定"已达上限"而**完全不再缓存**
     * （表现为第二首起缓存永远是 0）。
     *
     * @param capBytes 单曲上限；来自用户设置，所以改完设置下一首就生效
     */
    @Synchronized
    fun startFor(trackId: String, capBytes: Long) {
        id = trackId
        cap = capBytes.coerceAtLeast(0L)
        spent = 0L
    }

    /**
     * **一次性地**把某个资源"认领"为当前曲目。
     *
     * 代理在处理每条 HTTP 连接时都要问一句"这是不是当前这一首"：
     *  - 是 → 沿用已有记账（播放器可能重连开多条连接，不能每条连接都重置，
     *    否则上限会被反复重置，等于上限失效）
     *  - 不是（还没 startFor，或换了资源）→ 新建一份
     */
    @Synchronized
    fun claimFor(trackId: String, capBytes: Long) {
        if (id == trackId) return
        startFor(trackId, capBytes)
    }

    /** 这一首还能再缓存多少字节；已达上限返回 0 */
    @Synchronized
    fun remaining(): Long = (cap - spent).coerceAtLeast(0L)

    /** 记下已写入的字节数 */
    @Synchronized
    fun record(written: Long) {
        if (written > 0) spent += written
    }

    /** 已写入字节数（排查"为什么只缓存了几 MB"时看它） */
    @Synchronized
    fun spentBytes(): Long = spent

    /** 当前上限（日志里要说清封顶是多少） */
    @Synchronized
    fun capBytes(): Long = cap

    @Synchronized
    fun clear() {
        id = null
        cap = 0L
        spent = 0L
    }
}
