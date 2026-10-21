package com.asmrone.app.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 轻量提示（toast）的状态。
 *
 * 用法：
 * ```
 * val toast = rememberToastState()   // 全局共享的 AppToast
 * // 宿主只在 MainActivity 的最外层挂一次
 * ToastHost(state = AppToast, toastOffset = ..., modifier = Modifier.align(Alignment.BottomCenter))
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
     * 可选的行动按钮（如"已收藏至「我喜欢的」  **修改**"）。
     *
     * 与文字一起保留到淡出结束：淡出过程中按钮不能先消失，否则会看到
     * 提示框里"少了一块"再整体淡掉。
     */
    var actionLabel: String? by mutableStateOf(null)
        private set

    private var action: (() -> Unit)? = null

    /**
     * 自增序号：用来"重启"自动消失的计时。
     *
     * 必须是计数器而不是布尔 —— 连续调用两次 [show] 时，
     * 第二次也要能让 [ToastHost] 里的 `LaunchedEffect` 重新开始计时。
     */
    internal var seq: Int by mutableIntStateOf(0)
        private set

    /** 显示一条纯文字提示；已在显示时会被替换并重新计时 */
    fun show(message: String) {
        showInternal(message, actionLabel = null, action = null)
    }

    /**
     * 显示一条带行动按钮的提示。
     *
     * @param actionLabel 按钮文字（很短，如「修改」）
     * @param onAction 点击按钮：先收起提示，再执行回调
     */
    fun show(message: String, actionLabel: String, onAction: () -> Unit) {
        showInternal(message, actionLabel, onAction)
    }

    private fun showInternal(message: String, actionLabel: String?, action: (() -> Unit)?) {
        // 空消息没有任何意义，直接忽略（比显示一个空框好）
        if (message.isBlank()) return
        text = message
        this.actionLabel = actionLabel?.takeIf { it.isNotBlank() }
        this.action = if (this.actionLabel == null) null else action
        isVisible = true
        seq++
    }

    /** 立即收起 */
    fun dismiss() {
        isVisible = false
    }

    /** 触发行动按钮（由 [ToastHost] 调用，点完立即收起） */
    internal fun runAction() {
        val callback = action
        action = null
        dismiss()
        callback?.invoke()
    }
}

/**
 * 全局轻提示状态 —— 整个进程只有这一份。
 *
 * 以前是每个页面各自 `remember` 一个、各自在自己页面的 `Box` 里挂 host，
 * 结果是提示的位置和层级都跟着页面走：主界面同时有底部导航栏和迷你播放器时，
 * 提示会被压在它们下面（页面的 Box 在 Scaffold 内容区，底栏是 Scaffold 的
 * bottomBar，绘制顺序在后）。
 *
 * 现在只保留一份状态 + 一个 host（挂在 `MainActivity` 最外层 Box 的最后一个
 * 子元素上），提示永远在最上层。多页面共用一份也有副作用，但都是正向的：
 * 页面切换时提示不会残留、也不会出现两条提示叠在一起。
 */
val AppToast: ToastState = ToastState()

/** 记住一个 [ToastState] —— 现在返回全局共享的 [AppToast] */
@Composable
fun rememberToastState(): ToastState = AppToast

/**
 * 轻量提示宿主 —— 固定在屏幕偏下方，不随内容滚动。
 *
 * 样式：半透明深色圆角胶囊（22dp 圆角）+ 正文号浅色文字，淡入 150ms、淡出 400ms，
 * 默认 1.5 秒后自动消失；带行动按钮时停留 4 秒。
 *
 * 字号一度用的是 `labelSmall`（11sp），在 6.7 寸屏上小得几乎读不清，
 * 现在统一成 `bodyMedium`（14sp）——提示是要被读的，不是装饰。
 *
 * 位置刻意"偏下而不是贴底"：贴底会与底部导航栏 / 迷你播放器混在一起，
 * 让提示看起来像界面的一部分而不是临时反馈。
 *
 * **只应该挂一次**（`MainActivity` 最外层 Box 的最后一个子元素），这样它绘制在
 * 底栏、迷你播放器、播放器覆盖层之后，永远在最上层。页面内部再挂会让提示
 * 被 Scaffold 的 bottomBar 盖住 —— 那正是曾经的问题。
 *
 * [toastOffset] 指定 **距屏幕底部抬多高**（组件内部做 `offset(y = -toastOffset)`）。
 */
@Composable
fun ToastHost(
    state: ToastState,
    modifier: Modifier = Modifier,
    toastOffset: Dp = 0.dp,
    durationMs: Long = 1500L
) {
    // 带按钮的提示要留出反应时间：1.5 秒读完再抬手点「修改」根本来不及。
    val effectiveDuration = if (state.actionLabel != null) {
        maxOf(durationMs, ACTION_TOAST_DURATION_MS)
    } else {
        durationMs
    }
    // 每次 show 都重新计时；isVisible 为 false 时不做无谓的等待
    LaunchedEffect(state.seq) {
        if (state.seq > 0 && state.isVisible) {
            delay(effectiveDuration)
            state.dismiss()
        }
    }

    AnimatedVisibility(
        visible = state.isVisible,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(400)),
        // toastOffset 是"距屏幕底部抬多高"：贴底会被底部导航栏 / 迷你播放器挡住。
        // 曾经这个参数只是声明在那里没被用到，结果提示总是压在播放器下面。
        modifier = modifier.offset(y = -toastOffset)
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.text,
                    modifier = Modifier.padding(
                        start = 20.dp,
                        end = if (state.actionLabel == null) 20.dp else 6.dp,
                        top = 12.dp,
                        bottom = 12.dp
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.surface
                )
                state.actionLabel?.let { label ->
                    // 只有状态里存了真实回调时才显示按钮，避免出现点了没反应的假按钮
                    Text(
                        text = label,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { state.runAction() }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        // inversePrimary 是专门给"深色底"用的主色，
                        // 直接拿 primary 在深灰提示条上会偏暗
                        color = MaterialTheme.colorScheme.inversePrimary
                    )
                }
            }
        }
    }
}

/** 带按钮的提示停留时长：足够看清一句话并移手指点到按钮 */
internal const val ACTION_TOAST_DURATION_MS = 4000L
