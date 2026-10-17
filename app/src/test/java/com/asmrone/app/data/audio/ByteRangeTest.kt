package com.asmrone.app.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `Range` 请求头解析。
 *
 * 这组测试存在的理由很具体：代理一旦把 Range 解析错，回给播放器的
 * `Content-Range` 就对不上，表现是"某些音频播到一半卡死"或跳到错误位置 ——
 * 这种问题在真机上极难定位（看起来像网络问题）。
 */
class ByteRangeTest {

    // ═══════════════ 合法形态 ═══════════════

    @Test
    fun `从 0 到末尾`() {
        val r = ByteRange.parse("bytes=0-")
        assertEquals(0L, r?.from)
        assertNull("到文件末尾时 to 应为 null", r?.to)
    }

    @Test
    fun `从中间到末尾`() {
        val r = ByteRange.parse("bytes=1024-")
        assertEquals(1024L, r?.from)
        assertNull(r?.to)
    }

    @Test
    fun `闭合区间`() {
        val r = ByteRange.parse("bytes=0-1023")
        assertEquals(0L, r?.from)
        assertEquals(1023L, r?.to)
        assertEquals("区间长度应为 1024", 1024L, r?.length)
    }

    @Test
    fun `中间的一段`() {
        val r = ByteRange.parse("bytes=500-1499")
        assertEquals(500L, r?.from)
        assertEquals(1499L, r?.to)
        assertEquals(1000L, r?.length)
    }

    @Test
    fun `单个字节`() {
        val r = ByteRange.parse("bytes=7-7")
        assertEquals(7L, r?.from)
        assertEquals(7L, r?.to)
        assertEquals(1L, r?.length)
    }

    @Test
    fun `前缀大小写不敏感`() {
        assertEquals(0L, ByteRange.parse("BYTES=0-")?.from)
        assertEquals(0L, ByteRange.parse("Bytes=0-")?.from)
    }

    @Test
    fun `容忍多余空白`() {
        assertEquals(0L, ByteRange.parse("  bytes=0-  ")?.from)
        assertEquals(100L, ByteRange.parse("bytes= 100-")?.from)
    }

    // ═══════════════ 不支持 / 非法 → null ═══════════════

    @Test
    fun `★ 末尾式（最后 N 字节）不支持，返回 null`() {
        // 它的语义要先知道总长度；我们在收到请求时就要转发上游，那时还不知道。
        // 返回 null 后调用方按"整个文件"处理，行为仍正确。
        assertNull(ByteRange.parse("bytes=-1024"))
    }

    @Test
    fun `★ 多重区间不支持，返回 null`() {
        // 正确实现要返回 multipart/byteranges，代价远大于收益；
        // 播放器顺序播放也不会这么请求
        assertNull(ByteRange.parse("bytes=0-99,200-299"))
    }

    @Test
    fun `没有 Range 头时返回 null`() {
        assertNull(ByteRange.parse(null))
        assertNull(ByteRange.parse(""))
        assertNull(ByteRange.parse("   "))
    }

    @Test
    fun `前缀不对时返回 null`() {
        assertNull(ByteRange.parse("items=0-"))
        assertNull(ByteRange.parse("0-1023"))
        assertNull(ByteRange.parse("bytes"))
    }

    @Test
    fun `缺横线或起点时返回 null`() {
        assertNull(ByteRange.parse("bytes=1024"))
        assertNull(ByteRange.parse("bytes="))
        assertNull(ByteRange.parse("bytes=-"))
    }

    @Test
    fun `非数字返回 null`() {
        assertNull(ByteRange.parse("bytes=abc-"))
        assertNull(ByteRange.parse("bytes=0-xyz"))
    }

    @Test
    fun `★ 终点小于起点时返回 null`() {
        // 这种请求本身有问题；返回 null 让调用方按整文件处理，
        // 总比回一个反向的 Content-Range 让播放器崩掉好
        assertNull(ByteRange.parse("bytes=100-50"))
    }

    @Test
    fun `负数返回 null`() {
        assertNull(ByteRange.parse("bytes=-5-"))
    }

    // ═══════════════ 可读形式（日志用） ═══════════════

    @Test
    fun `text 便于日志阅读`() {
        assertEquals("0-", ByteRange.Range(0, null).text)
        assertEquals("0-1023", ByteRange.Range(0, 1023).text)
        assertEquals("500-", ByteRange.Range(500, null).text)
    }
}
