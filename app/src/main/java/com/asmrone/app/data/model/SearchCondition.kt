package com.asmrone.app.data.model

/**
 * 高级搜索的一个条件（一个输入栏里选中的一项）。
 *
 * ## 为什么同时有 [name] 与 [displayName]
 *
 * 服务端的条件语法只认**名称**（`$tag:ASMR$`、`$circle:Whisp$`），所以 [name]
 * 必须是字典里的原始名 —— 不能用译名，否则检索串对不上。
 *
 * 但 [TagNameResolver] 会把标签显示成中文译名（`环绕音` → `双声道立体声/人头麦`），
 * 用户看到的、选中的是那个译名。所以 [displayName] 单独带着界面上该显示的那个，
 * 结果页的"条件：标签「…」"才不会突然冒出一个用户没见过的名字。
 *
 * [id] 则是稳定标识：同名资源在字典里可能出现多次，而去重、Compose 的 key
 * 都需要一个可靠身份。
 *
 * [namespace] 是服务端认的五个之一，见 [SearchNamespaces]。
 */
data class SearchCondition(
    val namespace: String,
    val id: String,
    /** 发给服务端的**原始名**（语法认这个） */
    val name: String,
    /** 界面上显示的文本（标签会用中文译名） */
    val displayName: String,
    /** 用于界面上标注"这是标签还是声优" */
    val kindLabel: String
) {
    /** 去重用的身份：同一个资源的同一个条件只应加一次 */
    val dedupeKey: String get() = "$namespace|$id"

    /**
     * 存进搜索历史用。
     *
     * ⚠️ [displayName] 与 [kindLabel] **也要存**：前者是标签的中文译名，
     * 后者要靠 [namespace] 反查，但译名是字典里的数据、不是能算出来的 ——
     * 不存的话历史里的标签会显示成用户没见过的原始名。
     */
    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("ns", namespace)
        put("id", id)
        put("name", name)
        put("display", displayName)
        put("kind", kindLabel)
    }

    companion object {
        /** 从历史里读回；缺关键字段就返回 null（跳过这一条条件） */
        fun fromJson(o: org.json.JSONObject): SearchCondition? {
            val namespace = o.optString("ns", "")
            val name = o.optString("name", "")
            if (namespace.isBlank() || name.isBlank()) return null
            return SearchCondition(
                namespace = namespace,
                id = o.optString("id", ""),
                name = name,
                displayName = o.optString("display", "").takeIf { it.isNotBlank() } ?: name,
                kindLabel = o.optString("kind", "").takeIf { it.isNotBlank() }
                    ?: labelFor(namespace)
            )
        }

        /** [kindLabel] 丢了也能从命名空间反查回来 */
        private fun labelFor(namespace: String): String = when (namespace) {
            SearchNamespaces.TAG, SearchNamespaces.TAG_FUZZY -> SearchNamespaces.TAG_LABEL
            SearchNamespaces.VOICE_ACTOR -> SearchNamespaces.VOICE_ACTOR_LABEL
            SearchNamespaces.CIRCLE -> SearchNamespaces.CIRCLE_LABEL
            else -> namespace
        }
    }
}

