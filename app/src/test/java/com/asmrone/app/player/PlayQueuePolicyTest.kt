package com.asmrone.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放模式与"下一首去哪"的纯函数测试。
 *
 * 这段逻辑的错误症状是"播到最后一首就卡住"或"随机一直随机到同一首"，
 * 在真机上要听完整个列表才能发现；放进单测里几行就能覆盖边界。
 */
class PlayQueuePolicyTest {

    // ── 模式的名字与轮换 ──

    @Test
    fun `每个模式都有可读的中文名`() {
        assertEquals("列表循环", PlayQueuePolicy.label(PlayQueuePolicy.MODE_LIST))
        assertEquals("单曲循环", PlayQueuePolicy.label(PlayQueuePolicy.MODE_SINGLE))
        assertEquals("随机播放", PlayQueuePolicy.label(PlayQueuePolicy.MODE_SHUFFLE))
    }

    @Test
    fun `越界的模式也给出名字，不会崩`() {
        assertEquals("列表循环", PlayQueuePolicy.label(-1))
        assertEquals("列表循环", PlayQueuePolicy.label(99))
    }

    @Test
    fun `点一次换一个模式，三次回到原样`() {
        val first = PlayQueuePolicy.nextMode(PlayQueuePolicy.MODE_LIST)
        val second = PlayQueuePolicy.nextMode(first)
        val third = PlayQueuePolicy.nextMode(second)
        assertEquals(PlayQueuePolicy.MODE_SINGLE, first)
        assertEquals(PlayQueuePolicy.MODE_SHUFFLE, second)
        assertEquals(PlayQueuePolicy.MODE_LIST, third)
    }

    @Test
    fun `越界的模式点击后回到列表循环`() {
        assertEquals(PlayQueuePolicy.MODE_LIST, PlayQueuePolicy.nextMode(42))
    }

    // ── 自动衔接（一首放完之后）──

    @Test
    fun `播完即停时不自动换歌`() {
        val target = PlayQueuePolicy.completionIndex(
            mode = PlayQueuePolicy.MODE_LIST,
            index = 0,
            size = 5,
            autoNext = false,
            randomOffset = 0
        )
        assertEquals(-1, target)
    }

    @Test
    fun `列表循环播到最后一首会绕回第一首`() {
        val target = PlayQueuePolicy.completionIndex(
            mode = PlayQueuePolicy.MODE_LIST,
            index = 4,
            size = 5,
            autoNext = true,
            randomOffset = 0
        )
        assertEquals(0, target)
    }

    @Test
    fun `列表循环在中途就顺延`() {
        val target = PlayQueuePolicy.completionIndex(
            mode = PlayQueuePolicy.MODE_LIST,
            index = 1,
            size = 5,
            autoNext = true,
            randomOffset = 0
        )
        assertEquals(2, target)
    }

    @Test
    fun `单曲循环永远是同一首`() {
        val target = PlayQueuePolicy.completionIndex(
            mode = PlayQueuePolicy.MODE_SINGLE,
            index = 3,
            size = 5,
            autoNext = true,
            randomOffset = 123
        )
        assertEquals(3, target)
    }

    @Test
    fun `只有一首时列表循环也是重放它自己`() {
        val target = PlayQueuePolicy.completionIndex(
            mode = PlayQueuePolicy.MODE_LIST,
            index = 0,
            size = 1,
            autoNext = true,
            randomOffset = 0
        )
        assertEquals(0, target)
    }

    @Test
    fun `随机播放不会抽到当前这一首`() {
        repeat(50) { offset ->
            val target = PlayQueuePolicy.completionIndex(
                mode = PlayQueuePolicy.MODE_SHUFFLE,
                index = 2,
                size = 5,
                autoNext = true,
                randomOffset = offset
            )
            assertNotEquals(2, target)
            assertTrue("抽到的下标要落在列表内：$target", target in 0 until 5)
        }
    }

    @Test
    fun `只有两首时随机会给对方`() {
        repeat(20) { offset ->
            val target = PlayQueuePolicy.completionIndex(
                mode = PlayQueuePolicy.MODE_SHUFFLE,
                index = 0,
                size = 2,
                autoNext = true,
                randomOffset = offset
            )
            assertEquals(1, target)
        }
    }

    @Test
    fun `空列表或越界下标不返回目标`() {
        assertEquals(
            -1,
            PlayQueuePolicy.completionIndex(PlayQueuePolicy.MODE_LIST, 0, 0, true, 0)
        )
        assertEquals(
            -1,
            PlayQueuePolicy.completionIndex(PlayQueuePolicy.MODE_LIST, 9, 5, true, 0)
        )
        assertEquals(
            -1,
            PlayQueuePolicy.completionIndex(PlayQueuePolicy.MODE_LIST, -1, 5, true, 0)
        )
    }

    // ── 手动上一首 / 下一首 ──

    @Test
    fun `手动下一首在末尾绕回开头`() {
        assertEquals(
            0,
            PlayQueuePolicy.manualNextIndex(PlayQueuePolicy.MODE_LIST, 4, 5, 0)
        )
    }

    @Test
    fun `手动上一首在开头跳到末尾`() {
        assertEquals(
            4,
            PlayQueuePolicy.manualPrevIndex(PlayQueuePolicy.MODE_LIST, 0, 5, 0)
        )
    }

    @Test
    fun `手动上一首在中途就是前一首`() {
        assertEquals(
            1,
            PlayQueuePolicy.manualPrevIndex(PlayQueuePolicy.MODE_LIST, 2, 5, 0)
        )
    }

    @Test
    fun `只有一首时手动上下首都落在它自己`() {
        assertEquals(0, PlayQueuePolicy.manualNextIndex(PlayQueuePolicy.MODE_LIST, 0, 1, 0))
        assertEquals(0, PlayQueuePolicy.manualPrevIndex(PlayQueuePolicy.MODE_LIST, 0, 1, 0))
    }

    @Test
    fun `手动切歌不受播放模式影响，单曲模式也能换`() {
        // 单曲循环只约束"播完之后"，用户主动点下一首就该真的换
        assertEquals(
            3,
            PlayQueuePolicy.manualNextIndex(PlayQueuePolicy.MODE_SINGLE, 2, 5, 0)
        )
        assertEquals(
            1,
            PlayQueuePolicy.manualPrevIndex(PlayQueuePolicy.MODE_SINGLE, 2, 5, 0)
        )
    }

    @Test
    fun `随机模式下手动切歌也避开当前这一首`() {
        repeat(30) { offset ->
            val next = PlayQueuePolicy.manualNextIndex(PlayQueuePolicy.MODE_SHUFFLE, 1, 4, offset)
            val prev = PlayQueuePolicy.manualPrevIndex(PlayQueuePolicy.MODE_SHUFFLE, 1, 4, offset)
            assertNotEquals(1, next)
            assertNotEquals(1, prev)
            assertTrue(next in 0 until 4)
            assertTrue(prev in 0 until 4)
        }
    }

    @Test
    fun `空列表手动切歌不返回目标`() {
        assertEquals(-1, PlayQueuePolicy.manualNextIndex(PlayQueuePolicy.MODE_LIST, 0, 0, 0))
        assertEquals(-1, PlayQueuePolicy.manualPrevIndex(PlayQueuePolicy.MODE_LIST, 0, 0, 0))
    }
}
