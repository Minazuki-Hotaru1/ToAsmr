package com.asmrone.app.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 播放器设置与交互的**源码级守卫**。
 *
 * 这些是"接线"问题：单测能证明 [PlayQueuePolicy] 算得对，但证明不了
 * `AudioController` 真的把结果用上了 —— 少接一根线的症状是"界面点了没反应"，
 * 而 JVM 单测跑不到 Android 的播放内核。用字符串断言把关键接线钉住。
 */
class PlayerSettingsGuardTest {

    private fun source(relative: String): String = File("src/main/java/com/asmrone/app/$relative").readText()

    private val controller by lazy { source("player/AudioController.kt") }
    private val playerScreen by lazy { source("ui/screen/player/PlayerScreen.kt") }
    private val prefs by lazy { source("data/prefs/AppPrefs.kt") }
    private val sessionModel by lazy { source("player/PlaybackSessionModel.kt") }
    private val policy by lazy { source("player/PlayQueuePolicy.kt") }

    // ── 播放速度 ──

    @Test
    fun `播放速度用 PlaybackParams 变速但保持音高`() {
        assertTrue(controller.contains("PlaybackParams()"))
        assertTrue(controller.contains(".setSpeed(v)"))
        assertTrue(controller.contains(".setPitch(1f)"))
    }

    @Test
    fun `换曲之后会重新套用速度`() {
        // onPrepared 里套速度：否则切到下一首就悄悄回到原速
        val prepared = controller.substringAfter("setOnPreparedListener")
        assertTrue(prepared.contains("applySpeed(AppPrefs.playbackSpeed.value)"))
    }

    @Test
    fun `速度落在合法区间内`() {
        assertTrue(controller.contains("speed.coerceIn(AppPrefs.MIN_PLAYBACK_SPEED, AppPrefs.MAX_PLAYBACK_SPEED)"))
        assertTrue(prefs.contains("const val MIN_PLAYBACK_SPEED = 0.5f"))
        assertTrue(prefs.contains("const val MAX_PLAYBACK_SPEED = 2.0f"))
    }

    @Test
    fun `速度与播放模式都持久化`() {
        listOf(
            "KEY_PLAYBACK_SPEED",
            "KEY_PLAY_MODE",
            "KEY_AUTO_NEXT",
            "KEY_SLEEP_FADE_OUT"
        ).forEach { key ->
            assertTrue("缺少持久化键：$key", prefs.contains(key))
            // 键必须同时出现在读（getX）与写（editor.putX）两侧
            assertTrue("$key 只声明没使用", prefs.split(key).size >= 3)
        }
    }

    // ── 播完即停 / 继续播放 ──

    @Test
    fun `完成回调按播放模式决定下一首`() {
        assertTrue(controller.contains("PlayQueuePolicy.completionIndex("))
        assertTrue(controller.contains("autoNext = AppPrefs.autoNext.value"))
    }

    @Test
    fun `完成回调不再写死 hasNext 顺延`() {
        // 老实现是 if (cur.hasNext) playTrackAt(cur.index + 1)
        assertTrue(
            "完成回调仍在用 hasNext 顺延",
            !controller.contains("else if (cur.hasNext)")
        )
    }

    @Test
    fun `播完即停走统一收尾，不会留下还在转的 ticker`() {
        assertTrue(controller.contains("private fun finishPlayback(cur: PlayerState)"))
        val finish = controller.substringAfter("private fun finishPlayback(cur: PlayerState)")
        assertTrue(finish.contains("stopTicker()"))
        assertTrue(finish.contains("persistSession()"))
    }

    // ── 睡眠定时淡出 ──

    @Test
    fun `淡出叠加进音量，不覆盖音量与压低`() {
        assertTrue(controller.contains("gain * duckFactor * fadeFactor"))
    }

    @Test
    fun `定时到点时若等这首放完，淡出交给曲末`() {
        assertTrue(controller.contains("fadeAtTrackEnd = fade"))
        assertTrue(controller.contains("maybeFadeBeforeTrackEnd(pos"))
        // 曲末触发要落在 ticker 里（位置本来就在轮询）
        val ticker = controller.substringAfter("private fun startTicker()")
        assertTrue(ticker.contains("maybeFadeBeforeTrackEnd("))
    }

    @Test
    fun `切歌与重新定时都会清掉淡出`() {
        assertTrue(controller.contains("private fun cancelFade()"))
        val startCurrent = controller.substringAfter("private fun startCurrent()")
        assertTrue(startCurrent.contains("cancelFade()"))
        val setTimer = controller.substringAfter("fun setSleepTimer(minutes: Int)")
        assertTrue(setTimer.contains("cancelFade()"))
    }

    @Test
    fun `淡出暂停前会把音量系数还原`() {
        // 否则下次 resume 以 0 音量起播，表现成"点了播放但没声音"
        val fade = controller.substringAfter("private fun startFade(")
        assertTrue(fade.contains("fadeFactor = 1f"))
    }

    // ── 分享与快捷跳转 ──