/**
 * 服务端**实际支持**的检索命名空间（从网站 bundle 的 `namespace:"..."` 字面量里挖出来的）：
 *
 * | 命名空间 | 含义 | 值 |
 * |---|---|---|
 * | `tag`  | 标签（精确） | 标签名 |
 * | `tagw` | 标签（模糊，结果更多） | 标签名 |
 * | `va`   | 声优 | 声优名 |
 * | `circle` | 社团 | 社团名 |
 * | `age`  | 年龄分级 | 见下 |
 *
 * ## 实测（2026-10，对着 api.asmr-200.com）
 *
 * ```
 * $tag:ASMR$                      → 18315   ← 与 /api/tags/497/works 完全一致
 * $va:<声优名>$                    → 1283    ← 与 /api/vas/<id>/works 一致
 * $circle:Whisp$                  → 605     ← 端点 604（按名称查略有出入）
 * $tag:ASMR$ $tag:Vtuber$         → 730     ← 交集
 * $tag:ASMR$ $circle:Whisp$       → 332
 * $tag:ASMR$ $circle:Whisp$ $tag:Vtuber$ → 1
 * $tag:ASMR$ $-tag:Vtuber$        → 17585   ← 比 18315 少，排除生效
 * 耳かき $tag:ASMR$                → 3049    ← 裸文本与条件可混用
 * ```
 *
 * ## ⚠️ 三个坑（都实测过）
 *
 * 1. **条件之间必须有空格**：`$tag:ASMR$$tag:Vtuber$` → **0**，
 *    而 `$tag:ASMR$ $tag:Vtuber$` → 730。
 * 2. **值用名称、不用 id**：`$va:<uuid>$` → 0。
 * 3. **名称含空格时不要加引号**：`$circle:ajaib suara$` → 345（精确），
 *    但 `$circle:"ajaib suara"$` → **0**。
 *    网站前端会给含空格的值加引号，那套做法在这里是错的 —— 别照抄。
 */
object SearchNamespaces {
    const val TAG = "tag"
    const val TAG_FUZZY = "tagw"
    const val VOICE_ACTOR = "va"
    const val CIRCLE = "circle"
    const val AGE = "age"

    /** 界面上用来标注条件类型的短名 */
    const val TAG_LABEL = "标签"
    const val VOICE_ACTOR_LABEL = "声优"
    const val CIRCLE_LABEL = "社团"
}

/**
 * 把一组条件与一段裸关键词拼成服务端认识的检索串。
 *
 * 语法：条件写成 `$namespace:name$`，多个之间**空格分隔**；裸关键词原样放在里面。
 *
 * 空输入返回空串（调用方据此回落到不带筛选的列表查询）。
 */
object SearchQueryBuilder {

    /** 单个条件 → `$namespace:name$`；名称非法时返回 null（跳过这个条件） */
    fun token(condition: SearchCondition): String? {
        val name = condition.name.trim()
        if (name.isEmpty()) return null
        if (condition.namespace.isBlank()) return null
        return "\$${condition.namespace}:$name\$"
    }

    /**
     * 拼成完整检索串。
     *
     * 条件按**加入顺序**输出，因为条件的顺序在语义上无意义但会影响
     * 用户看到的字符串 —— 保持稳定便于排查。
     */
    fun build(conditions: List<SearchCondition>, keyword: String = ""): String {
        val parts = ArrayList<String>(conditions.size + 1)
        conditions.forEach { token(it)?.let(parts::add) }
        keyword.trim().takeIf { it.isNotEmpty() }?.let(parts::add)
        return parts.joinToString(" ")
    }

    /**
     * 高级搜索里一个输入栏对检索串的贡献。
     *
     * 一个栏有三种情况，正好对应"用户可以自由选择输入什么"：
     *  - **选了**字典里的条目 → `$tag:ASMR$` 这样的条件
     *  - 只敲了文字、没选 → 该文字**原样**作为关键词
     *    （服务端本来就支持裸文字与条件混用：实测 `耳かき $tag:ASMR$` → 3049）
     *  - 什么都没填 → 不贡献任何东西
     */
    data class SlotPart(val condition: SearchCondition?, val text: String) {
        fun toToken(): String? = when {
            condition != null -> SearchQueryBuilder.token(condition)
            text.isNotBlank() -> text.trim()
            else -> null
        }
    }

