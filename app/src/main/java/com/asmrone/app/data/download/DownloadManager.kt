package com.asmrone.app.data.download

import android.content.Context
import com.asmrone.app.data.model.Track
import com.asmrone.app.data.model.Work
import com.asmrone.app.data.repo.AsmrRepository
import com.asmrone.app.player.AudioController
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 下载调度：把"选了哪些文件"变成磁盘上的文件 + 清单。
 *
 * ## 一次只下一个作品
 *
 * 多个作品同时下只会互相抢带宽、把进度条都拖成龟速，而且 `MediaCodec`/代理那边
 * 对并发也不友好。所以用一把 [Mutex] 串行执行；**同一作品内部的多个文件也是串行的**
 * （`Range` 续传按文件独立记录，并行没有收益）。
 *
 * ## 进度怎么算
 *
 * 逐字节回调太吵（64KB 一次 → 每秒几十上百次重组），所以：
 *  - 写磁盘：真实字节；
 *  - 报进度：**至少间隔 [PROGRESS_INTERVAL_MS]** 才推一次，且每完成一个文件必推；
 *  - 总长度未知（作品里缺 `size`）时退化成"已完成文件数 / 总文件数"。
 *
 * ## 取消与续传
 *
 * [cancel] 取消协程 → `downloadToFile` 把已完成部分留在 `.part` 里。再次 [enqueue]
 * 同一作品时，已存在的正式文件直接跳过，`.part` 会被 `Range` 续上 —— 所以"下到一半
 * 没网了"只需要再点一次下载，不会从头再来。
 *
 * ## 清单何时写
 *
 * 每完成一个文件就写一次清单（原子写）。这样即使进程被杀，已下好的文件也都记在账上，
 * 下载页能立刻显示出来。清单里的树是**整棵树**（不是只留已下载的），详情页靠它
 * 与文件集合求交来"只显示已下载的内容"。
 */
object DownloadManager {

    enum class Status { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

    data class Task(
        val workId: Int,
        val title: String,
        val coverUrl: String?,
        val totalFiles: Int = 0,
        val doneFiles: Int = 0,
        val bytesTotal: Long = 0L,
        val bytesDone: Long = 0L,
        val status: Status = Status.QUEUED,
        val error: String? = null,
        /** 这一批存到哪儿（排队时定下来，中途改全局开关不影响它） */
        val storage: DownloadStorage = DownloadStorage.APP
    ) {
        /** 0f..1f；总量未知时按文件数估 */
        val progress: Float
            get() = when {
                bytesTotal > 0L -> (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
                totalFiles > 0 -> (doneFiles.toFloat() / totalFiles).coerceIn(0f, 1f)
                else -> 0f
            }

        val isActive: Boolean get() = status == Status.QUEUED || status == Status.RUNNING
    }

    /** 下载页卡片要显示的东西：作品 + 统计 */
    data class DownloadedWork(
        val work: Work,
        val host: String,
        val fileCount: Int,
        val bytes: Long,
        val savedAt: Long,
        val storage: DownloadStorage = DownloadStorage.APP
    ) {
        /** 同一部作品在两个目录各有一份时，列表里是两张卡，key 不能只用 id */
        val key: String get() = "${storage.name}:${work.id}"
    }

    private const val PROGRESS_INTERVAL_MS = 200L

    private val _tasks = MutableStateFlow<Map<Int, Task>>(emptyMap())

    /** 正在下载 / 刚结束的任务（下载页顶部进度区） */
    val tasks: StateFlow<Map<Int, Task>> = _tasks.asStateFlow()

    private val _library = MutableStateFlow<List<DownloadedWork>>(emptyList())

    /** 已下载作品（下载页卡片网格，按保存时间倒序；App 内与本地两份都列出来） */
    val library: StateFlow<List<DownloadedWork>> = _library.asStateFlow()

    /** 一次性提示（"已保存到本地/保存失败"），界面消费后 [clearMessage] */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val jobs = HashMap<Int, Job>()

    /** 只用来判断「所有文件访问权限」，不要拿它做别的事 */
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        DownloadStore.init(context)
        scope.launch {
            LocalDownloadStore.rescan()
            refreshLibrary()
        }
    }

    /** 本地保存权限（界面在切换模式时也会问一次，避免下到一半才失败） */
    fun canWriteLocal(): Boolean {
        val ctx = appContext ?: return false
        return LocalDownloadStore.hasAccess(ctx)
    }

