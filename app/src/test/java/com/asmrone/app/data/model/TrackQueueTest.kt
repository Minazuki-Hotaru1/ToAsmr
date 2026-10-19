package com.asmrone.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放队列收窄到"当前曲目所在的文件夹"。
 *
 * 这两条规则都有明确的现实来源，所以测试直接按它们命名：
 *  1. 跨文件夹的一律不进队列（跨章 / 跨语言版本不该被当成"下一首"）；
 *  2. `.mp4` 的 `type` 在接口里是 `audio`，必须靠扩展名排除。
 */
class TrackQueueTest {

    private fun audio(path: String, type: String = "audio") = Track(
        title = path.substringAfterLast('/'),
        type = type,
        mediaStreamUrl = "https://example.test/${path.replace('/', '_')}",
        path = path
    )

    @Test
    fun `只收同一文件夹下的音轨`() {
        val list = listOf(
            audio("mp3/第01話/01_a.mp3"),
            audio("mp3/第01話/02_b.mp3"),
            audio("mp3/第02話/01_c.mp3"),
            audio("mp3/第01話/extra/01_d.mp3") // 子目录也算不同文件夹
        )

        val queue = list.sameFolderQueue("mp3/第01話/01_a.mp3")

        assertEquals(
            listOf("mp3/第01話/01_a.mp3", "mp3/第01話/02_b.mp3"),
            queue.map { it.path }
        )
    }

    @Test
    fun `mp4 等视频即使 type 是 audio 也要排除`() {
        val list = listOf(
            audio("mp3/第01話/01_a.mp3"),
            audio("mp3/第01話/02_b.mp4"),
            audio("mp3/第01話/03_c.mkv"),
            audio("mp3/第01話/04_d.mp3")
        )

        val queue = list.sameFolderQueue("mp3/第01話/01_a.mp3")

        assertEquals(
            listOf("mp3/第01話/01_a.mp3", "mp3/第01話/04_d.mp3"),
            queue.map { it.path }
        )
        assertTrue(queue.none { it.path.endsWith(".mp4") })
    }

    @Test
    fun `同目录的图片 字幕 文本 不进队列`() {
        val list = listOf(
            audio("w/01_a.mp3"),
            audio("w/cover.jpg", type = "image"),
            audio("w/01_a.lrc", type = "text"),
            audio("w/readme.txt", type = "text"),
            audio("w/02_b.mp3")
        )

        val queue = list.sameFolderQueue("w/01_a.mp3")

        assertEquals(listOf("w/01_a.mp3", "w/02_b.mp3"), queue.map { it.path })
    }

    @Test
    fun `根目录下的音轨也算同一文件夹`() {
        val list = listOf(
            audio("01_a.mp3"),
            audio("02_b.mp3"),
            audio("sub/03_c.mp3")
        )

        val queue = list.sameFolderQueue("02_b.mp3")

        assertEquals(listOf("01_a.mp3", "02_b.mp3"), queue.map { it.path })
    }

    @Test
    fun `当前曲目不在列表里时返回空表`() {
        val list = listOf(audio("mp3/第01話/01_a.mp3"))

        assertTrue(list.sameFolderQueue("mp3/第09話/01_z.mp3").isEmpty())
        assertTrue(list.sameFolderQueue("").isEmpty())
    }

    @Test
    fun `传入视频路径时返回空表而不是退化成整张列表`() {
        val list = listOf(
            audio("mp3/01_a.mp3"),
            audio("mp3/02_b.mp4")
        )

        // 视频不是音频队列的合法起点：宁可不播，也不要悄悄从第 0 首开始放
        assertTrue(list.sameFolderQueue("mp3/02_b.mp4").isEmpty())
    }
}
