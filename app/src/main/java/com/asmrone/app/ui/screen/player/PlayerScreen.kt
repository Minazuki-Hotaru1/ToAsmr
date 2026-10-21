package com.asmrone.app.ui.screen.player

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.asmrone.app.ui.component.LiquidSlider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.asmrone.app.R
import com.asmrone.app.data.model.Track
import com.asmrone.app.data.model.Work
import com.asmrone.app.data.prefs.AppPrefs
import com.asmrone.app.player.AudioController
import com.asmrone.app.player.LyricsOverlay
import com.asmrone.app.player.PlayQueuePolicy
import com.asmrone.app.ui.component.CoverImage
import com.asmrone.app.ui.component.PlayingIndicator
import com.asmrone.app.ui.component.ToastHost
import com.asmrone.app.ui.component.rememberToastState
import com.asmrone.app.ui.screen.lyrics.PlayerLyrics
import com.asmrone.app.ui.theme.Sizes
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// ── 播放器的深色沉浸色板（对齐 Apple Music Now Playing 的「封面取色 + 黑底」）──
private val PlayerBackground = Color(0xFF0B0B0D)
private val PlayerSheet = Color(0xFF1B1B1D)
private val PlayerText = Color.White
private val PlayerSecondary = Color.White.copy(alpha = 0.62f)
private val PlayerTertiary = Color.White.copy(alpha = 0.42f)
private val PlayerChrome = Color.White.copy(alpha = 0.10f)