    /**
     * 排队下载 [paths] 这些文件（树里的 `path`）。
     *
     * 已存在的文件不会重下；已下载的文件在清单里累加，不会丢掉上次下的内容。
     *
     * @param paths 只接受**叶子文件**路径（文件夹由调用方展开成叶子）
     * @param storage 落到 App 私有目录还是公共目录；排队后不再变化
     */
    fun enqueue(
        work: Work,
        tree: List<Track>,
        paths: Collection<String>,
        storage: DownloadStorage = DownloadStorage.APP
    ) {
        val wanted = paths.toSet()
        if (wanted.isEmpty()) return
        val leaves = tree.flattenLeaves().filter { it.path in wanted }
        if (leaves.isEmpty()) return

        // 已经在下同一个作品：忽略重复点击（避免两个协程抢同一个 .part）
        synchronized(jobs) {
            if (jobs[work.id]?.isActive == true) return
        }

        _tasks.update { current ->
            current + (work.id to Task(
                workId = work.id,
                title = work.title,
                coverUrl = work.coverUrl(AsmrRepository.host),
                totalFiles = leaves.size,
                bytesTotal = leaves.sumOf { it.size ?: 0L },
                status = Status.QUEUED,
                storage = storage
            ))
        }

        if (storage == DownloadStorage.LOCAL) {
            // 写公共目录前必须拿到「所有文件访问权限」，没有就直接标失败，
            // 让界面去引导授权，而不是下到一半才发现写不进去
            if (!canWriteLocal()) {
                val reason = "没有存储权限，无法保存到本地文件夹"
                setStatus(work.id) {
                    it.copy(status = Status.FAILED, error = reason)
                }
                // 失败也要说出来：这条任务的错误在下载页可见，同时弹一次提示
                // （用户在详情页点的下载，根本没打开过下载页）
                _message.value = reason
                return
            }
            scope.launch { LocalDownloadStore.prewarm() }
        }

        val job = scope.launch {
            gate.withLock { runDownload(work, tree, leaves, storage) }
        }
        synchronized(jobs) { jobs[work.id] = job }
        job.invokeOnCompletion { synchronized(jobs) { jobs.remove(work.id) } }
    }

    fun cancel(workId: Int) {
        val job = synchronized(jobs) { jobs[workId] } ?: return
        job.cancel(CancellationException("已取消下载"))
    }

    /** 删除 App 内那份下载（公共目录里的文件是用户的，App 不碰） */
    fun remove(workId: Int) {
        cancel(workId)
        scope.launch {
            DownloadStore.remove(workId)
            _tasks.update { it - workId }
            refreshLibrary()
        }
    }

    /**
     * 把一条**已经结束**的任务从列表里划掉（失败/取消之后用户点「移除」）。
     *
     * 失败的任务会一直留在 [tasks] 里，好让下载页把错误说清楚；但不能让它永远挂着。
     */
    fun dismiss(workId: Int) {
        synchronized(jobs) { if (jobs[workId]?.isActive == true) return }
        _tasks.update { it - workId }
    }

    /**
     * 把 App 内那份下载复制到公共目录（「另存到本地」）。
     *
     * 复制而不是移动：App 内那份可能正在播放，也可能被"离线详情"依赖；
     * 用户只是想同时在文件管理器里有一份。
     */
    fun copyToLocal(workId: Int) {
        if (!canWriteLocal()) {
            // 没有权限就别去 mkdirs 撞墙：给一句能对得上的提示，界面据此引导授权
            _message.value = "没有存储权限，无法另存到本地文件夹"
            return
        }
        scope.launch {
            val ok = LocalDownloadStore.copyFromApp(workId)
            _message.value = if (ok) "已保存到本地文件夹" else "保存失败，请检查本地文件夹与存储权限"
            if (ok) refreshLibrary()
        }
    }

    /**
     * 重新扫描磁盘，刷新下载页列表。
     *
     * **只列 App 内那份**：存到公共目录的内容是用户自己的文件 —— 给文件管理器、
     * 给别的播放器用，App 不建立"我的下载"这层关系，也不提供删除。
     * 唯一还用它的是"已经存过就别再下一遍"的判断（见 [DownloadSources]）。
     */
    fun refreshLibrary() {
        scope.launch {
            val local = DownloadStore.loadAll().mapNotNull { manifest -> toDownloaded(manifest) }
            _library.value = local.sortedByDescending { it.savedAt }
        }
    }

    private fun toDownloaded(manifest: DownloadManifest): DownloadedWork? {
        val json = manifest.workJson ?: return null
        val work = runCatching { Work.fromJson(JSONObject(json), manifest.host) }.getOrNull()
            ?: return null
        return DownloadedWork(
            work = work,
            host = manifest.host,
            fileCount = manifest.fileCount,
            bytes = manifest.totalBytes,
            savedAt = manifest.savedAt,
            storage = manifest.storage
        )
    }

    // ─────────────────────────── 实际下载 ───────────────────────────

