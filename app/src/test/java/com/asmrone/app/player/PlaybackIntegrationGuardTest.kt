package com.asmrone.app.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 接入系统媒体播放的**接线**守卫。
 *
 * ## 为什么这类东西需要守卫
 *
 * 这一层全是"少写一行就不工作、但编译器不会说话"的东西：
 *
 *  - Manifest 里少一个 `FOREGROUND_SERVICE_MEDIA_PLAYBACK` → Android 14 上
 *    `startForeground` 直接抛 SecurityException（播放一按就崩，且只有真机新系统能复现）；
 *  - 通知忘了 `setMediaSession(token)` → 通知看着正常，但锁屏与系统媒体卡片
 *    根本不出现，也没法从耳机线控操作；
 *  - 会话的 `onPlay` 接成"切换播放/暂停" → 系统连点两次得到相反状态，
 *    锁屏上按一次"播放"可能反而暂停。
 *
 * 这些行为没法在 JVM 单测里真跑（要 MediaSession / NotificationManager 真机环境），
 * 所以按本工程既有做法（`DetailLinkGuardTest`、`PlaylistCardGestureGuardTest`）
 * 用源码级断言把它们钉住。
 */
class PlaybackIntegrationGuardTest {

    private val manifest: String by lazy { File("src/main/AndroidManifest.xml").readText() }

    private val serviceSource: String by lazy {
        File("src/main/java/com/asmrone/app/player/PlaybackService.kt").readText()
    }

    private val controllerSource: String by lazy {
        File("src/main/java/com/asmrone/app/player/AudioController.kt").readText()
    }

    private val activitySource: String by lazy {
        File("src/main/java/com/asmrone/app/MainActivity.kt").readText()
    }

    // ─────────────────────────── Manifest ───────────────────────────

    @Test
    fun `★ 前台服务必须声明 mediaPlayback 类型`() {
        assertTrue(
            "缺 foregroundServiceType=\"mediaPlayback\"：Android 14 起前台服务必须声明类型",
            manifest.contains("android:foregroundServiceType=\"mediaPlayback\"")
        )
        assertTrue(
            "缺 FOREGROUND_SERVICE_MEDIA_PLAYBACK 权限：startForeground 会抛 SecurityException",
            manifest.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK")
        )
        assertTrue(
            "缺 FOREGROUND_SERVICE 权限",
            manifest.contains("android.permission.FOREGROUND_SERVICE\"")
        )
    }

