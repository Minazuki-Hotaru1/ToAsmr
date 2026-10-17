package com.asmrone.app.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.asmrone.app.ui.theme.Motion

/**
 * 卡片入场淡入的统一参数（提炼自 comicApp 的 CardEntrance.kt）。
 *
 * 首页 / 搜索 / 收藏 / 下载 等所有列表页共用同一套时间参数：
 * 把 `tween(400, delayMillis = (index % 10) * 50)` 集中到一处，
 * 想再调速度只改 [Motion] 里的常量即可。
 */
object CardEntrance {
    /** 单张卡片的淡入时长 */
    const val DURATION_MS = Motion.CARD_ENTRANCE_MS

    /** 相邻卡片之间的错峰间隔 */
    const val STAGGER_MS = Motion.CARD_STAGGER_MS

    /** 错峰分组：序号超过它就回绕，长列表末尾才不会等太久 */
    const val STAGGER_GROUP = Motion.CARD_STAGGER_GROUP
}

/**
 * 按统一参数做入场淡入。
 *
 * 用法与 comicApp 一致 —— 在条目内部：
 * ```
 * val alpha = remember { Animatable(if (id in animatedKeys) 1f else 0f) }
 * LaunchedEffect(Unit) {
 *     if (id !in animatedKeys) {
 *         animatedKeys.add(id)
 *         alpha.animateCardEntrance(index)
 *     }
 * }
 * Box(Modifier.cardEntrance(alpha)) { WorkCard(...) }
 * ```
 */
suspend fun Animatable<Float, AnimationVector1D>.animateCardEntrance(index: Int) {
    animateTo(
        targetValue = 1f,
        animationSpec = tween(
            durationMillis = CardEntrance.DURATION_MS,
            delayMillis = (index % CardEntrance.STAGGER_GROUP) * CardEntrance.STAGGER_MS
        )
    )
}

/**
 * 把入场进度作用到图层上：透明度 + 轻微的向上位移
 * （comicApp 首页的 `translationY = (1 - alpha) * 40f` 就是这一条）。
 */
fun Modifier.cardEntrance(
    alpha: Animatable<Float, AnimationVector1D>,
    translationYRange: Float = 40f
): Modifier = this.graphicsLayer {
    this.alpha = alpha.value
    translationY = (1f - alpha.value) * translationYRange
}
