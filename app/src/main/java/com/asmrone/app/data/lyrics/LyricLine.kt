package com.asmrone.app.data.lyrics

/**
 * 一行带时间轴的歌词。
 *
 * 只有起始时间：当前歌词效果不再做逐字扫光，高亮只看“当前播放到哪一行”。
 * [translation] 允许为空；同时间戳的 LRC 第二行、VTT/ASS 的后续行会并到这里。
 */
data class LyricLine(
    val original: String,
    val translation: String,
    val startMs: Long
)
