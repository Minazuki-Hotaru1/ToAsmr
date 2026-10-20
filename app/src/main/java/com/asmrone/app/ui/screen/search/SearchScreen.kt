package com.asmrone.app.ui.screen.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.asmrone.app.R
import com.asmrone.app.data.links.WorkLink
import com.asmrone.app.data.model.SearchSuggestion
import com.asmrone.app.data.prefs.SearchHistoryEntry
import com.asmrone.app.data.prefs.SearchHistoryStore
import com.asmrone.app.ui.component.AsmrFilterChip
import com.asmrone.app.ui.component.CollapsibleBottomBarEffect
import com.asmrone.app.ui.component.CompactSearchField
import com.asmrone.app.ui.component.FluentIcon
import com.asmrone.app.ui.component.rememberStatusBarTopDp
import com.asmrone.app.ui.component.TopBarAction
import com.asmrone.app.ui.component.TopBarScrim
import com.asmrone.app.ui.theme.ErrorColor
import com.asmrone.app.ui.theme.Liquid
import com.asmrone.app.ui.theme.TouchTarget
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * 搜索页 —— **只负责提条件**，结果在独立的 [SearchResultScreen] 里展示。
 *
 * 布局与 comicApp 的搜索页一一对应：
 *
 * ```
 * Box {
 *   Box(layerBackdrop) {
 *     Column(imePadding) {
 *       （给悬浮顶栏留白）
 *       普通：一个输入框   ⇄   高级：多个条件栏 + 候选
 *       搜索历史（标题 / 逐条删除 / 展开全部 / 清空）
 *     }
 *   }
 *   TopBarScrim（滚动渐显）
 *   Row 悬浮顶栏：标题 + 玻璃「高级 / 关键词」切换
 * }
 * ```
 *
 * 与旧版的差别（这次是照 comicApp 重做，不是在原样式上改）：
 *  - 顶部的两枚模式 chip 换成**右上角一枚液态玻璃 [TopBarAction]**（label 指向"将进入的模式"）；
 *  - 两种模式之间用 `Crossfade + animateContentSize` 过渡（高度也一起动，历史区不会硬跳）；
 *  - 历史区补齐 comicApp 的三件套：**展开全部（N 条）/ 红色描边清空 / 112dp 底部留白**；
 *  - 空历史不再是富文本空态，而是 comicApp 的居中一行「无搜索记录」；
 *  - 顶栏高度仍用 `onSizeChanged` 实测，首帧按「稳定状态栏高 + 56dp」估算。
 *
 * @param onOpenResults 搜索已提交后跳转到结果页
 * @param onOpenRj 输入的是一串 RJ 号：不搜索、也不进结果页，直接打开那部作品
 */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onOpenResults: () -> Unit,
    onWorkClick: (Int) -> Unit,
    onOpenRj: (String) -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    // 搜索历史：本地持久化，直接订阅 store（单一数据源）
    val history by SearchHistoryStore.entries.collectAsState()
    val focusManager = LocalFocusManager.current
    // 顶栏的玻璃按钮从这里取下方内容做折射
    val topBarBackdrop = rememberLayerBackdrop()

    val density = LocalDensity.current
    val statusTopDp = rememberStatusBarTopDp()
    var topBarHeight by remember(density) {
        mutableIntStateOf(with(density) { (statusTopDp + Liquid.TOP_BAR_HEIGHT).roundToPx() })
    }

    var showAll by remember { mutableStateOf(false) }
    val visibleHistory = if (showAll) history else history.take(10)
    val historyListState = rememberLazyListState()
    val scrollFraction = if (historyListState.firstVisibleItemIndex > 0 ||
        historyListState.firstVisibleItemScrollOffset > 100
    ) 1f else 0f

    // 向下滚动时收起底部导航栏、向上滚动展开（可在「设置 → 外观」里关闭）
    CollapsibleBottomBarEffect {
        historyListState.firstVisibleItemIndex to historyListState.firstVisibleItemScrollOffset
    }

    val advancedMode = state.mode == SearchMode.ADVANCED

    fun submitSimple() {
        if (state.query.isBlank()) return
        // RJ 号是一个"地址"，不是一个"关键词"：直接打开那部作品，
        // 既不发搜索、也不进结果页（结果页会空着，因为 RJ 号不是按标题搜的）。
        // 把整条链接粘进搜索框也认（`https://asmr.one/work/RJ01700554`）。
        val token = WorkLink.tokenFromUrl(state.query) ?: WorkLink.rjCodeOf(state.query)
        if (token != null) {
            focusManager.clearFocus()
            onOpenRj(token)
            return
        }
        focusManager.clearFocus()
        viewModel.submit()
        onOpenResults()
    }

    fun submitAdvanced() {
        if (!state.hasSearched) return
        focusManager.clearFocus()
        viewModel.searchWithConditions()
        onOpenResults()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .imePadding()
                // 内容层作为取样源：右上角的玻璃按钮才能"透"出下面的内容
                .layerBackdrop(topBarBackdrop)
        ) {
            // 给悬浮顶栏留出空间（含状态栏）；玻璃按钮有背景可取样
            Spacer(Modifier.height(with(density) { topBarHeight.toDp() }))
            // 搜索栏与顶栏之间额外留一点呼吸空间，避免贴住标题
            Spacer(Modifier.height(Liquid.CONTENT_TOP_GAP))

            // ═══════════ 关键词 / 高级搜索 — 淡入淡出 + 高度动画 ═══════════
            Crossfade(
                targetState = advancedMode,
                animationSpec = tween(200),
                modifier = Modifier.animateContentSize()
            ) { adv ->
                if (adv) {
                    AdvancedSearchInputs(
                        state = state,
                        onSlotQueryChange = viewModel::onSlotQueryChange,
                        onSelect = viewModel::selectSuggestion,
                        onClearSlot = viewModel::clearSlot,
                        onRemoveSlot = viewModel::removeSlot,
                        onToggleExclude = viewModel::setSlotExclude,
                        onSlotFocused = viewModel::refreshSlotSuggestions,
                        onAddSlot = { viewModel.addSlot() },
                        onSearch = ::submitAdvanced,
                        onReset = { viewModel.resetConditions() }
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(animationSpec = tween(180))
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 8.dp)
                    ) {
                        CompactSearchField(
                            value = state.query,
                            onValueChange = viewModel::onQueryChange,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = "搜索作品、社团或声优",
                            leadingIconRes = R.drawable.ic_search_outline_fluent,
                            trailingIcon = {
                                if (state.query.isNotEmpty()) {
                                    IconButton(
                                        onClick = { viewModel.clearQuery() },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        FluentIcon(
                                            res = R.drawable.ic_close_fluent,
                                            contentDescription = "清空",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            size = 18.dp
                                        )
                                    }
                                }
                            },
                            imeAction = ImeAction.Search,
                            onImeAction = ::submitSimple
                        )
                    }
                }
            }

            // ── 历史记录 ────────────────────────────────────
            //
            // 历史**本地持久化**（SharedPreferences），界面直接订阅 store 的 StateFlow，
            // 而不是从 UiState 里读 —— 两份历史必然会引出"哪个是真的"。
            //
            // 两种形态**都展示历史**（高级搜索时挂在条件栏下面；普通搜索时输入框下面），
            // 点它的行为由 ViewModel 决定：
            //  - 普通搜索：照那条记录搜一次，然后进结果页
            //  - 高级搜索：只把文字与条件填回，**不立刻搜**，由用户自己按搜索
            if (history.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("无搜索记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text(
                    text = "搜索历史",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(
                    state = historyListState,
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp)
                ) {
                    items(visibleHistory, key = { it.dedupeKey }) { entry ->
                        HistoryRow(
                            entry = entry,
                            onClick = {
                                if (viewModel.replay(entry)) onOpenResults()
                            },
                            onRemove = { SearchHistoryStore.remove(entry) }
                        )
                    }

                    // 展开更多（本地历史最多 15 条，默认只露 10 条）
                    if (history.size > 10) {
                        item {
                            TextButton(
                                onClick = { showAll = !showAll },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (showAll) "收起" else "展开全部 (${history.size} 条)",
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // 清空全部
                    item {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { SearchHistoryStore.clear() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = ErrorColor
                            )
                        ) {
                            Text("清空搜索记录")
                        }
                    }

                    // 底部留白 — 保证清空按钮可滑动到导航栏上方
                    item { Spacer(Modifier.height(Liquid.CONTENT_BOTTOM_INSET_WITH_PLAYER)) }
                }
            }
        }

        // 顶部兜底渐隐（滚动后标题依旧可读）
        TopBarScrim(scrollFraction = scrollFraction)

        // 悬浮顶栏：先量总高度（含状态栏），内容层据此留白；玻璃按钮从内容层取样
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { topBarHeight = it.height }
                .padding(top = rememberStatusBarTopDp())
                .padding(horizontal = 16.dp, vertical = Liquid.TOP_BAR_VERTICAL_PADDING)
                .heightIn(min = TouchTarget.minimum),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "搜索",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            // 模式切换：label 指向"点下去会进入哪个模式"（与 comicApp 的「高级 / 关键词」一致）
            // 这里刻意不给图标：comicApp 的策略是文字按钮，宽度更稳、语义也更直接
            TopBarAction(
                onClick = {
                    viewModel.setMode(
                        if (advancedMode) SearchMode.SIMPLE else SearchMode.ADVANCED
                    )
                },
                modifier = Modifier.animateContentSize(animationSpec = tween(180)),
                backdrop = topBarBackdrop,
                label = if (advancedMode) "关键词" else "高级"
            )
        }
    }
}

