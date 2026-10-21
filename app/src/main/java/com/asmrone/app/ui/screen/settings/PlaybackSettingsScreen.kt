package com.asmrone.app.ui.screen.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.asmrone.app.player.PowerGuard
import com.asmrone.app.ui.component.FlatRow
import com.asmrone.app.ui.component.FlatSection
import com.asmrone.app.ui.component.RowChevron
import com.asmrone.app.ui.component.ToastHost
import com.asmrone.app.ui.component.rememberToastState

/**
 * 播放设置二级页。
 *
 * 目前只有「后台保护」一组：它管的不是播放本身，而是**播放能不能活下来**。
 * 后续与播放相关的开关（音质、缓冲、耳机拔出行为）都可以挂到这个页面，
 * 所以没有把它并进「外观」或「数据」。
 *
 * 状态是**回前台时重读**的，不是打开页面时读一次就完 —— 用户从这里跳到系统
 * 设置、改完再返回，如果不重读，页面上会一直显示"未开启"，反而让人以为没生效。
 */
@Composable
fun PlaybackSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val toast = rememberToastState()

    var protected by remember { mutableStateOf(PowerGuard.isIgnoringBatteryOptimizations(context)) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                protected = PowerGuard.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SettingsScaffold(title = "播放设置", onBack = onBack) {
            FlatSection(title = "后台") {
                FlatRow(
                    title = "后台保护",
                    description = if (protected) {
                        "已开启 · 息屏或切到后台也能继续播放"
                    } else {
                        "未开启 · 可能在息屏或切后台时被系统中断"
                    },
                    onClick = {
                        // 已经在白名单里也允许进设置（用户可能是想去关掉它），
                        // 但把失败提示留在外面：打不开系统界面时至少要给一句话。
                        if (!PowerGuard.tryOpenSettings(context)) {
                            toast.show("打不开系统设置，请到「电池」里手动关闭对本应用的优化")
                        }
                    },
                    trailing = { RowChevron() }
                )
            }
        }

        ToastHost(
            state = toast,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 96.dp)
        )
    }
}
