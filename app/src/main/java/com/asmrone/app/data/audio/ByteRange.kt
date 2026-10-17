package com.asmrone.app.data.audio

/**
 * 解析 `Range: bytes=...` 请求头。
 *
 * 纯函数，单独放在这里是因为它**必须**被单测覆盖：代理一旦把 Range 解析错，
 * 返回的 `Content-Range` 就对不上，播放器会报错或跳到错误位置 ——
 * 而这种 bug 在真机上表现为"某些音频播到一半卡死"，很难定位。
 *
 * ## 支持的形态（以及为什么只支持这些）
 *
 * | 请求 | 解析结果 |
 * |---|---|
 * | `bytes=0-` | from=0, to=null |
 * | `bytes=1024-` | from=1024, to=null |
 * | `bytes=0-1023` | from=0, to=1023 |
 * | `bytes=-1024`（最后 1024 字节）| **null**（见下） |
 * | `bytes=0-99,200-299`（多重）| **null** |
 *
 * 末尾式刻意不支持：它的语义是"最后 N 字节"，要先知道总长度，
 * 而我们在收到请求时就要转发上游，那时还没有总长度；播放器做顺序播放
 * 也不会用它（那是下载工具续传的用法）。多重区间同理 ——
 * 正确实现要返回 `multipart/byteranges`，代价远大于收益。
 *
 * 返回 null 时调用方按"整个文件"处理，行为依然正确，只是少了 `206` 的语义。
 */
object ByteRange {

    fun parse(header: String?): Range? {
        val raw = header?.trim().orEmpty()
        if (raw.length < 7) return null
        if (!raw.startsWith("bytes=", ignoreCase = true)) return null

        val spec = raw.substring(6).trim()
        if (spec.contains(',')) return null

        val dash = spec.indexOf('-')
        if (dash <= 0) return null

        val fromText = spec.substring(0, dash).trim()
        val toText = spec.substring(dash + 1).trim()

        val from = fromText.toLongOrNull() ?: return null
        if (from < 0) return null

        val to = if (toText.isEmpty()) null else (toText.toLongOrNull() ?: return null)
        if (to != null && to < from) return null

        return Range(from, to)
    }

    /** 请求的区间；`to` 为 null 表示"到文件末尾" */
    data class Range(val from: Long, val to: Long?) {
        val length: Long? get() = to?.let { it - from + 1 }

        /** 用于日志与测试的可读形式 */
        val text: String get() = "$from-" + (to?.toString() ?: "")
    }
}