/** 一条搜索历史：高级记录带「高级」标记，右侧是删除按钮 */
@Composable
private fun HistoryRow(
    entry: SearchHistoryEntry,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 高级搜索的记录标一个类型，否则"标签「ASMR」 社团「Whisp」"
        // 这种长文案看起来会像一句普通关键词
        if (entry.hasConditions) {
            KindBadge(text = "高级")
            Spacer(Modifier.width(8.dp))
        }
        Text(
            // 排除项（`-声优「xx」`）由分段里的 exclude 标成红色
            text = entry.summarySegments.toSummaryAnnotatedString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(TouchTarget.minimum)
        ) {
            FluentIcon(
                res = R.drawable.ic_close_fluent,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 16.dp
            )
        }
    }
}

/**
 * 高级搜索的输入区：**一个关键词框 + 多个条件栏** + 搜索/重置。
 *
 * ## 交互流程
 *
 * 每一栏自己就是"输入 → 从候选里点一条 → 这一栏锁定"。加栏**一直可用**
 * （只要没到上限），于是可以一栏一栏往上加，最后点「搜索」把关键词与所有条件一起发出去。
 *
 * 候选下拉跟着**当前聚焦的那一栏**走（`focused` 是每行自己的状态）。
 * 旧实现是"挂在最后一个未选中的栏下面"，于是加了两栏之后在**第一栏**打字
 * 时下拉永远不出现 —— 那个 bug 就是从这里来的。
 */
