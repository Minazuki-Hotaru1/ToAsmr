package com.asmrone.app.ui.screen.startup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.asmrone.app.ui.component.rememberToastState
import kotlinx.coroutines.delay

/**
 * 首次进入 App 的**字典准备页**。
 *
 * 只在"本地没有完整字典、且从没初始化过"时出现（判断在 [StartupViewModel.start]）。
 * 正常情况下老用户根本看不到这一屏。
 *
 * ## 三条路径，各自的停留时间不同
 *
 * | 情况 | 用户看到 |
 * |---|---|
 * | 拉取中 | 转圈 + 逐本进度；**超过 10 秒**追加"网络环境较差"的提示 |
 * | 成功 | 提示「搜索字典已就绪，共 N 条」→ 900ms 后自动进入 |
 * | 失败 | 提示 + **常驻卡片的失败原因** → **停住**，必须点「重试」或「暂不更新」 |
 *
 * ⚠️ 失败路径**没有任何自动跳转**。这是踩过坑之后强化的：早期版本失败卡片
 * 一闪而过，用户来不及看清原因（也来不及点重试）就进了首页。
 * 成功时自动进入是合理的（没什么可操作的），失败时不行 ——
 * 那正是最需要用户做决定的时候。
 */
@Composable
fun StartupScreen(onFinished: () -> Unit) {
    val viewModel: StartupViewModel = viewModel()
    val state by viewModel.state.collectAsState()

    // 提示用现有那套轻量样式（comicApp 的胶囊 toast），
    // 与收藏页 / 播放列表页的失败提示同一套，不再另造一个
    val toast = rememberToastState()

    LaunchedEffect(Unit) { viewModel.start() }

    // 拉取结束后弹一次提示：成功与失败共用同一条通道
    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { toast.show(it) }
    }

    // 成功才自动放行。不这么做就得给一个"进入"按钮，而那一步对用户毫无信息量
    //（还能不进入吗）。失败时**不**走这里 —— 见类注释。
    LaunchedEffect(state.readyOk) {
        if (state.readyOk) {
            delay(900)
            viewModel.proceed()
        }
    }

    // 到了 DONE 才真正放行
    LaunchedEffect(state.stage) {
        if (state.stage == StartupStage.DONE) onFinished()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (state.stage) {
                StartupStage.FETCHING -> {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = "正在准备搜索资料",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        // 逐本进度：三本合计约 830KB，说清"到第几本了"
                        // 比一个不动的圈让人安心得多
                        text = state.progress ?: "正在获取标签、声优与社团…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    // 慢网提示：只解释、不中断 —— 移动网络慢但能完成的情况是存在的
                    if (state.slowNetwork) {
                        Spacer(Modifier.height(20.dp))
                        SlowNetworkHint()
                    }
                    // 拉取**过程中**也给一条出路：慢网时用户不该被迫干等
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { viewModel.proceed() }) {
                        Text(FOREGO_DICTIONARIES)
                    }
                }

                StartupStage.READY -> {
                    if (state.readyOk) {
                        // 自动放行的倒计时中，只留一个安静的过渡
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        FailureContent(
                            detail = state.failureDetail,
                            onRetry = { viewModel.retry() },
                            onSkip = { viewModel.proceed() }
                        )
                    }
                }

                StartupStage.DONE -> {
                    // 已放行，这一帧只是过渡（LaunchedEffect 正在切界面）
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * 慢网提示。
 *
 * 移动网络下这 800 多 KB 可能真的要拉很久，与其让用户猜"是不是卡死了"，
 * 不如直接说明可能是网络环境的问题 —— 并给出可执行的建议。
 *
 * 提示只**解释**，不中断拉取（移动网络慢但能完成的情况是存在的）。
 * 想不等了就按下面的「暂不更新」直接进入 —— 那个按钮由调用方放在外面，
 * 这样拉取中无论有没有这句提示都能看到它。
 */
@Composable
private fun SlowNetworkHint() {
    Text(
        text = "当前网络环境较差，建议更换网络（如 Wi-Fi）或代理后再试。\n" +
            "也可以继续等待，或稍后在「设置 → 数据设置」里更新。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center
    )
}

/**
 * 失败卡片。
 *
 * 失败原因**常驻**在这里，而不是只靠 1.5 秒的 toast ——
 * 那是用户判断「重试还是暂不更新」的依据，必须一直看得到。
 */
@Composable
private fun FailureContent(
    detail: String?,
    onRetry: () -> Unit,
    onSkip: () -> Unit
) {
    Text(
        text = "搜索资料没有准备完整",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground
    )
    if (!detail.isNullOrBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = FOREGO_DICTIONARIES_HINT,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(24.dp))
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Button(onClick = onRetry) { Text("重试") }
        TextButton(onClick = onSkip) { Text(FOREGO_DICTIONARIES) }
    }
}

/**
 * 「暂不更新」这类入口的**统一文案**。
 *
 * ## ⚠️ 为什么不能用「跳过」这两个字
 *
 * 用户手机上装了跳过开屏广告的工具，那类工具靠**无障碍服务按文本找按钮**
 * （找的就是「跳过」「跳过广告」这类词），于是它会在这一屏刚出现时
 * 自动把按钮点掉 —— 现象就是"失败页面闪一下就进了首页"，
 * 用户既看不清原因、也来不及点重试。
 *
 * 这不是我们的 bug，但**受害的是我们的界面**，所以文案上主动避开：
 * 凡是"离开当前阶段"的按钮都不用「跳过」。
 *
 * 顺带一个好处：「暂不更新」比「跳过」更准确地说明了会发生什么
 * （字典这次不准备，之后可以在设置里更新），而「跳过」是个含糊的动作用词。
 */
internal const val FOREGO_DICTIONARIES = "暂不更新"

/** 失败卡片里那句说明；同样避开「跳过」 */
internal const val FOREGO_DICTIONARIES_HINT =
    "按标签 / 声优 / 社团搜索暂时不可用，关键词搜索不受影响。" +
        "可以检查网络后重试，也可以稍后在「设置 → 数据设置」里更新。"
