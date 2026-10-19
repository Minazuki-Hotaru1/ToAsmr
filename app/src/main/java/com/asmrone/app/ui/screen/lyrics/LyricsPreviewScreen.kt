package com.asmrone.app.ui.screen.lyrics

import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.asmrone.app.ui.component.LiquidSlider
import com.asmrone.app.ui.component.rememberStatusBarTopDp
import com.asmrone.app.ui.theme.Liquid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asmrone.app.data.lyrics.LyricLine
import com.asmrone.app.data.lyrics.LyricsLoad
import com.asmrone.app.data.lyrics.LyricsLoader
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

// ───────────────────── Apple Music 风格的参数（对齐参考实现） ─────────────────────

/** 每行的固定高度（含行间距）：固定行高让滚动换算确定。88dp 是缩小后的值 —— 原 104dp 行间空隙偏大 */
private val LyricRowHeight = 88.dp

/** 当前行距视口顶部的固定距离 —— Apple Music 的歌词不居中，而是锚在偏上位置 */
private val FocusAnchor = 64.dp

/** 未聚焦行：透明度 0.4、缩放 0.98（参考实现同值，尺寸不变化） */
private const val INACTIVE_ALPHA = 0.4f
private const val INACTIVE_SCALE = 0.98f

/** 失焦模糊：每远一行 +3dp（参考实现 blurDelta = 3f） */
private const val BLUR_PER_DISTANCE = 3f

/** 交错拖尾：每远一行，滚动动画晚启动 40ms（等效于参考实现里按距离衰减的弹簧刚度） */
private const val STAGGER_STEP_MS = 40

/** 拖动浏览后静置多久回到当前歌词（毫秒） */
private const val BROWSE_RETURN_DELAY_MS = 3_000L

/**
 * 只有离当前行这么近的行才做**交错动画**。
 *
 * 超出的行一定在视口外（视口约 6–8 行高），动画看不见却要占一帧的动画调度；
 * 一首 44 行的歌词本来会同时跑 44 条 `animateFloatAsState`。
 */
private const val MAX_ANIMATED_DISTANCE = 12

/**
 * 只有离当前行这么近的行才**挂模糊**。
 *
 * `Modifier.blur` 会给该行单独开一个渲染层；44 行同时挂就是 44 个模糊层。
 * 更远的行同样在视口外，模糊看不到却要付渲染成本 —— 这是进歌词页那一下卡顿的主因之一。
 */
private const val MAX_BLURRED_DISTANCE = 6

// ─────────────── Apple Music 风格歌词（交错 + 浏览式拖动 + 松手惯性） ───────────────

/**
 * Apple Music 风格歌词视口。
 *
 * 三种位移来源：
 *  - **跟随**（默认）：每行各自动画自己的 `translationY`，启动时间按与当前行的距离错开 → 交错拖尾；
 *  - **拖动 / 抛动浏览**：所有行共享同一个跟手位移（刚性、无交错）；松手后按手势速度做衰减惯性，
 *    碰到第一行 / 最后一行会自然停住，**且浏览与惯性期间都不做模糊**；
 *  - **回位**：静置 [BROWSE_RETURN_DELAY_MS] 后，用一段动画把整列滑回当前播放行，
 *    动画结束后才恢复"跟随"，所以不会硬跳。
 *
 * 拖动**不改变播放位置**：跳转只由点击行触发。
 */
