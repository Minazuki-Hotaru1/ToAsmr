package com.asmrone.app.data.cache

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * 「一次性下载、长期使用」的**具名字典**的统一存储。
 *
 * ## 为什么抽出来
 *
 * 标签、声优、社团三种字典的持久化需求**完全相同**：
 *  - 按 **host** 分文件（不同镜像站的字典各自独立，同一台服务器所有账号共用）
 *  - 文件带**格式版本号**与 **kind 标记**（跨版本、跨字典类型都不会误读）
 *  - **原子写**（走 [CacheFiles]，先写 .tmp 再 rename）
 *  - 内存快照 + **冷启动自动补读**
 *  - 可替换的内存存储（单测用）
 *
 * 复制三份的话，"存了却读不回来"这类 bug 就有三处可能复发 ——
 * 而那个 bug 真的发生过（见 [tagsWithSnapshot] 的说明）。
 * 所以把机制收在这里，三种字典只提供 [EntryCodec]。
 *
 * ## 与 `SyncCacheStore` 的区别
 *
 * 那个是**账号相关**的云端镜像（键里带 host + 用户名），会随收藏变化而更新；
 * 这里是**全局静态资源**，只有用户手动点"更新"才会变。
 */
internal class IdDictionaryStore<T>(
    private val kind: String,
    private val codec: EntryCodec<T>
) {

    /** 一条条目的编解码；由各字典实现 */
    interface EntryCodec<T> {
        /** 把一条条目写进 JSON；返回 null 表示这条无效、跳过 */
        fun encode(value: T): JSONObject?

        /** 从 JSON 读回一条；返回 null 表示这条无效、跳过 */
        fun decode(json: JSONObject): T?
    }

    data class Snapshot<T>(
        val host: String,
        val entries: List<T>,
        val updatedAt: Long
    )

    /** 文件格式版本；结构变化时改它，旧文件会被当成无效并忽略 */
    private val formatVersion = 1

    private var appContext: Context? = null
    private val memory = AtomicReference<Snapshot<T>?>(null)

    // 仅测试
    private var testStorage: MutableMap<String, String>? = null
    private var testDiskReads: Int = 0

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun keyFor(host: String): String = "$kind|${host.trimEnd('/')}"

    /** 只读内存：有就给，**完全不碰磁盘** */
    fun peek(host: String): Snapshot<T>? =
        memory.get()?.takeIf { it.host == host.trimEnd('/') }

    /**
     * 取字典；内存里没有就**从磁盘补一次**。
     *
     * 这是那个真实 bug 的修复点：早期实现里 `load()` 定义了却没有任何调用点，
     * 于是数据在磁盘上、冷启动后却总是"没有字典"。
     * 把"内存没有就补读"收在读取路径里，调用方不必记得先 load。
     *
     * 代价是**每个进程最多一次**磁盘读 + JSON 解析，之后快照常驻内存。
     */
    fun entries(host: String): List<T> = snapshot(host)?.entries.orEmpty()

    fun snapshot(host: String): Snapshot<T>? {
        peek(host)?.let { return it }
        val loaded = load(host) ?: return null
        // load 内部已写内存；再确认 host 匹配，避免竞态下返回别的服务器那本
        return loaded.takeIf { it.host == host.trimEnd('/') }
    }

    fun updatedAt(host: String): Long? = snapshot(host)?.updatedAt

    fun save(host: String, entries: List<T>, now: Long = System.currentTimeMillis()): Boolean {
        val normalizedHost = host.trimEnd('/')

        val array = org.json.JSONArray()
        entries.forEach { entry ->
            codec.encode(entry)?.let { array.put(it) }
        }
        if (array.length() == 0) return false

        val root = JSONObject().apply {
            put("v", formatVersion)
            put("kind", kind)
            put("host", normalizedHost)
            put("updatedAt", now)
            put("entries", array)
        }

        val ok = writeRaw(normalizedHost, root.toString())
        if (ok) memory.set(Snapshot(normalizedHost, entries, now))
        return ok
    }

    fun load(host: String): Snapshot<T>? {
        val normalizedHost = host.trimEnd('/')
        val raw = readRaw(normalizedHost) ?: return null
        val snapshot = runCatching { parse(raw, normalizedHost) }.getOrNull() ?: return null
        memory.set(snapshot)
        return snapshot
    }

    private fun parse(raw: String, host: String): Snapshot<T>? {
        val root = JSONObject(raw)
        if (root.optInt("v", 0) != formatVersion) return null
        // kind 必须匹配：三种字典放在同一目录下，靠它避免互相误读
        if (root.optString("kind", "") != kind) return null
        if (root.optString("host", "") != host) return null

        val array = root.optJSONArray("entries") ?: return null
        val entries = buildList {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { o ->
                    codec.decode(o)?.let(::add)
                }
            }
        }
        if (entries.isEmpty()) return null

        return Snapshot(host, entries, root.optLong("updatedAt", 0L))
    }

    fun clear(host: String) {
        val normalizedHost = host.trimEnd('/')
        // 用"读一次再判"而不是 compareAndSet(peek(host), null)：
        // Snapshot 是 data class，compareAndSet 走的是**结构相等**，
        // 万一有另一份内容相同的快照就会被误判为"同一个"而清错。
        val current = memory.get()
        if (current == null || current.host == normalizedHost) memory.set(null)
        testStorage?.let { it.remove(keyFor(normalizedHost)); return }
        appContext?.let { CacheFiles.delete(it, keyFor(normalizedHost)) }
    }

    // ─────────────────────────── 存储访问（可替换，便于单测）───────────────────────────

    private fun readRaw(host: String): String? {
        testStorage?.let {
            testDiskReads++
            return it[keyFor(host)]
        }
        val ctx = appContext ?: return null
        return CacheFiles.read(ctx, keyFor(host))
    }

    private fun writeRaw(host: String, json: String): Boolean {
        // 不要在这里判 appContext —— 该不该用真实文件由 testStorage 决定，
        // 早期那句 `appContext ?: return false` 会让测试替身永远写不进去
        testStorage?.let { it[keyFor(host)] = json; return true }
        val ctx = appContext ?: return false
        return CacheFiles.write(ctx, keyFor(host), json)
    }

    // ─────────────────────────── 仅测试 ───────────────────────────

    fun installForTest() {
        testStorage = HashMap()
        testDiskReads = 0
        memory.set(null)
    }

    fun uninstallForTest() {
        testStorage = null
        testDiskReads = 0
        memory.set(null)
    }

    /** 丢内存快照、保留"磁盘"，用来模拟**进程重启** */
    fun simulateRestartForTest() {
        memory.set(null)
    }

    /** 把磁盘内容改成指定文本（造坏数据 / 版本不符 / kind 不符 / host 不符） */
    fun corruptForTest(host: String, raw: String) {
        testStorage?.put(keyFor(host.trimEnd('/')), raw)
    }

    /** 累计磁盘读次数（断言热路径不会反复读盘） */
    fun diskReadCountForTest(): Int = testDiskReads
}
