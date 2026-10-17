package com.asmrone.app.data.repo

import com.asmrone.app.data.model.SearchCondition
import com.asmrone.app.data.model.SearchNamespaces
import com.asmrone.app.data.model.Tag
import com.asmrone.app.data.model.WorkFilter
import com.asmrone.app.data.prefs.SearchHistoryStore

/**
 * 三本搜索字典（标签索引 / 声优 / 社团）的**统一更新**。
 *
 * ## 为什么放在 data 层
 *
 * 原先这套逻辑长在设置页的 UI 文件里，只有设置页能用。现在**首次进入 App**
 * 也要拉一次（见 `ui/screen/startup/StartupViewModel`），两处必须是**同一套**
 * 行为 —— 否则"设置里更新"与"首次进入自动更新"会在进度文案、失败口径、
 * 提示语上各说各话。
 *
 * ## 为什么一次更新全部
 *
 * 三本字典都是搜索用的具名资源，用户的心理模型是**一件事**：
 * "把搜索用的资料更新一下"。而且它们**同时**被搜索页读取，只要有一本旧了，
 * 高级搜索里就会出现"标签能搜到、社团搜不到"这种费解的情况。
 *
 * 所以只留一个入口，内部依次拉三本，**任一本失败都不影响其他两本**。
 */
data class DictionaryUpdateOutcome(
    val succeeded: Int,
    val counts: Map<String, Int>,
    val failures: List<String>
) {
    /** 三本都成功？ */
    val isComplete: Boolean get() = failures.isEmpty() && succeeded > 0

    /** 有没有一本成功 */
    val isPartial: Boolean get() = succeeded > 0 && failures.isNotEmpty()

    /** 给用户看的结果文案 */
    fun message(total: Int): String = when {
        failures.isEmpty() && succeeded > 0 -> "更新成功，共 $total 条"

        succeeded > 0 ->
            "更新成功，共 $total 条；但${failures.joinToString("、")}没更新到"

        else -> "更新失败：${failures.joinToString("；")}"
    }
}

/** 一本字典的更新动作 + 它在界面上显示的名字 */
private data class DictionaryTarget(
    val title: String,
    val refresh: suspend () -> Int
)

/**
 * 依次更新三本字典。
 *
 * 每本单独 `runCatching`：某本失败（网络中断、服务端 5xx）不该把已经成功的
 * 那两本也标成失败，更不该让用户以为"全都没更新"。
 *
 * @param onProgress 进度回调，用于把"正在更新第 N 本"显示出去
 */
suspend fun updateAllDictionaries(
    onProgress: (String) -> Unit
): DictionaryUpdateOutcome {
    val targets = listOf(
        DictionaryTarget("标签") { AsmrRepository.refreshSearchTags() },
        DictionaryTarget("声优") { AsmrRepository.refreshVoiceActors() },
        DictionaryTarget("社团") { AsmrRepository.refreshCircles() }
    )

    var succeeded = 0
    val counts = LinkedHashMap<String, Int>()
    val failures = ArrayList<String>()

    targets.forEachIndexed { index, target ->
        onProgress("正在更新${target.title}（${index + 1}/${targets.size}）…")
        runCatching { target.refresh() }.fold(
            onSuccess = { n ->
                // n == 0 表示服务端没返回内容；保留本地那份，不算成功但要说清
                if (n > 0) {
                    succeeded++
                    counts[target.title] = n
                } else {
                    failures.add("${target.title}（服务端没有返回内容）")
                }
            },
            onFailure = { e ->
                failures.add("${target.title}（${e.message ?: "网络或服务端问题"}）")
            }
        )
    }

    return DictionaryUpdateOutcome(succeeded, counts, failures)
}

/** 三本字典的本地条数合计 —— 提示语里用它说"共 N 条" */
fun localDictionaryTotal(): Int =
    AsmrRepository.cachedSearchTags().size +
        AsmrRepository.cachedVoiceActors().size +
        AsmrRepository.cachedCircles().size

