package com.asmrone.app.ui.screen.search

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.asmrone.app.data.model.SearchSummarySegment
import com.asmrone.app.ui.theme.ErrorColor

/**
 * 搜索摘要 → 带样式的文本：**排除项画成错误色**（`-声优「xx」`），其余用默认色。
 *
 * 结果页顶栏标题与搜索历史列表共用这一份：
 * 同一个「这次按什么搜」，在两个地方必须是同一套写法，否则用户会以为是两件事。
 */
internal fun List<SearchSummarySegment>.toSummaryAnnotatedString(): AnnotatedString =
    buildAnnotatedString {
        forEachIndexed { index, segment ->
            // 片段之间用一个空格分隔（没有「且」这类连接词）
            if (index > 0) append(' ')
            if (segment.exclude) {
                withStyle(SpanStyle(color = ErrorColor)) { append(segment.text) }
            } else {
                append(segment.text)
            }
        }
    }
