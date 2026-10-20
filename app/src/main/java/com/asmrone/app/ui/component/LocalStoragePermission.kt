package com.asmrone.app.ui.component

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.asmrone.app.data.download.LocalDownloadStore

/**
 * 申请「能往公共目录写文件」的权限，返回一个"点一下就走流程"的函数。
 *
 * 两条路径必须分开，不能只跳系统设置页：
 * - API 30+：`MANAGE_EXTERNAL_STORAGE` 是特殊权限，只能在设置页里开；
 * - API 28/29：对应的是 `WRITE_EXTERNAL_STORAGE` **运行时权限**，设置页里根本没这个开关，
 *   跳过去用户会找不到东西可开 —— 必须弹运行时权限对话框。
 *
 * [onResult] 拿到的是"最终能不能写"（已用 [LocalDownloadStore.hasAccess] 复查过），
 * 而不是"对话框点了允许"，因为用户可能在设置页里只开了半截就返回。
 */
@Composable
fun rememberLocalStorageRequester(onResult: (granted: Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    // 只在"跳去系统设置页"这条路径上等待，运行时权限走 launcher 回调
    var awaitingSettings by remember { mutableStateOf(false) }

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        currentOnResult(granted && LocalDownloadStore.hasAccess(context))
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, awaitingSettings) {
        if (!awaitingSettings) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    awaitingSettings = false
                    currentOnResult(LocalDownloadStore.hasAccess(context))
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    return remember(context) {
        {
            when {
                LocalDownloadStore.hasAccess(context) -> currentOnResult(true)
                LocalDownloadStore.requiresRuntimePermission() ->
                    runtimeLauncher.launch(LocalDownloadStore.runtimePermission())
                else -> {
                    awaitingSettings = true
                    runCatching { context.startActivity(LocalDownloadStore.permissionIntent(context)) }
                }
            }
            Unit
        }
    }
}