/**
 * 一次"按某一项字典资源筛选"的检索意图。
 *
 * 详情页点标签 / 声优 / 社团，以及搜索页选中一个候选，产出的都是它。
 * 有了这个统一形态，"取数据"与"记历史"两件事就都能各写一处。
 */
data class DictionaryQuery(
    val namespace: String,
    val id: String,
    /** 界面上显示的名字（标签用中文译名） */
    val displayName: String,
    /** 发给服务端的原始名（条件语法认它） */
    val queryName: String = displayName,
    val kindLabel: String
) {
    val condition: SearchCondition
        get() = SearchCondition(
            namespace = namespace,
            id = id,
            name = queryName,
            displayName = displayName,
            kindLabel = kindLabel
        )

    /** 界面上那句来源说明，如 `标签「ASMR」` */
    val label: String get() = "$kindLabel「$displayName」"

    companion object {
        fun ofTag(tag: Tag) = DictionaryQuery(
            namespace = SearchNamespaces.TAG,
            id = tag.id.toString(),
            displayName = tag.name,
            queryName = tag.queryName,
            kindLabel = SearchNamespaces.TAG_LABEL
        )

        /**
         * 用**作品页自带的**标签信息构造。
         *
         * 作品页的标签只有中文译名（[com.asmrone.app.data.model.TagItem]），
         * 所以 [queryName] 直接用译名 —— 单条件检索走的是
         * `/api/tags/{id}/works`（按 id，与名字无关），所以照样能搜到。
         * 但这也意味着它**不能**当多条件检索串里的条件：那时名字必须对得上。
         */
        fun ofTagItem(id: Int, displayName: String) = DictionaryQuery(
            namespace = SearchNamespaces.TAG,
            id = id.toString(),
            displayName = displayName,
            queryName = displayName,
            kindLabel = SearchNamespaces.TAG_LABEL
        )

        fun ofCircle(id: Int, name: String) = DictionaryQuery(
            namespace = SearchNamespaces.CIRCLE,
            id = id.toString(),
            displayName = name,
            queryName = name,
            kindLabel = SearchNamespaces.CIRCLE_LABEL
        )

        fun ofVoiceActor(id: String, name: String) = DictionaryQuery(
            namespace = SearchNamespaces.VOICE_ACTOR,
            id = id,
            displayName = name,
            queryName = name,
            kindLabel = SearchNamespaces.VOICE_ACTOR_LABEL
        )
    }
}

/** 按一项字典资源检索时用的筛选条件 */
fun DictionaryQuery.toWorkFilter(): WorkFilter = when (namespace) {
    SearchNamespaces.TAG -> WorkFilter(tagId = id.toIntOrNull())
    SearchNamespaces.VOICE_ACTOR -> WorkFilter(vaId = id)
    SearchNamespaces.CIRCLE -> WorkFilter(circleId = id.toIntOrNull())
    else -> WorkFilter.NONE
}

/**
 * 把"按某一项字典资源检索"记进搜索历史。
 *
 * ⚠️ 抽出来是因为它有**两个**调用方：搜索页（选中候选后搜索）与
 * 详情页直达的结果页。两处必须记成一样的形状，否则从详情页进去的那条
 * 历史点开之后表现会不一样。
 *
 * 记 [SearchHistoryStore.push] 时 keywords 传空：这类检索**只有条件**，
 * 没有关键词 —— 传了关键词会让历史显示成"条件 + 关键词"，
 * 而用户并没有输入过任何词。
 */
fun recordDictionarySearch(query: DictionaryQuery) {
    SearchHistoryStore.push(query = "", conditions = listOf(query.condition))
}

/**
 * 三本字典是否都已就绪（都非空）。
 *
 * ⚠️ 这里刻意**不只看那个布尔标记**：标记只解决"要不要尝试拉一次"，
 * 而"数据到底在不在"要靠数据本身回答 —— 换了 API 主机、或用户在设置里
 * 清过某一本，标记仍可能是 true，但数据已经没了。
 */
fun dictionariesReady(): Boolean = AsmrRepository.dictionariesReady()
