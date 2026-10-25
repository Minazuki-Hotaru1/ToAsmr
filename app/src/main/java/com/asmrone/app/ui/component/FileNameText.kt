package com.asmrone.app.ui.component

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints

/**
 * 文件名，**允许两行**，但扩展名永远留在最后一行的末尾。
 *
 * 只用一个 `Text` 配 `TextOverflow.Ellipsis` 做不到这件事：省略号是从整串尾部截的，
 * 名字一长正好先吃掉 `.mp3`，而那恰恰是用户最想确认的信息。
 *
 * 做法是"先量、再拼"：把 `名字 + 扩展名` 交给 [TextMeasurer] 排一次，看它有没有溢出；
 * 溢出就把名字二分缩短，在末尾补一个 `…` 再接扩展名 —— 于是第二行的结尾一定是
 * `.MP3` 而不是 `…`。
 *
 * 量的结果按 (名字, 宽度, 样式) 记住，所以滚动、重组都不会重复测量；
 * [TextMeasurer] 自己还有一层缓存，同一批同名的行更省。
 */
@Composable
internal fun FileNameText(
    name: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    suffixColor: Color = Color.Unspecified,
    maxLines: Int = 2,
    showSuffix: Boolean = true
) {
    val suffix = if (showSuffix) fileNameSuffixOf(name) else null
    val base = if (suffix == null) name else name.dropLast(suffix.length)
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val annotated = remember(base, suffix, style, suffixColor, widthPx, maxLines) {
            fitFileName(
                measurer = measurer,
                base = base,
                suffix = suffix,
                style = style,
                suffixColor = suffixColor,
                widthPx = widthPx,
                maxLines = maxLines
            )
        }
        Text(
            text = annotated,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 名字里最后一个 `.` 之后的部分，当作扩展名。
 *
 * 判据刻意收紧（不超过 5 个字符、不含空白、不能是首尾的点）：像
 * `2.5 倍速用` 这种标题不该被当成"扩展名是 .5 倍速"。
 */
internal fun fileNameSuffixOf(name: String): String? {
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.lastIndex) return null
    val ext = name.substring(dot + 1)
    if (ext.length > 5 || ext.any { it.isWhitespace() }) return null
    return "." + ext.uppercase()
}

private fun fitFileName(
    measurer: TextMeasurer,
    base: String,
    suffix: String?,
    style: TextStyle,
    suffixColor: Color,
    widthPx: Int,
    maxLines: Int
): AnnotatedString {
    fun compose(candidate: String, ellipsis: Boolean): AnnotatedString = buildAnnotatedString {
        append(candidate)
        if (ellipsis) append('\u2026')
        if (suffix != null) {
            if (suffixColor == Color.Unspecified) {
                append(suffix)
            } else {
                withStyle(SpanStyle(color = suffixColor)) { append(suffix) }
            }
        }
    }

    fun fits(text: AnnotatedString): Boolean = !measurer.measure(
        text = text,
        style = style,
        overflow = TextOverflow.Ellipsis,
        maxLines = maxLines,
        constraints = Constraints(maxWidth = widthPx)
    ).hasVisualOverflow

    val full = compose(base, ellipsis = false)
    if (fits(full)) return full

    // 二分"还剩几个字能放下"：够长就溢出、够短就放得下，单调，所以二分成立
    var low = 0
    var high = base.length
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (fits(compose(base.take(mid).trimEnd(), ellipsis = true))) {
            low = mid
        } else {
            high = mid - 1
        }
    }
    return compose(base.take(low).trimEnd(), ellipsis = true)
}