    @Test
    fun `★ 播放服务已注册且不对外暴露`() {
        assertTrue(
            "Manifest 里没有 .player.PlaybackService",
            manifest.contains("android:name=\".player.PlaybackService\"")
        )
        assertTrue(
            "播放服务不该 exported —— 入口只有本 App 的 PendingIntent 与系统媒体按键",
            Regex("""PlaybackService"[^>]*android:exported="false"""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(manifest)
        )
    }

    // ─────────────────────────── 通知与会话 ───────────────────────────

    @Test
    fun `★ 通知必须绑定 MediaSession token`() {
        // 少了这一行：通知照常显示，但锁屏/系统媒体卡片不出现，耳机线控也没反应
        assertTrue(
            "通知的 MediaStyle 必须 setMediaSession(session.sessionToken)",
            serviceSource.contains("setMediaSession(session.sessionToken)")
        )
        assertTrue(
            "必须声明收起状态下显示哪三个按钮",
            serviceSource.contains("setShowActionsInCompactView(0, 1, 2)")
        )
    }

    @Test
    fun `★ 服务进入前台`() {
        assertTrue(
            "必须调用 startForeground，否则后台播放会被系统收走",
            serviceSource.contains("startForeground(NOTIFICATION_ID")
        )
        assertTrue(
            "暂停时应当 DETACH 而不是 REMOVE：REMOVE 会把通知一起收掉，用户就点不到继续播放了",
            serviceSource.contains("STOP_FOREGROUND_DETACH")
        )
    }

    @Test
    fun `★ 会话回调必须是明确的播放与暂停，不能是切换`() {
        assertTrue(
            "onPlay 必须走 resume()",
            serviceSource.contains("override fun onPlay() = AudioController.resume()")
        )
        assertTrue(
            "onPause 必须走 pause()",
            serviceSource.contains("override fun onPause() = AudioController.pause()")
        )
        // 用切换的话，系统连点两次会得到相反状态（锁屏按"播放"反而暂停）
        assertFalse(
            "MediaSession 回调里不该出现 togglePlayPause",
            serviceSource.contains("onPlay() = AudioController.togglePlayPause()")
        )
    }

    @Test
    fun `耳机拔出时暂停`() {
        assertTrue(
            "必须监听 ACTION_AUDIO_BECOMING_NOISY：否则拔耳机就直接外放",
            serviceSource.contains("AudioManager.ACTION_AUDIO_BECOMING_NOISY")
        )
        assertTrue(
            "该广播是系统保护广播，注册时要显式声明 NOT_EXPORTED",
            serviceSource.contains("ContextCompat.RECEIVER_NOT_EXPORTED")
        )
    }

    // ─────────────────────────── 播放内核 ───────────────────────────

    @Test
    fun `★ 播放时申请音频焦点、停止时让出`() {
        assertTrue(
            "开始播放前必须 request 焦点（否则来电/别家播放器时照样出声）",
            controllerSource.contains("ensureFocus()?.request()")
        )
        assertTrue(
            "暂停/停止时必须 abandon，别占着不让别人出声",
            controllerSource.contains("ensureFocus()?.abandon()")
        )
    }

    @Test
    fun `★ 焦点因临时丢失而暂停时不得让出焦点`() {
        // 让出之后就再也收不到 GAIN，"导航播报结束后自动续播"会失效
        assertTrue(
            "焦点丢失的暂停路径必须带 fromFocusLoss = true",
            controllerSource.contains("pauseInternal(fromFocusLoss = true)")
        )
        assertTrue(
            "用户主动暂停才让出焦点",
            controllerSource.contains("pauseInternal(fromFocusLoss = false)")
        )
    }

    @Test
    fun `播放时拉起服务、清空时收起服务`() {
        assertTrue(
            "开始播放要拉起前台服务",
            controllerSource.contains("PlaybackService.start(")
        )
        assertTrue(
            "stop() 要收起服务与通知",
            controllerSource.contains("PlaybackService.stop(")
        )
    }

    @Test
    fun `锁屏后继续出声需要唤醒锁`() {
        assertTrue(
            "必须 setWakeMode(PARTIAL_WAKE_LOCK)：否则部分机型锁屏几十秒后就停",
            controllerSource.contains("setWakeMode(")
        )
        assertTrue(
            "Manifest 里要有 WAKE_LOCK 权限",
            manifest.contains("android.permission.WAKE_LOCK")
        )
    }

    // ─────────────────────────── 通知 → 界面 ───────────────────────────

    @Test
    fun `从通知点进来直接展开播放器`() {
        assertTrue(
            "播放服务要有打开播放器的 PendingIntent 标记",
            serviceSource.contains("MainActivity.EXTRA_OPEN_PLAYER")
        )
        assertTrue(
            "MainActivity 要用 addOnNewIntentListener 接住 singleTask 下的二次唤醒",
            activitySource.contains("addOnNewIntentListener")
        )
        assertTrue(
            "MainActivity 要消费启动 Intent 里的标记",
            activitySource.contains("getBooleanExtra(EXTRA_OPEN_PLAYER, false)")
        )
    }

    @Test
    fun `Android 13 起要申请通知权限`() {
        assertTrue(
            "必须在播放时申请 POST_NOTIFICATIONS，否则通知与系统媒体卡片不显示",
            activitySource.contains("Manifest.permission.POST_NOTIFICATIONS")
        )
    }
}