    private suspend fun runDownload(
        work: Work,
        tree: List<Track>,
        leaves: List<Track>,
        storage: DownloadStorage
    ) {
        // 两套目录的 API 形状不一样（App 内用哈希文件名、本地用原目录结构），
        // 这里收成四个函数，下面的下载主循环就不用到处 if
        val local = storage == DownloadStorage.LOCAL
        suspend fun loadManifest(): DownloadManifest? =
            if (local) LocalDownloadStore.load(work.id) else DownloadStore.load(work.id)
        suspend fun saveManifest(m: DownloadManifest) {
            if (local) LocalDownloadStore.save(m, work.title, work.sourceId) else DownloadStore.save(m)
        }
        fun existingFile(path: String): File? =
            if (local) {
                // 本地这一档不靠清单判断：用户手动放进去的文件也算「已经下过」
                LocalDownloadStore.existingFileAt(work.id, path, work.title, work.sourceId)
            } else {
                DownloadStore.localFileFor(work.id, path)
            }
        fun targetFile(path: String): File =
            if (local) {
                LocalDownloadStore.fileFor(work.id, work.title, path, work.sourceId)
            } else {
                DownloadStore.fileFor(work.id, path)
            }

        var manifest = loadManifest()
            ?: DownloadManifest.empty(work.id, AsmrRepository.host, null, tree, storage)

        // 之前的清单里已经有作品 JSON 就沿用，避免为此多发一个请求
        if (manifest.workJson == null) {
            manifest = manifest.copy(workJson = AsmrRepository.rawWorkJson(work.id))
        }

        // 树可能变了（服务端加了新文件），顺手用这次的完整树更新清单：
        // 详情页的"继续下载"要靠它列出未下载的内容
        if (tree.isNotEmpty()) manifest = manifest.copy(tree = tree)

        setStatus(work.id) { it.copy(status = Status.RUNNING) }

        // 进度只按**这一批**算：清单里可能还有上一批已下完的文件，
        // 把它们算进分子会让进度条一开始就冲到 100%
        val leafPaths = leaves.map { it.path }.toSet()
        var doneFiles = manifest.files.keys.count { it in leafPaths }
        var bytesDone = manifest.files.entries.filter { it.key in leafPaths }.sumOf { it.value.bytes }
        val knownTotal = leaves.sumOf { it.size ?: 0L }

        try {
            leaves.forEach { node ->
                val already = existingFile(node.path)
                if (already != null && already.length() > 0) {
                    // 上次已经下完：直接记账（清单可能落后于磁盘）
                    if (manifest.files[node.path] == null) {
                        doneFiles += 1
                        bytesDone += already.length()
                    }
                    manifest = manifest.withFile(
                        DownloadedFile(path = node.path, fileName = already.name, bytes = already.length(), durationSec = node.durationSec)
                    )
                    return@forEach
                }
                val url = node.mediaStreamUrl.orEmpty().ifBlank { node.mediaDownloadUrl.orEmpty() }
                if (url.isBlank() && node.hash.isNullOrBlank()) {
                    // 没有下载地址的节点（服务端偶发缺字段）跳过，不让整批失败
                    doneFiles += 1
                    return@forEach
                }
                val target = targetFile(node.path)
                var lastEmit = 0L
                val baseDone = bytesDone - (manifest.files[node.path]?.bytes ?: 0L)
                val written = AsmrRepository.downloadToFile(node, target) { fileDone, _ ->
                    val now = System.currentTimeMillis()
                    if (now - lastEmit >= PROGRESS_INTERVAL_MS || fileDone <= 0L) {
                        lastEmit = now
                        val total = if (knownTotal > 0L) knownTotal else 0L
                        setStatus(work.id) { task ->
                            task.copy(
                                bytesDone = baseDone + fileDone,
                                bytesTotal = total,
                                doneFiles = doneFiles,
                                status = Status.RUNNING
                            )
                        }
                    }
                }
                bytesDone = baseDone + written
                doneFiles += 1
                manifest = manifest.withFile(
                    DownloadedFile(path = node.path, fileName = target.name, bytes = written, durationSec = node.durationSec)
                )
                // 每个文件落定就写一次清单：进程被杀也不丢账
                saveManifest(manifest)
                setStatus(work.id) { task ->
                    task.copy(
                        doneFiles = doneFiles,
                        bytesDone = bytesDone,
                        bytesTotal = if (knownTotal > 0L) knownTotal else 0L,
                        status = Status.RUNNING
                    )
                }
            }

            saveManifest(manifest)
            setStatus(work.id) {
                it.copy(
                    status = Status.DONE,
                    doneFiles = leaves.size,
                    bytesDone = bytesDone,
                    bytesTotal = if (knownTotal > 0L) knownTotal else bytesDone
                )
            }
            refreshLibrary()
            // 正在播的这轨如果刚下好，下次换轨/重开就会走本地文件，这里不用打断播放
            AudioController.persistSession()
        } catch (e: CancellationException) {
            setStatus(work.id) { it.copy(status = Status.CANCELLED) }
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: "下载失败"
            setStatus(work.id) {
                it.copy(status = Status.FAILED, error = reason)
            }
            // 用户在详情页点了下载就回到浏览，不会盯着下载页 —— 失败必须主动说出来
            _message.value = "「${work.title}」下载失败：$reason"
        }
    }

    private fun setStatus(workId: Int, transform: (Task) -> Task) {
        _tasks.update { current ->
            val task = current[workId] ?: return@update current
            current + (workId to transform(task))
        }
    }

    /** 递归取出所有叶子文件（跳过文件夹） */
    private fun List<Track>.flattenLeaves(out: MutableList<Track> = ArrayList()): List<Track> {
        forEach { node ->
            if (node.children.isEmpty()) out.add(node) else node.children.flattenLeaves(out)
        }
        return out
    }
}
