package com.asmrone.app.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.asmrone.app.R
import com.asmrone.app.data.model.Track
import com.asmrone.app.data.model.TrackKind
import com.asmrone.app.data.model.Work
import com.asmrone.app.ui.theme.Sizes

/**
 * ─────────────────────────────────────────────────────────────
 * 文件树（文件夹嵌套）—— 对齐 asmr.one 原始作品的存放结构
 * ─────────────────────────────────────────────────────────────
 *
 * 实测原站作品就是**压缩包目录树**：
 * ```
 * ├─ mp3/
 * │   ├─ Tr1.mp3      (7.6 MB, 5:17)
 * │   └─ Tr2.mp3
 * ├─ wav/            (无损备份)
 * └─ 03_omake/
 *     ├─ 01_wav（音声のみ）/ …
 *     └─ パッケージ.jpg   (image)
 * ```
 * 因此详情页不做「曲目平铺」，而是**忠实还原目录层级**：
 *  - 文件夹行：折叠箭头 + 文件夹图标 + 名称 + 「N 个音频 · 体积」；
 *  - 音频行：按层级缩进 16dp + 音频图标 + 文件名 + 时长 / 体积，点击即从该首开始播放；
 *  - 图片 / 文本 / 视频行：显示类型图标与体积，弱化处理（不可播放）。
 *
 * 渲染方式：**先按展开状态把树拍平成可见行列表**，再交给 LazyColumn 懒加载 ——
 * 这一点很关键：热门作品实测有 1450+ 个节点（`/api/tracks` 响应 6MB），
 * 递归 Compose 会把它们全部组合出来，必须拍平 + 懒加载。
 */

/**
 * 拍平后的一行。
 *
 * [key] 是交给 LazyColumn 的唯一键：正常情况下等于 [path]，
 * 一旦出现空路径或重复路径（异常数据的兜底）会被自动改写，
 * 从而**绝不会**再触发 "Key was already used" 崩溃。
 */
data class TreeRow(
    val node: Track,
    val depth: Int,
    val path: String,
    val key: String
)

/**
 * 按 [expanded] 里的路径集合，把树拍平成「当前可见行」。
 * 复杂度 O(可见节点数)，未展开的子树完全不遍历。
 *
 * 顺带做一次 key 唯一性兜底：path 为空或重复时追加序号。
 */
fun flattenVisibleTree(roots: List<Track>, expanded: Set<String>): List<TreeRow> {
    val out = ArrayList<TreeRow>()
    val usedKeys = HashMap<String, Int>()

    fun uniqueKey(path: String): String {
        val base = path.ifBlank { "未命名" }
        val seen = usedKeys.getOrDefault(base, 0)
        usedKeys[base] = seen + 1
        return if (seen == 0) base else "$base#$seen"
    }

    fun walk(nodes: List<Track>, depth: Int) {
        nodes.forEach { node ->
            out.add(TreeRow(node = node, depth = depth, path = node.path, key = uniqueKey(node.path)))
            if (node.children.isNotEmpty() && node.path in expanded) {
                walk(node.children, depth + 1)
            }
        }
    }
    walk(roots, 0)
    return out
}

/** 收集所有文件夹路径（用于「展开全部」；超大目录树会被调用方限流） */
fun collectFolderPaths(roots: List<Track>, limit: Int = 400): List<String> {
    val out = ArrayList<String>()
    fun walk(nodes: List<Track>) {
        nodes.forEach { node ->
            if (node.children.isNotEmpty()) {
                if (out.size < limit) out.add(node.path)
                walk(node.children)
            }
        }
    }
    walk(roots)
    return out
}

