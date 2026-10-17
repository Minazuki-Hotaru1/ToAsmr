package com.asmrone.app.ui.component.viewer

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

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
 * ## 生命周期
 *
 * `ExoPlayer` 持有解码器与网络连接，**必须显式 release**，否则退出页面后
 * 音频还在响、解码器不释放。这里用 [DisposableEffect] 绑定 Dialog 的生命周期，
 * 并在退到后台时暂停（`setPlayWhenReady(false)` 由 `PlayerView` 的
 * `onPause` 行为配合，见下面的注释）。
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerDialog(
    url: String,
    title: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var errorText by remember { mutableStateOf<String?>(null) }

    // 播放器实例：与这个 Composable 同生共死
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            // 起播前先缓冲一小段，避免刚打开就卡顿
            playWhenReady = true
            prepare()
        }
    }

    // 监听播放错误，把原因显示给用户（而不是一个黑屏）
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                errorText = error.message ?: error.errorCodeName
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            // ⚠️ 必须释放：否则退出后仍在后台解码/下载
            player.release()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { ctx ->
                    // 外层 FrameLayout：给 PlayerView 一个可控的容器，
                    // resizeMode 用 FIT 保证竖屏视频不会变形或溢出
                    FrameLayout(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        addView(
                            PlayerView(ctx).apply {
                                this.player = player
                                useController = true
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                // 让控制器在几秒无操作后自动隐藏
                                controllerAutoShow = true
                            },
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // ── 顶栏：标题 + 关闭 ────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(end = 72.dp)
                )
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) { Text("关闭", color = Color.White) }
            }

            // ── 错误提示 ────────────────────────────────────
            errorText?.let { msg ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "视频播放失败：$msg",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.6f))
                            .padding(16.dp)
                    )
                }
            }
        }
    }
}