    /**
     * 把各输入栏拼成检索串，**保持用户排列的顺序**。
     *
     * 与 [build] 的区别：[build] 会把所有条件排在关键词前面，
     * 而这里按栏的先后原样输出 —— 高级搜索的栏是可自由增删排序的，
     * 顺序变了检索串就变，排查日志时更容易对上用户看到的那一屏。
     */
    fun buildFromSlots(parts: List<SlotPart>): String =
        parts.mapNotNull { it.toToken() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    /**
     * 这个条件是不是"按 id 检索专用端点"能表达的。
     *
     * 只有一个条件、且没有裸关键词时，走 `/api/tags/{id}/works` 这类**精确**端点是更好的
     * —— 实测 `$tag:ASMR$`(18315) 与端点(18315) 一致，但 `$circle:Whisp$`(605)
     * 与端点(604) 就有出入，说明按名称查会带上同名的其他资源。
     *
     * 所以：**单条件优先走专用端点，多条件才用检索串**。
     */
    fun singleConditionEndpoint(conditions: List<SearchCondition>, keyword: String): WorkFilter? {
        if (keyword.isNotBlank()) return null
        val only = conditions.singleOrNull() ?: return null
        return when (only.namespace) {
            SearchNamespaces.TAG ->
                only.id.toIntOrNull()?.let { WorkFilter(tagId = it) }

            SearchNamespaces.VOICE_ACTOR -> WorkFilter(vaId = only.id)

            SearchNamespaces.CIRCLE ->
                only.id.toIntOrNull()?.let { WorkFilter(circleId = it) }

            else -> null
        }
    }

    /**
     * 检索串里的一个条件片段：`$tag:ASMR$`（`$-tag:X$` 是排除写法）。
     *
     * 值要求**至少一个字符**（`[^$]+` 而不是 `[^$]*`）：`$tag:$` 这种空值
     * 不是合法条件，匹配到它只会还原出一个没有名字的条件。
     */
    private val CONDITION_TOKEN = Regex("""\$(-?)([a-z]+):([^$]+)\$""")

    /**
     * 从一段文本里**剥掉检索串语法**，只留裸关键词。
     *
     * ## 为什么需要它
     *
     * 检索串（`$tag:ASMR$ $tag:催眠$`）是**发请求用的**，不是给人看的。
     * 一旦它混进"裸关键词"那条通道，就会以字面形态出现在界面上 ——
     * 而那是这个模块最容易犯的错：把"给机器看的"和"给人看的"混用
     * （候选显示名那次也是同一个病根）。
     *
     * 具体到两个真实场景：
     *
     *  1. **早期版本写下的历史**把检索串存进了 `query` 字段。重放时它被当成
     *     裸关键词，于是高级搜索里除了一栏"已选标签"胶囊，**还会多出一栏
     *     写着 `$tag:ASMR$` 的纯文本** —— 同一件事显示成两份。
     *  2. 用户手动把 `$tag:xx$` 打进某一栏（他没理由知道这个语法）。
     *
     * 剥掉之后剩下的才是真正的关键词；如果剩下的是空的，说明这段文本**只**
     * 是条件语法，不该再占一个关键词栏。
     */
    fun stripConditionSyntax(text: String): String =
        text.replace(CONDITION_TOKEN, " ").trim().replace(Regex("\\s+"), " ")

    /**
     * 这段文本是不是"只有条件语法、没有可读内容"。
     *
     * 用来判断要不要在重放时给它留一个关键词栏 —— 全是语法的文本留一栏
     * 出来只会显示一段用户看不懂的东西。
     */
    fun isOnlyConditionSyntax(text: String): Boolean =
        text.isNotBlank() && stripConditionSyntax(text).isEmpty()

    /**
     * 从文本里抽出条件语法并转成条件对象。
     *
     * 给"老历史"做**数据迁移**用：旧记录把检索串存在 `query` 里，
     * 条件是能从里面还原出来的（只是没有显示名与中文译名，
     * 那两样要靠字典，历史里没存）。
     */
    fun parseConditions(text: String, kindLabel: (String) -> String): List<SearchCondition> =
        CONDITION_TOKEN.findAll(text).mapNotNull { m ->
            val namespace = m.groupValues[2]
            val name = m.groupValues[3].trim()
            if (namespace.isBlank() || name.isEmpty()) return@mapNotNull null
            SearchCondition(
                namespace = namespace,
                id = "",
                name = name,
                // 没有字典，只能拿原始名当显示名 —— 至少不会显示成语法
                displayName = name,
                kindLabel = kindLabel(namespace)
            )
        }.toList()
}
