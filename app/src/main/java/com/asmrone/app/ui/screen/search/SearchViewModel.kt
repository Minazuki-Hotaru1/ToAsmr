package com.asmrone.app.ui.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.asmrone.app.data.model.Circle
import com.asmrone.app.data.model.SearchCondition
import com.asmrone.app.data.model.SearchNamespaces
import com.asmrone.app.data.model.SearchQueryBuilder
import com.asmrone.app.data.model.SearchSuggestion
import com.asmrone.app.data.model.SearchSummarySegment
import com.asmrone.app.data.model.SuggestionMerger
import com.asmrone.app.data.model.Tag
import com.asmrone.app.data.model.VoiceActor
import com.asmrone.app.data.model.Work
import com.asmrone.app.data.model.WorkFilter
import com.asmrone.app.data.prefs.SearchHistoryEntry
import com.asmrone.app.data.prefs.SearchHistoryStore
import com.asmrone.app.data.repo.AsmrRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 搜索页的两种形态。
 *
 *  - [SIMPLE] **普通搜索**：一个输入框，只按关键词搜作品。
 *    **不显示任何标签 / 声优 / 社团候选** —— 想不起来准确名字时它就是最快的那条路。
 *  - [ADVANCED] **高级搜索**：多个条件栏，每栏选中一项（标签 / 声优 / 社团），
 *    候选按**键入词**从三本本地字典里一起筛出来，可组合成多条件检索。
 */
enum class SearchMode(val label: String) {
    SIMPLE("普通搜索"),
    ADVANCED("高级搜索")
}

/**
 * 高级搜索里的一个输入栏。
 *
 * [query] 是用户敲的字，[selected] 是从候选里选中的那一项。**两者互斥又共存**：
 *
 *  - 只敲字、没选 → 这一栏当**裸关键词**用（服务端支持文字与条件混用）
 *  - 从候选里点了一条 → 这一栏是**条件**，[query] 变成那条的显示名
 *
 * 所以用户不需要先声明"我这一栏要搜什么类型"，随手填就行。
 *
 * [id] 只用于 Compose 的 key 与增删定位，**不参与检索**；默认 0 是为了构造方便，
 * 真正入列时会由 `withFreshIds` 重新连续编号。
 */
data class InputSlotState(
    val id: Int = 0,
    val query: String = "",
    val selected: SearchCondition? = null,
    val suggestions: List<SearchSuggestion> = emptyList(),
    /**
     * 这一栏是不是"排除"。
     *
     * 排除对**条件和裸文字都生效**（实测：`$-tag:X$` 与 `-词` 都有效），
     * 所以它挂在栏上、而不是只挂在 [selected] 上 —— 用户可以先切"排除"再打字。
     */
    val exclude: Boolean = false
)