/**
 * 全屏播放器 —— 参考 Apple Music 的 Now Playing 布局：
 *
 *  - 全屏封面模糊底图 + 深色渐变遮罩；
 *  - 大面积方形封面（圆角 + 阴影）；
 *  - 左对齐的曲目名 / 作品信息；
 *  - 细进度条 + 已播 / 剩余时间；
 *  - 上一首、白色圆形播放键、下一首；
 *  - 顶部仅保留收起与播放队列按钮；队列放在底部弹窗里，避免主界面堆信息。
 *
 * 这是一个通用的「苹果式」播放器布局，不使用 Apple 的 Logo、品牌粉色或任何专有素材。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    immersiveSystemBars: Boolean = true
) {
    val state by AudioController.state.collectAsState()
    var showQueue by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val sleepUntilTrackEnd by AppPrefs.sleepUntilTrackEnd.collectAsState()
    val playMode by AppPrefs.playMode.collectAsState()
    val context = LocalContext.current
    val toast = rememberToastState()

    // 悬浮窗歌词：开关状态就是 AppPrefs 里那一个布尔值 ——
    // 悬浮窗内部点「删除」时也能把这里的图标同步关掉，不用两边各存一份。
    val overlayLyricsOn by AppPrefs.overlayLyrics.collectAsState()
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val shown = LyricsOverlay.show(context)
        AppPrefs.setOverlayLyrics(shown)
        if (!shown) toast.show("没有获得悬浮窗权限")
    }
    LaunchedEffect(Unit) {
        LyricsOverlay.restore(context)
    }

    // 播放器是深色沉浸页：临时把系统栏图标切成白色，离开时恢复应用主题的设置。
    // 作为「迷你播放器展开」的覆盖层时，只有铺满屏幕后才切，避免在亮色 app 上提前出现白图标。
    val view = LocalView.current
    DisposableEffect(view, immersiveSystemBars) {
        if (!immersiveSystemBars) {
            onDispose { }
        } else {
            val controller = (view.context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view)
            }
            val oldStatus = controller?.isAppearanceLightStatusBars
            val oldNav = controller?.isAppearanceLightNavigationBars
            controller?.isAppearanceLightStatusBars = false
            controller?.isAppearanceLightNavigationBars = false
            onDispose {
                if (controller != null) {
                    oldStatus?.let { controller.isAppearanceLightStatusBars = it }
                    oldNav?.let { controller.isAppearanceLightNavigationBars = it }
                }
            }
        }
    }

    // 拖动进度条时用本地值，避免与 500ms 的位置刷新打架
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    val progress = if (dragging) dragValue else state.progress
    val totalSeconds = (state.durationMs / 1000L).coerceAtLeast(0L)
    val elapsedSeconds = (progress * totalSeconds).toLong().coerceIn(0L, totalSeconds)
    val remainingSeconds = (totalSeconds - elapsedSeconds).coerceAtLeast(0L)

    val metaLine = buildString {
        val work = state.workTitle.trim()
        if (work.isNotEmpty()) append(work)
        if (state.tracks.isNotEmpty()) {
            if (isNotEmpty()) append(" · ")
            append("第 ${state.index + 1} / ${state.tracks.size} 首")
        }
    }

    // 定时进行中时把剩余时间直接做进按钮文案，省掉一层"看一眼还剩多久"的弹窗
    val sleepTimerLabel = if (state.sleepTimerRemainingMs > 0L) {
        "定时 ${formatClock(state.sleepTimerRemainingMs)}"
    } else {
        "定时"
    }

    // 第 0 页 = 封面（初始页，左），第 1 页 = 歌词（右）。
    // 从封面向左划露出右侧歌词页；向右划回封面。
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    val lyricPositionMs = if (dragging) {
        (dragValue * state.durationMs.toFloat()).toLong()
    } else {
        state.positionMs.toLong()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(PlayerBackground)) {
        // ── 封面模糊底图（**降采样模糊**）──
        //
        // 之前是 `AsyncImage(fillMaxSize()).blur(56.dp)`：等于对一整屏
        // （1080×2400 级别）做高斯模糊，光栅化面积是整块屏幕，首帧非常贵 ——
        // 这正是"点开播放器仍能感觉到的那一下"。
        //
        // 现在改成：只在一张 **1/8 大小**的小图层上做小半径模糊，再用
        // graphicsLayer 把它放大 8 倍铺满。光栅化面积降到 1/64，而放大之后
        // 本来就是糊的，视觉上几乎看不出差别（alpha 0.55 还会再压一层暗）。
        //
        // 顺带让 Coil 只解一张小图（size(96)）：底图最终要放大并模糊，
        // 解 600×600 再让它糊掉纯属浪费内存与解码时间。
        val cover = state.coverUrl
        if (!cover.isNullOrBlank()) {
            val sampleFactor = 8
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(cover)
                    .size(96)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(maxWidth / sampleFactor, maxHeight / sampleFactor)
                    .graphicsLayer {
                        scaleX = sampleFactor.toFloat()
                        scaleY = sampleFactor.toFloat()
                    }
                    // 小图层上的 5dp，放大 8 倍后≈原来的 40dp 观感
                    .blur(5.dp)
                    .alpha(0.55f)
            )
        }

        // 深色渐变：顶部略暗、中部让封面透出、底部保证文字与控件可读
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.30f),
                        0.45f to Color.Black.copy(alpha = 0.42f),
                        1.0f to PlayerBackground.copy(alpha = 0.96f)
                    )
                )
        )

        // ── 歌词页的整页压暗 ──
        //
        // 透明度跟着 pager 的滑动进度连续变化：封面页 0 → 歌词页约 0.55。
        // 关键是压暗**整屏**（含顶栏与底部控件），而不是让歌词组件在页面中间
        // 自己铺一块黑色渐变 —— 后者会在页面上形成边界清楚的"分区"，
        // 这正是歌词页看起来"像贴上去的"的原因。
        //
        // 单独做成一个 composable：滑动的每一帧只重组这一层，不牵动整页。
        LyricsBackdrop(pagerState)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            // ── 顶部：收起 + 分享（Apple Music 的极简 chrome）──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayerIconButton(
                    iconRes = R.drawable.ic_chevron_right_fluent,
                    contentDescription = "收起播放器",
                    onClick = onBack,
                    rotate = 90f
                )
                Spacer(Modifier.weight(1f))
                // 分享：只复制链接，不调系统分享面板 —— 这个 app 的分享场景
                // 基本是"把作品发给同好"，一个链接比一堆 App 图标更快。
                PlayerIconButton(
                    iconRes = R.drawable.ic_share_fluent,
                    contentDescription = "复制作品链接",
                    onClick = {
                        val link = shareLink(state.workSourceId, state.workId)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        if (clipboard != null && link.isNotBlank()) {
                            clipboard.setPrimaryClip(ClipData.newPlainText("asmr.one", link))
                            toast.show("链接已复制：$link")
                        } else {
                            toast.show("暂时没有可分享的链接")
                        }
                    }
                )
            }

            // ── 可左右滑的内容区：封面（左，初始页） / 歌词（右）──
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                // 关键：默认 Pager 只组合当前页。歌词页第一次滑入时才组合
                // PlayerLyrics（44 行、每行多个 animateFloatAsState + blur 图层），
                // 这一下会卡在滑动手势里。这里预组合相邻页，让歌词的首次组合/测量
                // 提前到播放器页面出现时完成。
                beyondViewportPageCount = 1
            ) { page ->
                if (page == 0) {
                    PlayerCoverPage(
                        coverUrl = state.coverUrl,
                        workTitle = state.workTitle,
                        trackTitle = state.trackTitle,
                        metaLine = metaLine,
                        error = state.error
                    )
                } else {
                    PlayerLyrics(
                        workId = state.workId,
                        sourceId = state.workSourceId,
                        trackPath = state.currentTrack?.path.orEmpty(),
                        trackTitle = state.trackTitle,
                        positionMs = lyricPositionMs,
                        onSeekTo = { AudioController.seekTo(it.toInt()) }
                    )
                }
            }

            // 两页指示点：左 = 封面，右 = 歌词
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(2) { index ->
                    val active = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (active) 6.dp else 4.dp)
                            .clip(CircleShape)
                            .background(if (active) PlayerText else PlayerText.copy(alpha = 0.25f))
                    )
                }
            }

            // ── 固定底部：细进度条 + 时间 ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 26.dp)
            ) {
                LiquidSlider(
                    value = { progress },
                    onValueChange = {
                        dragging = true
                        dragValue = it
                    },
                    onValueChangeFinished = {
                        AudioController.seekToFraction(dragValue)
                        dragging = false
                    },
                    valueRange = 0f..1f,
                    visibilityThreshold = 0.001f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = Work.formatDuration(elapsedSeconds),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = PlayerTertiary
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "-${Work.formatDuration(remainingSeconds)}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = PlayerTertiary
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── 固定底部：传输控制 ──
            //
            // 五个按钮：上一首 / 下一首两侧各加一个**跳转**键 ——
            // 右侧 +30 秒（跳过还没听到的一段，ASMR 常见的"直接进正题"），
            // 左侧 −5 秒（回退刚听过的一小段，复听一句刚才没听清的台词）。
            // 图标用**描边**的双箭头（与上一首/下一首的描边风格一致），
            // 秒数仍由下方的小字给出 —— 这两个动作的时长不是平台惯例值，
            // 只靠图标无法表达"5 秒"还是"10 秒"。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayerSkipButton(
                    iconRes = R.drawable.ic_rewind_outline_fluent,
                    label = "-5s",
                    contentDescription = "回退 5 秒",
                    onClick = { AudioController.skipBack(5) },
                    enabled = state.isPrepared
                )

                IconButton(
                    onClick = { AudioController.prev() },
                    enabled = state.canSkipPrev,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_skip_previous_fluent),
                        contentDescription = "上一首",
                        tint = if (state.canSkipPrev) PlayerText else PlayerTertiary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(70.dp)
                        .shadow(10.dp, CircleShape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
                        .clip(CircleShape)
                        .background(PlayerText)
                        .clickable { AudioController.togglePlayPause() },
                    contentAlignment = Alignment.Center
                ) {
                    if (state.isBuffering && !state.isPrepared) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.dp,
                            color = Color.Black
                        )
                    } else {
                        Icon(
                            painter = painterResource(
                                if (state.isPlaying) R.drawable.ic_pause_fluent
                                else R.drawable.ic_play_fluent
                            ),
                            contentDescription = if (state.isPlaying) "暂停" else "播放",
                            tint = Color.Black,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                IconButton(
                    onClick = { AudioController.next() },
                    enabled = state.canSkipNext,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_skip_next_fluent),
                        contentDescription = "下一首",
                        tint = if (state.canSkipNext) PlayerText else PlayerTertiary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                PlayerSkipButton(
                    iconRes = R.drawable.ic_fast_forward_outline_fluent,
                    label = "+30s",
                    contentDescription = "快进 30 秒",
                    onClick = { AudioController.skipForward(30) },
                    enabled = state.isPrepared
                )
            }

            Spacer(Modifier.height(6.dp))

            // ── 底部四个入口：定时 / 播放模式 / 播放队列 / 设置 ──
            //
            // 只留图标，不再带文字：这一排是低频的"工具"入口，文字说明会让
            // 底部显得拥挤，而且和上方传输控制之间没有视觉分区时更像"没做完"。
            // 四个 Fluent 图标（timer / repeat / queue / settings）本身语义足够清楚，
            // 文案改由 contentDescription 承担，不影响无障碍朗读。
            //
            // 播放模式（列表 / 单曲 / 随机）放在这里而不是设置里：它会随手切换，
            // 属于"当前这次播放"的一部分，藏进设置会多点两下。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayerBottomIconButton(
                    iconRes = R.drawable.ic_timer_fluent,
                    contentDescription = if (state.sleepTimerRemainingMs > 0L) {
                        "定时关闭（$sleepTimerLabel）"
                    } else {
                        "定时关闭"
                    },
                    onClick = { showSleepTimer = true }
                )
                PlayerBottomIconButton(
                    iconRes = when (playMode) {
                        PlayQueuePolicy.MODE_SINGLE -> R.drawable.ic_repeat_one_fluent
                        PlayQueuePolicy.MODE_SHUFFLE -> R.drawable.ic_shuffle_fluent
                        else -> R.drawable.ic_repeat_all_fluent
                    },
                    contentDescription = "播放模式：${PlayQueuePolicy.label(playMode)}",
                    onClick = {
                        val next = PlayQueuePolicy.nextMode(playMode)
                        AppPrefs.setPlayMode(next)
                        toast.show(PlayQueuePolicy.label(next))
                    }
                )
                PlayerBottomIconButton(
                    iconRes = R.drawable.ic_queue_outline_fluent,
                    contentDescription = "播放队列",
                    onClick = { showQueue = true }
                )
                // 悬浮窗歌词：开着的时候图标保持高亮（白），关着是常规的 62% 白。
                // 状态不跟播放器生命周期绑定 —— 退出播放页窗口还在，正是这个功能的用途。
                PlayerBottomIconButton(
                    iconRes = R.drawable.ic_picture_in_picture_fluent,
                    contentDescription = if (overlayLyricsOn) "关闭悬浮窗歌词" else "悬浮窗歌词",
                    active = overlayLyricsOn,
                    onClick = {
                        when {
                            LyricsOverlay.isShowing -> {
                                LyricsOverlay.hide()
                                AppPrefs.setOverlayLyrics(false)
                            }

                            !LyricsOverlay.canDraw(context) -> {
                                toast.show("请在系统设置里允许「显示在其他应用上层」")
                                runCatching {
                                    overlayPermissionLauncher.launch(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                    )
                                }
                            }

                            else -> {
                                val shown = LyricsOverlay.show(context)
                                AppPrefs.setOverlayLyrics(shown)
                                if (!shown) toast.show("悬浮窗歌词打开失败")
                            }
                        }
                    }
                )
                PlayerBottomIconButton(
                    iconRes = R.drawable.ic_settings_fluent,
                    contentDescription = "播放设置",
                    onClick = { showSettings = true }
                )
            }

            Spacer(Modifier.height(20.dp))
        }

        ToastHost(
            state = toast,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 132.dp)
        )
    }

    if (showQueue) {
        PlayerQueueSheet(
            tracks = state.tracks,
            currentIndex = state.index,
            onSelect = { index ->
                AudioController.playTrackAt(index)
                showQueue = false
            },
            onDismiss = { showQueue = false }
        )
    }

    if (showSleepTimer) {
        SleepTimerSheet(
            remainingMs = state.sleepTimerRemainingMs,
            untilTrackEnd = sleepUntilTrackEnd,
            onToggleUntilTrackEnd = { AppPrefs.setSleepUntilTrackEnd(it) },
            onStart = { minutes ->
                AudioController.setSleepTimer(minutes)
                // 弹窗关掉之后没有任何"正在计时"的提示，用户会不确定按到没有
                toast.show(
                    if (AppPrefs.sleepUntilTrackEnd.value) {
                        "定时 $minutes 分钟 · 这一首会放完再停"
                    } else {
                        "定时 $minutes 分钟"
                    }
                )
                // 收起动画已经播完（closeWith 里先 hide 再回调），这里移除不会再"闪"
                showSleepTimer = false
            },
            onCancel = {
                AudioController.setSleepTimer(0)
                toast.show("定时已取消")
                showSleepTimer = false
            },
            onDismiss = { showSleepTimer = false }
        )
    }

    if (showSettings) {
        PlayerSettingsSheet(onDismiss = { showSettings = false })
    }
}

/** 播放器底部的纯图标入口（定时 / 播放模式 / 播放队列 / 悬浮歌词 / 设置） */
@Composable
private fun PlayerBottomIconButton(
    @DrawableRes iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = if (active) PlayerText else PlayerSecondary,
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * 播放器底部的快捷跳转键（−5s / +30s）。
 *
 * 图标用描边的双箭头（Rewind / Fast Forward），和上一首 / 下一首的描边图标同重；
 * 具体秒数放在图标下方的小字里 —— 5 秒 / 30 秒不是平台标准值，光看图标说不清。
 */
@Composable
private fun PlayerSkipButton(
    @DrawableRes iconRes: Int,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean
) {
    Column(
        modifier = Modifier
            .size(width = 52.dp, height = 60.dp)
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled, onClickLabel = contentDescription, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (enabled) PlayerSecondary else PlayerTertiary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            ),
            color = if (enabled) PlayerSecondary else PlayerTertiary
        )
    }
}

