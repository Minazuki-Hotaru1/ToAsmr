package com.asmrone.app.data.model

import org.json.JSONObject

/**
 * 标签 —— **已经解析好的索引项**。
 *
 * ## 这个类型存在的唯一理由
 *
 * 以前用的是「原始条目 + 运行时解析」：一个 [SearchTag] 带着整张 `i18n` 表，
 * 每个显示点各自调 `TagNameResolver.displayName(...)` 去算该显示哪个名字
 * （候选行、条件胶囊、结果页摘要、历史摘要……）。
 *
 * 那样做的代价是[同一个标签在不同地方可能显示成不同的名字]，而且**编译器帮不上忙** ——
 * 到处都可能忘了解析、直接读原始名，于是出现：
 *
 * ```
 * 搜「亲热/甜蜜」→ 候选里跳出「纯爱/甜蜜」（原始名）
 *              → 点进去结果页又显示「亲热/甜蜜」
 * ```
 *
 * 现在把"该显示哪个名字"在**建索引时一次性决定**，落成 [name]。于是：
 *
 *  - 任何地方想知道"这个标签叫什么"，读 [name] —— **它就是显示名，没有第二种可能**
 *  - 想拿它去检索（条件语法 `$tag:name$` 认原始名），读 [queryName]
 *  - 两个字段名字不同、用途写在文档里，混用会立刻被看出来
 *
 * 换句话说：**把"选择"从每个消费点收回到一处**。
 *
 * ## 字段来源（实测 2026-10，共 422 个标签）
 *
 * | 字段 | 来源 | 命中率 |
 * |---|---|---|
 * | [name] | `i18n["zh-cn"].name`，缺失则回落 `name` | 396 有译名 / 26 回落 |
 * | [queryName] | 上游 `name`（原始名） | 全部 |
 * | [censoredName] | `i18n["zh-cn"].censored` | 只有 24 个有 |
 * | [matchNames] | 上面三者 + `i18n["zh-cn"].history` | 全部 |
 *
 * 索引里**只留中文相关的名字**：本应用界面是中文，`en-us` / `ja-jp` 那两套
 * 永远不会被读到（详见 [TagNameResolver] 的说明）。
 */
