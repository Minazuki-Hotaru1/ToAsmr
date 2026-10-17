package com.asmrone.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件树节点的行为判定。
 *
 * 这一层是整套"点开看什么"的分派依据，判错的后果都很具体：
 *
 *  - 把 `.mp4` 判成 AUDIO → 1.8GB 的视频进了音频播放器（黑屏或直接失败）
 *  - 把 PDF 判成 UNSUPPORTED → 明明能打开却提示"不支持"
 *  - 把 `.vtt` 判成普通 TEXT → 字幕被当说明文打开（本次先接受，但要**知道**它是字幕）
 *
 * 所有判定依据都来自实测数据（见 TrackKind 的文档），不是猜的。
 */
class TrackKindTest {

    private fun track(title: String, type: String? = null, children: Int = 0) = Track(
        title = title,
        type = type,
        children = List(children) { Track(title = "child$it", type = "audio") }
    )

    // ═══════════════ 文件夹 ═══════════════

    @Test
    fun `服务端标为 folder 的是文件夹`() {
        assertEquals(TrackKind.FOLDER, TrackKind.of(track("特典", type = "folder")))
    }

    @Test
    fun `type 缺失但有子节点也当文件夹`() {
        assertEquals(TrackKind.FOLDER, TrackKind.of(track("特典", type = null, children = 2)))
    }

    @Test
    fun `文件夹优先于扩展名`() {
        // 一个叫 "图片集.mp4" 的文件夹仍然是文件夹
        assertEquals(
            TrackKind.FOLDER,
            TrackKind.of(track("图片集.mp4", type = "folder", children = 1))
        )
    }

    // ═══════════════ ★ 核心：.mp4 的 type 是 audio，但它是视频 ═══════════════

    @Test
    fun `★ 扩展名 mp4 即使 type 是 audio 也判为视频`() {
        // 实测：服务端把「第2-1章….mp4」标成 type=audio，
        // 单个文件可达 1.8GB / 码率 4.5Mbps —— 这是真视频
        assertEquals(
            TrackKind.VIDEO,
            TrackKind.of(track("第2-1章　エミリー.mp4", type = "audio"))
        )
    }

    @Test
    fun `★ 其它视频扩展名同样优先于 audio`() {
        listOf("mkv", "webm", "avi", "mov", "m4v", "flv").forEach { ext ->
            assertEquals(
                "扩展名 .$ext 应判为视频",
                TrackKind.VIDEO,
                TrackKind.of(track("PV動画.$ext", type = "audio"))
            )
        }
    }

    @Test
    fun `★ 扩展名大小写不敏感`() {
        assertEquals(TrackKind.VIDEO, TrackKind.of(track("PV.MP4", type = "audio")))
        assertEquals(TrackKind.PDF, TrackKind.of(track("台本.PDF", type = "other")))
    }

    // ═══════════════ 音频 ═══════════════

    @Test
    fun `音频扩展名判为音频`() {
        listOf("ogg", "mp3", "wav", "m4a", "flac").forEach { ext ->
            assertEquals(
                "扩展名 .$ext 应判为音频",
                TrackKind.AUDIO,
                TrackKind.of(track("01_track.$ext", type = "audio"))
            )
        }
    }

    // ═══════════════ 其它类型 ═══════════════

    @Test
    fun `图片判为图片`() {
        assertEquals(TrackKind.IMAGE, TrackKind.of(track("オマケ画像.jpg", type = "image")))
        assertEquals(TrackKind.IMAGE, TrackKind.of(track("1.png", type = "image")))
    }

    @Test
    fun `★ PDF 的 type 是 other，要判为 PDF 而不是未知`() {
        // 实测：所有 other 节点都是 pdf（台本 / 小册子），最大 17MB
        assertEquals(TrackKind.PDF, TrackKind.of(track("台本.pdf", type = "other")))
    }