@Composable
private fun AdvancedSearchInputs(
    state: SearchUiState,
    onSlotQueryChange: (Int, String) -> Unit,
    onSelect: (Int, SearchSuggestion) -> Unit,
    onClearSlot: (Int) -> Unit,
    onRemoveSlot: (Int) -> Unit,
    onToggleExclude: (Int, Boolean) -> Unit,
    onSlotFocused: (Int) -> Unit,
    onAddSlot: () -> Unit,
    onSearch: () -> Unit,
    onReset: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        state.slots.forEachIndexed { index, slot ->
            ConditionRow(
                slot = slot,
                removable = state.slots.size > 1,
                onQueryChange = { onSlotQueryChange(slot.id, it) },
                onToggleExclude = { onToggleExclude(slot.id, !slot.exclude) },
                onFocused = { onSlotFocused(slot.id) },
                onClear = { onClearSlot(slot.id) },
                onRemove = { onRemoveSlot(slot.id) },
                onSearch = onSearch,
                onSelectSuggestion = { onSelect(slot.id, it) }
            )

            if (index < state.slots.lastIndex) Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(10.dp))

        // ── 操作行：加条件 / 重置 / 搜索（与 comicApp 的排布一致：操作靠左、主按钮靠右）──
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.canAddSlot) {
                TextButton(onClick = onAddSlot) {
                    FluentIcon(
                        res = R.drawable.ic_add_fluent,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        size = 16.dp
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "添加条件",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

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

            Spacer(Modifier.weight(1f))

            Button(
                onClick = onSearch,
                enabled = state.hasSearched,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) {
                FluentIcon(
                    res = R.drawable.ic_search_fluent,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    size = 16.dp
                )
                Spacer(Modifier.width(4.dp))
                Text("搜索", style = MaterialTheme.typography.labelMedium)
            }
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
                    text = "本地还没有搜索字典。请到「设置 → 服务器」" +
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
 * 一个条件行 —— 与 comicApp 的 `SearchConditionRow` 同构：
 *
 * ```
 * [输入框 或 已选胶囊 ............]  [包含/排除]  [删除]
 * ```
 *
 * 搜索栏占满左侧剩余宽度，「包含/排除」与「删除」并排贴在右边 ——
 * 输入区尽量大，两个操作按钮收成一组。
 *
 * - 排除时 chip 变成红色「排除」，已选胶囊也转成错误色（与筛选 chip 的 negativeTone 同一套语言）；
 * - 胶囊上的 × 只是"清掉这个标签、回到输入框"；右侧垃圾桶才是删掉整行；
 * - 候选下拉跟着**本行**的聚焦状态出现，所以多栏时在任意一栏打字都有提示词。
 */
@Composable
private fun ConditionRow(
    slot: InputSlotState,
    removable: Boolean,
    onQueryChange: (String) -> Unit,
    onToggleExclude: () -> Unit,
    onFocused: () -> Unit,
    onClear: () -> Unit,
    onRemove: () -> Unit,
    onSearch: () -> Unit,
    onSelectSuggestion: (SearchSuggestion) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val selected = slot.selected

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(180))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 布局顺序：搜索栏（占满剩余宽度）→ 包含/排除 → 删除
            // —— 两个操作按钮都贴在右侧，输入框获得尽可能大的可点区域。
            if (selected != null) {
                SelectedTagChip(
                    kindLabel = selected.kindLabel,
                    label = selected.displayName,
                    exclude = slot.exclude,
                    onClear = onClear,
                    modifier = Modifier.weight(1f)
                )
            } else {
                CompactSearchField(
                    value = slot.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                    // 与普通搜索共用同一句提示：高级搜索里"第几栏"一类的说明是多余的
                    placeholder = "搜索关键词",
                    leadingIconRes = R.drawable.ic_search_outline_fluent,
                    imeAction = ImeAction.Search,
                    // 回车直接开搜 —— 这一栏不选候选也行，那就当裸关键词用
                    onImeAction = onSearch,
                    onFocusChange = {
                        focused = it
                        // 重新聚焦到已有文字的栏：候选原本只在"键入"时算，这里补一次
                        if (it) onFocused()
                    },
                    trailingIcon = {
                        if (slot.query.isNotEmpty()) {
                            IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                                FluentIcon(
                                    res = R.drawable.ic_close_fluent,
                                    contentDescription = "清空这一栏",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    size = 16.dp
                                )
                            }
                        }
                    }
                )
            }

            // 包含 / 排除：negativeTone = true 时选中就是红底 + 红字 + 描边。
            // 放在输入框右侧、删除按钮左边，和删除一起构成"这一行的操作区"。
            //
            // ⚠️ FilterChip 默认会把**布局高度**撑到 48dp（Material 最小触控尺寸），
            //    整行就比普通搜索的 40dp 输入框高出一截 —— 两个页面的输入框看起来不一样高。
            //    这里把最小触控尺寸设成 40dp：行高与普通搜索一致，
            //    chip 的胶囊本身仍是 32dp（居中），可点区域 40dp。
            CompositionLocalProvider(
                LocalMinimumInteractiveComponentSize provides 40.dp
            ) {
                AsmrFilterChip(
                    text = if (slot.exclude) "排除" else "包含",
                    selected = slot.exclude,
                    onClick = onToggleExclude,
                    negativeTone = true,
                    shape = RoundedCornerShape(10.dp)
                )
            }

            // 删除整行（只剩一行时 = 清空）：comicApp 同款红色垃圾桶
            IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
                FluentIcon(
                    res = R.drawable.ic_delete_fluent,
                    contentDescription = if (removable) "删除这个条件" else "清空这个条件",
                    tint = ErrorColor.copy(alpha = 0.85f),
                    size = 18.dp
                )
            }
        }

        // ── 候选：只在本行聚焦、且是自由文本时出现（与 comicApp 一致）──
        AnimatedVisibility(
            visible = selected == null && focused && slot.suggestions.isNotEmpty(),
            enter = expandVertically(animationSpec = tween(180), clip = false) +
                fadeIn(animationSpec = tween(180)),
            exit = shrinkVertically(animationSpec = tween(150), clip = false) +
                fadeOut(animationSpec = tween(150))
        ) {
            // 上下留出空间，让卡片阴影完整显示
            Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)) {
                SuggestionDropdown(
                    suggestions = slot.suggestions,
                    onSelect = onSelectSuggestion
                )
            }
        }
    }
}