/** 播放器顶部的小圆形描边按钮（收起 / 分享） */
@Composable
private fun PlayerIconButton(
    @DrawableRes iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    rotate: Float = 0f
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(PlayerChrome)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = if (enabled) PlayerText else PlayerTertiary,
            modifier = Modifier
                .size(22.dp)
                .rotate(rotate)
        )
    }
}

/**
 * 全屏播放器覆盖层：整页从屏幕下方滑入 / 滑出。
 *
 * [progress] 0 = 页面完全在屏幕下方；1 = 页面完全覆盖屏幕。
 * 不使用缩放或共享元素，只做垂直位移 + 压暗遮罩，保证进出方向一致、行为简单可预期。
 *
 * ## 为什么它是"常驻组合"的（[visible] 参数）
 *
 * 早先这个覆盖层是 `if (visible) { PlayerSlideOverlay(...) }` —— 第一次点迷你播放器
 * 才把整棵播放器（全屏模糊底图 + Pager + **44 行歌词**）组合出来，
 * 那一下正是"首次展开明显卡顿"的来源。数据预取只解决了网络与解析，组合与测量
 * 那次冷启动成本仍在点击的那一帧里。
 *
 * 现在改成：一旦有播放内容就**常驻组合**（离屏、不绘制、不吃触摸），
 * 第一次展开只跑位移动画，没有组合/测量成本。
 *
 * @param visible 是否处于"展开"状态。false 时：
 *   - 不注册 BackHandler（否则返回键会被一个看不见的页面吃掉，用户退不出 App）
 *   - 遮罩不接收点击（否则常驻的全屏 Box 会把整个界面的触摸吞掉）
 *   - 内容不绘制（省掉离屏渲染）
 */
