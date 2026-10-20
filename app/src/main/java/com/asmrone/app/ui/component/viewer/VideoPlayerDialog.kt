package com.asmrone.app.ui.component.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * 背景遮罩：与图片查看器同一套半透明黑（见 `ImageViewerDialog.ViewerScrim`，
 * 那边是 private，这里重复一个常量而不是为了复用把可见性放开）。
 */
private val VideoScrim = Color.Black.copy(alpha = 0.65f)

/**
 * 内置**视频播放器**（Media3 / ExoPlayer）。
 *
 * ## 为什么这里引入了新依赖
 *
 * 详情页文件树里的 `.mp4` 实测是**真视频**（单个最大 1.8GB、码率 4.5Mbps，
 * 文件名形如「第2-1章」「販促PV動画」），而且服务端支持 `Range`（实测 206），
 * 所以必须**流式播放**：
 *
 *  - 不能先下载：1.8GB 不现实
 *  - 不能交给外部 App：没有本地文件，且外部播放器拿不到进度/错误回调
 *  - 平台自带的 `VideoView` 能播，但它的错误处理、缓冲状态、seek 精度都不够
 *
 * 所以这是本工程**唯一**一处为功能引入的依赖。音频播放**没有**跟着改，
 * 仍然是平台 `MediaPlayer`（见 `AudioController`）—— 两者互不干扰。
 *
 * ## 界面约定（2026-10-21 改）
 *
 * - 背景与图片查看器一致：整屏（含状态栏）一层半透明黑，`Dialog` 关掉系统 dim；
 * - 播放器窗口**收缩到视频本身的比例**（不铺满屏幕，四周是半透明黑）；
 * - 去掉标题、关闭、上一/下一、快退 5 秒、快进 30 秒这几个按钮，只留
 *   播放/暂停、进度、设置（音轨/速度）和**全屏**；
 * - **全屏 = 横屏沉浸式**：强制横屏 + 隐藏状态栏/导航栏，返回键先退出全屏。
 *
 * 全屏依赖 Activity 不重建：`MainActivity` 在清单里声明了
 * `configChanges=orientation|screenSize|…`，旋转时只走
 * `onConfigurationChanged`，对话框与 ExoPlayer 实例都不会被销毁。
 *
 * ## 生命周期
 *
 * `ExoPlayer` 持有解码器与网络连接，**必须显式 release**，否则退出页面后
 * 音频还在响、解码器不释放。这里用 [DisposableEffect] 绑定 Dialog 的生命周期。
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerDialog(
    url: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var errorText by remember { mutableStateOf<String?>(null) }
    var isFullscreen by remember { mutableStateOf(false) }
    // 16:9 先占位；拿到 `VideoSize` 后按真实比例收缩窗口
    var videoAspect by remember(url) { mutableFloatStateOf(16f / 9f) }

    // 播放器实例：与这个 Composable 同生共死
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            // 起播前先缓冲一小段，避免刚打开就卡顿
            playWhenReady = true
            prepare()
        }
    }

    // 监听播放错误与视频尺寸（尺寸决定窗口大小）
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                errorText = error.message ?: error.errorCodeName
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val w = videoSize.width * videoSize.pixelWidthHeightRatio
                val h = videoSize.height
                if (w > 0f && h > 0f) videoAspect = w / h
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            // ⚠️ 必须释放：否则退出后仍在后台解码/下载
            player.release()
        }
    }

    // 全屏：强制横屏 + 隐藏系统栏；退出全屏（或对话框销毁）时还原
    DisposableEffect(isFullscreen) {
        if (isFullscreen && activity != null) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            onDispose {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        } else {
            onDispose { }
        }
    }

    // 全屏时返回键先退全屏，而不是直接关掉视频
    BackHandler(enabled = isFullscreen) { isFullscreen = false }

    // 与图片查看器一致：关掉对话框窗口自带的 dim，半透明黑只由我们画一次
    val view = LocalView.current
    LaunchedEffect(Unit) {
        (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // 铺到状态栏下面，整屏同一层遮罩
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isFullscreen) Color.Black else VideoScrim)
        ) {
            // 非全屏时点"视频以外"退出（关闭按钮已按需求移除）
            if (!isFullscreen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                )
            }

            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                val videoModifier = if (isFullscreen) {
                    Modifier.fillMaxSize()
                } else {
                    // 收缩到视频比例：先按宽度占 92%，再受高度 80% 限制
                    // （Dp 用 minOf —— kotlin.math.min 只接受数值类型）
                    val byWidth = maxWidth * 0.92f
                    val byHeight = maxHeight * 0.8f * videoAspect
                    Modifier
                        .width(minOf(byWidth, byHeight))
                        .aspectRatio(videoAspect)
                }

                Box(modifier = videoModifier) {
                    AndroidView(
                        factory = { ctx ->
                            // 外层 FrameLayout：给 PlayerView 一个可控容器，
                            // resizeMode 用 FIT 保证不裁切、不变形
                            FrameLayout(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                addView(
                                    PlayerView(ctx).apply {
                                        this.player = player
                                        useController = true
                                        keepScreenOn = true
                                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        controllerAutoShow = true
                                        // 只保留播放/进度/设置/全屏：
                                        // 上一首、下一首、快退、快进全部隐藏
                                        setShowPreviousButton(false)
                                        setShowNextButton(false)
                                        setShowRewindButton(false)
                                        setShowFastForwardButton(false)
                                        // 全屏按钮位于设置按钮右侧（media3 默认布局）
                                        setFullscreenButtonClickListener { fullscreen ->
                                            isFullscreen = fullscreen
                                        }
                                    },
                                    FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                )
                            }
                        },
                        update = { root ->
                            // 用户按返回键退出全屏时，让按钮图标同步回"进入全屏"
                            (root.getChildAt(0) as? PlayerView)
                                ?.setFullscreenButtonState(isFullscreen)
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    errorText?.let { msg ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "视频播放失败：$msg",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.6f))
                                    .padding(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 从任意 `Context` 往上找 Activity（Compose 的 `LocalContext` 可能是
 * `ContextThemeWrapper`，不能直接强转）。全屏需要它来改方向、藏系统栏。
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