/** 已选条件（comicApp 的 `SelectedTagChip`）：40dp 高、r12 胶囊，排除时转错误色 */
@Composable
private fun SelectedTagChip(
    kindLabel: String,
    label: String,
    exclude: Boolean,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val content = if (exclude) ErrorColor else cs.primary
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = content.copy(alpha = 0.12f)
    ) {
        Row(
            modifier = Modifier.height(40.dp).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                // 显示 displayName（标签用中文译名）：胶囊是用户点出来的，
                // 显示原始名会让"我点的那个"和"显示的这个"对不上
                text = "$kindLabel：$label",
                modifier = Modifier.weight(1f),
                color = content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                FluentIcon(
                    res = R.drawable.ic_close_fluent,
                    contentDescription = "清除",
                    tint = content.copy(alpha = 0.7f),
                    size = 16.dp
                )
            }
        }
    }
}

/** 条件类型的小标记：标签 / 声优 / 社团（历史列表的「高级」角标也用它） */
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
 * 候选列表 —— 与 comicApp 的 `SuggestionDropdown` 同款：
 * 白底卡片 + 4dp 投影 + 最高 280dp 自身滚动；每行**两行文本**（显示名 + `#类型 原始名`）。
 *
 * 先只露 10 条，点「加载更多」再展开 —— 标签候选动辄几十条，一次全铺会把下面挤出屏幕。
 *
 * 数据全部来自本地字典，所以输入时零请求、即时出结果。
 */
@Composable
private fun SuggestionDropdown(
    suggestions: List<SearchSuggestion>,
    onSelect: (SearchSuggestion) -> Unit,
    modifier: Modifier = Modifier
) {
    if (suggestions.isEmpty()) return
    var visibleCount by remember(suggestions) {
        mutableIntStateOf(minOf(10, suggestions.size))
    }
    val shown = suggestions.take(visibleCount)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState())
        ) {
            shown.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(item) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            // ⚠️ 显示 **displayName**（标签的中文译名），不是 name（原始名）
                            text = item.displayName,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "#${item.kindLabel} ${item.name} · ${item.count} 部",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (visibleCount < suggestions.size) {
                TextButton(
                    onClick = { visibleCount = minOf(visibleCount + 10, suggestions.size) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "加载更多（${suggestions.size - visibleCount}）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