data class SearchUiState(
    val mode: SearchMode = SearchMode.SIMPLE,
    /**
     * **普通搜索**的关键词。
     *
     * 高级搜索不用它 —— 那边每个输入栏自己就能装文字或标签，
     * 多一个"全局关键词框"只会让人不知道文字该写哪儿。
     */
    val query: String = "",
    /**
     * **高级搜索**的输入栏。
     *
     * **始终至少有一个**，这样界面永远是"一个输入栏 + 加号"，
     * 而不是"什么都没有 + 加号"（后者会让人不知道从哪里开始）。
     */
    val slots: List<InputSlotState> = listOf(InputSlotState(id = 0)),
    /** 三本字典都为空 —— 用来提示"去设置里更新搜索字典" */
    val dictionaryEmpty: Boolean = false,
    /**
     * 上一次**真正发出去**的那次检索的摘要 —— 结果页大标题用它。
     *
     * 不能直接用 [descriptionSegments]：结果页在返回栈里活着，用户退回去改输入栏时
     * slots 已经变了，而标题必须仍然是"这批结果按什么搜的"。
     */
    val appliedSummary: List<SearchSummarySegment> = emptyList(),
    val results: List<Work> = emptyList(),
    /**
     * 这次检索的**总条数**（服务端 `pagination.totalCount`）。
     *
     * ⚠️ 与 `results.size` 不是一回事：后者只是**已经加载到本地**的条数
     * （首屏 20 条，翻页才增长）。用户想知道的是"一共有多少条"，
     * 原版网站显示的也是总条数。
     */
    val totalCount: Int = 0,
    val page: Int = 1,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null
) {
    /** 已选中的条件（按栏顺序） */
    val conditions: List<SearchCondition>
        get() = slots.mapNotNull { it.selected }

    /** 只敲了文字、没从候选里选的栏（这些文字当裸关键词用） */
    val freeTexts: List<String>
        get() = slots.filter { it.selected == null && it.query.isNotBlank() }.map { it.query.trim() }

    /**
     * 当前形态有没有可搜的内容。
     *
     * 普通搜索看关键词，高级搜索看输入栏（文字或条件都算）。
     */
    val hasSearched: Boolean
        get() = when (mode) {
            SearchMode.SIMPLE -> query.isNotBlank()
            SearchMode.ADVANCED -> conditions.isNotEmpty() || freeTexts.isNotEmpty()
        }

    /**
     * 结果页标题用的"这次按什么搜"的摘要（**分段**，界面据此把排除项画红）。
     *
     * 多条件的结果数字往往小得让人困惑（取交集），把**文字与条件都**摊开才能
     * 直接回答"凭什么只有这几条"。
     *
     * 条件里显示的是 [SearchCondition.displayName]（标签用中文译名），
     * 而不是发给服务端的原始名 —— 用户没见过的名字出现在这里会很突兀。
     *
     * 排除项写成 `-标签「X」` / `-词`，不再用「排除」二字；
     * 分段之间是一个空格，不再用「且」连接（条件一多那句话就读不完了）。
     */
    val descriptionSegments: List<SearchSummarySegment>
        get() {
            if (mode == SearchMode.SIMPLE) {
                return listOfNotNull(SearchSummarySegment.ofKeyword(query))
            }
            // 按栏顺序，文字与条件各按各的写法；空栏跳过
            return slots.mapNotNull { slot ->
                val selected = slot.selected
                when {
                    selected != null -> SearchSummarySegment.ofCondition(
                        // 以栏上的 exclude 为准：它和 chip 的显示永远一致
                        selected.copy(exclude = slot.exclude)
                    )

                    slot.query.isNotBlank() -> SearchSummarySegment.ofKeyword(
                        slot.query,
                        slot.exclude
                    )

                    else -> null
                }
            }
        }

    /** 同上，纯文本形态（日志、测试与不需要上色的地方用） */
    val description: String
        get() = SearchSummarySegment.join(descriptionSegments)

    /** 还能不能再加一栏（到上限就不给加了） */
    val canAddSlot: Boolean get() = slots.size < MAX_SLOTS

    companion object {
        /**
         * 输入栏上限。
         *
         * 再多也没法在一个屏幕上读清楚，而且条件越多命中越接近 0 ——
         * 6 个已经足够把范围收到很小了。
         */
        const val MAX_SLOTS = 6
    }
}

/**
 * 搜索页 ViewModel。
 *
 * ## 普通搜索走哪里
 *
 * `GET /api/search/{keyword}`（经由 `AsmrRepository.getWorks(keyword = …)`）。
 *
 * ## 高级搜索走哪里
 *
 * **单条件**时走专用端点（`/api/tags/{id}/works` 等）—— 更精确。
 * **多条件**时才拼检索串走 `/api/search/{keyword}`。
 *
 * 为什么这么分：实测 `$tag:ASMR$`(18315) 与 `/api/tags/497/works`(18315) 一致，
 * 但 `$circle:Whisp$`(605) 与 `/api/circles/41088/works`(604) **有出入** ——
 * 按名称查会把同名的其他资源也算进来。所以能走端点就走端点。
 *
 * ## 服务端条件语法（全部实测，详见 [SearchNamespaces]）
 *
 * 条件写成 `$namespace:name$`，多个之间**空格分隔**（粘在一起会返回 0），
 * 值用**名称**不用 id，名称含空格时**不要加引号**。
 */