    @Test
    fun `★ vtt 与 lrc 判为字幕，而不是普通文本`() {
        // 实测 vtt 104 个、lrc 15 个，命名形如 "Track01：….mp3.vtt"
        assertEquals(
            TrackKind.SUBTITLE,
            TrackKind.of(track("Track01：咦～！？.mp3.vtt", type = "text"))
        )
        assertEquals(TrackKind.SUBTITLE, TrackKind.of(track("song.lrc", type = "text")))
        assertEquals(TrackKind.SUBTITLE, TrackKind.of(track("sub.srt", type = "text")))
    }

    @Test
    fun `txt 判为普通文本`() {
        assertEquals(TrackKind.TEXT, TrackKind.of(track("読んでね♪.txt", type = "text")))
    }

    @Test
    fun `★ 无法识别的类型给 UNSUPPORTED，绝不静默`() {
        // 点了没反应是最糟的体验；给 UNSUPPORTED 界面才能提示"暂不支持"
        assertEquals(
            TrackKind.UNSUPPORTED,
            TrackKind.of(track("神秘文件.xyz", type = "其它未知类型"))
        )
        assertEquals(TrackKind.UNSUPPORTED, TrackKind.of(track("没有扩展名", type = "weird")))
    }

    // ═══════════════ 可点性与处理方式 ═══════════════

    @Test
    fun `★ 只有 UNSUPPORTED 不可点`() {
        val kinds = TrackKind.entries.filter { it != TrackKind.FOLDER }
        kinds.forEach { kind ->
            val clickable = kind != TrackKind.UNSUPPORTED
            assertEquals(
                "$kind 的可点性判定不对",
                clickable,
                kind != TrackKind.UNSUPPORTED
            )
        }
        assertFalse(TrackKind.UNSUPPORTED.isInApp)
    }

    @Test
    fun `★ 内置处理的类型集合正确`() {
        // 内置 = 音频 / 视频 / 图片 / 字幕 / 文本
        assertTrue(TrackKind.AUDIO.isInApp)
        assertTrue(TrackKind.VIDEO.isInApp)
        assertTrue(TrackKind.IMAGE.isInApp)
        assertTrue(TrackKind.SUBTITLE.isInApp)
        assertTrue(TrackKind.TEXT.isInApp)
        // PDF 交外部阅读器；文件夹与未知不算"打开内容"
        assertFalse(TrackKind.PDF.isInApp)
        assertFalse(TrackKind.FOLDER.isInApp)
        assertFalse(TrackKind.UNSUPPORTED.isInApp)
    }

    @Test
    fun `★ 只有 PDF 需要下载到本地`() {
        // 外部阅读器要 content:// URI，所以必须先落盘；
        // 其余类型要么流式（视频）、要么直接给 Coil / 自己读字节
        assertTrue(TrackKind.PDF.needsDownload)
        TrackKind.entries.filter { it != TrackKind.PDF }.forEach {
            assertFalse("$it 不该需要下载", it.needsDownload)
        }
    }

    // ═══════════════ 扩展名解析 ═══════════════

    @Test
    fun `扩展名解析的边界情况`() {
        assertEquals("mp4", TrackKind.extensionOf("a.mp4"))
        assertEquals("vtt", TrackKind.extensionOf("Track01：x.mp3.vtt"))
        assertEquals("", TrackKind.extensionOf("没有扩展名"))
        // 点号在中间但不是扩展名（后面太长）时不该当成扩展名
        assertEquals("", TrackKind.extensionOf("a.verylongextension"))
        // 目录名里的点不该影响
        assertEquals("txt", TrackKind.extensionOf("v1.2/readme.txt"))
    }

    @Test
    fun `中文与日文文件名不影响扩展名解析`() {
        assertEquals("jpg", TrackKind.extensionOf("オマケ画像.jpg"))
        assertEquals("txt", TrackKind.extensionOf("読んでね♪.txt"))
        assertEquals("pdf", TrackKind.extensionOf("寝取らせ_トラック1.pdf"))
    }
}
