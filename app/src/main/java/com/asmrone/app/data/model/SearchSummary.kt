package com.asmrone.app.data.model

/**
 * 搜索摘要里的一个片段（结果页标题 / 搜索历史共用）。
 *
 * ## 为什么不是一整条 String
 *
 * 排除项要**画成红色**（`-声优「xx」`），而颜色是 UI 的事、数据层不该知道。
 * 所以这里只给「文本 + 是否排除」两件事，由界面决定怎么上色 ——
 * 结果页顶栏与历史列表因此能用同一份数据、各自排版。
 *
 * ## 展示规则
 *
 * | 情况 | 文本 |
 * |---|---|
 * | 包含的标签 / 声优 / 社团 | `标签「ASMR」` |
 * | 排除的标签 / 声优 / 社团 | `-标签「ASMR」`（前缀横杠，界面上是红色） |
 * | 包含的自由关键词 | `关键词「耳かき」` |
 * | 排除的自由关键词 | `-耳かき` |
 *
 * 片段之间用**一个空格**连接：不再出现「且」「且排除」这类连接词
 * （那套写法在条件一多时会变成一句读不完的话）。
 */
data class SearchSummarySegment(
    val text: String,
    /** true = 排除项，界面应把它画成错误色 */
    val exclude: Boolean = false
) {
    companion object {

        /** 一个已选条件 → 片段；排除时前面加 `-`、并把 [exclude] 一起带出去 */
        fun ofCondition(condition: SearchCondition) = SearchSummarySegment(
            text = (if (condition.exclude) "-" else "") +
                "${condition.kindLabel}「${condition.displayName}」",
            exclude = condition.exclude
        )

        /**
         * 一段自由关键词 → 片段。
         *
         * 排除的关键词同样加 `-`（服务端语法就是 `-词`，见 [SearchQueryBuilder.negateText]）；
         * 包含的关键词包成「关键词「…」」，避免它与条件混在一起时分不清。
         */
        fun ofKeyword(text: String, exclude: Boolean = false): SearchSummarySegment? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            return if (exclude) {
                SearchSummarySegment(text = "-$trimmed", exclude = true)
            } else {
                SearchSummarySegment(text = "关键词「$trimmed」")
            }
        }

        /**
         * 已经带语法痕迹的检索文字 → 片段（**重放历史**时用）。
         *
         * 历史里存的排除词就是 `-耳かき` 这种形态 —— 直接沿用这段文字，
         * 不要再包一层「关键词「-耳かき」」。
         */
        fun ofSearchText(text: String): SearchSummarySegment? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            return if (trimmed.startsWith("-")) {
                SearchSummarySegment(text = trimmed, exclude = true)
            } else {
                SearchSummarySegment(text = "关键词「$trimmed」")
            }
        }

        /** 片段列表 → 纯文本（空格连接）。给日志、纯文本场景用。 */
        fun join(segments: List<SearchSummarySegment>): String =
            segments.joinToString(" ") { it.text }
    }
}
