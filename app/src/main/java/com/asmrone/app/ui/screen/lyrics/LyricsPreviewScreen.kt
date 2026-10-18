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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asmrone.app.R
import com.asmrone.app.data.lyrics.LyricLine
import com.asmrone.app.data.lyrics.LyricsLoad
import com.asmrone.app.data.lyrics.LyricsLoader
import com.asmrone.app.ui.component.GlassCircleButton
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

// ───────────────────────────── 演示数据 ─────────────────────────────

/** 演示总时长：**精确 2:00**。进度条范围、时钟循环、歌词时间轴都以它为准 */
private const val DEMO_TOTAL_MS = 120_000L

/** 前奏空白 / 结尾空白（毫秒） */
private const val LYRIC_INTRO_MS = 600L
private const val LYRIC_TAIL_MS = 600L

/**
 * 演示歌词（**假数据**，与真实音频无关）。
 *
 * 时间轴按 [DEMO_TOTAL_MS] **自动分配**：把「总时长 − 前奏 − 结尾」均分给所有行，
 * 每行只记录**起始时间**（不再有逐字时间片），最后一行也必然落在 2:00 之前。
 */
private val DemoLyrics: List<LyricLine> = run {
    val seed = listOf(
        "こんばんは。今日も一日、お疲れさま。" to "晚上好。今天一整天，辛苦了。",
        "ここに来てくれて、ありがとう。" to "谢谢你来到这里。",
        "まずは、深く息を吸ってみて。" to "先深深吸一口气。",
        "ゆっくり、口から吐いて。もう一度。" to "再慢慢从嘴里吐出来，再一次。",
        "肩の力を、すとんと抜いてね。" to "让肩膀的力气，一下子松掉。",
        "頭のてっぺんから、ゆるめていくよ。" to "从头顶开始，一点点放松。",
        "おでこ、まぶた、頬……" to "额头、眼皮、脸颊……",
        "あごの力も、いらないよ。" to "下巴的力气也不需要了。",
        "首の後ろが、あったかくなってきた。" to "后颈开始变得暖暖的。",
        "手のひらを、ゆっくり開いて。" to "慢慢把手掌张开。",
        "指の先まで、力が抜けていく。" to "力气一直松到指尖。",
        "足のつま先まで、ぽかぽかしてる。" to "连脚尖都暖暖的。",
        "今、君のそばで、息をしているよ。" to "现在，我就在你身边呼吸着。",
        "耳元で、小さく話しているから。" to "在你耳边轻轻地说话。",
        "髪を、そっと撫でてあげる。" to "轻轻抚摸你的头发。",
        "眠くなってきたら、そのままでいい。" to "如果困了，就这样睡吧。",
        "頑張らなくていいんだよ。" to "不用努力也没关系的。",
        "何も考えなくて、大丈夫。" to "什么都不用想，没问题的。",
        "僕の声だけ、追いかけてみて。" to "只要跟着我的声音就好。",
        "遠くの音は、聞こえなくていい。" to "远处的声音，听不见也没关系。",
        "雨の音みたいに、静かに。" to "像雨声一样，安安静静地。",
        "呼吸が、少しずつ深くなる。" to "呼吸一点点变深了。",
        "まぶたが、だんだん重いね。" to "眼皮渐渐变重了呢。",
        "その重さに、身をあずけて。" to "把身体交给那份重量。",
        "大丈夫、ここは安全だから。" to "没关系，这里是安全的。",
        "朝まで、ずっとそばにいるよ。" to "我会一直在你身边，直到早上。",
        "もし目が覚めたら、また呼んで。" to "如果醒了，再叫我一声。",
        "おやすみ。いい夢を見てね。" to "晚安。做个好梦。",
        "また明日、ここで会おうね。" to "明天也在这里见面吧。",
        "ゆっくり、休んでいいよ。" to "慢慢休息就好。",
        "焦らなくて、いいからね。" to "不用着急，也没关系。",
        "今夜は、何もしなくていい。" to "今晚什么也不用做。",
        "静かな部屋で、ふたりきり。" to "安静的房间，只有我们两个。",
        "時計の音だけが、聞こえるね。" to "只听得见钟表的声音呢。",
        "その音に、合わせて呼吸して。" to "跟着那个声音呼吸。",
        "体中が、ふわふわしてきた。" to "整个身体都轻飘飘的。",
        "夢の入り口に、立っているよ。" to "我们正站在梦的入口。",
        "手を、つないでおくね。" to "我牵着你的手。",
        "離さないから、安心して。" to "不会放开，所以安心吧。",
        "明日のことは、明日でいい。" to "明天的事，明天再说就好。",
        "今はただ、眠ってね。" to "现在只管睡吧。",
        "ゆっくり、深く、眠ってね。" to "慢慢地、深深地，睡去吧。",
        "ゆっくり、目を閉じてごらん。" to "慢慢试着闭上眼睛。",
        "おやすみなさい。また明日。" to "晚安，明天见。"
    )
    // 把可用时长均分：每行占一个等长的时间片，行首 = 前奏 + index * pitch
    val pitchMs = (DEMO_TOTAL_MS - LYRIC_INTRO_MS - LYRIC_TAIL_MS) / seed.size
    var cursor = LYRIC_INTRO_MS
    seed.map { (original, translation) ->
        val start = cursor
        cursor += pitchMs
        LyricLine(
            original = original,
            translation = translation,
            startMs = start
        )
    }
}

/** 演示总时长（与进度条 / 时钟循环一致，精确 2:00） */
private val DemoDurationMs: Long
    get() = DEMO_TOTAL_MS

// ───────────────────── Apple Music 风格的参数（对齐参考实现） ─────────────────────

/** 每行的固定高度（含行间距）：固定行高让滚动换算确定 */
private val LyricRowHeight = 104.dp

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

