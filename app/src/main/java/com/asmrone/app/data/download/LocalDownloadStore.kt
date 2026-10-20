package com.asmrone.app.data.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import com.asmrone.app.data.model.Work
import com.asmrone.app.data.prefs.AppPrefs
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 「保存到本地」的存储：把下载内容放进公共存储，文件管理器里看得见。
 *
 * ## 目录结构
 * ```
 * /storage/emulated/0/ToAsmr/
 *   └─ 作品名 [RJ01681251]/           ← 一人一个目录，目录名带 RJ 号便于人肉辨认
 *        ├─ .toasmr.json             ← 清单（隐藏文件，别和音频混在一起）
 *        ├─ 01_本編/01_トラック.mp3   ← 保留作品原来的目录结构与文件名
 *        └─ 01_本編/封面.jpg
 * ```
 *
 * ## 为什么和 [DownloadStore] 两套
 *
 * App 私有目录里的文件名是哈希（怕中文/全角/长度出问题），用户看不见；
 * 这里的文件名必须**原样保留**——用户就是要在文件管理器里认名字、
 * 丢进别的播放器。所以两边的「路径 → 文件」映射完全不同，各存一份清单。
 *
 * ## 同步查询
 *
 * [localFileFor] 要给播放器在主线程调，不能读磁盘：清单加载/保存时顺手建好
 * `path -> File` 的索引，之后只做一次 `isFile` 判断。索引没预热（冷启动后的头几秒）
 * 时返回 null，播放器会退回在线流，不会卡。
 */
object LocalDownloadStore {

    private const val MANIFEST_NAME = ".toasmr.json"
    private const val MAX_SEGMENT = 60

    /** 目录名里不允许出现的字符（公共存储上还要给别的 App/电脑看，尽量保守） */
    private val INVALID = Regex("""[\\/:*?"<>|\u0000-\u001F]""")

    private val lock = Any()

    /** 测试用：把根目录指到临时目录（生产环境为 null，始终读 [AppPrefs.localDownloadDir]） */
    private var overrideDir: File? = null

    /** workId -> 作品目录（扫描时填，[save] 时补） */
    private val dirs = HashMap<Int, File>()

    /** workId -> (树内路径 -> 真实文件)，供 [localFileFor] 同步查询 */
    private val index = HashMap<Int, MutableMap<String, File>>()

    /** 已解析的清单（含"确认没有"的 null） */
    private val cache = HashMap<Int, DownloadManifest?>()

    fun baseDir(): File = overrideDir ?: File(AppPrefs.localDownloadDir.value)

    /** 测试入口：换根目录并清掉索引；传 null 恢复正常行为 */
    fun initForTest(dir: File?) {
        synchronized(lock) {
            overrideDir = dir
            dirs.clear()
            index.clear()
            cache.clear()
        }
        dir?.mkdirs()
    }

    /**
     * 本地保存权限（逐个任务的目录选择都靠它判断）。
     *
     * API 30+ 只有「所有文件访问权限」一条路；API 28/29 用的是普通的
     * 运行时权限 `WRITE_EXTERNAL_STORAGE`，需要在界面里 [requestPermission]。
     */
    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    /** API 28/29 要弹运行时权限对话框，而不是跳设置页 */
    fun requiresRuntimePermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R

    /** 运行时权限的名字（只在 [requiresRuntimePermission] 为真时用） */
    fun runtimePermission(): String = android.Manifest.permission.WRITE_EXTERNAL_STORAGE

