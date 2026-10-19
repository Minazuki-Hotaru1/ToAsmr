package com.asmrone.app.data.cache

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * 收藏类数据的本地 JSON 缓存目录。
 *
 * 为什么用**文件**而不是 SharedPreferences：收藏内容是一部作品一条的列表，
 * 收藏多的用户可能上千条。SharedPreferences 是整串读进内存再整串写回，
 * 上千条时主线程会出现明显卡顿；而且它的写入不是原子的（写一半掉电会留下坏 JSON）。
 *
 * 这里用零依赖的做法：写临时文件 → `renameTo` 覆盖正式文件。
 * 同目录内的 rename 在 Android 上是原子的，所以**任何时刻读到的都是完整的一份**。
 *
 * 存的是**投影后的精简字段**（见 [CachedWork]），不是完整 `Work` ——
 * 接口返回的完整作品带 `i18n` 标签翻译表，每部约 4.8KB，
 * 只留列表要显示的几个字段后每部约 250 字节，**小 20 倍**。
 */
internal object CacheFiles {

    private const val DIR = "asmrone_sync_cache"

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /**
     * 把逻辑 key 映射成安全的文件名。
     *
     * key 里含 `|`、`:`、主机名、用户名等，不能直接当文件名（Windows/Android 都有限制，
     * 而且用户名可能含奇怪字符），所以统一取 SHA-256 前 32 位十六进制。
     * 原始 key 会写在文件内容里，便于排查。
     */
    private fun fileNameFor(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder()
        for (i in 0 until 16) hex.append("%02x".format(digest[i]))
        return "$hex.json"
    }

    private fun fileFor(context: Context, key: String): File = File(dir(context), fileNameFor(key))

    /** 读原始 JSON 文本；不存在或读失败都返回 null（**当作没有缓存**，绝不让坏数据把界面搞崩） */
    fun read(context: Context, key: String): String? = runCatching {
        val f = fileFor(context, key)
        if (!f.exists()) null else f.readText(Charsets.UTF_8).takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * 原子写入。
     *
     * 先写 `.tmp`，再 rename 覆盖。这样即使写到一半进程被杀，
     * 正式文件仍然是上一份完整数据 —— 不会出现"读到半个 JSON"。
     */
    fun write(context: Context, key: String, json: String): Boolean = runCatching {
        val target = fileFor(context, key)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(json, Charsets.UTF_8)
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    }.getOrDefault(false)

    fun delete(context: Context, key: String): Boolean = runCatching {
        fileFor(context, key).delete()
    }.getOrDefault(false)

    /**
     * 清掉全部同步缓存。
     *
     * 用在两个地方：切换服务器主机、以及缓存格式升级（见 [SCHEMA_VERSION]）。
     * 之所以整目录删而不是逐个 key：这里的文件全是我们自己的，没有别的用途。
     */
    fun clearAll(context: Context): Boolean = runCatching {
        dir(context).listFiles()?.forEach { it.delete() }
        true
    }.getOrDefault(false)

    /** 缓存目录当前占用字节数（用于设置页展示） */
    fun sizeBytes(context: Context): Long = runCatching {
        dir(context).listFiles()?.sumOf { it.length() } ?: 0L
    }.getOrDefault(0L)

    // ───────────────────────── 结构版本与迁移 ─────────────────────────

    private const val PREFS = "asmrone_sync_cache_meta"
    private const val KEY_SCHEMA = "schema_version"

    /**
     * 判断是否需要执行缓存迁移。**纯函数**，便于单测。
     *
     * @param stored 上次记录的版本；0 表示"从未记录过"（全新安装或旧版本升上来）
     * @param current 当前代码的版本
     * @param hasCacheFiles 缓存目录里是否已有文件
     */
    internal fun shouldMigrate(stored: Int, current: Int, hasCacheFiles: Boolean): Boolean {
        if (stored == current) return false
        // 从未记录版本 + 目录为空 = 全新安装：没什么可清的
        if (stored == 0 && !hasCacheFiles) return false
        return true
    }

    /**
     * 启动时校准缓存结构版本；不一致就**作废旧缓存并重置同步记账**。
     *
     * 为什么必须显式做这件事：旧 JSON 会被 [SyncCacheStore] 按版本号忽略，
     * 但"今天已自动对齐"的记账还在 —— 当天自动同步会被闸门挡下，
     * 收藏页就会空白到第二天。所以这里两件事一起做。
     *
     * @return 是否真的执行了迁移
     */
    fun ensureSchema(context: Context): Boolean = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getInt(KEY_SCHEMA, 0)
        val hasFiles = dir(context).listFiles()?.isNotEmpty() == true
        val migrate = shouldMigrate(stored, SCHEMA_VERSION, hasFiles)
        if (migrate) {
            clearAll(context)
            // 记账也要清：否则"今天已经对齐过"会让旧缓存作废后的当天不再拉取
            com.asmrone.app.data.prefs.AppPrefs.clearAutoSyncDays()
        }
        prefs.edit().putInt(KEY_SCHEMA, SCHEMA_VERSION).apply()
        migrate
    }.getOrDefault(false)

