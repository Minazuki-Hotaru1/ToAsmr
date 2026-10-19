package com.asmrone.app.player

import android.media.session.PlaybackState

/**
 * 把 [PlayerState] 翻译成系统媒体框架要的两样东西：**播放状态**与**可用操作位**。
 *
 * 单独抽出来做纯映射，是因为这两件事一旦写错，症状是"通知栏的按钮点了没反应"
 * 或"锁屏显示的进度不动"，而这两种错误在真机上很难靠肉眼定位 —— 放进 JVM 单测里
 * 直接断言数值，比在手机上试快得多。
 */
internal object PlaybackSessionModel {

    /**
     * 系统的播放状态。
     *
     * 顺序有讲究：**错误优先于一切** —— 已经出错时若还报 PLAYING，
     * 系统媒体卡片会一直转着"正在播放"，用户看到的是一个不会动的进度。
     */
    fun stateOf(state: PlayerState): Int = when {
        state.error != null -> PlaybackState.STATE_ERROR
        state.isBuffering -> PlaybackState.STATE_BUFFERING
        state.isPlaying -> PlaybackState.STATE_PLAYING
        state.isActive -> PlaybackState.STATE_PAUSED
        else -> PlaybackState.STATE_NONE
    }

    /**
     * 系统可以发给我们哪些操作。
     *
     * 只在真有上一首/下一首时声明 SKIP_* —— 声明了却做不到，系统的耳机键
     * 和蓝牙上一曲会变成一个"按了没反应"的按钮。
     */
    fun actionsOf(state: PlayerState): Long {
        var actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_STOP
        if (state.hasNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (state.hasPrev) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        return actions
    }

    /** 播放速度：暂停时必须报 0，否则系统会按"还在走"继续外推进度 */
    fun speedOf(state: PlayerState): Float = if (state.isPlaying) 1f else 0f
}