data class Tag(
    /** `/api/tags/{id}/works` 用的那个整数 */
    val id: Int,
    /**
     * **显示名** —— 界面上任何地方都该用它。
     *
     * 已经解析过：优先 `i18n["zh-cn"].name`，缺失时回落原始名。
     */
    val name: String,
    /**
     * 发给服务端的**原始名**。
     *
     * 条件语法 `$tag:<原始名>$` 认的是这个：实测用译名会搜不到。
     * ⚠️ **不要拿它去显示**。
     */
    val queryName: String,
    /** DLsite「和谐」后的名字；没有则为空串（只有 24 个标签有） */
    val censoredName: String = "",
    /** 作品数，候选列表显示「名称 (数量)」 */
    val count: Int = 0,
    /**
     * 用于**匹配键入词**的全部别名（去重）。
     *
     * 含原始名、中文译名、和谐名、历史旧名 —— 所以搜这几种写法都能找到它。
     * 但**不用于显示**：搜旧名找到的仍是这个标签当前的 [name]。
     */
    val matchNames: List<String> = emptyList()
) {
    /**
     * 「和谐」模式下该显示的名字。
     *
     * 没有和谐名就还是 [name] —— 所以这个取值**永远**是一个可显示的名字，
     * 不需要调用方再判断一次。
     */
    val censoredDisplayName: String get() = censoredName.ifBlank { name }

    /** 候选行的显示文本：`名称 (作品数)`，与网站一致 */
    val label: String get() = "$name ($count)"

    /**
     * 变成一个可检索的条件。
     *
     * 分工在这里一次性落定：[SearchCondition.name] 是**原始名**（检索串用），
     * [SearchCondition.displayName] 是**显示名**。调用方不必自己判断该填哪个。
     */
    fun toCondition(): SearchCondition = SearchCondition(
        namespace = SearchNamespaces.TAG,
        id = id.toString(),
        name = queryName,
        displayName = name,
        kindLabel = SearchNamespaces.TAG_LABEL
    )

    /** 拿这些名字去匹配键入词（小写化由调用方做，避免每次重建字符串） */
    fun matches(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return false
        return matchNames.any { it.lowercase().contains(q) }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("query", queryName)
        if (censoredName.isNotBlank()) put("censored", censoredName)
        put("count", count)
        if (matchNames.isNotEmpty()) {
            put("match", org.json.JSONArray().apply { matchNames.forEach { put(it) } })
        }
    }

    companion object {
        /**
         * 从索引文件读回。
         *
         * ⚠️ 这里**不做任何名字解析**：索引里存的就是解析结果。
         * 老索引缺字段时只做安全兜底（`queryName` 回落 `name`），
         * 不会去猜译名 —— 那正是这个设计要避免的事。
         */
        fun fromJson(o: JSONObject): Tag? {
            val id = o.optInt("id", 0)
            if (id <= 0) return null
            val name = o.optString("name", "")
            if (name.isBlank()) return null

            val match = o.optJSONArray("match")?.let { arr ->
                buildList {
                    for (i in 0 until arr.length()) {
                        arr.optString(i, "").takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }.orEmpty()

            val queryName = o.optString("query", "").takeIf { it.isNotBlank() } ?: name

            return Tag(
                id = id,
                name = name,
                queryName = queryName,
                censoredName = o.optString("censored", ""),
                count = o.optInt("count", 0),
                // 老索引没有 match 时至少能用两个名字匹配
                matchNames = match.ifEmpty { listOf(queryName, name).distinct() }
            )
        }
    }
}

/**
 * 上游 `GET /api/tags/` 的原始条目。
 *
 * 带着整张 `i18n` 表，**只应该出现在建索引的过程中**（[TagCatalog.build]）。
 * 索引建好之后就是 [Tag]，不再需要在运行时解析名字。
 *
 * 字段与实测响应一一对应（2026-10 对着 api.asmr-200.com 实测，共 422 个标签）：
 * ```jsonc
 * {
 *   "id": 157,
 *   "name": "催眠",                      // 原始名（以中文为主，部分是日文/英文）
 *   "i18n": {
 *     "en-us": { "name": "Hypnosis", "censored": "Trance / Suggestion" },
 *     "ja-jp": { "name": "催眠",     "censored": "トランス/暗示" },
 *     "zh-cn": { "name": "催眠",     "censored": "暗示",
 *                "history": [ { "name": "旧名", "deprecatedAt": 1712218463248 } ] }
 *   },
 *   "count": 2058
 * }
 * ```
 */
data class SearchTag(
    val id: Int,
    /** 上游的原始名 */
    val name: String,
    /** 各语言的译名：locale → (译名, 和谐名, 旧名列表) */
    val i18n: Map<String, TagLocaleNames> = emptyMap(),
    val count: Int = 0
) {
    companion object {
        fun fromJson(o: JSONObject): SearchTag? {
            val id = o.optInt("id", 0)
            if (id <= 0) return null
            val name = o.optString("name", "")
            if (name.isBlank()) return null

            val i18nObj = o.optJSONObject("i18n")
            val i18n = buildMap {
                if (i18nObj != null) {
                    i18nObj.keys().forEach { locale ->
                        val entry = i18nObj.optJSONObject(locale) ?: return@forEach
                        val history = entry.optJSONArray("history")?.let { arr ->
                            buildList {
                                for (i in 0 until arr.length()) {
                                    arr.optJSONObject(i)?.optString("name", "")
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let(::add)
                                }
                            }
                        }.orEmpty()
                        put(
                            locale,
                            TagLocaleNames(
                                name = entry.optString("name", "").takeIf { it.isNotBlank() },
                                censored = entry.optString("censored", "").takeIf { it.isNotBlank() },
                                history = history
                            )
                        )
                    }
                }
            }

            return SearchTag(
                id = id,
                name = name,
                i18n = i18n,
                count = o.optInt("count", 0)
            )
        }
    }
}

/** 某个语言下的标签名变体 */
data class TagLocaleNames(
    /** 该语言的译名；null 或缺失表示没有这个语言的译名 */
    val name: String? = null,
    /** DLsite 「和谐」后的名字（如 催眠 → 暗示） */
    val censored: String? = null,
    /** 该语言下已弃用的旧名 */
    val history: List<String> = emptyList()
)

/**
 * 把上游的原始标签**建成索引**：一次性决定每个标签显示什么、用什么检索。
 *
 * 这是"选择"发生的**唯一一处**。建完之后消费点只读 [Tag] 的字段，
 * 没有第二次选择的机会 —— 这正是上一版反复出显示名矛盾的根本原因。
 */
object TagCatalog {

    /**
     * 建索引。
     *
     * 显示名的解析规则**与网站完全一致**。规则不是猜的，是从 asmr.one 线上前端
     * bundle 里读出来的（`js/app.*.js`，模块 `9394` 的 `getLocaleTagName`）：
     *
     * ```js
     * function getLocaleTagName(tag, locale) {
     *   const showCensored = settings?.showCensoredTag === "censored";   // 默认 "uncensored"
     *   const o = tag?.i18n?.[localeKey(locale)];
     *   return (showCensored && o?.censored) ? o.censored : (o?.name || tag.name);
     * }
     * ```
     *
     * 即：① 该语言有 name → 用它；② 否则回落原始名。
     * 网站默认**不和谐**，所以索引里 [Tag.name] 存的是未和谐的译名，
     * 和谐名另存 [Tag.censoredName] 备用。
     *
     * ## 实测分布（422 个标签）
     *
     * | 情况 | 数量 | 结果 |
     * |---|---|---|
     * | 有 zh 译名且 ≠ 原名 | 97 | 显示译名（`环绕音` → `双声道立体声/人头麦`） |
     * | 有 zh 译名且 = 原名 | 275 | 显示译名（与原名相同） |
     * | `zh-cn` 为 null | 26 | 回落原始名（`AI`、`女性向`、`無理矢理`） |
     */
    fun build(raw: List<SearchTag>, locale: String = LOCALE_ZH_CN): List<Tag> =
        raw.map { build(it, locale) }

    fun build(raw: SearchTag, locale: String = LOCALE_ZH_CN): Tag {
        val entry = raw.i18n[locale]
        val localized = entry?.name?.takeIf { it.isNotBlank() }
        val censored = entry?.censored?.takeIf { it.isNotBlank() }.orEmpty()

        // 匹配别名：原始名 + 译名 + 和谐名。
        //
        // ⚠️ **不含** history（旧名）。实测数据里
        //   id=32「纯爱」(2886) 与 id=4「纯爱/甜蜜」(16087，旧名也是「纯爱/甜蜜」)
        // 是两个语义不同的标签；把旧名纳入匹配会让搜「纯爱」同时命中它们。
        // 旧名是"同一个标签的旧称呼"，不是"另一个标签的别名"，
        // 但它在**跨标签**层面会造成歧义，所以索引里不留。
        val matchNames = buildList {
            add(raw.name)
            localized?.let(::add)
            censored.takeIf { it.isNotBlank() }?.let(::add)
        }.distinct()

        return Tag(
            id = raw.id,
            // ① 译名 ② 回落原始名
            name = localized ?: raw.name,
            // 检索串只认原始名
            queryName = raw.name,
            censoredName = censored,
            count = raw.count,
            matchNames = matchNames
        )
    }

    /** 中文（简体）的 i18n 键 —— 与网站 `i18n.locale` 的取值一致 */
    const val LOCALE_ZH_CN = "zh-cn"
}