// ───────────────────────────── 页面 ─────────────────────────────

/**
 * 歌词效果预览页（独立演示页，**不接真实音频**）。
 *
 * 效果：**Apple Music 风格的逐字卡拉OK + 交错滚动 + 浏览式拖动**。
 * 参数与做法对齐参考项目 `accompanist-lyrics-ui`（其目标就是复刻 Apple Music）。
 *
 * 交互约定：
 *  - **点击某一行** → 跳到该行（唯一的跳转入口）；
 *  - **上下拖动** → 只是浏览歌词，**不改播放位置**：歌词跟手、逐字填充随手指**连续**变化、
 *    **不做模糊**；松手后若 **3 秒无操作**，平滑回到当前播放的歌词；
 *  - 3 秒窗口内**再次拖动**会**接着上次浏览的位置**继续（不会弹回播放行）。
 */
@Composable
fun LyricsPreviewScreen(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val hazeState = rememberHazeState()

    var playing by remember { mutableStateOf(true) }
    var browsingLyrics by remember { mutableStateOf(false) }

    // 播放时钟（按帧推进，播到末尾回到开头循环）
    val clock = remember { mutableLongStateOf(0L) }
    val currentIndex by remember {
        derivedStateOf {
            DemoLyrics.indexOfLast { it.startMs <= clock.longValue }.coerceAtLeast(0)
        }
    }

    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var lastFrame = 0L
        while (true) {
            withFrameNanos { now ->
                if (lastFrame != 0L) {
                    val deltaMs = (now - lastFrame) / 1_000_000L
                    val next = clock.longValue + deltaMs
                    clock.longValue = if (next >= DemoDurationMs) next - DemoDurationMs else next
                }
                lastFrame = now
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.background)
            .hazeSource(hazeState)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // ── 顶栏 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassCircleButton(
                    hazeState = hazeState,
                    iconRes = R.drawable.ic_arrow_left_fluent,
                    contentDescription = "返回",
                    onClick = onBack
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "歌词效果预览",
                        style = MaterialTheme.typography.titleMedium,
                        color = cs.onBackground
                    )
                    Text(
                        text = "Apple Music 风格 · 点击歌词跳转 · 拖拽浏览（松手惯性 · 3 秒后返回）",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant
                    )
                }
            }

            // ── 歌词区 ──
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AppleMusicLyrics(
                    currentIndex = currentIndex,
                    lines = DemoLyrics,
                    onSeek = { startMs ->
                        // 点击歌词 = 跳转（唯一的跳转入口）
                        clock.longValue = startMs
                        playing = true
                    },
                    onBrowsingChange = { browsingLyrics = it }
                )
            }

            // ── 底部：播放/暂停 + 进度（拖动歌词不会改动它）──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassCircleButton(
                    hazeState = hazeState,
                    iconRes = if (playing) R.drawable.ic_pause_fluent else R.drawable.ic_play_fluent,
                    contentDescription = if (playing) "暂停" else "播放",
                    onClick = { playing = !playing }
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (browsingLyrics) {
                            "浏览歌词中 · 3 秒后回到当前歌词"
                        } else {
                            "${formatMs(clock.longValue)} / ${formatMs(DemoDurationMs)}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (browsingLyrics) cs.primary else cs.onSurfaceVariant
                    )
                    Slider(
                        value = clock.longValue.toFloat(),
                        onValueChange = { value ->
                            playing = false
                            clock.longValue = value.toLong()
                        },
                        valueRange = 0f..DemoDurationMs.toFloat(),
                        modifier = Modifier.height(30.dp)
                    )
                }
            }
        }
    }
}

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
    fadeColor: Color? = null
) {
    if (lines.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val resolvedFocusedColor = focusedColor ?: cs.primary
    val resolvedInactiveColor = inactiveColor ?: cs.onSurfaceVariant
    val resolvedTranslationColor = translationColor ?: cs.onSurfaceVariant.copy(alpha = 0.75f)
    val resolvedFadeColor = fadeColor ?: cs.background
    BoxWithConstraints(modifier = Modifier.fillMaxSize().clipToBounds()) {
        val density = LocalDensity.current
        val viewportPx = with(density) { maxHeight.toPx() }
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
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            LyricLineText(
                                text = line.original,
                                focused = focused,
                                focusedColor = resolvedFocusedColor,
                                inactiveColor = resolvedInactiveColor
                            )
                            if (line.translation.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
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

        // 边缘渐隐：顶部渐隐到「锚点上方 16dp」为止（不盖住当前行），底部下半屏渐隐
        val topFadeEnd = (anchorPx - with(density) { 16.dp.toPx() }).coerceAtLeast(1f)
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(0f to resolvedFadeColor, 1f to Color.Transparent),
                        startY = 0f,
                        endY = topFadeEnd
                    )
                )
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(0f to Color.Transparent, 1f to resolvedFadeColor),
                        startY = viewportPx * 0.5f,
                        endY = viewportPx
                    )
                )
        )
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
 */
@Composable
fun PlayerLyrics(
    workId: Int,
    sourceId: String?,
    trackPath: String,
    trackTitle: String,
    positionMs: Long,
    onSeekTo: (Long) -> Unit
) {
    // 首帧直接拿预热好的结果：命中时整个组合过程不产生挂起
    var result by remember(workId, trackPath) {
        mutableStateOf(LyricsLoader.cached(workId, trackPath))
    }

    LaunchedEffect(workId, trackPath, trackTitle, sourceId) {
        if (result != null) return@LaunchedEffect
        result = LyricsLoader.load(workId, sourceId, trackPath, trackTitle)
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
                fadeColor = Color(0xFF0B0B0D).copy(alpha = 0.78f)
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