/** 文件树的一行（缩进 + 类型图标 + 名称 + 尾部信息） */
@Composable
fun FileTreeRow(
    row: TreeRow,
    expanded: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val node = row.node
    val cs = MaterialTheme.colorScheme
    val indent = (row.depth * 16).dp

    // 只算一次：图标、可点性、点击后的行为三者必须依赖同一个判定，
    // 否则会出现「图标像图片但点不开」这种自相矛盾的状态
    val kind = TrackKind.of(node)

    val rowBackground = when {
        playing -> cs.primary.copy(alpha = 0.08f)
        else -> androidx.compose.ui.graphics.Color.Transparent
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = indent)
            .clip(RoundedCornerShape(10.dp))
            .background(rowBackground)
            // 可点性用 TrackKind 判：以前只让"文件夹或可播放音频"能点，
            // 于是图片 / 文本 / PDF 点下去毫无反应（这正是本次要修的问题）
            .clickable(enabled = kind != TrackKind.UNSUPPORTED, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 折叠箭头（文件行用等宽占位，保证名称左对齐）
        // 展开/收起时箭头旋转 90°，与下方内容的展开动画同步
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (node.children.isNotEmpty()) {
                val arrowRotation by animateFloatAsState(
                    targetValue = if (expanded) 90f else 0f,
                    animationSpec = tween(durationMillis = 200),
                    label = "folderArrow"
                )
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right_fluent),
                    contentDescription = if (expanded) "折叠" else "展开",
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(arrowRotation)
                )
            }
        }

        // 类型图标（Fluent UI System Icons · Regular，见 res/drawable/ic_*_fluent.xml）
        Icon(
            painter = painterResource(fileTypeIcon(kind, expanded)),
            contentDescription = null,
            tint = fileTypeTint(kind),
            modifier = Modifier.size(20.dp)
        )

        Spacer(Modifier.width(8.dp))

        // 名称（+ 文件夹的统计副标题）
        Column(modifier = Modifier.weight(1f)) {
            val nameColor = when {
                playing -> cs.primary
                // 打不开的内容压暗一点，不诱使用户去点
                kind == TrackKind.UNSUPPORTED -> cs.onSurfaceVariant.copy(alpha = 0.6f)
                else -> cs.onBackground
            }
            // 扩展名单独拿出来渲染：长名字被省略号截断时，格式仍要看得见。
            //
            // 只用一个 Text 做不到 —— TextOverflow.Ellipsis 从整串的尾部截，
            // 名字一长就正好把 ".mp3" 截掉，而那偏偏是用户最想确认的信息。
            // 拆成 Row 之后，扩展名先按自身宽度占位，名字拿剩下的宽度再省略。
            val suffix = fileSuffixOf(node)
            if (suffix == null) {
                Text(
                    text = node.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = nameColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = node.title.dropLast(suffix.length),
                        style = MaterialTheme.typography.bodyMedium,
                        color = nameColor,
                        // 单行：扩展名固定占一行末尾，名字再多行也只会排出
                        // ".MP3" 在第二行中间这种读起来别扭的版式。
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = suffix,
                        style = MaterialTheme.typography.bodyMedium,
                        color = nameColor.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                }
            }
            if (node.isFolder) {
                val audioCount = node.audioCount()
                val folderCount = node.children.count { it.children.isNotEmpty() }
                val summary = buildString {
                    if (folderCount > 0) append("$folderCount 个子文件夹 · ")
                    append("$audioCount 个音频")
                    val size = node.subtreeSize()
                    if (size > 0) append(" · ${Work.formatBytes(size)}")
                }
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        // 尾部：播放中指示器 / 时长 / 体积 / 类型标签
        if (playing) {
            PlayingIndicator()
        } else {
            Column(horizontalAlignment = Alignment.End) {
                if (node.isAudio && node.durationText.isNotBlank()) {
                    Text(
                        text = node.durationText,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant
                    )
                }
                val sizeText = node.sizeText
                if (sizeText.isNotBlank()) {
                    Text(
                        text = sizeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                }
            }
        }
    }
}

/**
 * 文件名的扩展名（含点、统一大写）；不像扩展名时返回 null。
 *
 * 用来实现"名字再长也要看得见格式"：截断只截名字部分，`.MP3` 始终留在行尾。
 * 统一大写是因为它在这里起的是**标签**的作用（`TRACK01` 和 `track01` 的格式
 * 是同一个），混着大小写读起来反而费劲。
 *
 * 只在确实像扩展名时才返回：文件夹没有格式；没有点、点在开头（`.gitignore`）
 * 或点在结尾都不算；超过 5 个字符或含空白的（`RJ12345.这是一个说明`）
 * 多半不是格式，一并放弃 —— 否则会把半句话当成格式钉在行尾。
 */
internal fun fileSuffixOf(node: Track): String? {
    if (node.isFolder) return null
    val dot = node.title.lastIndexOf('.')
    if (dot <= 0 || dot == node.title.lastIndex) return null
    val ext = node.title.substring(dot + 1)
    if (ext.length > 5 || ext.any { it.isWhitespace() }) return null
    return "." + ext.uppercase()
}

/**
 * 文件类型 → 图标资源。
 *
 * 采用 **Fluent UI System Icons（Regular）**：同一套字形、圆润统一，
 * 已转成矢量图放在 `res/drawable/ic_*_fluent.xml`（MIT 授权，可随包分发）。
 *
 * ⚠️ 判定用的是 [TrackKind] 而**不是**裸的 `node.type`：服务端把 `.mp4` 视频
 * 标成 `type=audio`（实测单个到 1.8GB），按 type 判会给视频显示音频图标；
 * 而 PDF 的 type 是 `other`，按 type 判只能落到通用文件图标。
 * 用 [TrackKind] 也保证了"图标"与"点击后的行为"永远一致 ——
 * 这正是给用户"点之前就知道会发生什么"的那个依据。
 */
@DrawableRes
private fun fileTypeIcon(kind: TrackKind, expanded: Boolean): Int = when (kind) {
    TrackKind.FOLDER ->
        if (expanded) R.drawable.ic_folder_open_fluent else R.drawable.ic_folder_fluent
    TrackKind.AUDIO -> R.drawable.ic_audio_fluent
    TrackKind.VIDEO -> R.drawable.ic_video_fluent
    TrackKind.IMAGE -> R.drawable.ic_image_fluent
    // 字幕与纯文本共用文本图标：没有专门的字幕字形，
    // 而它们"点开都是看文字"，用同一个图标不会误导
    TrackKind.SUBTITLE, TrackKind.TEXT -> R.drawable.ic_text_fluent
    // PDF 与未知类型都用通用文件图标（没有 PDF 字形）
    TrackKind.PDF, TrackKind.UNSUPPORTED -> R.drawable.ic_file_fluent
}

@Composable
private fun fileTypeTint(kind: TrackKind) = when (kind) {
    // 蓝色 = 能播放/能打开：音频、视频、图片
    TrackKind.FOLDER, TrackKind.AUDIO, TrackKind.VIDEO, TrackKind.IMAGE ->
        MaterialTheme.colorScheme.primary
    // 次要色 = "能看但不是主体内容"：文本、字幕、PDF
    TrackKind.TEXT, TrackKind.SUBTITLE, TrackKind.PDF ->
        MaterialTheme.colorScheme.onSurfaceVariant
    // 最低调 = 打不开的：与"点了没反应"一致，不诱使用户去点
    TrackKind.UNSUPPORTED ->
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
}

/** 文件树区块的统计文本，例如「3 个文件夹 · 12 个音频 · 1.2 GB」 */
fun trackTreeSummary(roots: List<Track>): String {
    if (roots.isEmpty()) return "暂无文件"
    val folders = roots.sumOf { countFolders(it) }
    val audios = roots.sumOf { it.audioCount() }
    val size = roots.sumOf { it.subtreeSize() }
    return buildString {
        if (folders > 0) append("$folders 个文件夹 · ")
        append("$audios 个音频")
        if (size > 0) append(" · ${Work.formatBytes(size)}")
    }
}

private fun countFolders(node: Track): Int =
    if (node.children.isEmpty()) 0 else 1 + node.children.sumOf { countFolders(it) }

/** 曲目行的高度（供 LazyColumn 占位估算） */
val TreeRowMinHeight = Sizes.ROW_THUMB_HEIGHT
