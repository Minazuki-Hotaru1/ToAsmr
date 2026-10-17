package com.asmrone.app.data.cache

import android.content.Context
import org.json.JSONObject

/**
 * 云端的本地镜像 —— 带 ETag 的 JSON 缓存。
 *
 * ## 定位：**性能与节流的载体**，冲突时以云端为准
 *
 * 本地缓存有两个作用：
 *  1. 进入页面时**立刻**把界面铺出来，不必等网络；
 *  2. 让自动核对可以低频 —— **本次会话**首次打开这份数据时才核对一次
 *     （见 [SyncGate]），之后的进入都用这份本地内容。
 *
 * 但**一旦真的发出请求，就以服务端为准**：服务端回 200 就全量替换本地
 * （哪怕内容变成空的 —— 例如网页端把所有作品都删了），回 304 才继续用本地。
 * 所以这里**不存在"本地与服务端不一致时偏向本地"**的情况。
 *
 * 也正因为"问了就听服务端的"，这里**不需要 TTL / 过期时间**：
 * 数据一致性不靠"本地数据会自己过期"，而靠"该问的时候问一次、问了以云端为准"。
 *
 * ## ETag 的语义边界（重要）
 *
 * [CacheEntry.etag] 是**发请求时那个条件下的整份内容**的 ETag。
 * 例如标记页是按某个筛选（`filter=listening`）拿的，
 * 这个 ETag 只对"同样的 host + 账号 + 筛选"有效。
 * 所以缓存 key 必须把这几个维度都编进去（见 [SyncKeys]），
 * 否则会拿 A 筛选的 ETag 去校验 B 筛选的内容 —— 那样 304 就变成假阴性了。
 *
 * 另有一个已知的保守之处：`/api/review` 一页只有 20 条，而列表可能有多页。
 * 我们只对**第 1 页**保存 ETag；第 1 页 304 时认为缓存仍可用（不重新下载），
 * 但这并不保证后面几页没变。刻意接受的取舍 ——
 * 换来的是"翻过页的列表不会每次都重新下载"。
 */
object SyncCacheStore {

    /**
     * 缓存键的构造。
     *
     * **必须带 host 与账号**：同一台设备切换账号后，两人的收藏内容完全不同，
     * 键里少了任何一维都会让 B 看到 A 的收藏 —— 那是正确性问题，不只是优化问题。
     */
    object SyncKeys {
        fun collection(host: String, user: String, filter: String?): String =
            "collection|$host|$user|${filter ?: "all"}"

        fun playlists(host: String, user: String): String = "playlists|$host|$user"

        fun playlistWorks(host: String, user: String, playlistId: String): String =
            "playlistWorks|$host|$user|$playlistId"

        fun playlistMeta(host: String, user: String, playlistId: String): String =
            "playlistMeta|$host|$user|$playlistId"

        /** 待补写操作（本地已改、云端未收到）—— 按 host + 账号分文件 */
        fun pendingWrites(host: String, user: String): String = "pending|$host|$user"
    }

