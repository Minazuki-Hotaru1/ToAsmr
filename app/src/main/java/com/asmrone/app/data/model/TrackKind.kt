package com.asmrone.app.data.model

/**
 * 详情页文件树里一个节点「点开之后该干什么」。
 *
 * ## 为什么需要它
 *
 * 服务端给的 `type` 只有 5 个值（`folder` / `audio` / `image` / `text` / `other`），
 * 而**实际要分派的行为有 7 种**。差异来自两处实测事实：
 *
 *  1. **`.mp4` 的 `type` 是 `audio`，但它是视频**。实测单个文件可达 1.8GB、
 *     码率 4.5Mbps，文件名形如「第2-1章」「販促PV動画」。按 `type` 分派会把它
 *     交给音频播放器（黑屏或直接失败），所以必须**结合扩展名**判断。
 *  2. **`text` 里混着三种东西**：`.txt` 是说明文、`.vtt` 是字幕、`.lrc` 是歌词。
 *     实测比例约为 txt 155 / vtt 104 / lrc 15。它们的正确呈现方式不同
 *     （字幕/歌词是"跟随播放"的，说明文是"阅读"的）。
 *
 * 所以这里把"服务端 type + 扩展名"合成一个明确的行为枚举，
 * 让界面层只需 `when (kind)`，不必再各自拼条件。
 *
 * ## 各类型的处理方式（与用户确认过的方案一致）
 *
 * | kind | 处理 |
 * |---|---|
 * | [FOLDER] | 展开/收起 |
 * | [AUDIO] | 现有音频播放器（顺序播放 + 后台播放） |
 * | [VIDEO] | 内置视频页（Media3，流式，不下载） |
 * | [IMAGE] | 内置图片查看页（同目录左右滑动 + 双指缩放） |
 * | [PDF] | 下载后交外部阅读器（自带能力不足以替代系统 PDF 阅读器） |
 * | [SUBTITLE] | 内置文本查看（本次先按纯文本；跟随播放留后续） |
 * | [TEXT] | 内置文本查看（带编码切换） |
 * | [UNSUPPORTED] | 明确提示，不留"点了没反应" |
 */
enum class TrackKind {
    FOLDER,
    AUDIO,
    VIDEO,
    IMAGE,
    PDF,
    SUBTITLE,
    TEXT,
    UNSUPPORTED;

    /** 点了会进内置页面（相对外部 App 而言） */
    val isInApp: Boolean
        get() = this == AUDIO || this == VIDEO || this == IMAGE || this == SUBTITLE || this == TEXT

    /** 需要下载到本地才能处理（外部阅读器要 content:// URI） */
    val needsDownload: Boolean get() = this == PDF

    companion object {

        /** 视频扩展名。`.mp4` 的 `type` 实测是 `audio`，所以不能只信 type */
        private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "wmv", "flv", "ts")

        /** 字幕/歌词：都是"带时间轴的文本"，与说明文区分开 */
        private val SUBTITLE_EXTS = setOf("vtt", "lrc", "srt", "ass", "ssa")

        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif")

        /** 取小写扩展名；没有扩展名返回空串 */
        fun extensionOf(title: String): String =
            title.substringAfterLast('.', "")
                .takeIf { it.isNotEmpty() && it.length <= 5 && it.all { c -> c.isLetterOrDigit() } }
                ?.lowercase()
                .orEmpty()

        /**
         * 判定一个节点的行为。
         *
         * 判定顺序很关键：
         *  1. 文件夹（服务端的 `type` 或"有子节点"）
         *  2. **扩展名优先于 `type`** —— 因为 `audio` 里混着 `.mp4` 视频
         *  3. 再按 `type` 分派
         *  4. 全都不认识时给 [UNSUPPORTED]，绝不静默
         */
        fun of(track: Track): TrackKind {
            if (track.isFolder) return FOLDER

            val ext = extensionOf(track.title)

            // ② 扩展名优先：`.mp4` 的 type 是 audio，但它是视频
            if (ext in VIDEO_EXTS) return VIDEO
            if (ext in SUBTITLE_EXTS) return SUBTITLE
            if (ext == "pdf") return PDF

            // ③ 按服务端 type
            return when {
                track.isAudio -> AUDIO
                track.isImage || ext in IMAGE_EXTS -> IMAGE
                track.isText -> TEXT
                // ④ 未知：既不是已知 type，也没有可识别的扩展名
                else -> UNSUPPORTED
            }
        }
    }
}

/**
 * 一个可在内置页面打开的文件。
 *
 * 图片与文本都用它：前者进图片查看页，后者进文本查看页。
 * 只带"打开它需要什么"，不带任何界面状态 —— 界面状态由各自的 ViewModel 持有。
 */
data class OpenableFile(
    /** 树里的标题（含扩展名），用于顶栏显示 */
    val title: String,
    val kind: TrackKind,
    /** 取内容的地址（图片/视频是 CDN 直连；文本是 API 代理） */
    val url: String,
    val sizeBytes: Long = 0L,
    /**
     * 同目录下的同类文件 —— 图片查看页用它做"左右滑动切换"。
     *
     * 由调用方在树里按**同一个父目录**筛出来，避免查看页自己去理解树结构。
     */
    val siblings: List<OpenableFile> = emptyList()
) {
    /** 在 [siblings] 里的下标；找不到则 0 */
    val indexInSiblings: Int
        get() = siblings.indexOfFirst { it.url == url }.coerceAtLeast(0)

    val sizeText: String
        get() = when {
            sizeBytes <= 0L -> ""
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
            else -> "%.1f MB".format(sizeBytes / 1024.0 / 1024.0)
        }
}
