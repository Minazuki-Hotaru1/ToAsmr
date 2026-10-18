package com.asmrone.app.data.lyrics

import com.asmrone.app.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词解析器（LRC / VTT / SRT / ASS）的行为测试。
 *
 * ## 为什么要有这组测试
 *
 * 这里的解析器之前在真机上一进播放器就闪退 —— 但原因不是解析逻辑，
 * 而是**一条 ASS 正则在 Android 的 ICU 引擎上非法**（末尾未转义的 `}`），
 * 写在 `object` 的静态初始化里，于是变成 `ExceptionInInitializerError`。
 *
 * 桌面 JVM 的正则引擎不会报这个错，所以**任何纯 JVM 测试都不可能发现它**。
 * 那这次测试能守住什么？
 *
 *  - ASS 标签剥离改成手写扫描后，**行为**必须和原来一致（多级花括号、未闭合、连续标签）；
 *  - 各格式的时间轴解析不能因为换实现而退化；
 *  - 双语歌词"同一时间戳第二行当翻译"的约定要有回归保护。
 *
 * 而"别再让正则进静态字段"这条，只能靠代码约定 —— 见 `LyricsLoader` 顶部的注释。
 */
class LyricsLoaderTest {

    private fun parse(text: String, fileName: String) =
        LyricsLoader.parseForTest(text, fileName)

    // ═══════════════ LRC ═══════════════

    @Test
    fun `★ 基本 LRC 时间轴`() {
        val lines = parse(
            """
            [00:01.00]第一行
            [00:12.50]第二行
            [01:05.25]第三行
            """.trimIndent(),
            "a.lrc"
        )
        assertEquals(3, lines.size)
        assertEquals(1_000L, lines[0].startMs)
        assertEquals(12_500L, lines[1].startMs)
        assertEquals(65_250L, lines[2].startMs)
        assertEquals("第二行", lines[1].original)
    }

    @Test
    fun `★ 同一时间戳的第二行当作翻译`() {
        val lines = parse(
            """
            [00:05.00]こんにちは
            [00:05.00]你好
            """.trimIndent(),
            "a.lrc"
        )
        assertEquals(1, lines.size)
        assertEquals("こんにちは", lines.single().original)
        assertEquals("你好", lines.single().translation)
    }

    @Test
    fun `多个翻译行合并到一条`() {
        val lines = parse(
            """
            [00:01.00]原文
            [00:01.00]译一
            [00:01.00]译二
            """.trimIndent(),
            "a.lrc"
        )
        assertEquals("译一 / 译二", lines.single().translation)
    }

    @Test
    fun `逐字时间标签被剥掉`() {
        val lines = parse("[00:01.00]<00:01.00>あ<00:01.50>い", "a.lrc")
        assertEquals("あい", lines.single().original)
    }

    @Test
    fun `LRC 支持逗号毫秒与纯毫秒`() {
        val lines = parse(
            """
            [00:01.25]a
            [00:02.5]b
            """.trimIndent(),
            "a.lrc"
        )
        assertEquals(1_250L, lines[0].startMs)
        assertEquals(2_500L, lines[1].startMs)
    }

    @Test
    fun `没有时间戳的 LRC 行被忽略`() {
        val lines = parse(
            """
            [ar:某社团]
            [ti:标题]
            没有时间轴的说明文字
            [00:03.00]正文
            """.trimIndent(),
            "a.lrc"
        )
        assertEquals(1, lines.size)
        assertEquals("正文", lines.single().original)
    }

    @Test
    fun `LRC 按时间排序`() {
        val lines = parse(
            """
            [00:30.00]后
            [00:10.00]前
            """.trimIndent(),
            "a.lrc"
        )
        assertEquals(listOf("前", "后"), lines.map { it.original })
    }

    // ═══════════════ VTT / SRT ═══════════════

