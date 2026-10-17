package com.asmrone.app.data.model

/**
 * 高级搜索的一条候选。
 *
 * 三种资源统一成一个形态，这样候选列表、输入栏、去重逻辑都只需要一套。
 *
 * ## ⚠️ [name] 与 [displayName] 千万别混用
 *
 *  - [name] 是发给服务端的**原始名**，条件语法 `$tag:name$` 认它。
 *    **界面上任何地方都不该显示它。**
 *  - [displayName] 是**界面上该显示的**那个（标签用中文译名）。
 *
 * 这两者混用会造成一个很难察觉的矛盾：搜「亲热/甜蜜」却在候选里看到
 * 「纯爱/甜蜜」（那是 id=4 的原始名），而点进去之后结果页又显示译名 ——
 * 用户看到的是两个不同的名字指的是同一件事。
 */
data class SearchSuggestion(
    /** 服务端认的命名空间（tag / va / circle） */
    val namespace: String,
    val id: String,
    /** 发给服务端的**原始名**（语法认这个，不能用译名） */
    val name: String,
    /** 界面上显示的文本（标签会用中文译名） */
    val displayName: String,
    /** 候选行左边那个类型标记的文字：标签 / 声优 / 社团 */
    val kindLabel: String,
    /** 作品数，用于排序与显示 */
    val count: Int,
    /**
     * 还能用哪些名字匹配到这一条（原名、译名、和谐名……）。
     *
     * 搜索框按键入词筛候选时用它，所以搜译名也能找到；但**不用于显示**。
     */
    val matchNames: List<String> = emptyList()
) {
    /** 候选行的显示文本：`名称 (作品数)`，与网站一致 */
    val label: String get() = "$displayName ($count)"

    fun toCondition(): SearchCondition = SearchCondition(
        namespace = namespace,
        id = id,
        name = name,
        displayName = displayName,
        kindLabel = kindLabel
    )

    companion object {
        fun ofTag(tag: Tag) = SearchSuggestion(
            namespace = SearchNamespaces.TAG,
            id = tag.id.toString(),
            // 原始名发给服务端（条件语法认它）
            name = tag.queryName,
            // 显示名直接取索引里解析好的那个 —— **不再在这里解析一次**
            displayName = tag.name,
            kindLabel = SearchNamespaces.TAG_LABEL,
            count = tag.count,
            matchNames = tag.matchNames
        )

        fun ofVoiceActor(va: VoiceActor) = SearchSuggestion(
            namespace = SearchNamespaces.VOICE_ACTOR,
            id = va.id,
            name = va.name,
            // 声优/社团的 i18n 实测全空，没有译名可挑
            displayName = va.name,
            kindLabel = SearchNamespaces.VOICE_ACTOR_LABEL,
            count = va.count,
            matchNames = listOf(va.name)
        )

        fun ofCircle(circle: Circle) = SearchSuggestion(
            namespace = SearchNamespaces.CIRCLE,
            id = circle.id,
            name = circle.name,
            displayName = circle.name,
            kindLabel = SearchNamespaces.CIRCLE_LABEL,
            count = circle.count,
            matchNames = listOf(circle.name)
        )
    }
}

/**
 * 从三本本地字典里筛候选。
 *
 * 抽成纯函数是为了能直接断言"候选上显示的是什么名字"——
 * 那个 bug（候选显示原始名、结果页显示译名）就是在这条路径上出的，
 * 而它藏在 Compose 与 ViewModel 里面，没法用单测够到。
 */
object SuggestionMerger {

    /**
     * @param limit 候选上限。声优/社团各有五千多条，全铺出来既慢也没意义。
     */
    fun merge(
        query: String,
        tags: List<Tag> = emptyList(),
        voiceActors: List<VoiceActor> = emptyList(),
        circles: List<Circle> = emptyList(),
        limit: Int = 40
    ): List<SearchSuggestion> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()

        // 标签拿索引里预存的别名匹配（原名 / 译名 / 和谐名），不在运行时拼
        val matchedTags = tags.asSequence()
            .filter { it.matches(q) }
            .sortedByDescending { it.count }
            .map { SearchSuggestion.ofTag(it) }

        val matchedVas = voiceActors.asSequence()
            .filter { it.name.lowercase().contains(q) }
            .sortedByDescending { it.count }
            .map { SearchSuggestion.ofVoiceActor(it) }

        val matchedCircles = circles.asSequence()
            .filter { it.name.lowercase().contains(q) }
            .sortedByDescending { it.count }
            .map { SearchSuggestion.ofCircle(it) }

        // 按作品数倒序。三个接口返回的顺序都**不是**按热度
        // （标签非热度序、声优按 UUID 随机序、社团按名称字母序），
        // 不排的话候选里会先冒出 `야디`(1) 这种冷门条目。
        // 作品数相同时按"标签 → 声优 → 社团"排：标签最常用，先给。
        return (matchedTags + matchedVas + matchedCircles)
            .sortedWith(compareByDescending<SearchSuggestion> { it.count }.thenBy { kindOrder(it.namespace) })
            .take(limit)
            .toList()
    }

    private fun kindOrder(namespace: String): Int = when (namespace) {
        SearchNamespaces.TAG -> 0
        SearchNamespaces.VOICE_ACTOR -> 1
        else -> 2
    }
}
