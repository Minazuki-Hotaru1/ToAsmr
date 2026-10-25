package com.asmrone.app.ui.screen.download

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.asmrone.app.R
import com.asmrone.app.data.download.DownloadStorage
import com.asmrone.app.data.download.LocalDownloadStore
import com.asmrone.app.data.model.Track
import com.asmrone.app.data.model.TrackKind
import com.asmrone.app.data.prefs.AppPrefs
import com.asmrone.app.ui.component.DownloadStorageButton
import com.asmrone.app.ui.component.TreeRow
import com.asmrone.app.ui.component.collectFolderPaths
import com.asmrone.app.ui.component.fileSuffixOf
import com.asmrone.app.ui.component.flattenVisibleTree
import com.asmrone.app.ui.component.rememberLocalStorageRequester

/**
 * 下载选择器的下半屏弹窗。
 *
 * ## 点击语义（2026-10 调整）
 *
 * - **文件夹**：点行 = 展开 / 收起，**不会**改变勾选；只有点左边的勾选框才是
 *   「选中/取消它下面的全部叶子文件」（三态：全选 / 部分 / 未选）。
 *   这样在一个大目录里找文件时，不会一碰行就把 200 个文件全勾上。
 * - **文件**：点行的任意位置或勾选框都是选/不选 —— 文件没有子级，点行没有
 *   第二种含义，多一步反而难用。
 * - **已在 App 内的文件**：取决于这次存哪儿 ——
 *   存「系统」时勾选框固定选中且禁用，标「已下载」（同一份不会有第二个副本）；
 *   存「本地」时**可以勾**，标「已在 App 内 · 复制」，下载时直接把 App 里那份
 *   复制到本地文件夹，不再走网络（详见 `DownloadManager.runDownload`）。
 *
 * 行按层级缩进（每级 14dp，最多 5 级），文件夹名比文件名稍重，展开时子项
 * 用 `animateItem()` 淡入、旧行平滑让位，所以能一眼看出哪些内容属于哪个文件夹。
 *
 * 下载的是**叶子文件**（音频、字幕、图片、PDF 都算），文件夹本身不占空间。
 * 默认不选任何东西，也默认全部收起：用户点哪个下哪个，避免误点「下载」
 * 就把 300 个文件拖下来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadPickerSheet(
    workTitle: String,
    tree: List<Track>,
    downloadedPaths: Set<String>,
    onDownload: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 文件夹 path → 它下面的所有叶子文件 path（一次算好，避免每帧遍历整棵树）
    val leavesUnder = remember(tree) { folderLeaves(tree) }
    val downloadableLeaves = remember(tree, downloadedPaths) {
        leavesUnder.values.flatten().toSet().filter { path ->
            // 已经在 App 内的文件哪怕树上缺了下载地址，也能勾 —— 它是从本地复制，
            // 不是联网取（这类节点在服务端偶发缺字段，挡掉反而没法另存）
            path in downloadedPaths || findNode(tree, path)?.let { node ->
                node.mediaStreamUrl.orEmpty().isNotBlank() ||
                    node.mediaDownloadUrl.orEmpty().isNotBlank() ||
                    !node.hash.isNullOrBlank()
            } ?: false
        }.toSet()
    }
    val allFolderPaths = remember(tree) { collectFolderPaths(tree, limit = Int.MAX_VALUE) }

    // 默认全部收起：先看清作品有几个文件夹，再决定展开哪一个（需求 2026-10）
    var expanded by remember(tree) { mutableStateOf(emptySet<String>()) }
    var selected by remember(tree) { mutableStateOf(emptySet<String>()) }

    // ── 保存位置（系统 / 本地）────────────────────────────
    //
    // 开关放在这里而不是设置页：用户是在"要下载这一部"的当下决定存哪儿的，
    // 跑一趟设置再回来反而容易忘。只影响之后的下载，已下好的要另存。
    val context = LocalContext.current
    val storage by AppPrefs.downloadStorage.collectAsState()

    // 哪些文件**不能**勾。
    //
    // 存到 App 内时，已经下好的那份再勾一遍没有意义（不会有第二个副本），禁用并标「已下载」；
    // 存到本地时它们反而必须能勾 —— 那份在 App 里，用户勾它就是"复制一份到本地文件夹"，
    // 所以这里按存储模式切换，而不是一律禁用。
    val blockedPaths = remember(storage, downloadedPaths) {
        if (storage == DownloadStorage.LOCAL) emptySet() else downloadedPaths
    }
    /** 已经在 App 内、但这次要落到本地文件夹（走复制而不是下载） */
    fun copiesToLocal(path: String): Boolean =
        storage == DownloadStorage.LOCAL && path in downloadedPaths

    fun toggleSelection(row: TreeRow) {
        val node = row.node
        if (node.children.isEmpty()) {
            if (node.path in blockedPaths) return
            selected = if (node.path in selected) selected - node.path else selected + node.path
        } else {
            val leaves = leavesUnder[row.path].orEmpty().filter { it in downloadableLeaves }
            val selectable = leaves - blockedPaths
            if (selectable.isEmpty()) return
            val allSelected = selectable.all { it in selected }
            selected = if (allSelected) selected - selectable.toSet() else selected + selectable
        }
    }

    fun toggleFolder(row: TreeRow) {
        expanded = if (row.path in expanded) expanded - row.path else expanded + row.path
    }

    val rows = remember(tree, expanded) { flattenVisibleTree(tree, expanded) }
    val selectedBytes = remember(selected) {
        selected.sumOf { path -> findNode(tree, path)?.size ?: 0L }
    }
    val totalLeaves = downloadableLeaves.size

    // 「全部展开 / 全部收起」的文案必须和点击效果一致：
    // 只有**真的全都展开了**才显示「全部收起」，否则显示「全部展开」。
    // （旧写法用 expanded.isEmpty() 判断，展开其中任意一个就变成「全部收起」，
    //  可是点下去做的是"全展开"，文案和动作正好相反。）
    val allExpanded = allFolderPaths.isNotEmpty() && expanded.containsAll(allFolderPaths)

    // ── 防止「滚到顶再松手」把整个弹窗收起来 ──
    //
    // ModalBottomSheet 的嵌套滚动里，内层列表消费不掉的位移会被当成
    // "把弹窗往下拖" —— 列表滚回顶部后继续拖动（手指向下）时，这段剩余
    // 位移正好冒泡到 sheet，于是整页被拖下去收起，而用户只是想回第一行。
    // 这里在列表自己的 nestedScroll 里把「已经到顶 + 向下拖」的位移和惯性
    // 吃掉，弹窗仍可通过下滑把手 / 点遮罩 / 返回键关闭。
    val listState = rememberLazyListState()
    val blockPullToDismiss = remember(listState) {
        object : NestedScrollConnection {
            /** 列表已经到顶，却还有向下的剩余位移（= 想继续看上面的内容） */
            fun leftoverAtTop(availableY: Float) =
                availableY > 0f && !listState.canScrollBackward

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source == NestedScrollSource.UserInput && leftoverAtTop(available.y)) {
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity
            ): Velocity {
                if (leftoverAtTop(available.y)) return Velocity(0f, available.y)
                return Velocity.Zero
            }
        }
    }

    // 授权回来后要补那次"点了下载但被权限拦下"的操作
    var pendingPaths by remember { mutableStateOf<List<String>?>(null) }
    var permissionHint by remember { mutableStateOf(false) }
    val requestLocalAccess = rememberLocalStorageRequester(
        onResult = { granted ->
            if (granted) {
                // 授权成功就直接切过去（按钮本身就是存储模式的开关）
                AppPrefs.setDownloadStorage(DownloadStorage.LOCAL)
                permissionHint = false
                pendingPaths?.let { paths ->
                    onDownload(paths)
                    onDismiss()
                }
            } else {
                permissionHint = true
            }
            pendingPaths = null
        },
        onFailure = {
            permissionHint = true
            pendingPaths = null
        }
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.fillMaxHeight(0.88f)) {
            // ── 顶栏：标题在左，「下载」在右上角 ──────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "选择下载内容",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = workTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                DownloadStorageButton(
                    storage = storage,
                    warn = storage == DownloadStorage.LOCAL &&
                        !LocalDownloadStore.hasAccess(context),
                    onClick = {
                        when {
                            storage == DownloadStorage.LOCAL ->
                                AppPrefs.setDownloadStorage(DownloadStorage.APP)
                            LocalDownloadStore.hasAccess(context) ->
                                AppPrefs.setDownloadStorage(DownloadStorage.LOCAL)
                            else -> requestLocalAccess()
                        }
                    }
                )
                TextButton(
                    onClick = {
                        val paths = selected.toList()
                        // 存到本地要先有权限。开关可能早就是"本地"（上次授权过），
                        // 之后权限被撤销时这里必须重新走一遍授权，而不是直接入队失败。
                        if (storage == DownloadStorage.LOCAL &&
                            !LocalDownloadStore.hasAccess(context)
                        ) {
                            pendingPaths = paths
                            requestLocalAccess()
                        } else {
                            onDownload(paths)
                            onDismiss()
                        }
                    },
                    enabled = selected.isNotEmpty()
                ) {
                    Text(
                        text = "下载",
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected.isEmpty()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
            }

            // 存到本地却还没权限：在用户点「下载」之前就把话说清楚
            if (storage == DownloadStorage.LOCAL && !LocalDownloadStore.hasAccess(context)) {
                Text(
                    text = if (permissionHint) {
                        "没拿到存储权限，点右上角「本地」可再试一次"
                    } else {
                        "存到本地文件夹需要存储权限，点右上角「本地」可前往授予"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp)
                )
            }

            // ── 汇总 + 快捷操作 ─────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "已选 ${selected.size} 个" +
                        if (selectedBytes > 0) " · ${formatBytes(selectedBytes)}" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    expanded = if (allExpanded) emptySet() else allFolderPaths.toHashSet()
                }) {
                    Text(
                        text = if (allExpanded) "全部收起" else "全部展开",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                TextButton(onClick = {
                    val audio = downloadableLeaves.filter { path ->
                        findNode(tree, path)?.let { TrackKind.of(it) == TrackKind.AUDIO } ?: false
                    }.toSet() - blockedPaths
                    selected = if (audio.isNotEmpty() && audio.all { it in selected }) {
                        selected - audio
                    } else {
                        selected + audio
                    }
                }) {
                    Text(
                        text = if (selected.size == totalLeaves) "取消全选" else "全选音频",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .nestedScroll(blockPullToDismiss),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 16.dp)
            ) {
                items(rows, key = { it.key }) { row ->
                    val isFolder = row.node.children.isNotEmpty()
                    // 已经在 App 内：存 App 时禁用（不会有第二份），存本地时可选（复制过去）
                    val inApp = !isFolder && row.path in downloadedPaths
                    val leaves = leavesUnder[row.path].orEmpty().filter { it in downloadableLeaves } - blockedPaths
                    val checked = when {
                        isFolder -> leaves.isNotEmpty() && leaves.all { it in selected }
                        else -> row.path in blockedPaths || row.path in selected
                    }
                    val partially = isFolder && !checked && leaves.any { it in selected }
                    val canSelect = if (isFolder) leaves.isNotEmpty() else row.path !in blockedPaths
                    // 文件夹点行 = 展开收起；文件点行 = 选/不选
                    val rowClickable = isFolder || row.path !in blockedPaths

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 子项淡入 + 其余行平滑让位：展开/收起有连续感，不是硬跳
                            .animateItem(
                                fadeInSpec = tween(durationMillis = 160),
                                placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                fadeOutSpec = tween(durationMillis = 120)
                            )
                            .padding(start = (row.depth.coerceAtMost(5) * 14).dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = rowClickable) {
                                if (isFolder) toggleFolder(row) else toggleSelection(row)
                            }
                            .padding(start = 2.dp, end = 12.dp)
                            .sizeIn(minHeight = 44.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PickerCheckbox(
                            checked = checked,
                            partially = partially,
                            enabled = canSelect,
                            onClick = { toggleSelection(row) }
                        )
                        if (isFolder) {
                            val rotation by animateFloatAsState(
                                targetValue = if (row.path in expanded) 90f else 0f,
                                animationSpec = tween(durationMillis = 180),
                                label = "folderChevron"
                            )
                            Box(
                                modifier = Modifier.size(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_chevron_right_fluent),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .rotate(rotation),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Spacer(Modifier.width(24.dp))
                        }
                        Icon(
                            painter = painterResource(kindIcon(TrackKind.of(row.node))),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        // 名字与扩展名分开渲染：名字长了只截名字，格式始终留在行尾
                        val suffix = fileSuffixOf(row.node)
                        if (suffix == null) {
                            Text(
                                text = row.node.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isFolder) FontWeight.Medium else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = row.node.title.dropLast(suffix.length),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Text(
                                    text = suffix,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    maxLines = 1
                                )
                            }
                        }
                        val trailing = when {
                            // 存本地时可勾选：说明这一勾是"复制"，不是再下一遍
                            inApp && copiesToLocal(row.path) -> "已在 App 内 · 复制"
                            inApp -> "已下载"
                            isFolder -> {
                                val count = leavesUnder[row.path].orEmpty().size
                                if (count > 0) "$count 个" else ""
                            }
                            else -> row.node.sizeText
                        }
                        if (trailing.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = trailing,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 选择器自己的勾选框：比 Material 默认的更小、更轻。
 *
 * 18dp 圆角方块 + 1.2dp 细描边（未选）/ 半透明主色填充（选中/部分），
 * 外面套 36dp 的可点区域 —— 默认 Checkbox 是 20dp 视觉 + 2dp 描边，
 * 在一片浅色行里显得很重，而且它内部的 `requiredSize` 让外部改不了尺寸。
 */
@Composable
private fun PickerCheckbox(
    checked: Boolean,
    partially: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(5.dp)
    val marked = checked || partially
    val fill = when {
        !marked -> Color.Transparent
        enabled -> cs.primary.copy(alpha = 0.85f)
        else -> cs.onSurfaceVariant.copy(alpha = 0.35f)
    }
    val border = when {
        !enabled -> cs.onSurfaceVariant.copy(alpha = 0.25f)
        else -> cs.onSurfaceVariant.copy(alpha = 0.45f)
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .toggleable(
                value = marked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onClick() }
            ),
        contentAlignment = Alignment.Center
    ) {
        val box = Modifier
            .size(18.dp)
            .clip(shape)
            .background(fill)
        Box(
            modifier = if (marked) box else box.border(1.2.dp, border, shape),
            contentAlignment = Alignment.Center
        ) {
            when {
                checked -> Icon(
                    painter = painterResource(R.drawable.ic_check_fluent),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = cs.onPrimary
                )
                partially -> Box(
                    modifier = Modifier
                        .size(width = 8.dp, height = 2.dp)
                        .background(cs.onPrimary, RoundedCornerShape(1.dp))
                )
            }
        }
    }
}

// ─────────────────────────── 工具 ───────────────────────────

private fun folderLeaves(nodes: List<Track>): Map<String, List<String>> {
    val map = HashMap<String, List<String>>()

    fun walk(node: Track): List<String> {
        if (node.children.isEmpty()) return listOf(node.path)
        val leaves = ArrayList<String>()
        node.children.forEach { child -> leaves.addAll(walk(child)) }
        map[node.path] = leaves
        return leaves
    }

    nodes.forEach { walk(it) }
    // 根目录不在 nodes 里，这里补一条"整棵树"的映射，供"全选"使用
    map[""] = nodes.flatMap { walk(it) }.distinct()
    return map
}

private fun findNode(nodes: List<Track>, path: String): Track? {
    for (node in nodes) {
        if (node.path == path) return node
        val hit = findNode(node.children, path)
        if (hit != null) return hit
    }
    return null
}

private fun kindIcon(kind: TrackKind): Int = when (kind) {
    TrackKind.FOLDER -> R.drawable.ic_folder_fluent
    TrackKind.AUDIO -> R.drawable.ic_audio_fluent
    TrackKind.VIDEO -> R.drawable.ic_video_fluent
    TrackKind.IMAGE -> R.drawable.ic_image_fluent
    TrackKind.SUBTITLE, TrackKind.TEXT, TrackKind.PDF -> R.drawable.ic_text_fluent
    TrackKind.UNSUPPORTED -> R.drawable.ic_file_fluent
}