    @Test
    fun `分享复制带 asmr one 前缀的链接`() {
        assertTrue(playerScreen.contains("https://asmr.one/work/"))
        assertTrue(playerScreen.contains("ClipboardManager"))
        assertTrue(playerScreen.contains("setPrimaryClip("))
        assertTrue(playerScreen.contains("ClipData.newPlainText("))
    }

    @Test
    fun `分享链接优先用 RJ 号，缺了才退回数字 id`() {
        val helper = playerScreen.substringAfter("private fun shareLink(")
        assertTrue(helper.contains("key.isNotEmpty()"))
        assertTrue(helper.contains("workId > 0"))
    }

    @Test
    fun `上一首与下一首两侧各有一个跳转键`() {
        assertTrue(playerScreen.contains("label = \"-5s\""))
        assertTrue(playerScreen.contains("label = \"+30s\""))
        assertTrue(playerScreen.contains("AudioController.skipBack(5)"))
        assertTrue(playerScreen.contains("AudioController.skipForward(30)"))
    }

    @Test
    fun `跳转键是图标加秒数，不是纯文字`() {
        // 2026-10 需求：播放器里所有功能都要有图标；±5s / +30s 的秒数不是平台惯例值，
        // 所以保留文字，做成"描边双箭头 + 下方小字"，避免只剩图标说不清时长。
        val skipButton = playerScreen.substringAfter("private fun PlayerSkipButton(")
            .substringBefore("private fun PlayerIconButton(")
        assertTrue("跳转键要有图标", skipButton.contains("Icon("))
        assertTrue("秒数仍要由文字给出", skipButton.contains("Text("))
        assertTrue("用的是描边 Rewind", playerScreen.contains("ic_rewind_outline_fluent"))
        assertTrue("用的是描边 Fast Forward", playerScreen.contains("ic_fast_forward_outline_fluent"))
    }

    // ── 播放模式入口在播放器主页 ──

    @Test
    fun `底部四个入口只有图标，顶部不再重复放队列按钮`() {
        // 2026-10 需求：定时 / 播放模式 / 播放队列 / 设置 只留图标（文案进 contentDescription）；
        // 同时删掉顶部那个和第二、第三、四项重复的队列按钮。
        val bottomButton = playerScreen.substringAfter("private fun PlayerBottomIconButton(")
            .substringBefore("@Composable")
        assertFalse("底部入口不应再带文字", bottomButton.contains("Text("))
        assertTrue("无障碍描述不能省", bottomButton.contains("contentDescription = contentDescription"))
        assertTrue(
            "四个入口都要用新按钮",
            Regex("""PlayerBottomIconButton\(
\s+iconRes =""").findAll(playerScreen).count() >= 4
        )
        val topBar = playerScreen.substringBefore("HorizontalPager(")
        assertFalse("顶栏不再放队列按钮（底部已有）", topBar.contains("ic_queue_outline_fluent"))
    }

    @Test
    fun `播放模式入口在播放器底部，不在设置里`() {
        assertTrue(playerScreen.contains("PlayQueuePolicy.label(playMode)"))
        assertTrue(playerScreen.contains("PlayQueuePolicy.nextMode(playMode)"))
        assertTrue(playerScreen.contains("AppPrefs.setPlayMode(next)"))
        val settings = playerScreen.substringAfter("private fun PlayerSettingsSheet(")
        assertTrue("播放模式不该出现在设置弹窗里", !settings.contains("PlayQueuePolicy.nextMode"))
    }

    @Test
    fun `设置弹窗包含速度、播完行为与淡出三项`() {
        val settings = playerScreen.substringAfter("private fun PlayerSettingsSheet(")
        assertTrue(settings.contains("AudioController.setPlaybackSpeed("))
        assertTrue(settings.contains("AudioController.previewPlaybackSpeed("))
        assertTrue(settings.contains("AppPrefs.setAutoNext("))
        assertTrue(settings.contains("AppPrefs.setSleepFadeOut("))
    }

    // ── 系统媒体卡片 ──

    @Test
    fun `系统媒体卡片按三种模式都能换歌`() {
        assertTrue(sessionModel.contains("state.canSkipNext"))
        assertTrue(sessionModel.contains("state.canSkipPrev"))
        assertTrue(controller.contains("val canSkipNext: Boolean get() = tracks.size > 1"))
    }

    @Test
    fun `系统媒体卡片报真实倍速`() {
        assertTrue(sessionModel.contains("state.playbackSpeed"))
        assertTrue(controller.contains("_state.value.copy(playbackSpeed = v)"))
    }

    @Test
    fun `播放模式只有三种`() {
        assertTrue(policy.contains("MODE_LIST = 0"))
        assertTrue(policy.contains("MODE_SINGLE = 1"))
        assertTrue(policy.contains("MODE_SHUFFLE = 2"))
        assertTrue(prefs.contains("val PLAY_MODE_RANGE = 0..2"))
    }

    @Test
    fun `项目里不用系统 Toast，一律走自带组件`() {
        assertTrue(playerScreen.contains("toast.show("))
        assertTrue(!playerScreen.contains("android.widget.Toast"))
    }
}
