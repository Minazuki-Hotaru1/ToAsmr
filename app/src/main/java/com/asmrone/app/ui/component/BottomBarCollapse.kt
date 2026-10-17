package com.asmrone.app.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect

/**
 * 底部导航栏「随页面滚动收起」的共享状态
 * （提炼自 comicApp 的 ui/component/BottomBarState.kt）。
 *
 * 由当前主页面（首页 / 搜索 / 收藏 / 设置）的滚动驱动，
 * 宿主 [MainActivity] 里的悬浮底栏据此收起或展开。
 */
@Stable
class BottomBarState {
    /** true = 已收起 */
    var collapsed by mutableStateOf(false)
}

/** 宿主提供；二级页面拿不到 —— 二级页面本来就不显示底栏，也不需要收起。 */
val LocalBottomBarState = compositionLocalOf<BottomBarState?> { null }

/**
 * 收起判定：**同一方向上连续滚动**超过这个距离才收起。
 * 之前是"方向一变就翻转"，手指轻微回弹、惯性拖尾都会触发，显得很跳。
 */
private val CollapseThreshold = 56.dp

/** 展开判定：向上连续滚动超过这个距离才展开（比收起略松一点，方便把底栏找回来）。 */
private val ExpandThreshold = 40.dp

/**
 * 观察页面滚动，驱动底栏收起 / 展开。
 *
 * 规则：
 *  - **进入页面时 → 展开**（不下判断）：`snapshotFlow` 会立刻吐出当前滚动位置，
 *    把它当成一次滚动事件的话，「在列表中段切走、再切回来」会被误判成「正在向下滚」而立刻收起。
 *  - **同一方向累计滚动超过阈值 → 才翻转**：向下收起、向上展开；
 *    期间方向一变（抖动、回弹、惯性拖尾）累计值立刻清零，所以不灵敏、不跳。
 *  - 跨过整个条目时 offset 会重置，但方向是明确的，因此直接按"已超过阈值"计。
 *
 * [position] 返回「首个可见项下标 to 偏移量」：首页/收藏是瀑布流、
 * 搜索是普通列表、设置页只有偏移量（下标恒为 0），都能用同一种表达。
 *
 * 实现上用 `snapshotFlow` 在组合之外收集滚动状态：滚动过程中不会每帧重组页面，
 * 只有收起状态真正翻转时才会触发底栏动画。
 */
@Composable
fun CollapsibleBottomBarEffect(position: () -> Pair<Int, Int>) {
    val host = LocalBottomBarState.current ?: return
    val density = LocalDensity.current
    LaunchedEffect(host, density) {
        val collapsePx = with(density) { CollapseThreshold.roundToPx() }
        val expandPx = with(density) { ExpandThreshold.roundToPx() }
        val crossItemStep = collapsePx * 2

        var prevIndex = 0
        var prevOffset = 0
        var firstEmission = true
        /** 同一方向上累计的位移；方向一变就清零 */
        var accumulated = 0

        snapshotFlow(position).collect { (index, offset) ->
            if (firstEmission) {
                // 进页面（含从别的页面切回来）一律先展开，且不推断滚动方向
                firstEmission = false
                host.collapsed = false
                prevIndex = index
                prevOffset = offset
                return@collect
            }

            val delta = if (index != prevIndex) {
                if (index > prevIndex) crossItemStep else -crossItemStep
            } else {
                offset - prevOffset
            }
            prevIndex = index
            prevOffset = offset
            if (delta == 0) return@collect

            // 方向翻转 → 重新累计（"不灵敏"的关键）
            if (accumulated != 0 && (delta > 0) != (accumulated > 0)) accumulated = 0
            accumulated += delta

            if (accumulated >= collapsePx) {
                host.collapsed = true
                accumulated = 0
            } else if (accumulated <= -expandPx) {
                host.collapsed = false
                accumulated = 0
            }
        }
    }
}