@Composable
fun PlayerSlideOverlay(
    progress: () -> Float,
    visible: Boolean,
    onBack: () -> Unit
) {
    // 覆盖层在 NavHost 之后组合，BackHandler 优先级高于底层导航：
    // 返回手势先收起播放页，而不是把播放页下面的页面 pop 掉。
    // ⚠️ enabled 必须跟着 visible：常驻组合时它一直存在，否则会抢走返回键。
    BackHandler(enabled = visible, onBack = onBack)

    // ⚠️ 动画进度**只在绘制阶段读**（graphicsLayer / drawBehind 的 lambda 里）。
    //
    // 之前 `val p = progress()` 写在组合体里，于是展开动画的**每一帧**都会：
    //   重组整个覆盖层 → BoxWithConstraints 重新 subcompose → PlayerScreen 重组
    //   → 新 RoundedCornerShape → 重新布局（offset 是布局阶段）
    // 一帧下来整棵播放器被组合+测量一遍，动画当然不平滑。
    //
    // 现在：
    //   - 位移与圆角交给 graphicsLayer（绘制阶段，只更新图层变换/轮廓）
    //   - 遮罩透明度交给 drawBehind
    //   - 只有"是否已经铺满屏幕"这一个布尔值进组合阶段（derivedStateOf 保证
    //     整段动画里只翻转一次）
    val progressState = rememberUpdatedState(progress)
    val fullyExpanded by remember {
        derivedStateOf { progressState.value() > 0.999f }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val fullHeightPx = with(density) { maxHeight.toPx() }

        // 背后压暗 + 吞掉点击，避免滑动过程中误触下面的页面 / 迷你播放器。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val pp = progressState.value().coerceIn(0f, 1f)
                    if (visible && pp > 0.001f) {
                        drawRect(Color.Black.copy(alpha = 0.45f * pp))
                    }
                }
                .then(
                    if (visible) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                    } else {
                        Modifier
                    }
                )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                // ⚠️ 这里**故意不**加"收起时不绘制"的闸门。
                //
                // 收起状态下它整块被 translationY 推到屏幕外，正常情况下 Skia
                // 会把它裁掉、不产生光栅化；但 Compose 仍会把这一层录进
                // RenderNode —— 一旦系统愿意画，纹理上传与图层就在这里完成，
                // 首次展开就没有"第一次光栅化"的那一下。
                //
                // 代价：如果它真的被画了，positionMs 每 500ms 更新会让整层重新
                // 光栅化。因为底图模糊已经降到 1/64 面积，这个代价很小（约 2 次/秒）。
                .graphicsLayer {
                    val pp = progressState.value().coerceIn(0f, 1f)
                    // 整页从屏幕下方滑入（绘制阶段变换，不触发布局）
                    translationY = (1f - pp) * fullHeightPx
                    // 顶部圆角随展开收平；完全展开时用 Rectangle，省掉轮廓裁剪
                    val radius = ((1f - pp) * 24f).coerceAtLeast(0f)
                    shape = if (radius <= 0.5f) RectangleShape else RoundedCornerShape(radius.dp)
                    clip = true
                }
        ) {
            PlayerScreen(
                onBack = onBack,
                // 页面真正覆盖状态栏之后才切白色系统栏图标
                immersiveSystemBars = fullyExpanded
            )
        }
    }
}

