package com.asmrone.app.player

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * 音频焦点（Audio Focus）。
 *
 * ## 为什么必须有
 *
 * 没有它的时候，本 App 会和系统里所有别的音频源"平起平坐"：来电、导航播报、
 * 别的音乐 App 开始时，我们照样出声，混在一起。表现是用户接了电话还在播放，
 * 或者两个播放器同时响。这是"接入安卓媒体播放"里最容易被忽略、但用户最先感知到的一环。
 *
 * ## 策略
 *
 * 申请 [AudioManager.AUDIOFOCUS_GAIN]（独占），并按系统给的三类事件反应：
 *
 *  - **永久丢失**（来电接通、别的播放器开始）：暂停，且**不**自动恢复。
 *  - **临时丢失**（导航播报、通知音）：暂停，焦点回来时自动续上。
 *  - **允许压低**：把音量系数压到 1/5，焦点回来后还原。
 *
 * 这里用 `setWillPauseWhenDucked(true)` 主动**放弃**"压低"这条路：本 App 放的是
 * 低声压的耳语素材，压低到 20% 基本等于听不见，暂停再续上反而更自然。
 * 仍保留 `CAN_DUCK` 分支：部分定制系统不认这个声明，会照发 CAN_DUCK。
 *
 * ## 线程
 *
 * [request] 必须在主线程调用 —— 系统把焦点回调投递到**注册时所在线程**的 Looper，
 * 而这里的三个回调都会直接改播放器状态（StateFlow 只在主线程写）。
 */
internal class AudioFocusCoordinator(
    private val audioManager: AudioManager,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
    private val onDuck: (Boolean) -> Unit
) {

    /** 因为"临时丢失"而暂停 —— 焦点回来后要自动续播。永久丢失不算。 */
    private var pausedForFocus = false

    private val request: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // 延迟获得焦点对"播放音频"没有意义：拿不到就直接放，别挂着等
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(::onFocusChange)
            .build()

    /** @return 是否真的拿到焦点。为 false 时调用方可以照样播放，但要按 [onFocusChange] 处理后续 */
    fun request(): Boolean =
        audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    /** 用户主动暂停 / 停止时让出焦点，别占着不让别人出声 */
    fun abandon() {
        pausedForFocus = false
        onDuck(false)
        runCatching { audioManager.abandonAudioFocusRequest(request) }
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedForFocus = false
                onDuck(false)
                onPause()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                pausedForFocus = true
                onPause()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onDuck(true)

            AudioManager.AUDIOFOCUS_GAIN -> {
                onDuck(false)
                if (pausedForFocus) {
                    pausedForFocus = false
                    onResume()
                }
            }
        }
    }
}