    @Test
    fun `★ VTT 基本窗口`() {
        val lines = parse(
            """
            WEBVTT

            00:00:01.000 --> 00:00:03.000
            一行

            00:00:04.500 --> 00:00:06.000
            二行
            """.trimIndent(),
            "a.vtt"
        )
        assertEquals(2, lines.size)
        assertEquals(1_000L, lines[0].startMs)
        assertEquals("一行", lines[0].original)
        assertEquals(4_500L, lines[1].startMs)
    }

    @Test
    fun `VTT 的 html 标签与实体被清理`() {
        val lines = parse(
            """
            00:00:01.000 --> 00:00:02.000
            <v 声优>你好 &amp; 再见</v>
            """.trimIndent(),
            "a.vtt"
        )
        assertEquals("你好 & 再见", lines.single().original)
    }

    @Test
    fun `★ SRT 的逗号毫秒`() {
        val lines = parse(
            """
            1
            00:00:02,500 --> 00:00:04,000
            第一句

            2
            00:00:05,000 --> 00:00:06,000
            第二句
            """.trimIndent(),
            "a.srt"
        )
        assertEquals(2, lines.size)
        assertEquals(2_500L, lines[0].startMs)
        assertEquals(5_000L, lines[1].startMs)
    }

    @Test
    fun `同一个窗口的多个内容行合成一条`() {
        val lines = parse(
            """
            00:00:01.000 --> 00:00:02.000
            原文
            译文
            """.trimIndent(),
            "a.vtt"
        )
        assertEquals(1, lines.size)
        assertEquals("原文", lines.single().original)
        assertEquals("译文", lines.single().translation)
    }

    @Test
    fun `带小时的三段式时间能解析`() {
        val lines = parse(
            """
            01:02:03.000 --> 01:02:04.000
            很久以后
            """.trimIndent(),
            "a.vtt"
        )
        assertEquals(((1 * 60L + 2) * 60L + 3) * 1000L, lines.single().startMs)
    }

    // ═══════════════ ASS / SSA ═══════════════

    /** 10 段的 Dialogue 行：Layer/Start/End/Style/Name/…/Text */
    private fun assLine(text: String, start: String = "0:00:01.00") =
        "Dialogue: 0,$start,0:00:03.00,Default,,0,0,0,,$text"

    @Test
    fun `★ ASS 覆盖标签被剥掉（正是这条正则曾经让 App 闪退）`() {
        val lines = parse(
            """
            [Script Info]
            ScriptType: v4.00+

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            ${assLine("""{\pos(400,300)}こんばんは""")}
            """.trimIndent(),
            "a.ass"
        )
        assertEquals(1, lines.size)
        assertEquals("こんばんは", lines.single().original)
    }

    @Test
    fun `★ ASS 连续与嵌套的花括号全部剥掉`() {
        val lines = parse(
            "${assLine("{\\i1}前{\\i0}{\\c&HFFFFFF&}后")}",
            "a.ass"
        )
        assertEquals("前后", lines.single().original)
    }

    @Test
    fun `ASS 未闭合的花括号之后的内容被丢弃`() {
        // 原正则匹配不到"未闭合的 {"，所以 `abc{def` 会原样留下；手写扫描改为保守丢弃。
        val lines = parse(assLine("abc{def"), "a.ass")
        assertEquals("abc", lines.single().original)
    }

    @Test
    fun `ASS 的换行转义与空白转义`() {
        val lines = parse(assLine("原文\\N译文"), "a.ass")
        assertEquals(1, lines.size)
        assertEquals("原文", lines.single().original)
        assertEquals("译文", lines.single().translation)

        val spaced = parse(assLine("a\\hb"), "a.ass")
        assertEquals("a b", spaced.single().original)
    }

    @Test
    fun `ASS 正文里的逗号不会被当成字段分隔`() {
        val lines = parse(assLine("喂，你好，世界"), "a.ass")
        assertEquals("喂，你好，世界", lines.single().original)
    }

