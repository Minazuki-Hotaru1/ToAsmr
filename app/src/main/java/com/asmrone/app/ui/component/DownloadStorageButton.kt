package com.asmrone.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.asmrone.app.R
import com.asmrone.app.data.download.DownloadStorage
import com.asmrone.app.ui.theme.Liquid

/**
 * 下载保存位置按钮：显示**当前**模式（"系统"/"本地"），点一下切换。
 *
 * 做成实心胶囊而不是普通文字按钮，是因为它是个会改变下载行为的开关：
 * 混在"部数 · 大小"这种只读统计里、或者干脆只有图标，用户很容易看漏；
 * "本地方案"下染主色底，顺便提示"现在不是默认位置"。
 *
 * 只影响**之后**的下载 —— 已经下好的内容要另存（下载页长按卡片）。
 *
 * [warn] 为 true 时用错误色：按钮上写着"本地"但系统里的权限已经没了（用户关掉了
 * 所有文件访问权限、或换了台机器恢复备份）。不标出来的话，用户会以为一切就绪。
 */
@Composable
fun DownloadStorageButton(
    storage: DownloadStorage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    warn: Boolean = false
) {
    val local = storage == DownloadStorage.LOCAL
    val tint = when {
        warn -> MaterialTheme.colorScheme.error
        local -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = Liquid.ACTION_HEIGHT)
            .clip(LiquidCapsule)
            .background(
                when {
                    warn -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                    local -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Icon(
            painter = painterResource(
                if (local) R.drawable.ic_folder_check_fluent else R.drawable.ic_folder_fluent
            ),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = storage.label,
            style = MaterialTheme.typography.labelMedium,
            color = tint
        )
    }
}