/**
 * 播放器第一页（右侧初始页）：封面 + 曲目名 / 作品信息。
 * 进度与播放按钮在 pager 外，保持固定。
 */
@Composable
private fun PlayerCoverPage(
    coverUrl: String?,
    workTitle: String,
    trackTitle: String,
    metaLine: String,
    error: String?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 26.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            val artworkShape = RoundedCornerShape(12.dp)
            CoverImage(
                url = coverUrl,
                contentDescription = workTitle.ifBlank { trackTitle },
                modifier = Modifier
                    .widthIn(max = Sizes.PLAYER_COVER_MAX)
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .shadow(
                        elevation = 24.dp,
                        shape = artworkShape,
                        clip = false,
                        ambientColor = Color.Black,
                        spotColor = Color.Black
                    ),
                shape = artworkShape,
                contentScale = ContentScale.Crop,
                showBackdrop = false
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = trackTitle.ifBlank { "未在播放" },
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.2).sp
            ),
            color = PlayerText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (metaLine.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = metaLine,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = PlayerSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        error?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFFB4AB),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}

/**
 * 底部「播放队列」弹窗 —— 对应 Apple Music Now Playing 的 Up Next 入口。
 * 播放器主界面保持极简，曲目切换放到这里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerQueueSheet(
    tracks: List<Track>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PlayerSheet,
        contentColor = PlayerText,
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.28f))
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "播放队列",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = PlayerText,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${tracks.size} 首",
                    style = MaterialTheme.typography.labelSmall,
                    color = PlayerSecondary
                )
            }

            if (tracks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无曲目",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PlayerSecondary
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    itemsIndexed(
                        tracks,
                        key = { index: Int, track: Track ->
                            track.path.ifBlank { "queue-$index" }
                        }
                    ) { index, track ->
                        val playing = index == currentIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (playing) Color.White.copy(alpha = 0.06f)
                                    else Color.Transparent
                                )
                                .clickable { onSelect(index) }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.width(28.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (playing) {
                                    PlayingIndicator(
                                        barCount = 3,
                                        color = PlayerText
                                    )
                                } else {
                                    Text(
                                        text = "${index + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = PlayerTertiary
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (playing) PlayerText else Color.White.copy(alpha = 0.86f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = track.durationSec?.let { Work.formatDuration(it.toLong()) } ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = PlayerTertiary
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 歌词页的整屏压暗层。
 *
 * 透明度由 pager 的滑动进度驱动（0 = 封面页，1 = 歌词页），
 * 这样翻页过程中背景是**连续**变暗的，不会出现"到了歌词页突然多一块暗区"。
 *
 * 单独成 composable 的目的：滑动时只重组这一层，不影响封面页 / 歌词页的内容。
 */
