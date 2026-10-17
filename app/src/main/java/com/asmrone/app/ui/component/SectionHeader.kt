package com.asmrone.app.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.asmrone.app.R

/**
 * 区块标题（+ 可选副标题 + 右侧「更多」入口）。
 *
 * 说明：comicApp 里的「标题 + 右侧操作」是**顶栏**形态（首页「漫画书库 + 刷新」），
 * 列表内的分组标题只有弱化的 `FlatSectionLabel`，并没有「更多 ›」入口。
 * 由于 asmr.one 首页是「若干横滑榜单」的结构（每个榜单需要自己的标题与更多入口），
 * 这里按同一套视觉语言**新增**了这个组件：标题用 `titleMedium/SemiBold`、
 * 副标题用 `labelSmall/onSurfaceVariant`、右侧「更多」用主色小字 + 箭头。
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    moreText: String = "全部",
    onMore: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (onMore != null) {
            MoreAction(text = moreText, onClick = onMore)
        }
    }
}

@Composable
private fun MoreAction(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right_fluent),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.height(18.dp)
        )
    }
}
