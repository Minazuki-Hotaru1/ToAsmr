package com.asmrone.app.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.remember
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

/** 记住一个 [ToastState] */
@Composable
fun rememberToastState(): ToastState = remember { ToastState() }

/**
 * 迷你播放器当前占用的高度 —— 由 [MiniPlayerBar] 自己在进出组合时上报。
 *
 * 为什么用全局对象而不是 CompositionLocal：提示宿主挂在**各页面内部**
 * （不经过 Scaffold 的 bottomBar），拿不到播放器那一层的组合上下文；
 * 而要"把提示抬到播放器上方"就必须知道播放器有多高。
 *
 * 值为 `0.dp` 表示"这一屏没有播放器"，提示就回到各页面原本的位置。
 */
object MiniPlayerInset {
    var height: Dp by mutableStateOf(0.dp)
        internal set
}

/** 迷你播放器出现/消失时，提示跟着移动的动画参数 —— 与播放器自己的滑入一致 */
private val MiniInsetSpec = spring<Dp>(dampingRatio = 0.8f, stiffness = 300f)

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
 * 调用方把它放进一个 `Box` 里给 `align`，再用 [toastOffset] 指定
 * **距屏幕底部抬多高**（组件内部做 `offset(y = -(toastOffset + 播放器高度))`）：
 * 主页面（导航栏 + 迷你播放器）约 168dp，详情页在播放器出现时约 128dp。
 *
 * ## 为什么要自动加上播放器高度
 *
 * 提示宿主在页面内容区，而迷你播放器是 Scaffold 的 bottomBar（绘制在后）。
 * 只要两者重叠，提示就会被压住 —— 调大的 `toastOffset` 只是把重叠的概率降低，
 * 播放器一出现（滑入 300ms）就会重新压上来。
 *
 * 现在 [MiniPlayerBar] 把自己真实占用的高度上报给 [MiniPlayerInset]，
 * 这里用同一套弹簧参数把提示顶上去：播放器滑入时提示同步上移，滑出时同步落回，
 * 没有播放器的页面（高度为 0）位置完全不变。
 */
@Composable
fun ToastHost(
    state: ToastState,
    modifier: Modifier = Modifier,
    toastOffset: Dp = 0.dp,
    durationMs: Long = 1500L,
    /** 是否让出迷你播放器的高度；全屏播放器等已经盖住播放器的界面传 false */
    offsetMiniPlayer: Boolean = true
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

    // 有播放器就整体抬到它上方；高度变化时做动画，与播放器自己的滑入滑出同步。
    val miniInset by animateDpAsState(
        targetValue = if (offsetMiniPlayer) MiniPlayerInset.height else 0.dp,
        animationSpec = MiniInsetSpec,
        label = "toastMiniInset"
    )

    AnimatedVisibility(
        visible = state.isVisible,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(400)),
        // toastOffset 是"距屏幕底部抬多高"：贴底会被底部导航栏挡住。
        // 曾经这个参数只是声明在那里没被用到，结果提示总是压在播放器下面。
        modifier = modifier.offset(y = -(toastOffset + miniInset))
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