@Composable
private fun LyricsBackdrop(pagerState: PagerState) {
    val progress = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
        .coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PlayerBackground.copy(alpha = 0.55f * progress))
    )
}

/** 睡眠定时可调范围（分钟） */
private const val SLEEP_TIMER_MIN_MINUTES = 1f
private const val SLEEP_TIMER_MAX_MINUTES = 120f

/** 打开弹窗时的默认值：半小时，是最常用的"听着睡着"时长 */
private const val SLEEP_TIMER_DEFAULT_MINUTES = 30f

/**
 * 底部「定时」弹窗：滑轨选分钟数 → 开始。
 *
 * 只做两件事，刻意保持简单：一个 1–120 分钟的滑轨，一个"开始"按钮。
 * 另外给一个**持久化**开关 [AppPrefs.sleepUntilTrackEnd]：到点时这一首没放完，
 * 是立刻静音还是等它自然结束 —— 后者才符合"听着睡着"的实际场景。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerSheet(
    remainingMs: Long,
    untilTrackEnd: Boolean,
    onToggleUntilTrackEnd: (Boolean) -> Unit,
    onStart: (Int) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var minutes by remember { mutableFloatStateOf(SLEEP_TIMER_DEFAULT_MINUTES) }
    val picked = minutes.roundToInt().coerceIn(
        SLEEP_TIMER_MIN_MINUTES.roundToInt(),
        SLEEP_TIMER_MAX_MINUTES.roundToInt()
    )

    /**
     * 先播收起动画，再做动作。
     *
     * 直接从外面 `showSleepTimer = false` 会把整个 ModalBottomSheet 从组合里摘掉，
     * 表现就是"闪一下消失"（开场有动画、退场没有）。这里让 sheet 自己滑下去，
     * 到位后再让调用方移除它 —— 与拖动/点遮罩关闭的观感一致。
     */
    fun closeWith(action: () -> Unit) {
        scope.launch {
            runCatching { sheetState.hide() }
            action()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PlayerSheet,
        contentColor = PlayerText,
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.28f))
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 26.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "定时关闭",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = PlayerText
            )

            Spacer(Modifier.height(18.dp))

            Text(
                text = "$picked 分钟",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                color = PlayerText
            )

            Spacer(Modifier.height(10.dp))

            LiquidSlider(
                value = { minutes },
                onValueChange = { minutes = it },
                valueRange = SLEEP_TIMER_MIN_MINUTES..SLEEP_TIMER_MAX_MINUTES,
                // 一分钟一档：滑轨总宽只有几百 dp，阈值给 0.5 分钟即可稳定吸附
                visibilityThreshold = 0.5f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
            )

            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${SLEEP_TIMER_MIN_MINUTES.roundToInt()} 分钟",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = PlayerTertiary
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${SLEEP_TIMER_MAX_MINUTES.roundToInt()} 分钟",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = PlayerTertiary
                )
            }

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "自动延时到歌曲播放完毕",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PlayerText
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = untilTrackEnd,
                    onCheckedChange = onToggleUntilTrackEnd,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = PlayerText,
                        uncheckedThumbColor = PlayerTertiary,
                        uncheckedTrackColor = PlayerChrome
                    )
                )
            }

            Spacer(Modifier.height(22.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(50))
                    .background(PlayerText)
                    .clickable { closeWith { onStart(picked) } },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "开始",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = Color.Black
                )
            }

            if (remainingMs > 0L) {
                Spacer(Modifier.height(10.dp))
                // 与「开始」同一套胶囊语言：同样 48dp 高、同样圆角，只是描边而非实心。
                // 原来是一行裸文字，和上面的按钮不像同一个界面的东西。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(50))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))
                        .clickable { closeWith { onCancel() } },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "取消定时（剩余 ${formatClock(remainingMs)}）",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = PlayerSecondary
                    )
                }
            } else {
                Spacer(Modifier.height(14.dp))
            }
        }
    }
}