    @Test
    fun `ASS 的百分秒时间`() {
        val lines = parse(assLine("a", start = "0:01:02.50"), "a.ass")
        assertEquals(62_500L, lines.single().startMs)
    }

    @Test
    fun `Dialogue 大小写不敏感`() {
        val lines = parse("dialogue: 0,0:00:01.00,0:00:02.00,D,,0,0,0,,正文", "a.ass")
        assertEquals("正文", lines.single().original)
    }

    // ═══════════════ 通用 ═══════════════

    @Test
    fun `空白与纯标签行被过滤`() {
        val lines = parse(
            """
            [00:01.00]
            [00:02.00]   
            [00:03.00]实内容
            """.trimIndent(),
            "a.lrc"
        )
        assertEquals(1, lines.size)
        assertEquals("实内容", lines.single().original)
    }

    @Test
    fun `未知扩展名按 LRC 处理`() {
        val lines = parse("[00:01.00]用 LRC 规则", "a.txt")
        assertEquals("用 LRC 规则", lines.single().original)
    }

    @Test
    fun `完全无法识别时返回空列表而不是抛异常`() {
        assertTrue(parse("随便一段没有时间轴的文字", "a.lrc").isEmpty())
        assertTrue(parse("", "a.vtt").isEmpty())
        assertTrue(parse("", "a.ass").isEmpty())
    }

    @Test
    fun `无意义的 LRC 时间戳被跳过而不是崩`() {
        // LRC 只按 `[分:秒.毫秒]` 的字面数字取值，**不做 0..59 的范围校验**
        // （范围校验只在 VTT/SRT/ASS 的 parseClock 里）。所以这里只保证
        // "完全不是时间戳"的行被跳过，而不是把 99 秒判成非法。
        val lines = parse(
            """
            [aa:bb]不是时间戳
            [没有中括号]
            [00:05.00]正常
            """.trimIndent(),
            "a.lrc"
        )
        assertEquals(1, lines.size)
        assertEquals("正常", lines.single().original)
    }

    @Test
    fun `VTT 的秒数超界会被拒绝`() {
        // 与上面相反：cue 格式走 parseClock，会校验秒/分范围。
        val lines = parse(
            """
            00:00:99.000 --> 00:01:00.000
            超界的秒
            """.trimIndent(),
            "a.vtt"
        )
        assertTrue("秒数 99 应当被拒绝", lines.isEmpty())
    }

    // ═══════════════ 曲目 ↔ 歌词匹配 ═══════════════

    private fun audio(path: String) = Track(title = path.substringAfterLast('/'), type = "audio", path = path)

    private fun lyric(path: String) = Track(
        title = path.substringAfterLast('/'),
        type = "text",
        path = path,
        mediaStreamUrl = "https://cdn/$path"
    )

    private fun folder(path: String, vararg children: Track) =
        Track(title = path.substringAfterLast('/'), type = "folder", path = path, children = children.toList())

    private fun pick(audioPath: String, tree: List<Track>): String? =
        LyricsLoader.findLyricTrack(tree, audioPath, audioPath.substringAfterLast('/'))?.path

    @Test
    fun `★ 歌词放在别的文件夹里也能找到`() {
        // 这就是被去掉的那条限制：歌词整包放在 lyrics/ 下，音频在 mp3/ 下
        val tree = listOf(
            folder("mp3", audio("mp3/01_はじめに.mp3"), audio("mp3/02_つづき.mp3")),
            folder("lyrics", lyric("lyrics/01_はじめに.lrc"), lyric("lyrics/02_つづき.lrc"))
        )
        assertEquals("lyrics/01_はじめに.lrc", pick("mp3/01_はじめに.mp3", tree))
        assertEquals("lyrics/02_つづき.lrc", pick("mp3/02_つづき.mp3", tree))
    }