class SearchViewModel : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    /**
     * 上一次**真正发出去**的检索参数（翻页照抄这一份）。
     *
     * 为什么不能现读界面上的输入框：
     *
     *  1. **点搜索历史重放**时输入框是**空的** —— 那条记录按设计不回填输入框
     *     （见 [replay]），于是翻页时再怎么归纳也得到"空白关键词"，`loadMore()`
     *     第一行就 return 了。表现是"滑到底不加载、点「加载更多」也没反应"，
     *     而这一页的数据其实是好的。
     *  2. 用户在结果页返回后**改了关键词却没重新搜**，现读会拿新词去翻旧结果的页，
     *     第二页和第一页从此对不上。
     *
     * 所以"要搜什么"在提交那一刻就该定下来，之后翻页只认这份快照。
     */
    private var appliedKeyword: String = ""
    private var appliedConditions: List<SearchCondition> = emptyList()
    private var appliedFilter: WorkFilter? = null

    /** 下一个条件栏的 id（只增不减，避免 Compose 复用错栏） */
    private var nextSlotId = 1

    /**
     * 三本字典的进程内缓存。
     *
     * 一次性读进来（标签 422 / 声优 5552 / 社团 5882 条），之后按键入词筛候选
     * 只在内存里做，既不读盘也不发请求 —— 磁盘读由各字典自己保证"每进程最多一次"。
     */
    private var tagCache: List<Tag> = emptyList()
    private var vaCache: List<VoiceActor> = emptyList()
    private var circleCache: List<Circle> = emptyList()

    /**
     * 候选列表最多给这么多条。
     *
     * 声优/社团各有五千多条，全铺出来既慢也没意义，
     * 而且候选区高度有限（见 `SuggestionList` 的 maxHeight）。
     */
    private val suggestionLimit = 40

    private companion object {
        /**
         * 每页条数。
         *
         * 首屏与翻页**必须用同一个值**：`hasMore` 是 `currentPage * pageSize < totalCount`
         * 算出来的，两处不一致会让"还有没有下一页"的判断忽真忽假。
         */
        const val PAGE_SIZE = 20
    }

    init {
        reloadDictionaries()
    }

    /** 重新读取三本本地字典（进搜索页 / 设置里更新过之后调用） */
    fun reloadDictionaries() {
        tagCache = AsmrRepository.cachedSearchTags()
        vaCache = AsmrRepository.cachedVoiceActors()
        circleCache = AsmrRepository.cachedCircles()
        _state.value = _state.value.copy(
            dictionaryEmpty = tagCache.isEmpty() && vaCache.isEmpty() && circleCache.isEmpty()
        )
    }

    // ─────────────────────────── 模式切换 ───────────────────────────

    fun setMode(mode: SearchMode) {
        if (_state.value.mode == mode) return
        searchJob?.cancel()
        _state.value = _state.value.copy(
            mode = mode,
            // 清掉结果与错误：换了形态还挂着上一次的结果，
            // 看起来就像"输入框和结果对不上"
            results = emptyList(),
            appliedSummary = emptyList(),
            error = null,
            loading = false,
            loadingMore = false,
            hasMore = false,
            page = 1
        )
        reloadDictionaries()
    }

    // ─────────────────────── 普通搜索（关键词）───────────────────────

    fun onQueryChange(value: String) {
        _state.value = _state.value.copy(query = value)
    }

    /**
     * 普通搜索提交。
     *
     * 只负责把关键词落到状态上并清掉可能残留的条件（普通搜索页没有条件栏，
     * 但历史重放可能刚填过），真正的请求交给 [runSearch] —— 那样
     * "文字 + 条件"的两种形态就只有一处实现。
     */
    fun submit(keyword: String = _state.value.query) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        val s = _state.value
        _state.value = s.copy(
            query = kw,
            // 走普通搜索就是"只要这个关键词"，把重放留下的条件清掉
            slots = listOf(InputSlotState(id = 0))
        )
        nextSlotId = 1
        runSearch()
    }

    // ─────────────────────── 高级搜索（条件栏）───────────────────────

    fun onSlotQueryChange(slotId: Int, value: String) {
        val suggestions = queryPicker(value)
        updateSlot(slotId) {
            it.copy(
                query = value,
                // 改了输入就把已选项清掉：否则"已选 ASMR"和"框里写着催眠"同时成立，
                // 用户无法判断搜索实际用的是哪个
                selected = null,
                suggestions = suggestions
            )
        }
    }

    fun selectSuggestion(slotId: Int, item: SearchSuggestion) {
        updateSlot(slotId) {
            // 选中后清空候选（这一栏"锁定"，收起下拉）。
            // 栏里回显 **displayName**（中文译名）而不是原始名 ——
            // 否则用户刚点的那条候选与栏里留下的文字对不上。
            // 条件继承这一栏的"排除"状态：切了排除再选标签，就该生成 `$-tag:X$`。
            it.copy(
                query = item.displayName,
                selected = item.toCondition().copy(exclude = it.exclude),
                suggestions = emptyList()
            )
        }
        _state.value = _state.value.copy(error = null)
    }

    /**
     * 切换某一栏的「包含 / 排除」。
     *
     * 已经把标签选好的栏要**同步改条件本身**（`$tag:X$` ⇄ `$-tag:X$`），
     * 否则会出现"chip 显示排除、检索串仍是包含"的错位。
     */
    fun setSlotExclude(slotId: Int, exclude: Boolean) {
        updateSlot(slotId) {
            it.copy(
                exclude = exclude,
                selected = it.selected?.copy(exclude = exclude)
            )
        }
    }

    /**
     * 重新算某一栏的候选。
     *
     * 用在"重新聚焦到已有文字的栏"时：候选原本只在**键入**时算，
     * 光把光标点回那一栏不会触发 —— 下拉就一直是空的。
     */
    fun refreshSlotSuggestions(slotId: Int) {
        val slot = _state.value.slots.firstOrNull { it.id == slotId } ?: return
        if (slot.selected != null || slot.query.isBlank()) return
        val suggestions = queryPicker(slot.query)
        updateSlot(slotId) { it.copy(suggestions = suggestions) }
    }

    /** 清掉某一栏的选择与输入，回到空白栏 */
    fun clearSlot(slotId: Int) {
        updateSlot(slotId) { it.copy(query = "", selected = null, suggestions = emptyList()) }
    }

    /**
     * 加一个空的输入栏。
     *
     * **只要没到上限就一直可用** —— 每个栏自己就能装文字或标签，
     * 所以没有"必须先把上一栏选完"这种约束。用户想加几个就加几个，
     * 空栏不参与检索。
     */
    fun addSlot() {
        val s = _state.value
        if (!s.canAddSlot) return
        _state.value = s.copy(slots = s.slots + InputSlotState(id = nextSlotId++))
    }

    /**
     * 删掉一栏。
     *
     * 最后一栏**不删**（只清空）：界面上始终保留"一个输入栏"这个落点，
     * 全部删光会让人不知道该从哪里开始输入。
     */
    fun removeSlot(slotId: Int) {
        val s = _state.value
        if (s.slots.size <= 1) {
            clearSlot(slotId)
            return
        }
        _state.value = s.copy(slots = s.slots.filterNot { it.id == slotId })
    }

    /** 高级搜索：按各输入栏开搜 */
    fun searchWithConditions() {
        val s = _state.value
        if (!s.hasSearched) return
        runSearch()
    }

    /** 高级搜索重置：清空所有栏，回到一个空栏 */
    fun resetConditions() {
        searchJob?.cancel()
        nextSlotId = 1
        _state.value = _state.value.copy(
            slots = listOf(InputSlotState(id = 0)),
            results = emptyList(),
            appliedSummary = emptyList(),
            error = null,
            loading = false,
            loadingMore = false,
            hasMore = false,
            page = 1
        )
        reloadDictionaries()
    }

    // ─────────────────────────── 结果加载 ───────────────────────────

    /**
     * 按当前输入开搜 —— **普通搜索看关键词，高级搜索看各输入栏**。
     *
     * 高级搜索的每个栏自己决定贡献什么，所以"纯文字 / 纯标签 / 文字+标签 / 混合"
     * 全都自然成立，不需要用户先声明"我这一栏要搜什么类型"：
     *
     * | 一栏的内容 | 对检索串的贡献 |
     * |---|---|
     * | 选了标签 ASMR | `$tag:ASMR$` |
     * | 只敲了 `耳かき` | `耳かき`（裸关键词） |
     * | 两栏分别是上面两个 | `$tag:ASMR$ 耳かき` |
     * | 空栏 | 无 |
     *
     * 只有"**恰好一个条件、且没有任何裸文字**"才走专用端点：那时按 id 查更精确
     * （实测 `$circle:Whisp$` 605 与端点 604 有出入，会带上同名的其他资源）。
     */
    private fun runSearch() {
        val (conditions, keyword, historyText) = searchInputs()
        startSearch(
            conditions = conditions,
            keyword = keyword,
            historyText = historyText,
            // 标题反映的是"这次要搜什么"，所以在提交这一刻取快照
            summary = _state.value.descriptionSegments
        )
    }

    /**
     * 真正发起检索。
     *
     * 与 [runSearch] 分开，是因为**"要搜什么"不一定等于"界面上现在写着什么"**。
     * 最典型的是点历史：那条记录的内容已经由用户确认过了，不该为了搜它而
     * 先把它填进输入框（见 [replay]）。
     *
     * @param record 是否记进搜索历史。点历史重放时不记 ——
     *   那条本来就来自历史，再记一次只会把它重复提到最前
     * @param historyText 存进历史的**裸文字**（不含 `$tag:X$` 这类检索语法）。
     *   条件已经结构化地存在 `conditions` 里了，再往 query 里塞一遍语法，
     *   历史列表就会显示成「标签「ASMR」 关键词「$tag:ASMR$ 耳かき」」。
     */
    private fun startSearch(
        conditions: List<SearchCondition>,
        keyword: String,
        record: Boolean = true,
        historyText: String = keyword,
        summary: List<SearchSummarySegment> = emptyList()
    ) {
        if (conditions.isEmpty() && keyword.isBlank()) return

        if (record) {
            // 记进本地历史：文字与条件分开存，这样点历史才能各归各位地填回界面
            SearchHistoryStore.push(query = historyText, conditions = conditions)
        }

        val endpointFilter = SearchQueryBuilder.singleConditionEndpoint(conditions, keyword)
        val requestKeyword = if (endpointFilter != null) null else keyword

        // 翻页要照抄这一刻的检索参数（理由见 [appliedKeyword]）
        appliedConditions = conditions
        appliedFilter = endpointFilter
        appliedKeyword = requestKeyword.orEmpty()

        _state.value = _state.value.copy(
            // 结果页大标题用的快照：之后用户改输入栏，这行字也不会跟着变
            appliedSummary = summary,
            loading = true,
            error = null,
            results = emptyList(),
            totalCount = 0,
            page = 1,
            hasMore = false
        )
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            runCatching {
                AsmrRepository.getWorks(
                    page = 1,
                    pageSize = PAGE_SIZE,
                    keyword = requestKeyword,
                    filter = endpointFilter
                )
            }
                .onSuccess { applyFirstPage(it.items, it.currentPage, it.hasMore, it.totalCount) }
                .onFailure { applyFailure(it) }
        }
    }

    /**
     * 把当前形态的输入归纳成"条件列表 + 检索串 + 历史里的裸文字"。
     *
     * 两种形态的差别只在这一处：
     *  - **普通搜索**：条件恒为空，检索串就是那个关键词（所以单条件端点用不上）
     *  - **高级搜索**：逐栏取贡献，按栏的先后拼（见 [SearchQueryBuilder.buildFromSlots]）
     *
     * 归纳成三元组而不是直接拼字符串，是为了让
     * "单条件走专用端点"这个优化在两处（首屏与翻页）用同一份判断；
     * 第三个元素专供**历史**：它只要用户真敲过的裸文字（排除词保留 `-词`），
     * 不要 `$tag:X$` 这类给机器看的语法。
     */
    private fun searchInputs(): Triple<List<SearchCondition>, String, String> {
        val s = _state.value
        if (s.mode == SearchMode.SIMPLE) {
            val q = s.query.trim()
            return Triple(emptyList(), q, q)
        }
        val parts = s.slots.map { slot ->
            SearchQueryBuilder.SlotPart(
                condition = slot.selected,
                text = slot.query,
                exclude = slot.exclude
            )
        }
        // 历史文本：只取**没选中条件**的栏（那些才是用户真敲过的词）。
        // 已选中的栏里回显的是标签译名，再存一份就会让历史变成
        // 「标签「ASMR」 关键词「ASMR」」—— 同一件事显示两遍，重放时还会多带一个条件。
        val historyText = SearchQueryBuilder.buildFromSlots(
            s.slots
                .filter { it.selected == null }
                .map {
                    SearchQueryBuilder.SlotPart(
                        condition = null,
                        text = it.query,
                        exclude = it.exclude
                    )
                }
        )
        return Triple(
            s.conditions,
            SearchQueryBuilder.buildFromSlots(parts),
            historyText
        )
    }

    /**
     * 点一条搜索历史。
     *
     * ## 两种形态的行为不同
     *
     * **普通搜索**：直接照那条记录搜一次（返回 true，调用方据此进结果页）。
     * 记录里的条件虽然在这一页看不见，但会一并生效 —— 结果页的副标题会把它们
     * 摊开，不会"偷偷"多加了条件却看不出来。
     *
     * **高级搜索**：**只填进输入栏，不立刻搜**（返回 false）。
     * 填完用户还能继续加栏或改，由他自己决定什么时候按搜索 ——
     * 否则一点历史就跳走，想再叠加一个条件还得先返回来。
     *
     * 填法：记录里的条件各占一栏（显示成已选胶囊），文字单独占一栏（当裸关键词）。
     * 末尾**总是再多留一个空栏** —— 否则重放一条"每个栏都填满了"的记录之后，
     * 界面会变成一整排已选胶囊、没有可以打字的地方。
     *
     * @return 是否已经发起搜索
     */
    fun replay(entry: SearchHistoryEntry): Boolean {
        searchJob?.cancel()

        // ── 老历史的数据迁移 ───────────────────────────────
        //
        // 早期版本把**检索串**存进了 `query`（如 `$tag:ASMR$ $tag:催眠$`）。
        // 直接重放会让它在高级搜索里多占一栏纯文本，字面显示 `$tag:ASMR$` ——
        // 也就是"同一件事显示成两份（一份胶囊 + 一份语法）"。
        //
        // 所以先把语法从 `query` 里剥出来：能还原成条件的还原，剩下的才算关键词。
        val restored = SearchQueryBuilder.parseConditions(entry.query) { kindLabelOf(it) }
        // replayText 还会去掉旧版本存进去的"标签译名重复片段"（见 SearchHistoryEntry）
        val cleanQuery = entry.replayText
        // 条件以结构化字段为准；老历史没有这个字段时才用还原出来的
        val conditions = entry.conditions.ifEmpty { restored }

        // ── 普通搜索：直接去结果页，**不动输入框** ──────────────
        //
        // 记录的内容用户已经在历史列表里确认过了，再把它填进输入框、
        // 让用户看一眼、再点一次搜索 —— 纯属多余的中间步骤。
        //
        // 注意这里**不填 `query`**：填了就等于"把内容放到输入框中"，
        // 正是要避免的那一步。返回搜索页时输入框保持原样，
        // 用户想改词重新搜就直接打。
        if (_state.value.mode == SearchMode.SIMPLE) {
            // 顺带把高级搜索那边的栏位也重置，免得下次切过去看到上一条的残留
            _state.value = _state.value.copy(slots = listOf(InputSlotState(id = 0)))
            nextSlotId = 1
            // ★ 条件必须**拼回检索串**：只把裸文字当关键词发出去的话，
            //   一条"多个标签、没有关键词"的记录会变成"没有任何条件" → 返回全库。
            //   这里与检索页提交走同一个 SearchQueryBuilder.build，口径只有一处。
            val builtQuery = SearchQueryBuilder.build(conditions, cleanQuery)
            // 单条件 + 没有文字时仍然让它走"按 id 查"的专用端点（更精确）：
            // 此时把 keyword 留空，startSearch 里就会选端点。
            val useEndpoint =
                SearchQueryBuilder.singleConditionEndpoint(conditions, cleanQuery) != null
            // 标题按这条历史记录的内容重建（排除项来自 conditions，排除词保留 `-词`）
            val summary = conditions.map { SearchSummarySegment.ofCondition(it) } +
                listOfNotNull(SearchSummarySegment.ofSearchText(cleanQuery))
            startSearch(
                conditions = conditions,
                keyword = if (useEndpoint) "" else builtQuery,
                record = false,
                summary = summary
            )
            return true
        }

        // ── 高级搜索：把文字与条件各归各位地填好，**不立刻搜** ────
        //
        // 填完用户还能继续加栏或改，由他自己决定什么时候按搜索 ——
        // 否则一点历史就跳走，想再叠加一个条件还得先返回来。
        val filled = buildList {
            conditions.forEach { condition ->
                add(
                    InputSlotState(
                        query = condition.displayName,
                        selected = condition,
                        // 重放时把"排除"也带回来，否则历史里的 `-tag:X` 会变成包含
                        exclude = condition.exclude
                    )
                )
            }
            cleanQuery.takeIf { it.isNotEmpty() }?.let { text ->
                add(InputSlotState(query = text))
            }
        }
        // 末尾补一个空栏；整个记录什么都没有时就只有一个空栏
        _state.value = _state.value.copy(
            slots = withFreshIds(filled + InputSlotState()),
            results = emptyList(),
            error = null,
            hasMore = false,
            totalCount = 0,
            page = 1
        )
        return false
    }

    /** 命名空间 → 界面上显示的类型名 */
    private fun kindLabelOf(namespace: String): String = when (namespace) {
        SearchNamespaces.TAG -> SearchNamespaces.TAG_LABEL
        SearchNamespaces.VOICE_ACTOR -> SearchNamespaces.VOICE_ACTOR_LABEL
        SearchNamespaces.CIRCLE -> SearchNamespaces.CIRCLE_LABEL
        else -> namespace
    }

    /** 重排输入栏的 id（从 0 连续编号）并把 `nextSlotId` 推到下一个可用值 */
    private fun withFreshIds(slots: List<InputSlotState>): List<InputSlotState> {
        nextSlotId = slots.size
        return slots.mapIndexed { index, slot -> slot.copy(id = index) }
    }

    /**
     * "重新搜索"入口：直接照当前状态重发。
     *
     * 两种形态共用一套逻辑 —— 反正 [runSearch] 同时读文字与条件。
     */
    fun research() = runSearch()

    /**
     * 取下一页。
     *
     * 三个要点（都是踩过的坑）：
     *
     *  1. **只认提交时的快照**（[appliedKeyword] / [appliedConditions] / [appliedFilter]），
     *     不重新读输入框 —— 否则点历史重放进来的结果永远翻不了页。
     *  2. **按 id 去重**：结果页的瀑布流用 `key = work.id`，两页之间出现重复 id
     *     会直接让列表崩掉（"Key was already used"）；服务端分页边界也可能重叠。
     *  3. **一页没带来任何新内容就停下**（`hasMore = false`）：否则提前一屏的触发
     *     会一直喊，请求打空转。
     */
    fun loadMore() {
        val s = _state.value
        if (!s.hasMore || s.loading || s.loadingMore) return
        if (appliedKeyword.isBlank() && appliedConditions.isEmpty()) return

        _state.value = s.copy(loadingMore = true)
        viewModelScope.launch {
            runCatching {
                AsmrRepository.getWorks(
                    page = s.page + 1,
                    pageSize = PAGE_SIZE,
                    keyword = appliedKeyword.ifBlank { null },
                    filter = appliedFilter
                )
            }.onSuccess { result ->
                val current = _state.value
                val seen = current.results.mapTo(HashSet(current.results.size)) { it.id }
                val fresh = result.items.filter { it.id > 0 && seen.add(it.id) }
                val merged = current.results + fresh
                current.copy(
                    results = merged,
                    page = result.currentPage,
                    totalCount = maxOf(result.totalCount, merged.size),
                    // 一页全是重复的（或本页为空）就别再往下要了
                    hasMore = fresh.isNotEmpty() && result.hasMore,
                    loadingMore = false
                ).let { _state.value = it }
            }.onFailure {
                _state.value = _state.value.copy(loadingMore = false)
            }
        }
    }

    /**
     * 清空当前形态的输入与结果。
     *
     * 普通搜索清输入框，高级搜索清所有条件栏 —— 两种形态各清各的。
     */
    fun clearQuery() {
        searchJob?.cancel()
        when (_state.value.mode) {
            SearchMode.SIMPLE -> _state.value = _state.value.copy(
                query = "",
                results = emptyList(),
                appliedSummary = emptyList(),
                error = null,
                loading = false,
                hasMore = false
            )

            SearchMode.ADVANCED -> {
                nextSlotId = 1
                _state.value = _state.value.copy(
                    slots = listOf(InputSlotState(id = 0)),
                    results = emptyList(),
                    appliedSummary = emptyList(),
                    error = null,
                    loading = false,
                    hasMore = false
                )
            }
        }
        // 顺手刷新一次本地字典：用户可能刚去设置里更新过
        reloadDictionaries()
    }

    // ─────────────────────────── 内部工具 ───────────────────────────

    /**
     * 首屏结果。
     *
     * [totalCount] 来自服务端的 `pagination.totalCount`，是"一共有多少条"；
     * 界面显示的是它，而不是 `results.size`（那只是已加载的条数）。
     */
    private fun applyFirstPage(items: List<Work>, page: Int, hasMore: Boolean, totalCount: Int) {
        _state.value = _state.value.copy(
            results = items,
            page = page,
            hasMore = hasMore,
            totalCount = totalCount,
            loading = false
        )
    }

    private fun applyFailure(e: Throwable) {
        _state.value = _state.value.copy(
            loading = false,
            error = e.message ?: "搜索失败"
        )
    }

    private inline fun updateSlot(slotId: Int, transform: (InputSlotState) -> InputSlotState) {
        val s = _state.value
        if (s.slots.none { it.id == slotId }) return
        _state.value = s.copy(slots = s.slots.map { if (it.id == slotId) transform(it) else it })
    }

    /**
     * 按键入词从**三本字典一起**筛候选。
     *
     * 具体怎么筛、怎么排序、**每条显示哪个名字**都在 [SuggestionMerger] 里 ——
     * 那部分抽成纯函数是有原因的：曾经这里用 `item.name`（原始名）渲染候选，
     * 于是搜「亲热/甜蜜」却看到「纯爱/甜蜜」，而结果页又显示译名，两处对不上。
     * 现在这类"显示名一致性"能直接被单测钉住（见 SuggestionMergerTest）。
     */
    private fun queryPicker(input: String): List<SearchSuggestion> = SuggestionMerger.merge(
        query = input,
        tags = tagCache,
        voiceActors = vaCache,
        circles = circleCache,
        limit = suggestionLimit
    )
}
