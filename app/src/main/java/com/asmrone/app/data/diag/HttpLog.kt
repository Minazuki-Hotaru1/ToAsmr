package com.asmrone.app.data.diag

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量的网络请求日志（内存环形缓冲，不落盘）。
 *
 * ## 为什么需要它
 *
 * 排查"标记失败"这类问题时，最需要知道的是**请求到底发出去了什么**：
 * 哪个方法、哪个地址、**有没有带 Authorization 头**、服务端回了什么码。
 * 这些信息只存在于请求发生的那一刻，事后靠推演代码很容易得出错误结论
 * （我就在这件事上推错过两次）。
 *
 * 所以每次请求都记一条，并在「设置 → 关于」里给出入口，让用户能自己看到，
 * 也能直接复制给我 —— 比要一份 logcat 快得多。
 *
 * 刻意**不落盘**：里面可能含主机名与路径，长期留存没有必要；
 * 而且失败信息在 App 存活期间就够了。
 */
object HttpLog {

    private const val MAX_ENTRIES = 120

    enum class Level { REQUEST, RESPONSE, ERROR }

    data class Entry(
        val at: Long,
        val level: Level,
        val message: String
    )

    private val buffer = ArrayDeque<Entry>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun add(level: Level, message: String) {
        synchronized(buffer) {
            buffer.addLast(Entry(System.currentTimeMillis(), level, message))
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        }
    }

    fun request(message: String) = add(Level.REQUEST, message)
    fun response(message: String) = add(Level.RESPONSE, message)
    fun error(message: String) = add(Level.ERROR, message)

    /** 最近的记录，最新的在最后 */
    fun snapshot(): List<Entry> = synchronized(buffer) { buffer.toList() }

    fun clear() = synchronized(buffer) { buffer.clear() }

    /** 渲染成可直接复制/粘贴的文本 */
    fun dump(): String {
        val entries = snapshot()
        if (entries.isEmpty()) return "（暂无网络请求记录）"
        return buildString {
            appendLine("AsmrOne 网络日志（${entries.size} 条）")
            appendLine("时间        级别   内容")
            appendLine("-".repeat(60))
            entries.forEach { e ->
                append(timeFormat.format(Date(e.at)))
                append("  ")
                append(e.level.name.padEnd(8))
                append(e.message)
                appendLine()
            }
        }
    }

    /** 是否发生过错误（设置页用来提示"日志里有错误"） */
    fun hasErrors(): Boolean = snapshot().any { it.level == Level.ERROR }

    // ─────────────────── 调试期落盘 ───────────────────
    //
    // 只在 debug 构建里启用（见调用方）。为什么要它：内存环形缓冲只能
    // 在 App 内的「设置 → 关于」里看，**无法从外部读取** —— 而验证
    // 本地音频代理究竟收到了什么 Range 请求，需要把日志取出来分析。
    // 落盘后用 `adb exec-out run-as <包名> cat files/http_log.txt` 即可。
    //
    // release 构建不写：里面可能含主机名与路径，长期留存没有必要
    // （这也是这个类刻意不落盘的原因）。

    private var dumpFile: java.io.File? = null

    /** 由 Application 在 debug 构建下调用一次 */
    fun enableFileDump(file: java.io.File) {
        dumpFile = file
    }

    /**
     * 把当前日志写进文件（覆盖写；文件很小，开销可忽略）。
     *
     * 失败时**记一条 ERROR 再重试一次**：第一版这里是 `runCatching{}` 静默吞掉，
     * 结果"日志没落盘"这件事本身无从排查（外部只看到文件没更新）。
     * 现在失败会体现在下一次 dump 的内容里。
     */
    fun flushToFile() {
        val f = dumpFile ?: return
        val first = runCatching { f.writeText(dump(), Charsets.UTF_8) }
        if (first.isFailure) {
            add(Level.ERROR, "日志落盘失败：${first.exceptionOrNull()?.message}")
            runCatching { f.writeText(dump(), Charsets.UTF_8) }
        }
    }
}