    @Test
    fun `★ 同文件夹优先于其它文件夹`() {
        val tree = listOf(
            folder("mp3", audio("mp3/Tr1.mp3"), lyric("mp3/Tr1.lrc")),
            folder("lyrics", lyric("lyrics/Tr1.lrc"))
        )
        assertEquals("同目录的那份必须先命中", "mp3/Tr1.lrc", pick("mp3/Tr1.mp3", tree))
    }

    @Test
    fun `★ 同样是跨目录，歌词目录优先于普通目录`() {
        val tree = listOf(
            folder("mp3", audio("mp3/Tr1.mp3")),
            folder("omake", lyric("omake/Tr1.lrc")),
            folder("歌詞", lyric("歌詞/Tr1.lrc"))
        )
        assertEquals("歌詞/Tr1.lrc", pick("mp3/Tr1.mp3", tree))
    }

    @Test
    fun `跨目录时扩展名优先级仍然生效`() {
        val tree = listOf(
            folder("mp3", audio("mp3/Tr1.mp3")),
            folder("sub", lyric("sub/Tr1.ass"), lyric("sub/Tr1.lrc"))
        )
        assertEquals("sub/Tr1.lrc", pick("mp3/Tr1.mp3", tree))
    }

    @Test
    fun `★ 名字对不上就不匹配 —— 跨目录不等于乱配`() {
        val tree = listOf(
            folder("mp3", audio("mp3/Tr1.mp3"), audio("mp3/Tr2.mp3")),
            folder("lyrics", lyric("lyrics/Tr2.lrc"))
        )
        assertNull("没有与 Tr1 同名的歌词，就应当返回 null", pick("mp3/Tr1.mp3", tree))
        assertEquals("lyrics/Tr2.lrc", pick("mp3/Tr2.mp3", tree))
    }

    @Test
    fun `支持 同名加音频扩展名 的写法`() {
        val tree = listOf(
            folder("mp3", audio("mp3/Tr1.mp3")),
            folder("字幕", lyric("字幕/Tr1.mp3.vtt"))
        )
        assertEquals("字幕/Tr1.mp3.vtt", pick("mp3/Tr1.mp3", tree))
    }

    @Test
    fun `同名匹配不区分大小写`() {
        val tree = listOf(
            folder("mp3", audio("mp3/TRACK01.mp3")),
            folder("lyrics", lyric("lyrics/track01.lrc"))
        )
        assertEquals("lyrics/track01.lrc", pick("mp3/TRACK01.mp3", tree))
    }

    @Test
    fun `音频在根目录时也能跨目录找到歌词`() {
        val tree = listOf(
            audio("root.mp3"),
            folder("lyrics", lyric("lyrics/root.lrc"))
        )
        assertEquals("lyrics/root.lrc", pick("root.mp3", tree))
    }

    @Test
    fun `wav 与 mp3 两套音频共用同一份歌词`() {
        val tree = listOf(
            folder("mp3", audio("mp3/Tr1.mp3")),
            folder("wav", audio("wav/Tr1.wav")),
            folder("lyrics", lyric("lyrics/Tr1.lrc"))
        )
        assertEquals("lyrics/Tr1.lrc", pick("mp3/Tr1.mp3", tree))
        assertEquals("lyrics/Tr1.lrc", pick("wav/Tr1.wav", tree))
    }

    @Test
    fun `深层嵌套目录里的歌词同样能找到`() {
        val tree = listOf(
            folder("本編", folder("mp3", audio("本編/mp3/01.mp3"))),
            folder("特典", folder("歌詞", lyric("特典/歌詞/01.lrc")))
        )
        assertEquals("特典/歌詞/01.lrc", pick("本編/mp3/01.mp3", tree))
    }

    @Test
    fun `没有歌词时返回 null`() {
        val tree = listOf(folder("mp3", audio("mp3/Tr1.mp3")))
        assertNull(pick("mp3/Tr1.mp3", tree))
    }
}