@Composable
private fun AppleMusicLyrics(
    currentIndex: Int,
    lines: List<LyricLine>,
    onSeek: (Long) -> Unit,
    onBrowsingChange: (Boolean) -> Unit,
    focusedColor: Color? = null,
    inactiveColor: Color? = null,
    translationColor: Color? = null,
    rowHorizontalPadding: Dp = 16.dp
) {
    if (lines.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val resolvedFocusedColor = focusedColor ?: cs.primary
    val resolvedInactiveColor = inactiveColor ?: cs.onSurfaceVariant
    val resolvedTranslationColor = translationColor ?: cs.onSurfaceVariant.copy(alpha = 0.75f)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            // 上下边缘的淡出用 **DstIn 蒙版**，而不是盖一层深色。
            //
            // 盖深色的问题：在歌词页上会形成一块边界清楚的暗色区域 ——
            // 播放器里尤其明显（上下是透明顶栏 / 深色控件，中间突然一块更黑），
            // 看起来像"把歌词组件贴进了一个分区"。
            // 蒙版则是把歌词**本身**渐隐到透明，露出来的是页面原本的背景，
            // 与封面页、顶栏、底部控件自然连成一片。
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val h = size.height
                if (h <= 0f) return@drawWithContent
                // 顶部只让"当前行上方 48dp"渐隐（当前行保持完全清晰）；
                // 底部从 62% 开始渐隐到屏幕底。
                val topFade = (48.dp.toPx() / h).coerceIn(0.02f, 0.45f)
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        topFade to Color.Black,
                        0.62f to Color.Black,
                        1f to Color.Transparent
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
    ) {
        val density = LocalDensity.current
        val rowPx = with(density) { LyricRowHeight.toPx() }
        val anchorPx = with(density) { FocusAnchor.toPx() }

        // 让第 currentIndex 行的顶部正好落在锚点位置
        val targetOffset = anchorPx - currentIndex * rowPx
        // 拖动的合法范围：第一行贴锚点 ↔ 最后一行贴锚点
        val maxOffset = anchorPx
        val minOffset = anchorPx - lines.lastIndex * rowPx
        val latestTarget by rememberUpdatedState(targetOffset)

        // ── 浏览状态 ──
        var browsing by remember { mutableStateOf(false) }        // 手动位移是否生效（拖动 / 惯性 / 回位）
        var dragging by remember { mutableStateOf(false) }        // 手指是否仍按在屏幕上
        var flinging by remember { mutableStateOf(false) }        // 松手后的惯性是否仍在跑
        var returning by remember { mutableStateOf(false) }       // 3 秒静置后是否正在滑回
        var browsePx by remember { mutableFloatStateOf(0f) }      // 手动位移（px）
        var interactionTick by remember { mutableIntStateOf(0) }  // 松手 / 惯性结束时 +1，重新开始 3 秒计时
        val returnAnim = remember { Animatable(0f) }              // 回位动画
        val browseAnim = remember { Animatable(0f) }              // 惯性动画的载体（带上下界）
        val velocityTracker = remember { VelocityTracker() }
        val scope = rememberCoroutineScope()
        val minFlingVelocity = LocalViewConfiguration.current.minimumFlingVelocity
        val flingDecay = rememberSplineBasedDecay<Float>()
        var flingJob by remember { mutableStateOf<Job?>(null) }

        // 当前"手动位移"：浏览 / 惯性 / 回位中才非空。
        // 注意顺序：回位动画期间必须优先读 [returnAnim]，否则会一直读到静止的 [browsePx]，
        // 看起来就像"3 秒后直接跳回当前行"、完全没有过渡动画。
        val manualOffset: () -> Float? = {
            if (returning) returnAnim.value else if (browsing) browsePx else null
        }

        val browsingActive = browsing || returning
        // 字体突出 / 颜色只跟随**正在播放的那一行**（currentIndex）；
        // 浏览或惯性时不让“离锚点最近的行”抢走这个样式。
        val focusedIndex = currentIndex

        // 松手后的惯性：把手指速度交给 splineBasedDecay，Animatable 的上下界负责在
        // 第一行 / 最后一行自然停住；跑完后重新起 3 秒回位计时。
        fun startFling(velocityY: Float) {
            flingJob?.cancel()
            flinging = true
            flingJob = scope.launch {
                browseAnim.updateBounds(minOffset, maxOffset)
                browseAnim.snapTo(browsePx)
                browseAnim.animateDecay(
                    initialVelocity = velocityY,
                    animationSpec = flingDecay
                ) {
                    browsePx = value
                }
                flinging = false
                interactionTick++
            }
        }

        // 手指抬起且没有惯性时，静置 3 秒 → 平滑滑回当前歌词，然后恢复跟随。
        // 拖动 / 惯性期间这个 effect 直接返回，所以按住不放不会被回位动画打断。
        LaunchedEffect(browsing, dragging, flinging, interactionTick) {
            if (!browsing || dragging || flinging) return@LaunchedEffect
            delay(BROWSE_RETURN_DELAY_MS)
            returnAnim.snapTo(browsePx)
            returning = true
            returnAnim.animateTo(latestTarget, tween(durationMillis = 460, easing = FastOutSlowInEasing))
            returning = false
            browsing = false
            onBrowsingChange(false)
        }

        Layout(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(anchorPx, rowPx, minOffset, maxOffset) {
                    // 自己留一组相邻采样：部分设备 / 模拟器上 VelocityTracker 会返回 0，
                    // 这时用最后两帧的位移回退计算速度，保证惯性一定能触发。
                    var prevMoveTime = 0L
                    var prevMoveY = 0f
                    var lastMoveTime = 0L
                    var lastMoveY = 0f
                    detectVerticalDragGestures(
                        onDragStart = {
                            prevMoveTime = 0L
                            prevMoveY = 0f
                            lastMoveTime = 0L
                            lastMoveY = 0f
                            // 手势优先：立刻停掉可能还在跑的惯性 / 回位
                            flingJob?.cancel()
                            flingJob = null
                            flinging = false
                            velocityTracker.resetTracking()

                            // 已经在浏览（3 秒窗口内再次拖动）就保留上次拖到的位置，不要弹回播放行。
                            if (returning) {
                                browsePx = returnAnim.value
                                returning = false
                            } else if (!browsing) {
                                browsePx = latestTarget
                            }
                            dragging = true
                            browsing = true
                            interactionTick++
                            onBrowsingChange(true)
                        },
                        onVerticalDrag = { change, deltaY ->
                            prevMoveTime = lastMoveTime
                            prevMoveY = lastMoveY
                            lastMoveTime = change.uptimeMillis
                            lastMoveY = change.position.y
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            browsePx = (browsePx + deltaY).coerceIn(minOffset, maxOffset)
                        },
                        onDragEnd = {
                            val trackedVelocity = velocityTracker.calculateVelocity().y
                            val manualVelocity = if (lastMoveTime > prevMoveTime && prevMoveTime > 0L) {
                                (lastMoveY - prevMoveY) /
                                    (lastMoveTime - prevMoveTime).toFloat() * 1000f
                            } else {
                                0f
                            }
                            val velocityY = if (abs(trackedVelocity) > abs(manualVelocity)) {
                                trackedVelocity
                            } else {
                                manualVelocity
                            }
                            if (abs(velocityY) > minFlingVelocity) {
                                startFling(velocityY)
                                dragging = false
                            } else {
                                dragging = false
                                interactionTick++
                            }
                        },
                        onDragCancel = {
                            dragging = false
                            interactionTick++
                        }
                    )
                },
            content = {
                lines.forEachIndexed { index, line ->
                    val distance = abs(index - currentIndex)
                    val focused = index == focusedIndex

                    // 跟随模式下的交错位移（浏览期间不生效，但仍继续跑，回位后可无缝接上）
                    //
                    // 只有视口附近的行才走交错动画；更远的行用 snap 直接到位 ——
                    // 它们在视口外，看不到差别，但省掉几十条并行动画。
                    val nearEnoughToStagger = distance <= MAX_ANIMATED_DISTANCE
                    val followOffset by animateFloatAsState(
                        targetValue = targetOffset,
                        animationSpec = if (nearEnoughToStagger) {
                            tween(
                                durationMillis = 650,
                                delayMillis = (distance * STAGGER_STEP_MS).coerceAtMost(320),
                                easing = FastOutSlowInEasing
                            )
                        } else {
                            snap()
                        },
                        label = "lineOffset"
                    )

                    val focusScale by animateFloatAsState(
                        targetValue = if (focused) 1f else INACTIVE_SCALE,
                        animationSpec = if (focused) {
                            tween(durationMillis = 600, easing = LinearOutSlowInEasing)
                        } else {
                            tween(durationMillis = 300, easing = EaseInOut)
                        },
                        label = "lineScale"
                    )
                    val focusAlpha by animateFloatAsState(
                        targetValue = if (focused) 1f else INACTIVE_ALPHA,
                        animationSpec = tween(durationMillis = 300),
                        label = "lineAlpha"
                    )
                    // 浏览（拖动 / 回位）期间**去掉模糊**，让用户看清内容。
                    // 远离当前行的行也压成 0：它们在视口外，挂模糊只是白花渲染成本。
                    val blurTarget = if (browsingActive || distance > MAX_BLURRED_DISTANCE) {
                        0f
                    } else {
                        (distance * BLUR_PER_DISTANCE).coerceAtMost(9f)
                    }
                    val blurRadius by animateFloatAsState(
                        targetValue = blurTarget,
                        animationSpec = tween(durationMillis = 300),
                        label = "lineBlur"
                    )
                    val blurable = blurRadius > 0.05f

                    Box(
                        modifier = Modifier
                            .graphicsLayer {
                                // 浏览时整列刚性跟手；否则用各自的交错跟随位移
                                translationY = manualOffset() ?: followOffset
                            }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(LyricRowHeight)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { onSeek(line.startMs) }
                                )
                                .graphicsLayer {
                                    scaleX = focusScale
                                    scaleY = focusScale
                                    alpha = focusAlpha
                                    transformOrigin = TransformOrigin(0f, 1f)
                                }
                                // 只有需要模糊时才挂：Modifier.blur 会为该行单开渲染层
                                .then(
                                    if (blurable) Modifier.blur(blurRadius.dp) else Modifier
                                )
                                .padding(horizontal = rowHorizontalPadding),
                            verticalArrangement = Arrangement.Center
                        ) {
                            LyricLineText(
                                text = line.original,
                                focused = focused,
                                focusedColor = resolvedFocusedColor,
                                inactiveColor = resolvedInactiveColor
                            )
                            if (line.translation.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = line.translation,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = resolvedTranslationColor,
                                    textAlign = TextAlign.Start,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        ) { measurables, constraints ->
            // 关键：**所有行都必须被测量并放到自己的行位上**，但容器本身只占视口大小。
            // 之前直接用 Column 时，Column 会被父级视口高度约束，只有前 ~6 行拿到 104dp，
            // 其余行被压成 0 高 —— 这就是「歌词只显示前六行」的根因。
            // 而直接把 Column 撑到 44 行高，又会被 BoxWithConstraints 重新居中（行位整体跑到视口上方）。
            // 用自定义 Layout：返回视口尺寸，子行按 index * 行高 定位，超出部分由外层 clipToBounds 裁掉，
            // graphicsLayer.translationY 才能把任意一行移进视口。
            val rowHeightPx = LyricRowHeight.roundToPx()
            val width = constraints.maxWidth
            val placeables = measurables.map { measurable ->
                measurable.measure(Constraints.fixed(width, rowHeightPx))
            }
            layout(width, constraints.maxHeight) {
                placeables.forEachIndexed { i, placeable ->
                    placeable.place(0, i * rowHeightPx)
                }
            }
        }

        // 边缘渐隐已经由外层的 DstIn 蒙版完成（见 BoxWithConstraints 的 modifier），
        // 这里不再盖深色渐变 —— 那正是"分区感"的来源。
    }
}

/**
 * 给播放页复用的歌词页。
 *
 * ## 加载时机（这是卡顿的根因，已改）
 *
 * 旧实现在这里 `LaunchedEffect` 里直接调 `LyricsLoader.load` —— 于是**每次打开
 * 播放器**都要在展开动画期间做"取文件树 → 找歌词 → 下载 → 解析"，
 * 冷启动时甚至要拉那棵最大 6MB 的树。
 *
 * 现在：
 *  1. **开始播放时**就在后台预热（`MainActivity` 里监听当前曲目变化调
 *     `LyricsLoader.prefetch`）；
 *  2. 这里首帧先同步读内存缓存（`LyricsLoader.cached`），命中就直接渲染，
 *     **组合期间零网络、零解析**；
 *  3. 只有缓存没命中（预热还没完成 / 预热失败）才回落到异步加载。
 *
 * 所以正常路径下，滑到歌词页看到的已经是成品。
 *
 * ## [active]：不显示时不联网
 *
 * 播放器覆盖层是常驻组合的（冷启动就挂上）。如果一挂上就来这里加载歌词，
 * 就会出现"用户什么都没点，网络日志里却多了 `/api/media/stream/...`"。
 *
 * 所以由覆盖层把"是否正在显示"传进来：
 *  - `false` → 只读内存与本地磁盘（[LyricsLoader] 落过盘的歌词照样秒出，
 *    预热效果不丢）；没有就保持加载态，**不发请求**；
 *  - `true`  → 允许联网补齐。
 */
@Composable
fun PlayerLyrics(
    workId: Int,
    sourceId: String?,
    trackPath: String,
    trackTitle: String,
    positionMs: Long,
    onSeekTo: (Long) -> Unit,
    active: Boolean = true
) {
    // 首帧直接拿预热好的结果：命中时整个组合过程不产生挂起
    var result by remember(workId, trackPath) {
        mutableStateOf(LyricsLoader.cached(workId, trackPath))
    }

    LaunchedEffect(workId, trackPath, trackTitle, sourceId, active) {
        if (result != null) return@LaunchedEffect
        result = LyricsLoader.load(
            workId = workId,
            sourceId = sourceId,
            trackPath = trackPath,
            trackTitle = trackTitle,
            allowNetwork = active
        )
    }

    when (val load = result) {
        null -> LyricsPlaceholder(
            loading = true,
            title = "正在加载歌词…",
            hint = "正在查找与当前曲目同名的歌词文件"
        )

        LyricsLoad.None -> LyricsPlaceholder(
            loading = false,
            title = "未找到匹配的歌词",
            hint = "需要有一个与音频同名的歌词文件（可放在任意文件夹）"
        )

        is LyricsLoad.Failed -> LyricsPlaceholder(
            loading = false,
            title = "歌词加载失败",
            hint = load.message
        )

        is LyricsLoad.Ready -> {
            val currentIndex = load.lines
                .indexOfLast { it.startMs <= positionMs }
                .coerceAtLeast(0)
            AppleMusicLyrics(
                currentIndex = currentIndex,
                lines = load.lines,
                onSeek = onSeekTo,
                onBrowsingChange = {},
                focusedColor = Color.White,
                inactiveColor = Color.White.copy(alpha = 0.8f),
                translationColor = Color.White.copy(alpha = 0.55f),
                // 与封面页的左右留白对齐（封面页是 26dp），歌词不会显得贴边
                rowHorizontalPadding = 26.dp
            )
        }
    }
}

@Composable
private fun LyricsPlaceholder(
    loading: Boolean,
    title: String,
    hint: String? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(14.dp))
            }
            Text(
                text = title,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            if (!hint.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = hint,
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * 一行歌词：**不再做逐字扫光**。
 *
 * 当前行整行用主色高亮；其它行用弱化色，由外层 [AppleMusicLyrics] 统一做
 * alpha / 模糊 / 缩放过渡。字号所有行一致（Apple Music 不靠字号区分当前行）。
 */
@Composable
private fun LyricLineText(
    text: String,
    focused: Boolean,
    focusedColor: Color,
    inactiveColor: Color
) {
    Text(
        text = text,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        color = if (focused) focusedColor else inactiveColor,
        textAlign = TextAlign.Start,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

/** 毫秒 → m:ss */
private fun formatMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
