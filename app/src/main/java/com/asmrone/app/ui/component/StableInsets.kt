package com.asmrone.app.ui.component

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * 「稳定」的顶部状态栏高度（提炼自 comicApp 的 StableInsets.kt）。
 *
 * 背景：全屏播放器进入沉浸模式会隐藏系统状态栏，此时 `WindowInsets.statusBars` 会变成 0；
 * 从播放器返回二级页面时状态栏重新出现，如果页面直接读取**实时** inset，
 * 整体内容就会跟随状态栏上下移动一小段（顶部栏被推低、首屏内容跟着下移）。
 *
 * 做法：在 Activity 组合时记录状态栏高度（只增不减，取到过的最大值）并向下提供，
 * 顶部栏与首屏留白都使用这个固定值。
 *
 * 默认 null：未提供时各组件退回使用实时的 `statusBarsPadding()`。
 */
val LocalStableStatusBarTop = compositionLocalOf<Dp?> { null }

/**
 * 当前页面应该使用的**状态栏高度**。
 *
 * 所有页面统一通过它取状态栏高度，而不是各自写一遍
 * `LocalStableStatusBarTop.current ?: WindowInsets.statusBars…`：
 * 各页面的顶部留白因此永远基于同一个数，不会因为某个页面漏了兜底
 * 而整体上移 / 下移。
 */
@Composable
fun rememberStatusBarTopDp(): Dp {
    LocalStableStatusBarTop.current?.let { return it }
    val density = LocalDensity.current
    return with(density) {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    }
}

/**
 * 「稳定」的底部系统导航栏高度 —— 与 [LocalStableStatusBarTop] 同理。
 *
 * 播放器会同时隐藏底部导航栏，返回时它淡入会让底部元素（迷你播放器、
 * 底部悬浮导航栏）跟着上移一小段；使用这个固定值定位即可避免。
 */
val LocalStableNavigationBarBottom = compositionLocalOf<Dp?> { null }