    private lateinit var appContext: Context

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
    }

    /** 带元信息的缓存条目 */
    data class CacheEntry<T>(
        val value: T,
        /** 服务端 ETag；为 null 表示该端点不返回 ETag（就退化成无条件请求） */
        val etag: String?,
        /** 上次成功同步的时间戳（仅用于界面展示"x 分钟前同步"） */
        val syncedAt: Long,
        /**
         * 本地内容已改、但**尚未被服务端确认**（写请求失败）。
         *
         * 置位时下次核对会跳过 `If-None-Match` 改用无条件请求：
         * 因为此刻"本地改了、服务端没改"，拿旧 ETag 去问会错误地收到 304，
         * 从而把一个失败的写入永久固化下来。
         */
        val dirty: Boolean = false
    ) {
        /** 距上次同步过了多久（毫秒） */
        fun ageMs(now: Long = System.currentTimeMillis()): Long = now - syncedAt
    }

    // ─────────────────────────── 读写 ───────────────────────────

    fun <T> read(key: String, codec: CacheCodec<T>): CacheEntry<T>? {
        testStorage?.get(key)?.let { raw ->
            return runCatching { parseEntry(raw, codec) }.getOrNull()
        }
        testRead?.let { return runCatching { it(key) }.getOrNull()?.let { raw ->
            runCatching { parseEntry(raw, codec) }.getOrNull()
        } }
        if (!::appContext.isInitialized) return null
        val raw = CacheFiles.read(appContext, key) ?: return null
        return runCatching { parseEntry(raw, codec) }.getOrNull()
    }

    /**
     * 解析一条缓存。
     *
     * 刻意**不用** `?: return null` 的短路写法：那样会让推断出的类型变成
     * `CacheEntry<T>?` 与 `Nothing` 的交叉类型，和 `runCatching` 的泛型参数不兼容
     * （编译器会报 "Return type mismatch ... & Any"）。
     * 这里全部改成显式的 if-else / `?:` 表达式。
     */
    private fun <T> parseEntry(raw: String, codec: CacheCodec<T>): CacheEntry<T>? {
        val o = JSONObject(raw)
        if (o.optInt("v", 0) != CacheFiles.SCHEMA_VERSION) return null
        val body = o.optJSONObject("body") ?: return null
        val value: T = codec.decode(body) ?: return null
        return CacheEntry(
            value = value,
            etag = o.optString("etag", "").takeIf { it.isNotBlank() },
            syncedAt = o.optLong("syncedAt", 0L),
            dirty = o.optBoolean("dirty", false)
        )
    }

    fun <T> write(key: String, codec: CacheCodec<T>, entry: CacheEntry<T>): Boolean {
        val json = JSONObject().apply {
            put("v", CacheFiles.SCHEMA_VERSION)
            put("key", key)
            put("etag", entry.etag ?: "")
            put("syncedAt", entry.syncedAt)
            put("dirty", entry.dirty)
            put("body", codec.encode(entry.value))
        }
        // 先看有没有内存替身，再回落到真实存储 ——
        // 这个顺序很重要：appContext 未初始化时必须走替身，否则测试什么也写不进去
        testStorage?.let { it[key] = json.toString(); return true }
        testWrite?.let { return runCatching { it(key, json.toString()) }.getOrDefault(false) }
        if (!::appContext.isInitialized) return false
        return CacheFiles.write(appContext, key, json.toString())
    }

    /**
     * 只更新时间戳 / ETag / dirty 标记，不动内容 —— **304 时用它**。
     *
     * 这是最常见的一条路径：内容没变，只是把"上次校验时间"往前推、清掉 dirty。
     */
    fun <T> touch(
        key: String,
        codec: CacheCodec<T>,
        etag: String? = null,
        dirty: Boolean = false,
        syncedAt: Long = System.currentTimeMillis()
    ): Boolean {
        val existing = read(key, codec) ?: return false
        return write(
            key,
            codec,
            existing.copy(etag = etag ?: existing.etag, syncedAt = syncedAt, dirty = dirty)
        )
    }

    fun delete(key: String) {
        testStorage?.let { it.remove(key); return }
        testDelete?.let { runCatching { it(key) }; return }
        if (!::appContext.isInitialized) return
        CacheFiles.delete(appContext, key)
    }

    /** 切换服务器主机时用：整个目录清掉 */
    fun clearAll() {
        if (::appContext.isInitialized) CacheFiles.clearAll(appContext)
    }

    fun sizeBytes(): Long =
        if (::appContext.isInitialized) CacheFiles.sizeBytes(appContext) else 0L

    // ─────────────────────────── 单测支持 ───────────────────────────
    //
    // 存储层本身没有复杂逻辑（键哈希 + 原子写盘），值得单测的是**基于它的策略**：
    // 比如"AsmrRepository 在本地没有完整基线时，绝不能凭一次写入伪造出一份列表"。
    // 那条规则需要能读回缓存来断言，所以这里给一个内存替身。

    /** 仅测试：用内存实现替换真实文件存储 */
    fun installForTest() {
        testRead = null
        testWrite = null
        testDelete = null
        testStorage = HashMap()
    }

    /** 仅测试：卸载内存替身，恢复真实存储 */
    fun uninstallForTest() {
        testStorage = null
        testRead = null
        testWrite = null
        testDelete = null
    }

    /** 仅测试：当前内存替身里的全部键 */
    fun testKeys(): Set<String> = testStorage?.keys?.toSet().orEmpty()

    /** 仅测试：读取内存替身里的原始 JSON */
    fun testRaw(key: String): String? = testStorage?.get(key)

    /** 仅测试：写入内存替身（用于预置"已有基线"这类前置条件） */
    fun testPut(key: String, raw: String) {
        testStorage?.put(key, raw)
    }

    /** 仅测试：把一条条目序列化成缓存格式，便于预置数据 */
    fun <T> encodeForTest(codec: CacheCodec<T>, entry: CacheEntry<T>): String =
        JSONObject().apply {
            put("v", CacheFiles.SCHEMA_VERSION)
            put("key", "")
            put("etag", entry.etag ?: "")
            put("syncedAt", entry.syncedAt)
            put("dirty", entry.dirty)
            put("body", codec.encode(entry.value))
        }.toString()

    private var testStorage: MutableMap<String, String>? = null
    private var testRead: ((String) -> String?)? = null
    private var testWrite: ((String, String) -> Boolean)? = null
    private var testDelete: ((String) -> Unit)? = null
}