/**
 * 底部「设置」弹窗：播放速度、一首放完之后、睡眠淡出、暂停淡出。
 *
 * 四件事都**立刻生效并持久化**，没有"保存"按钮 —— 播放器里的开关都是
 * 边调边听的，加一步确认反而打断试听。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSettingsSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val savedSpeed by AppPrefs.playbackSpeed.collectAsState()
    val autoNext by AppPrefs.autoNext.collectAsState()
    val fadeOut by AppPrefs.sleepFadeOut.collectAsState()
    val pauseFadeOut by AppPrefs.pauseFadeOut.collectAsState()

    // 拖动过程中只改内核速度（preview），松手才落盘：
    // 否则每一帧都写一次 SharedPreferences，既放大磁盘写入又会引起全 App 重组。
    var draftSpeed by remember { mutableFloatStateOf(savedSpeed) }
    // 弹窗重新打开时要跟上已保存的值（用户可能在外面被别的入口改过）
    LaunchedEffect(savedSpeed) { draftSpeed = savedSpeed }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PlayerSheet,
        contentColor = PlayerText,
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.28f))
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 26.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "设置",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = PlayerText
            )

            Spacer(Modifier.height(18.dp))

            // ── 播放速度 ──
            SettingsLabel("播放速度")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatSpeed(draftSpeed),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = PlayerText
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (draftSpeed == 1f) "原速" else "变速不变调",
                    style = MaterialTheme.typography.labelSmall,
                    color = PlayerTertiary
                )
            }
            LiquidSlider(
                value = { draftSpeed },
                onValueChange = {
                    draftSpeed = it
                    AudioController.previewPlaybackSpeed(it)
                },
                onValueChangeFinished = { AudioController.setPlaybackSpeed(draftSpeed) },
                valueRange = AppPrefs.MIN_PLAYBACK_SPEED..AppPrefs.MAX_PLAYBACK_SPEED,
                visibilityThreshold = 0.01f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
            )
            // 滑杆是连续值，但实际想用的就那么几个档位：给一排快捷值，
            // 免得不小心滑成 1.13× 这种自己都记不住的速度。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SPEED_PRESETS.forEach { preset ->
                    SpeedPresetChip(
                        label = formatSpeed(preset),
                        selected = kotlin.math.abs(preset - draftSpeed) < 0.005f,
                        onClick = {
                            draftSpeed = preset
                            AudioController.setPlaybackSpeed(preset)
                        }
                    )
                }
            }

            SettingsDivider()

            // ── 一首放完之后 ──
            SettingsLabel("一首放完之后")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                EndActionChip(
                    label = "继续播放",
                    selected = autoNext,
                    modifier = Modifier.weight(1f),
                    onClick = { AppPrefs.setAutoNext(true) }
                )
                EndActionChip(
                    label = "播完即停",
                    selected = !autoNext,
                    modifier = Modifier.weight(1f),
                    onClick = { AppPrefs.setAutoNext(false) }
                )
            }

            SettingsDivider(spacing = 8.dp)

            // ── 睡眠定时淡出 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "睡眠定时淡出",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                        color = PlayerText
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "停止前 6 秒渐弱音量",
                        style = MaterialTheme.typography.labelSmall,
                        color = PlayerTertiary
                    )
                }
                Switch(
                    checked = fadeOut,
                    onCheckedChange = { AppPrefs.setSleepFadeOut(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = PlayerText,
                        uncheckedThumbColor = PlayerSecondary,
                        uncheckedTrackColor = PlayerChrome
                    )
                )
            }

            SettingsDivider(spacing = 8.dp)

            // ── 暂停淡出 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "暂停淡出",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                        color = PlayerText
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "暂停时音量渐弱约 0.4 秒",
                        style = MaterialTheme.typography.labelSmall,
                        color = PlayerTertiary
                    )
                }
                Switch(
                    checked = pauseFadeOut,
                    onCheckedChange = { AppPrefs.setPauseFadeOut(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = PlayerText,
                        uncheckedThumbColor = PlayerSecondary,
                        uncheckedTrackColor = PlayerChrome
                    )
                )
            }
        }
    }
}

/** 设置项的小标题 */
@Composable
private fun SettingsLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = PlayerSecondary,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

