package com.asmrone.app.ui.screen.search

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.asmrone.app.R
import com.asmrone.app.data.model.Work
import com.asmrone.app.data.prefs.LibraryStore
import com.asmrone.app.data.model.SearchSuggestion
import com.asmrone.app.data.prefs.SearchHistoryEntry
import com.asmrone.app.data.prefs.SearchHistoryStore
import com.asmrone.app.data.repo.AsmrRepository
import com.asmrone.app.ui.component.AsmrFilterChip
import com.asmrone.app.ui.component.CollapsibleBottomBarEffect
import com.asmrone.app.ui.component.CompactSearchField
import com.asmrone.app.ui.component.EmptyHint
import com.asmrone.app.ui.component.ErrorHint
import com.asmrone.app.ui.component.LocalStableStatusBarTop
import com.asmrone.app.ui.component.SmallTagChip
import com.asmrone.app.ui.component.TabTopBar
import com.asmrone.app.ui.component.TagTone
import com.asmrone.app.ui.component.TopBarScrim
import com.asmrone.app.ui.component.WorkCard
import com.asmrone.app.ui.component.WorkCardShimmer
import com.asmrone.app.ui.component.animateCardEntrance
import com.asmrone.app.ui.component.cardEntrance
import com.asmrone.app.ui.theme.TouchTarget

/**
 * 搜索页 —— **只负责提条件**，结果在独立的 [SearchResultScreen] 里展示。
 *
 * ```
 * Box {
 *   Box(hazeSource) {
 *     Column(imePadding) {
 *       搜索形态 chip（普通 / 高级）
 *       普通：一个输入框   ⇄   高级：多个条件栏 + 候选 + 搜索按钮
 *       搜索历史
 *     }
 *   }
 *   TabTopBar 透明标题行 + 顶部渐变模糊
 * }
 * ```
 *
 * ## 为什么结果不在这里
 *
 * 原先输入区与结果区挤在同一页，两个问题：
 *  1. 高级搜索有好几个条件栏，结果一出来输入区就被顶得很窄，改条件很别扭
 *  2. 想在结果里换一组条件只能原地清空重来，没有"退回去改"的自然路径
 *
 * 拆开之后：搜索页提条件、结果页看结果，系统返回键就是"回去改条件"。
 *
 * @param onOpenResults 搜索已提交后跳转到结果页
 */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onOpenResults: () -> Unit,
    onWorkClick: (Int) -> Unit
) {
    val state by viewModel.state.collectAsState()
    // 搜索历史：本地持久化，直接订阅 store（见文件里的说明）
    val history by SearchHistoryStore.entries.collectAsState()
    val host = remember { AsmrRepository.host }
    val progressMap by LibraryStore.progress.collectAsState()
    val hazeState = dev.chrisbanes.haze.rememberHazeState()

    val density = LocalDensity.current
    val statusTopDp = LocalStableStatusBarTop.current
        ?: with(density) { WindowInsets.statusBars.asPaddingValues().calculateTopPadding() }
    val structuralTopPx = with(density) { (statusTopDp + 56.dp).roundToPx() }
    var barHeight by remember { mutableIntStateOf(structuralTopPx) }
    val topPadding = remember(barHeight, density) {
        with(density) { barHeight.toDp() } + 8.dp
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            Spacer(Modifier.height(topPadding))

            // ── 搜索形态（普通 / 高级）────────────────────────
            //
            // 只有两个选项，所以不需要横向滚动。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SearchMode.entries.forEach { mode ->
                    AsmrFilterChip(
                        text = mode.label,
                        selected = state.mode == mode,
                        onClick = { viewModel.setMode(mode) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            when (state.mode) {
                // ── 普通搜索：一个输入框，不显示任何标签 ──────────
                SearchMode.SIMPLE -> SimpleSearchInput(
                    query = state.query,
                    onQueryChange = viewModel::onQueryChange,
                    // 先让 ViewModel 记下检索并开跑，再跳结果页 ——
                    // 结果页读的就是这个 ViewModel，所以进去即能看到加载中 → 结果
                    onSubmit = {
                        viewModel.submit()
                        onOpenResults()
                    },
                    onClear = { viewModel.clearQuery() }
                )

                // ── 高级搜索：关键词 + 多个条件栏，候选来自三本字典 ──
                SearchMode.ADVANCED -> AdvancedSearchInputs(
                    state = state,
                    onSlotQueryChange = viewModel::onSlotQueryChange,
                    onSelect = viewModel::selectSuggestion,
                    onClearSlot = viewModel::clearSlot,
                    onRemoveSlot = viewModel::removeSlot,
                    onAddSlot = { viewModel.addSlot() },
                    onSearch = {
                        viewModel.searchWithConditions()
                        onOpenResults()
                    },
                    onReset = { viewModel.resetConditions() }
                )
            }

            // ── 搜索历史 ────────────────────────────────────
            //
            // 历史**本地持久化**（SharedPreferences），所以进程重启后还在。
            // 界面直接订阅 store 的 StateFlow，而不是从 UiState 里读 ——
            // 两份历史必然会引出"哪个是真的"。
            //
            // 两种形态**都展示历史**（高级搜索时挂在条件栏下面），
            // 但点它的行为不同（见 viewModel.replay）：
            //  - 普通搜索：直接照那条记录搜一次，然后进结果页
            //  - 高级搜索：只把文字与条件填回输入框 / 条件栏，**不立刻搜** ——
            //    填完还能继续加条件，由用户自己决定何时按搜索
            if (history.isNotEmpty() && (state.mode == SearchMode.ADVANCED || state.query.isEmpty())) {
                Spacer(Modifier.height(16.dp))
                SearchHistory(
                    history = history,
                    onPick = { entry ->
                        // 普通搜索照搜了才跳结果页；高级搜索只是填好，
                        // 留在本页让用户接着改
                        if (viewModel.replay(entry)) onOpenResults()
                    },
                    onRemove = { SearchHistoryStore.remove(it) },
                    onClear = { SearchHistoryStore.clear() }
                )
            }
        }

        // 透明标题行 + 顶部渐变模糊（与首页同一套指标）
        //
        // 这里**没有"重新搜索"按钮**：结果已经在独立的结果页里了，
        // 本页只负责提条件 —— 想换条件直接改输入框即可。
        TabTopBar(
            hazeState = hazeState,
            modifier = Modifier.onSizeChanged { barHeight = it.height }
        ) {
            Text(
                text = "搜索",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
        }

        // 顶部兜底渐隐（Haze 不可用时也能保证标题可读）
        TopBarScrim(scrollFraction = 0.35f, modifier = Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun SearchHistory(
    history: List<SearchHistoryEntry>,
    onPick: (SearchHistoryEntry) -> Unit,
    onRemove: (SearchHistoryEntry) -> Unit,
    onClear: () -> Unit
) {
    if (history.isEmpty()) {
        EmptyHint(
            title = "还没有搜索记录",
            description = "试试搜索喜欢的声音、声优或社团"
        )
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "搜索历史",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            items(history, key = { it.dedupeKey }) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(entry) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 高级搜索的记录标一个类型，否则"标签「ASMR」 且 社团「Whisp」"
                    // 这种长文案看起来会像一句关键词
                    if (entry.hasConditions) {
                        KindBadge(text = "高级")
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = entry.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { onRemove(entry) },
                        modifier = Modifier.size(TouchTarget.minimum)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close_fluent),
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            item {
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = onClear,
                    modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget.minimum)
                ) {
                    Text(
                        text = "清空搜索记录",
                        color = com.asmrone.app.ui.theme.ErrorColor
                    )
                }
            }
        }
    }
}


// ═══════════════════════════ 普通搜索 ═══════════════════════════

/**
 * 普通搜索的输入区：**一个**输入框，下面不显示任何标签。
 *
 * 这是"知道作品名/关键词，只想快点搜"的路径，所以刻意保持极简 ——
 * 一旦在下面铺候选，它就和高级搜索没有区别了，反而拖慢这条常用路径。
 */
@Composable
private fun SimpleSearchInput(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit
) {
    CompactSearchField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        placeholder = "搜索作品、社团或声优",
        leadingIconRes = R.drawable.ic_search_outline_fluent,
        imeAction = ImeAction.Search,
        onImeAction = onSubmit,
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close_fluent),
                        contentDescription = "清空",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    )
    Spacer(Modifier.height(10.dp))
}