    /**
     * 缓存格式版本。
     *
     * 只要 [CachedWork] 的字段发生不兼容变化就要 +1 ——
     * 否则旧版本写下的 JSON 被新版本读出来会缺字段，静默产生错误数据。
     *
     * ## 为什么必须靠这个版本、而不是"下次刷新自然就更新了"
     *
     * 同步缓存是**条件请求**的本地副本：内容没变时服务端回 304，本地**不会重写**。
     * 所以给缓存加了字段之后，旧 JSON 会一直缺字段 —— 下拉刷新多少次都拿不到
     * （请求带 If-None-Match，304 直接命中）。+1 会让 [SyncCacheStore] 把旧文件
     * 视为不存在，下一次同步自然变成无条件请求，把带新字段的内容重新写一遍。
     *
     * 版本历史：
     * - 1：初版
     * - 2（2026-10）：[CachedWork] 追加声优名 `vas`（收藏卡片要显示声优）
     * - 3（2026-10）：[CachedWork] 追加年龄分级 `nsfw` / `age`
     *   （否则还原出来的作品永远是"全年龄"，整个收藏夹会被误标）
     */
    const val SCHEMA_VERSION = 3
}

/**
 * 「交给外部 App 打开」用的文件落盘目录。
 *
 * ## 为什么与 [CacheFiles] 分开
 *
 * [CacheFiles] 存的是**我们自己读的**内部数据（收藏投影、搜索字典），
 * 放在 `filesDir` 下、文件名是 SHA-256。而这里存的是**要给外部 App 看的**
 * 文件：路径必须被 `FileProvider` 的 `file_paths.xml` 声明出去，
 * 文件名也最好是可读的（外部阅读器的标题栏会显示它）。
 *
 * 两者混在一起的话，就得把 `filesDir` 整个暴露给外部 App —— 没必要。
 *
 * ## 为什么放 cacheDir
 *
 * 这些文件是可再生的（重新下载即可），系统在空间紧张时可以自行清理。
 * 放 cacheDir 正是表达这个语义。
 */
internal object ExternalOpenFiles {

    private const val DIR = "external_open"

    /** 供 FileProvider 声明的子目录（与 `res/xml/file_paths.xml` 必须一致） */
    fun dir(context: Context): File =
        File(context.cacheDir, DIR).apply { if (!exists()) mkdirs() }

    /**
     * 落盘一个待外部打开的文件。
     *
     * 文件名取 SHA-256 前 16 字节 —— **不直接用原名**：原名含日文、空格、
     * `♪` 等字符，虽然多数文件系统能存，但 `FileProvider` 的 URI 编码与
     * 部分外部 App 的处理都容易出问题。保留扩展名让系统能判类型。
     */
    fun write(context: Context, key: String, extension: String, bytes: ByteArray): File? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder()
        for (i in 0 until 16) hex.append("%02x".format(digest[i]))
        val ext = extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        val target = File(dir(context), "$hex$ext")
        if (target.exists() && target.length() == bytes.size.toLong()) return@runCatching target

        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (target.exists()) target.delete()
        if (tmp.renameTo(target)) target else null
    }.getOrNull()

    /** 目录占用字节数（可与 [CacheFiles.sizeBytes] 一起展示） */
    fun sizeBytes(context: Context): Long = runCatching {
        dir(context).listFiles()?.sumOf { it.length() } ?: 0L
    }.getOrDefault(0L)
}
