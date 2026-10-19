package com.asmrone.app.data.model

/**
 * 播放队列的作用域：**当前曲目所在的那一个文件夹**。
 *
 * ## 为什么要收窄
 *
 * 音声作品的目录本身就是章节（`mp3/第01話/`、`mp3/第02話/` …）。若把整部作品的
 * 音频串成一条队列，"下一首"经常跳到完全不同的场景 —— 跨章、跨语言版本、
 * 甚至跳到 SE / 素材目录，顺序播放反而打断沉浸。按文件夹收窄后，
 * "下一首"天然等于"这一章里的下一轨"，播完自然停在本章末尾。
 *
 * ## 为什么要按扩展名排除视频
 *
 * 服务端把 `.mp4` 的 `type` 也标成 `audio`（见 [TrackKind]），所以只按
 * `isAudio` 过滤会把视频混进队列：真走到那一轨，音频内核打不开，表现为
 * 直接报错或者无声。因此这里用 [TrackKind.of] 判 `== AUDIO`，
 * 视频 / 图片 / 字幕 / 文本 / PDF 全部排除在外。
 *
 * @param currentPath 当前（即将播放的）那一轨的 [Track.path]
 * @return 同目录下的音频，保持原有顺序；[currentPath] 不在其中时返回空表
 *         （调用方据此不启动播放，而不是退化成"整张列表"）
 */
fun List<Track>.sameFolderQueue(currentPath: String): List<Track> {
    if (currentPath.isBlank()) return emptyList()
    val folder = currentPath.substringBeforeLast('/', "")
    val queue = filter { track ->
        track.path.substringBeforeLast('/', "") == folder &&
            TrackKind.of(track) == TrackKind.AUDIO
    }
    // 当前这一轨自己必须在队列里，否则 startIndex 无从算起。
    // 传进来的若是视频/字幕等，说明调用方用错了 —— 返回空表让它暴露出来。
    return if (queue.any { it.path == currentPath }) queue else emptyList()
}
