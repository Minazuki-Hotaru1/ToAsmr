package com.asmrone.app.ui.component.viewer

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asmrone.app.R
import com.asmrone.app.data.model.OpenableFile
import com.asmrone.app.data.repo.AsmrRepository
import com.asmrone.app.data.text.DecodedText
import com.asmrone.app.data.text.TextCharset
import com.asmrone.app.data.text.TextDecoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 文本查看器要打开的文件。
 *
 * 以前是 `Dialog`，但对话框自带一层窗口 dim，关闭时内容先滑走、窗口还留在屏幕上，
 * 出现约 1 秒"点不动的半透明灰屏"。现在改成**覆盖层页面**（挂在 `MainActivity`
 * 最外层、与播放器覆盖层并列），没有窗口、没有 dim，进出就是整页滑动。
 */
object TextViewerState {

    private val _file = MutableStateFlow<OpenableFile?>(null)

    /** 当前要查看的文件；null 表示页面已关闭。 */
    val file: StateFlow<OpenableFile?> = _file.asStateFlow()

    fun open(openable: OpenableFile) {
        _file.value = openable
    }

    fun close() {
        _file.value = null
    }
}

/**
 * 内置**文本查看器页面**。
 *
 * ## 为什么不用 Dialog
 *
 * 之前的实现是 `Dialog` + `decorFitsSystemWindows = false`。关闭时先播退出动画、
 * 再 `onDismiss()`，这段窗口期里内容已经滑出屏幕，但**对话框窗口连同系统的
 * 半透明遮罩还在**，加上窗口会拦截触摸，表现就是"约 1 秒的灰色界面、点不动"。
 * 遮罩是系统在窗口层画的，`setDimAmount(0f)` 只能压掉自己的那一份，
 * 覆盖层方案则根本不存在这个窗口 —— 页面直接画在 Activity 的最外层。
 *
 * ## 为什么 txt 不走外部 App
 *
 * 实测判断：Android **没有**内置纯文本查看器，也没有约定俗成的第三方
 * 接管 `text/plain`。外跳 `ACTION_VIEW` 的常见结果是
 * "没有应用可执行此操作" —— 也就是用户点了没反应。所以 txt 一律内置。
 *
 * ## 这个查看器要解决的真问题：编码
 *
 * 服务端对文本只回 `Content-Type: text/plain`，**不带 charset**（实测）。
 * 而内容确实有多种编码，且用字节级严格校验测出：
 *
 * | 判定 | 数量 |
 * |---|---|
 * | UTF-8（无歧义） | 187 |
 * | Shift-JIS 与 GBK **都合法** | 24 |
 *
 * 那 24 个自动判不准 —— 所以标题下面常驻一排**编码切换**胶囊。
 * 用户看到乱码点一下换一个，比"猜错了还嘴硬"有用得多。
 * 若是手动选的编码解出了替换符，会额外提示"可能选错"。
 *
 * 页面本身是浅色（白底/深字），与 App 的浅色主题一致；展开动画由宿主
 * （`MainActivity`）的 `AnimatedVisibility` 负责，参数与播放器覆盖层相同。
 */
@Composable
fun TextViewerPage(file: OpenableFile, onClose: () -> Unit) {
    var loading by remember(file.url) { mutableStateOf(true) }
    var error by remember(file.url) { mutableStateOf<String?>(null) }
    var raw by remember(file.url) { mutableStateOf<ByteArray?>(null) }
    var decoded by remember(file.url) { mutableStateOf<DecodedText?>(null) }
    // null = 自动判断；非 null = 用户手动指定
    var manual by remember(file.url) { mutableStateOf<TextCharset?>(null) }

    BackHandler { onClose() }

    // 取字节（只取一次；切换编码不重新下载）
    LaunchedEffect(file.url) {
        loading = true
        error = null
        runCatching { AsmrRepository.fetchBytes(file.url) }
            .onSuccess {
                raw = it
                decoded = TextDecoder.decode(it, file.title, hint = null)
                loading = false
            }
            .onFailure {
                error = it.message ?: "下载失败"
                loading = false
            }
    }

    // 切换编码：用**同一份字节**重新解，不重新下载
    LaunchedEffect(manual) {
        val bytes = raw ?: return@LaunchedEffect
        decoded = TextDecoder.decode(bytes, file.title, hint = manual)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
    ) {
        // ── 顶部：只有一个圆形返回键（与播放页收起键同款）──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ViewerIconButton(
                iconRes = R.drawable.ic_chevron_right_fluent,
                contentDescription = "关闭",
                onClick = onClose,
                rotate = 90f
            )
        }

        // ── 标题区：左对齐，和播放页的曲目信息同一套层级 ──
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Text(
                text = file.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = buildString {
                    val size = file.sizeText
                    if (size.isNotEmpty()) append(size)
                    decoded?.let {
                        if (isNotEmpty()) append(" · ")
                        append(it.charset.displayName)
                        if (it.uncertain) append("（可能选错）")
                    }
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── 编码切换（三类编码各一个入口）──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CharsetPill(
                text = "自动",
                selected = manual == null,
                enabled = manual != null,
                onClick = { manual = null }
            )
            TextCharset.entries.forEach { cs ->
                CharsetPill(
                    text = cs.displayName,
                    selected = manual == cs,
                    enabled = decoded?.charset != cs || manual != cs,
                    onClick = { manual = cs }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        when {
            loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp
                )
            }

            error != null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = error.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            }

            else -> TextBody(decoded?.text.orEmpty())
        }
    }
}

/**
 * 正文。
 *
 * 用 [LazyColumn] 按行渲染而不是一个巨大的 `Text`：实测长文本能到 684KB，
 * 一次性交给 Compose 文本布局会明显卡顿。按行懒加载后，
 * 滚动到哪一行才排版哪一行。
 *
 * 行高放大到 1.55 倍（22sp）—— 纯文本阅读比界面文字更需要行间距。
 */
@Composable
private fun TextBody(text: String) {
    val lines = remember(text) { text.split('\n') }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp)
    ) {
        itemsIndexed(lines) { _, line ->
            // 文本要可选中复制（台本常见用法是抄一份关键词去搜）
            SelectionContainer {
                Text(
                    text = line.ifEmpty { " " },
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}

/** 圆形图标键：44dp、浅色圆底、深色描边图标（播放页 chrome 的浅色版） */
@Composable
private fun ViewerIconButton(
    @DrawableRes iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    rotate: Float = 0f
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .size(22.dp)
                .then(if (rotate == 0f) Modifier else Modifier.rotate(rotate))
        )
    }
}

/** 编码切换胶囊：选中填主色，未选中是浅灰底 */
@Composable
private fun CharsetPill(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val background = when {
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    }
    val contentColor = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor
        )
    }
}
