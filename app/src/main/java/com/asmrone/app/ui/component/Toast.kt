package com.asmrone.app.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 轻量提示（toast）的状态。
 *
 * 用法：
 * ```
 * val toast = rememberToastState()
 * // 在某个 Box 里挂上宿主
 * ToastHost(state = toast, toastOffset = 48.dp, modifier = Modifier.align(Alignment.BottomCenter))
 * // 任意位置触发
 * toast.show("刷新失败")
 * ```
 *
 * ## 为什么"文字"与"是否显示"分成两份状态
 *
 * 这是从 comicApp 抄过来的一个细节，也是这个组件最容易被写错的地方：
 * 退出动画是淡出，需要**在隐藏过程中继续显示文字**。
 * 如果只用一个 `String?`（null = 不显示），淡出刚开始文字就被置空，
 * 用户会看到提示框"空着"闪一下才消失。
 *
 * 所以 [text] 一直保留最后一条内容，是否可见由 [isVisible] 决定。
 */
class ToastState internal constructor() {

    var text: String by mutableStateOf("")
        private set

    var isVisible: Boolean by mutableStateOf(false)
        private set

    /**
     * 自增序号：用来"重启"自动消失的计时。
     *
     * 必须是计数器而不是布尔 —— 连续调用两次 [show] 时，
     * 第二次也要能让 [ToastHost] 里的 `LaunchedEffect` 重新开始计时。
     */
    internal var seq: Int by mutableIntStateOf(0)
        private set

    /** 显示一条提示；已在显示时会被替换并重新计时 */
    fun show(message: String) {
        // 空消息没有任何意义，直接忽略（比显示一个空框好）
        if (message.isBlank()) return
        text = message
        isVisible = true
        seq++
    }

    /** 立即收起 */
    fun dismiss() {
        isVisible = false
    }
}

/** 记住一个 [ToastState] */
@Composable
fun rememberToastState(): ToastState = remember { ToastState() }

/**
 * 轻量提示宿主 —— 固定在屏幕偏下方，不随内容滚动。
 *
 * 样式与 **comicApp 漫画详情页**的「已复制」提示一致：
 * 半透明深色圆角胶囊 + 小号浅色文字，淡入 150ms、淡出 400ms，
 * 默认 1.5 秒后自动消失。
 *
 * 位置刻意"偏下而不是贴底"：贴底会与底部导航栏混在一起，
 * 让提示看起来像界面的一部分而不是临时反馈。
 *
 * 调用方需要把它放在一个 `Box` 里并自己给 `align` / `offset`
 * （见 `toastOffset`），这样同一个组件在带底部导航与不带导航的页面
 * 都能落到合适的位置。
 */
@Composable
fun ToastHost(
    state: ToastState,
    modifier: Modifier = Modifier,
    toastOffset: Dp = 0.dp,
    durationMs: Long = 1500L
) {
    // 每次 show 都重新计时；isVisible 为 false 时不做无谓的等待
    LaunchedEffect(state.seq) {
        if (state.seq > 0 && state.isVisible) {
            delay(durationMs)
            state.dismiss()
        }
    }

    AnimatedVisibility(
        visible = state.isVisible,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(400)),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
        ) {
            Text(
                text = state.text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.surface
            )
        }
    }
}
