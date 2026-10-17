package com.asmrone.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.asmrone.app.R
import com.asmrone.app.data.model.RateCountDetail
import com.asmrone.app.ui.theme.RatingStar
import kotlin.math.roundToInt

/**
 * 评分星级（提炼自 comicApp 详情页的 5 星文本方案，改为图标实现，更贴合音频作品卡片）。
 */
@Composable
fun RatingStars(
    rate: Double,
    modifier: Modifier = Modifier,
    iconSize: Dp = 14.dp
) {
    val filled = rate.roundToIntCompat()
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        repeat(5) { index ->
            Icon(
                painter = painterResource(
                    if (index < filled) R.drawable.ic_star_fluent else R.drawable.ic_star_outline_fluent
                ),
                contentDescription = null,
                tint = if (index < filled) RatingStar
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .width(iconSize)
                    .height(iconSize)
            )
        }
    }
}

/**
 * 评分分布条（详情页用）—— 每档一条水平进度条 + 数量，
 * 用青绿主色的深浅区分档位，比纯文字列表更直观。
 */
@Composable
fun RateDistribution(
    detail: List<RateCountDetail>,
    modifier: Modifier = Modifier
) {
    if (detail.isEmpty()) return
    val total = detail.sumOf { it.count }.coerceAtLeast(1)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        detail.sortedByDescending { it.reviewPoint }.forEach { item ->
            val ratio = item.count.toFloat() / total
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${item.reviewPoint}★",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    if (ratio > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(ratio)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(RatingStar.copy(alpha = 0.85f))
                        )
                    }
                }
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

private fun Double.roundToIntCompat(): Int = this.roundToInt().coerceIn(0, 5)
