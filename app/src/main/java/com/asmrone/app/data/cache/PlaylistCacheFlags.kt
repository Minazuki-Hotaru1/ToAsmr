package com.asmrone.app.data.cache

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 每个收藏夹一个"**内容已缓存**"标识 —— 最兜底的那道判断。
 *
 * ## 为什么在"缓存文件存在"之外还要单独记一个标识
 *
 * 缓存文件能表达"现在磁盘上有没有这份内容"，但表达不了**意图与责任**：
 *
 *  - 文件可能被**外部清掉**（清缓存、系统回收、换设备），但业务上我们仍然知道
 *    "这个收藏夹的内容我们还没真正拿到过，第一次要收藏时必须先拿"；
 *  - 只写了一半的内容（分页中的第 1 页）在磁盘上**也是文件**，
 *    但它不是"完整的一份"，不能拿它做"就地插入一部作品"这种操作；
 *  - 反过来，一份"确定为空"的完整缓存（新建的收藏夹）在磁盘上是一个空列表，
 *    与"没缓存过"在文件层面很难区分（都可能是 0 条）。
 *
 * 所以用一个**独立的、显式的**标识来记录"这份内容是不是已经缓存好了"，
 * 由所有会改变它的操作去维护。谁改动了缓存，谁就更新标识。
 *
 * ## 兜底的作用
 *
 * 「加入收藏夹」这条路径在动手改本地之前先看这个标识：
 * 标识说"没缓存"→ **先把这份内容缓存下来，再收藏**。
 * 这样即使前面所有保证都失效了（用户在别处建了收藏夹、缓存被清、限额跳过……），
 * 也不会出现"手里的基线是残缺的，却往上插了一条作品"。
 *
 * ## 存储
 *
 * 按 host + 账号分文件（与其它缓存一致：换账号不该看到上一个人的状态），
 * 内容形如 `{"v":1,"cached":["<playlistId>", …]}`。
 * 只存**已缓存**的 id 集合；不在集合里就是"没缓存"。
 */
internal object PlaylistCacheFlags {

    private const val SCHEMA = 1
    private const val DIR = "asmrone_playlist_flags"

    /** 未初始化时只操作内存（避免任何调用点因为忘记 init 而崩） */
    private var rootDir: File? = null

    /** 内存兜底 + 文件命中缓存，减少每次判断都读盘 */
    private val memo = mutableMapOf<String, MutableSet<String>>()

    fun init(context: Context) {
        if (rootDir != null) return
        setRoot(File(context.applicationContext.filesDir, DIR))
    }

    /** 仅供测试：指定（或清空）存储目录 */
    internal fun initForTest(dir: File?) {
        setRoot(dir)
    }

    private fun setRoot(dir: File?) {
        rootDir = dir
        memo.clear()
        dir?.let { runCatching { it.mkdirs() } }
    }

    private fun keyFor(host: String, user: String): String = "$host|$user"

    private fun fileFor(host: String, user: String): File? {
        val root = rootDir ?: return null
        // 与 CacheFiles 同样的理由：host/用户名可能含不能做文件名的字符
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(keyFor(host, user).toByteArray(Charsets.UTF_8))
        val hex = buildString { for (i in 0 until 16) append("%02x".format(digest[i])) }
        return File(root, "$hex.json")
    }

    private fun load(host: String, user: String): MutableSet<String> {
        val key = keyFor(host, user)
        memo[key]?.let { return it }
        val restored = runCatching {
            val f = fileFor(host, user)
            if (f == null || !f.exists()) return@runCatching mutableSetOf<String>()
            val o = JSONObject(f.readText(Charsets.UTF_8))
            if (o.optInt("v", 0) != SCHEMA) return@runCatching mutableSetOf<String>()
            val arr = o.optJSONArray("cached") ?: JSONArray()
            buildSet {
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                }
            }.toMutableSet()
        }.getOrDefault(mutableSetOf())
        memo[key] = restored
        return restored
    }

    private fun save(host: String, user: String, cached: MutableSet<String>) {
        memo[keyFor(host, user)] = cached
        runCatching {
            val f = fileFor(host, user) ?: return@runCatching
            val json = JSONObject().apply {
                put("v", SCHEMA)
                put("cached", JSONArray().apply { cached.sorted().forEach { put(it) } })
            }
            // 原子写：先写 .tmp 再 rename，避免进程被杀时留下半个 JSON
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.toString(), Charsets.UTF_8)
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        }
    }

    /** 这份收藏夹的内容**是否已经缓存好**（可安全地就地增删） */
    fun isCached(host: String, user: String, playlistId: String): Boolean {
        if (host.isBlank() || user.isBlank() || playlistId.isBlank()) return false
        return playlistId in load(host, user)
    }

    /** 内容已经完整缓存下来（成功拉取 / 预置空缓存） */
    fun markCached(host: String, user: String, playlistId: String) {
        if (host.isBlank() || user.isBlank() || playlistId.isBlank()) return
        val set = load(host, user)
        if (set.add(playlistId)) save(host, user, set)
    }

    /** 内容缓存被作废（只剩残缺几页，或文件被删）→ 下次收藏前必须重新缓存 */
    fun markNotCached(host: String, user: String, playlistId: String) {
        if (host.isBlank() || user.isBlank() || playlistId.isBlank()) return
        val set = load(host, user)
        if (set.remove(playlistId)) save(host, user, set)
    }

    /** 收藏夹整个没了 → 忘掉它的标识 */
    fun forget(host: String, user: String, playlistId: String) = markNotCached(host, user, playlistId)

    /** 当前账号下所有"已缓存"的收藏夹 id（排查与测试用） */
    fun cachedIds(host: String, user: String): Set<String> =
        if (host.isBlank() || user.isBlank()) emptySet() else load(host, user).toSet()

    /** 切站 / 清缓存时全部清空 */
    fun clearAll() {
        memo.clear()
        runCatching { rootDir?.listFiles()?.forEach { it.delete() } }
    }
}