/**
 * 分节用的细分割线。
 *
 * @param spacing 线上下各留多少。默认 18dp 适合"有标题、有滑杆"的重区块；
 *   底部两个开关行的信息量小，用 8dp 收拢，免得一屏里全是空白。
 */
@Composable
private fun SettingsDivider(spacing: Dp = 18.dp) {
    Spacer(Modifier.height(spacing))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.08f))
    )
    Spacer(Modifier.height(spacing))
}

/** 播放速度的快捷档位 */
@Composable
private fun RowScope.SpeedPresetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) Color.Black else PlayerSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(50))
            .background(if (selected) PlayerText else PlayerChrome)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    )
}

/** 「继续播放 / 播完即停」二选一 */
@Composable
private fun EndActionChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
        color = if (selected) Color.Black else PlayerSecondary,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) PlayerText else PlayerChrome)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    )
}

/** `1.25×` / `1.00×` —— 两位小数，避免 1.3000001 这种浮点尾巴 */
private fun formatSpeed(speed: Float): String = "%.2f×".format(speed)

/** 播放速度的快捷档位（0.75–2.0） */
private val SPEED_PRESETS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/**
 * 拼出可分享的作品链接。
 *
 * 优先用 RJ 号（`https://asmr.one/work/RJ01234567`）—— 这是站点的规范地址，
 * 换域名也认；拿不到 RJ 时退回数字 id，站点同样能打开。
 */
private fun shareLink(sourceId: String?, workId: Int): String {
    val key = sourceId?.trim().orEmpty()
    return when {
        key.isNotEmpty() -> "https://asmr.one/work/$key"
        workId > 0 -> "https://asmr.one/work/$workId"
        else -> ""
    }
}

/**
 * 毫秒 → `m:ss`。
 *
 * 定时上限 120 分钟，不会出现小时段，所以只做到分钟。
 */
private fun formatClock(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}
