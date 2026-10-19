package com.asmrone.app.player

import com.asmrone.app.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PlaybackSessionModel] 的映射守卫。
 *
 * 这几条映射一旦写错，症状全是"在真机上很难看出来"的那种：
 * 通知栏一直转圈、锁屏进度条不动、暂停后进度自己往前跑、耳机键按了没反应。
 * 放进 JVM 单测直接断言数值，比反复装机试快得多。
 *
 * 断言用**字面量**而不是 `PlaybackState.STATE_*`：那些常量是编译期内联的，
 * 拿常量比对等于自己和自己比，改了映射也不会变红。
 */
class PlaybackSessionModelTest {

    private fun stateOf(
        index: Int = 0,
        trackCount: Int = 3,
        isPlaying: Boolean = false,
        isPrepared: Boolean = true,
        isBuffering: Boolean = false,
        error: String? = null
    ): PlayerState = PlayerState(
        workId = 1,
        workTitle = "测试作品",
        tracks = List(trackCount) { Track(title = "t$it", type = "audio", path = "p$it") },
        index = index,
        isPlaying = isPlaying,
        isPrepared = isPrepared,
        isBuffering = isBuffering,
        error = error
    )

    @Test
    fun `播放中 → STATE_PLAYING`() {
        assertEquals(3, PlaybackSessionModel.stateOf(stateOf(isPlaying = true)))
    }

    @Test
    fun `暂停 → STATE_PAUSED`() {
        assertEquals(2, PlaybackSessionModel.stateOf(stateOf(isPlaying = false)))
    }

    @Test
    fun `缓冲 → STATE_BUFFERING`() {
        assertEquals(6, PlaybackSessionModel.stateOf(stateOf(isBuffering = true)))
    }

    @Test
    fun `错误优先于播放中 → STATE_ERROR`() {
        // 已经出错却还报 PLAYING，系统媒体卡片会一直"正在播放"、进度永远不动
        assertEquals(
            7,
            PlaybackSessionModel.stateOf(stateOf(isPlaying = true, error = "播放失败"))
        )
    }

    @Test
    fun `空会话 → STATE_NONE`() {
        assertEquals(0, PlaybackSessionModel.stateOf(PlayerState()))
    }

    @Test
    fun `中间位置同时声明上一首与下一首`() {
        // STOP(1) | PAUSE(2) | PLAY(4) | SEEK_TO(256) | SKIP_TO_NEXT(32) | SKIP_TO_PREVIOUS(16)
        assertEquals(311L, PlaybackSessionModel.actionsOf(stateOf(index = 1)))
    }

    @Test
    fun `第一首不声明上一首 —— 声明了却做不到的按钮等于坏按钮`() {
        // 311 - SKIP_TO_PREVIOUS(16)
        assertEquals(295L, PlaybackSessionModel.actionsOf(stateOf(index = 0)))
    }

    @Test
    fun `最后一首不声明下一首`() {
        // 311 - SKIP_TO_NEXT(32)
        assertEquals(279L, PlaybackSessionModel.actionsOf(stateOf(index = 2)))
    }

    @Test
    fun `单曲作品只有播放暂停与跳转`() {
        // STOP(1) | PAUSE(2) | PLAY(4) | SEEK_TO(256)
        assertEquals(263L, PlaybackSessionModel.actionsOf(stateOf(trackCount = 1)))
    }

    @Test
    fun `暂停时播放速度必须是 0`() {
        // 报 1 的话系统会按"还在播"继续外推位置，锁屏进度条就越走越偏
        assertEquals(0f, PlaybackSessionModel.speedOf(stateOf(isPlaying = false)))
        assertEquals(1f, PlaybackSessionModel.speedOf(stateOf(isPlaying = true)))
    }
}