// ═══════════════════════════ 高级搜索 ═══════════════════════════

/**
 * 高级搜索的输入区：**一个关键词框 + 多个条件栏** + 搜索/重置。
 *
 * ## 交互流程
 *
 * 每一栏自己就是"输入 → 从候选里点一条 → 这一栏锁定"。选完之后
 * [SearchUiState.canAddSlot] 才为真，右下角出现「添加条件」，
 * 于是可以一栏一栏往上加，最后点「搜索」把关键词与所有条件一起发出去。
 *
 * 为什么是"选完才允许加下一栏"：允许半填的栏会堆出
 * "哪些条件算数"的含糊状态 —— 而现在每个有内容的栏必定带一个已选项，
 * 一眼就能看出最终会搜什么。
 *
 * 只显示**最后一个**未选中栏的候选：同时展开多个下拉会把界面挤乱，
 * 而且用户一次只可能在敲一个框。
 *
 * ## 每个输入栏自己决定"装文字还是装标签"
 *
 * 这里**没有**单独的"关键词框"：一个栏里敲字、从候选里点一条就是标签条件，
 * 只敲字不选就是裸关键词。服务端的检索串本来就支持两者混用
 * （实测 `耳かき $tag:ASMR$` → 3049），所以用户想怎么组合都行。
 *
 * 多一个全局关键词框的坏处是：文字与标签被拆到两个地方，
 * 用户得先想"我这个词该写在哪儿"—— 而现在只需按顺序一栏一栏填。
 */
