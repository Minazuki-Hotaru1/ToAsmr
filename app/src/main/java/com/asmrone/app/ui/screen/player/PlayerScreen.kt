package com.asmrone.app.ui.screen.player

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import com.asmrone.app.R
import com.asmrone.app.data.model.Track
import com.asmrone.app.data.model.Work
import com.asmrone.app.player.AudioController
import com.asmrone.app.ui.component.CoverImage
import com.asmrone.app.ui.component.PlayingIndicator
import com.asmrone.app.ui.screen.lyrics.PlayerLyrics
import com.asmrone.app.ui.theme.Sizes
import kotlin.math.roundToInt

// ── 播放器的深色沉浸色板（对齐 Apple Music Now Playing 的「封面取色 + 黑底」）──
private val PlayerBackground = Color(0xFF0B0B0D)
private val PlayerSheet = Color(0xFF1B1B1D)
private val PlayerText = Color.White
private val PlayerSecondary = Color.White.copy(alpha = 0.62f)
private val PlayerTertiary = Color.White.copy(alpha = 0.42f)
private val PlayerChrome = Color.White.copy(alpha = 0.10f)
private val PlayerTrackInactive = Color.White.copy(alpha = 0.24f)

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

    // 第 0 页 = 封面（初始页，左），第 1 页 = 歌词（右）。
    // 从封面向左划露出右侧歌词页；向右划回封面。
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    val lyricPositionMs = if (dragging) {
        (dragValue * state.durationMs.toFloat()).toLong()
    } else {
        state.positionMs.toLong()
    }

    Box(modifier = Modifier.fillMaxSize().background(PlayerBackground)) {
        // ── 封面模糊底图 ──
        if (!state.coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = state.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(56.dp)
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            // ── 顶部：收起 + 队列（Apple Music 的极简 chrome）──
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
                PlayerIconButton(
                    iconRes = R.drawable.ic_queue_fluent,
                    contentDescription = "播放队列",
                    onClick = { showQueue = true },
                    enabled = state.tracks.isNotEmpty()
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
                Slider(
                    value = progress,
                    onValueChange = {
                        dragging = true
                        dragValue = it
                    },
                    onValueChangeFinished = {
                        AudioController.seekToFraction(dragValue)
                        dragging = false
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = PlayerText,
                        activeTrackColor = PlayerText,
                        inactiveTrackColor = PlayerTrackInactive
                    ),
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 26.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { AudioController.prev() },
                    enabled = state.hasPrev,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_skip_previous_fluent),
                        contentDescription = "上一首",
                        tint = if (state.hasPrev) PlayerText else PlayerTertiary,
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
                    enabled = state.hasNext,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_skip_next_fluent),
                        contentDescription = "下一首",
                        tint = if (state.hasNext) PlayerText else PlayerTertiary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
        }
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
}

/** 播放器顶部的小圆形描边按钮（收起 / 队列） */
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
 */
@Composable
fun PlayerSlideOverlay(
    progress: () -> Float,
    onBack: () -> Unit
) {
    // 覆盖层在 NavHost 之后组合，BackHandler 优先级高于底层导航：
    // 返回手势先收起播放页，而不是把播放页下面的页面 pop 掉。
    BackHandler(onBack = onBack)

    val p = progress().coerceIn(0f, 1f)
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val fullHeightPx = with(density) { maxHeight.toPx() }
        val topCorner = (24f * (1f - p)).coerceAtLeast(0f).dp

        // 背后压暗 + 吞掉点击，避免滑动过程中误触下面的页面 / 迷你播放器。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f * p))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, ((1f - p) * fullHeightPx).roundToInt()) }
                .clip(RoundedCornerShape(topStart = topCorner, topEnd = topCorner))
        ) {
            PlayerScreen(
                onBack = onBack,
                // 页面真正覆盖状态栏之后才切白色系统栏图标
                immersiveSystemBars = p > 0.999f
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