    /** 跳「所有文件访问权限」的授权页（API 30+ 专用；低版本走运行时权限） */
    fun permissionIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
        }

    /**
     * 把系统文件选择器（`ACTION_OPEN_DOCUMENT_TREE`）返回的 tree Uri 映射回文件系统路径。
     *
     * 落盘走的是 `java.io.File`（拿到「所有文件访问权限」后能原样保留作品内部的目录结构），
     * 所以系统选择器只用来**挑位置**，并不使用 SAF 的文档树写入。
     *
     * `DocumentsContract.getTreeDocumentId(uri)` 给出 `<volumeId>:<相对路径>`：
     *  - `primary:Download/ToAsmr` → `/storage/emulated/0/Download/ToAsmr`
     *  - `1A2B-3C4D:Music`          → `/storage/1A2B-3C4D/Music`
     *
     * 网盘类 provider 没有对应的文件系统路径（或对应的卷不存在），返回 null 让调用方提示重选。
     */
    fun pathFromTreeUri(uri: Uri): File? {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return null
        val volume = docId.substringBefore(':', "")
        val relative = docId.substringAfter(':', "")
        val root = when {
            volume.isEmpty() || volume.equals("primary", ignoreCase = true) ->
                Environment.getExternalStorageDirectory()
            else -> File("/storage", volume)
        }
        if (!root.isDirectory) return null
        // 选中的一定有已存在的路径；映射不出来就说明这家 provider 不是本机存储
        val dir = if (relative.isEmpty()) root else File(root, relative)
        return dir.takeIf { it.isDirectory }
    }

    /** 启动/改目录后扫描一遍：只认带清单的作品目录 */
    suspend fun rescan() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            dirs.clear()
            index.clear()
            cache.clear()
        }
        val base = baseDir()
        if (!base.isDirectory) return@withContext
        val found = base.listFiles()
            ?.filter { it.isDirectory && File(it, MANIFEST_NAME).isFile }
            .orEmpty()
        for (dir in found) {
            // 身份认清单里的 workId：目录名里的 `[RJ243448]` 是真 RJ 号而不是 workId，
            // 按名字索引会把这部作品登记到另一个 id 上（isDownloaded 就查不到了）
            val manifest = readManifest(dir) ?: continue
            val id = manifest.workId.takeIf { it > 0 } ?: parseWorkId(dir.name) ?: continue
            synchronized(lock) {
                dirs[id] = dir
                index[id] = manifest.files.entries.associate { (path, file) ->
                    path to File(dir, file.fileName)
                }.toMutableMap()
                cache[id] = manifest
            }
        }
    }

    /** 预热：播放器/详情页要用时不必再碰磁盘 */
    suspend fun prewarm() {
        if (synchronized(lock) { cache.isEmpty() }) rescan()
    }

    fun isDownloaded(workId: Int): Boolean = synchronized(lock) { workId in dirs }

    /** 已下载作品（下载页列表用） */
    suspend fun loadAll(): List<DownloadManifest> {
        prewarm()
        val ids = synchronized(lock) { dirs.keys.toList() }
        return ids.mapNotNull { load(it) }
    }

    fun cached(workId: Int): DownloadManifest? = synchronized(lock) { cache[workId] }

    suspend fun load(workId: Int): DownloadManifest? {
        synchronized(lock) { if (cache.containsKey(workId)) return cache[workId] }
        val manifest = withContext(Dispatchers.IO) {
            val dir = synchronized(lock) { dirs[workId] } ?: findDir(workId)
            dir?.let { readManifest(it) }?.copy(storage = DownloadStorage.LOCAL)
        }
        synchronized(lock) {
            cache[workId] = manifest
            if (manifest != null) {
                val dir = dirs[workId] ?: findDir(workId)
                if (dir != null) dirs[workId] = dir
                index[workId] = manifest.files.entries.associate { (path, file) ->
                    path to File(dir ?: baseDir(), file.fileName)
                }.toMutableMap()
            }
        }
        return manifest
    }

    /** 播放用：已下载则返回真实文件；索引没建好时返回 null（调用方退回在线流） */
    fun localFileFor(workId: Int, path: String): File? {
        if (workId <= 0 || path.isBlank()) return null
        val file = synchronized(lock) { index[workId]?.get(path) } ?: return null
        return file.takeIf { it.isFile && it.length() > 0 }
    }

    /**
     * 下载前问一句「这个文件是不是已经在本地文件夹里了」。
     *
     * 和 [localFileFor] 的区别：这里**不要求清单和索引齐全** —— 用户可能自己把文件
     * 拷进来、也可能把 `.toasmr.json` 删了，只要目标路径上有个非空文件就当已经下过，
     * 直接跳过，不再重复占空间。
     *
     * 目录认不出时按 [title] + [sourceId] 推出它应该在的名字（只算路径，不建目录）：
     * 清单丢了以后，名字才是唯一的线索。
     */
    fun existingFileAt(
        workId: Int,
        path: String,
        title: String? = null,
        sourceId: String? = null
    ): File? {
        if (workId <= 0 || path.isBlank()) return null
        synchronized(lock) { index[workId]?.get(path) }
            ?.let { return it.takeIf { file -> file.isFile && file.length() > 0 } }
        val known = synchronized(lock) { dirs[workId] }
            ?: findDir(workId)
            ?: title?.takeIf { it.isNotBlank() }?.let { expectedDir(workId, it, sourceId) }
            ?: return null
        return File(known, sanitizeRelative(path)).takeIf { it.isFile && it.length() > 0 }
    }

    /** 目标文件（下载前算路径；父目录顺手建好） */
    fun fileFor(workId: Int, title: String, path: String, sourceId: String? = null): File {
        val manifest = synchronized(lock) { cache[workId] }
        val dir = synchronized(lock) { dirs[workId] } ?: workDir(workId, title, sourceId)
        // 清单里已经记过这条路径：沿用记录（避免重名被加 (2)，导致续传落到新文件）
        manifest?.files?.get(path)?.let { return File(dir, it.fileName) }
        val relative = sanitizeRelative(path)
        var target = File(dir, relative)
        var n = 2
        val occupied = synchronized(lock) { index[workId]?.values?.map { it.absolutePath }.orEmpty() }
        while (target.exists() && target.absolutePath !in occupied) {
            val ext = relative.substringAfterLast('.', "")
            val stem = if (ext.isEmpty()) relative else relative.dropLast(ext.length + 1)
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            target = File(dir, "$stem ($n)$suffix")
            n += 1
        }
        target.parentFile?.mkdirs()
        return target
    }

    suspend fun save(manifest: DownloadManifest, title: String, sourceId: String? = null) {
        // 写进公共目录的清单一律标成 LOCAL：调用方可能把 App 内那份的
        // manifest 直接传进来（另存），不修正会让下载页把同一份内容列两次
        val stamped = manifest.copy(storage = DownloadStorage.LOCAL)
        withContext(Dispatchers.IO) {
            val dir = synchronized(lock) { dirs[stamped.workId] }
                ?: workDir(stamped.workId, title, sourceId)
            writeManifest(dir, stamped)
            synchronized(lock) {
                dirs[stamped.workId] = dir
                index[stamped.workId] = stamped.files.entries.associate { (path, file) ->
                    path to File(dir, file.fileName)
                }.toMutableMap()
                cache[stamped.workId] = stamped
            }
        }
    }

    suspend fun remove(workId: Int): Boolean {
        val dir = synchronized(lock) { dirs[workId] } ?: withContext(Dispatchers.IO) { findDir(workId) }
        val ok = withContext(Dispatchers.IO) {
            dir?.let { if (it.exists()) it.deleteRecursively() else true } ?: true
        }
        synchronized(lock) {
            dirs.remove(workId)
            index.remove(workId)
            cache.remove(workId)
        }
        return ok
    }

    /** 把 App 内那份复制到公共目录（已有下载「另存到本地」用） */
    suspend fun copyFromApp(workId: Int): Boolean {
        val source = DownloadStore.load(workId) ?: return false
        val title = titleOf(source) ?: "作品 $workId"
        return withContext(Dispatchers.IO) {
            runCatching {
                var target = source.copy(storage = DownloadStorage.LOCAL)
                val dir = workDir(workId, title, sourceIdOf(source))
                for ((path, file) in source.files) {
                    val from = File(DownloadStore.workDir(workId), file.fileName)
                    if (!from.isFile) continue
                    val to = File(dir, sanitizeRelative(path))
                    to.parentFile?.mkdirs()
                    if (!to.isFile || to.length() != from.length()) {
                        from.inputStream().use { input ->
                            to.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    target = target.withFile(file.copy(fileName = to.relativeTo(dir).path))
                }
                writeManifest(dir, target)
                synchronized(lock) {
                    dirs[workId] = dir
                    index[workId] = target.files.entries.associate { (path, file) ->
                        path to File(dir, file.fileName)
                    }.toMutableMap()
                    cache[workId] = target
                }
                true
            }.getOrDefault(false)
        }
    }

    // ─────────────────────────── 路径与 IO ───────────────────────────

    /**
     * 作品目录：`作品名 [RJ243448]`（拿不到真 RJ 时退回 `作品名 [w1681251]`）。
     *
     * **目录名里的号码只是给人看的**：真正的身份认的是清单里的 `workId`，
     * 所以标题改了、RJ 号后补上了，也不会多出一个新目录。
     */
    fun workDir(workId: Int, title: String, sourceId: String? = null): File {
        synchronized(lock) { dirs[workId] }?.let { return it }
        findDir(workId)?.let {
            synchronized(lock) { dirs[workId] = it }
            return it
        }
        val base = baseDir()
        if (!base.exists()) base.mkdirs()
        val dir = expectedDir(workId, title, sourceId)
        if (!dir.exists()) dir.mkdirs()
        synchronized(lock) { dirs[workId] = dir }
        return dir
    }

    /** 作品目录该叫什么（纯计算，不建目录 —— 清单坏了时靠它认出老目录） */
    private fun expectedDir(workId: Int, title: String, sourceId: String? = null): File {
        val marker = sourceId?.trim()?.takeIf { it.startsWith("RJ", ignoreCase = true) } ?: "w$workId"
        return File(baseDir(), "${sanitizeSegment(title).ifBlank { "作品" }} [$marker]")
    }

    /**
     * 找已有目录：**先看清单里的 workId**（目录名里的号码可能是真 RJ，不是 workId），
     * 再退回按名字里的 `RJ…` / `w…` 数字匹配（清单坏了时还能找回目录）。
     */
    private fun findDir(workId: Int): File? {
        val entries = baseDir().listFiles()?.filter { it.isDirectory }.orEmpty()
        entries.firstOrNull { readManifest(it)?.workId == workId }?.let { return it }
        return entries.firstOrNull { parseWorkId(it.name) == workId }
    }

    /** 从目录名尾巴的 `[RJ123]` / `[w123]` 里取号（清单不可用时的退路） */
    private fun parseWorkId(name: String): Int? {
        val open = name.lastIndexOf('[')
        if (open < 0 || !name.endsWith("]")) return null
        val marker = name.substring(open + 1, name.length - 1)
        val digits = marker.dropWhile { !it.isDigit() }
        return digits.toIntOrNull()
    }

    private fun titleOf(manifest: DownloadManifest): String? =
        workOf(manifest)?.title

    private fun sourceIdOf(manifest: DownloadManifest): String? =
        workOf(manifest)?.sourceId

    private fun workOf(manifest: DownloadManifest): Work? {
        val json = manifest.workJson ?: return null
        return runCatching { Work.fromJson(JSONObject(json), manifest.host) }.getOrNull()
    }

    /** 树内路径（`a/b/c.mp3`）→ 保留层级但逐段清洗的相对路径 */
    private fun sanitizeRelative(path: String): String =
        path.split('/').filter { it.isNotBlank() }.joinToString("/") { sanitizeSegment(it) }

    private fun sanitizeSegment(raw: String): String {
        val cleaned = INVALID.replace(raw, "_").trim().trimEnd('.', ' ')
        if (cleaned.isEmpty()) return "未命名"
        if (cleaned.length <= MAX_SEGMENT) return cleaned
        // 太长就保留扩展名，截断主干
        val dot = cleaned.lastIndexOf('.')
        val ext = if (dot > 0 && cleaned.length - dot <= 8) cleaned.substring(dot) else ""
        return cleaned.take(MAX_SEGMENT - ext.length) + ext
    }

    private fun manifestFile(dir: File): File = File(dir, MANIFEST_NAME)

    private fun readManifest(dir: File): DownloadManifest? = runCatching {
        DownloadManifest.fromJson(JSONObject(manifestFile(dir).readText()))
    }.getOrNull()

    private fun writeManifest(dir: File, manifest: DownloadManifest) {
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, MANIFEST_NAME)
        val tmp = File(dir, "$MANIFEST_NAME.tmp")
        val text = manifest.toJson().toString()
        tmp.writeText(text)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }
}