@Composable
private fun AdvancedSearchInputs(
    state: SearchUiState,
    onSlotQueryChange: (Int, String) -> Unit,
    onSelect: (Int, SearchSuggestion) -> Unit,
    onClearSlot: (Int) -> Unit,
    onRemoveSlot: (Int) -> Unit,
    onAddSlot: () -> Unit,
    onSearch: () -> Unit,
    onReset: () -> Unit
) {
    // 只给"最后一个还没选中的栏"显示候选，避免多个下拉同时展开
    val activeSlotId = state.slots.lastOrNull { it.selected == null }?.id

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        state.slots.forEachIndexed { index, slot ->
            SlotField(
                index = index,
                slot = slot,
                removable = state.slots.size > 1,
                onQueryChange = { onSlotQueryChange(slot.id, it) },
                onClear = { onClearSlot(slot.id) },
                onRemove = { onRemoveSlot(slot.id) },
                onSearch = onSearch
            )

            // 候选只挂在当前活跃栏下面
            if (slot.id == activeSlotId && slot.suggestions.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                SuggestionList(
                    suggestions = slot.suggestions,
                    onPick = { onSelect(slot.id, it) }
                )
            }

            if (index < state.slots.lastIndex) Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(10.dp))

        // ── 操作行：加条件 / 搜索 / 重置 ────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 加输入栏：**一直可用**（只有到上限才消失）。
            // 每个栏自己就能装文字或标签，所以没有"必须先把上一栏选完"这种约束。
            if (state.canAddSlot) {
                TextButton(onClick = onAddSlot) { Text("+ 添加输入栏") }
            }

            Spacer(Modifier.weight(1f))

            // 重置：有任何内容（文字或已选）才需要给这个入口
            if (state.slots.any { it.query.isNotBlank() || it.selected != null }) {
                TextButton(onClick = onReset) {
                    Text(
                        text = "重置",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Button(
                onClick = onSearch,
                enabled = state.hasSearched,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text("搜索", style = MaterialTheme.typography.labelMedium)
            }
        }

        // 将要搜什么：多条件的结果容易让人困惑（为什么这么少），
        // 把文字与条件都摊开能直接回答"到底按什么搜的"。
        if (state.hasSearched) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "将搜索：" + state.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 三本字典都空 → 提示去更新，而不是让用户对着空候选发呆
        if (state.dictionaryEmpty) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(12.dp)
            ) {
                Text(
                    text = "本地还没有搜索字典。请到「设置 → 服务器 → 数据设置」" +
                        "点一下「更新搜索字典」，之后这里就能按标签 / 声优 / 社团选了。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    Spacer(Modifier.height(10.dp))
}

/**
 * 一个条件栏。
 *
 * 已选中的栏显示成一个带类型标记与 × 的"胶囊"，未选中的栏是普通输入框 ——
 * 一眼能看出哪些栏已经定下来了。
 */
@Composable
private fun SlotField(
    index: Int,
    slot: InputSlotState,
    removable: Boolean,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onRemove: () -> Unit,
    onSearch: () -> Unit
) {
    val selected = slot.selected

    if (selected != null) {
        // 已锁定：显示成胶囊
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KindBadge(text = selected.kindLabel)
            Spacer(Modifier.width(8.dp))
            Text(
                // 同样是 **displayName**（标签用中文译名）：胶囊是用户点出来的，
                // 显示原始名会让"我点的那个"和"显示的这个"对不上
                text = selected.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_close_fluent),
                    contentDescription = if (removable) "移除这个条件" else "清空这个条件",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    } else {
        CompactSearchField(
            value = slot.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = "第 ${index + 1} 栏：输入文字，或从候选里选标签 / 声优 / 社团",
            leadingIconRes = R.drawable.ic_search_outline_fluent,
            imeAction = ImeAction.Search,
            // 回车直接开搜 —— 这一栏不选候选也行，那就当裸关键词用
            onImeAction = onSearch,
            trailingIcon = {
                if (slot.query.isNotEmpty()) {
                    IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close_fluent),
                            contentDescription = "清空这一栏",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        )
    }
}

/** 条件类型的小标记：标签 / 声优 / 社团 */
@Composable
private fun KindBadge(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/**
 * 候选列表。
 *
 * 数据**全部来自本地字典**，所以输入时零请求、即时出结果。
 * 每条左边带类型标记（标签 / 声优 / 社团）—— 高级搜索把三种资源混在一起给，
 * 没有这个标记就分不清 `Whisp` 到底命中哪个。
 *
 * 高度限制在 240dp 并自身可滚动：否则候选一多会把下面全挤出屏幕。
 */
@Composable
private fun SuggestionList(
    suggestions: List<SearchSuggestion>,
    onPick: (SearchSuggestion) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        items(suggestions, key = { it.namespace + "|" + it.id }) { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(item) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                KindBadge(text = item.kindLabel)
                Spacer(Modifier.width(8.dp))
                Text(
                    // ⚠️ 显示的是 **displayName**（标签的中文译名），不是 name（原始名）。
                    // 用 name 会让候选里冒出用户从没见过的日文/英文原名 ——
                    // 搜「亲热/甜蜜」却看到「纯爱/甜蜜」就是这么来的。
                    text = item.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item.count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
