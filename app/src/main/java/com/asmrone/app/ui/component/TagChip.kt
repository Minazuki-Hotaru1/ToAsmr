package com.asmrone.app.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.asmrone.app.R
import com.asmrone.app.ui.theme.ErrorColor

/**
 * 胶囊筛选 chip（提炼自 comicApp 的筛选 chip 规范）。
 *
 * - 未选中：`surfaceVariant.copy(0.5f)` 底 + onSurfaceVariant 文字
 * - 选中：`primary.copy(0.16f)` 底 + primary 文字
 * - 语义反转（"排除"类，[negativeTone] = true）：`ErrorColor.copy(0.26f)` 底 + ErrorColor 文字 + 描边
 *
 * 搜索页的排序、分级、时长、字幕等筛选全部用它。
 */
@Composable
fun AsmrFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    negativeTone: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp)
) {
    val cs = MaterialTheme.colorScheme
    val negativeActive = negativeTone && selected
    val container = when {
        negativeActive -> ErrorColor.copy(alpha = 0.26f)
        selected -> cs.primary.copy(alpha = 0.16f)
        else -> cs.surfaceVariant.copy(alpha = 0.5f)
    }
    val label = when {
        negativeActive -> ErrorColor
        selected -> cs.primary
        else -> cs.onSurfaceVariant
    }
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        // 排除态的描边**必须交给 FilterChip 自己的 border 参数**：
        // 用 Modifier.border 画的是「布局外框」（含 48dp 最小触控区 + 内边距），
        // 与 chip 实际的圆角底色不同形、不同大小 —— 会露出一圈红色方框（截图 5 的那个 bug），
        // 而 border 参数画在 chip 自己的 shape 上，边缘严丝合缝。
        border = if (negativeActive) {
            BorderStroke(1.dp, ErrorColor.copy(alpha = 0.5f))
        } else {
            null
        },
        shape = shape,
        label = {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingIcon = leadingIcon,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = container,
            labelColor = label,
            selectedContainerColor = container,
            selectedLabelColor = label,
            iconColor = label,
            selectedLeadingIconColor = label
        )
    )
}

/** 标签色调 */
enum class TagTone { Primary, Neutral, Subtitle, Download }

/**
 * 小方块标签（提炼自 comicApp 卡片上的「分类角标」）。
 * 用在作品卡片、详情页标签墙、曲目列表等处。
 */
@Composable
fun SmallTagChip(
    text: String,
    modifier: Modifier = Modifier,
    tone: TagTone = TagTone.Primary
) {
    val cs = MaterialTheme.colorScheme
    val container: Color
    val content: Color
    when (tone) {
        TagTone.Primary -> { container = cs.primary.copy(alpha = 0.12f); content = cs.primary }
        TagTone.Neutral -> { container = cs.surfaceVariant; content = cs.onSurfaceVariant }
        TagTone.Subtitle -> { container = cs.secondary.copy(alpha = 0.16f); content = cs.secondary }
        TagTone.Download -> {
            container = Color(0xFF5EA57A).copy(alpha = 0.16f)
            content = Color(0xFF3E7A55)
        }
    }
    Surface(shape = RoundedCornerShape(4.dp), color = container, modifier = modifier) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 叠加在**图片之上**的角标（卡片左上角的「全年龄 / 字幕」）。
 *
 * 两个角标**共用这一套样式**：浅色底色 + 同色系文字（与页面底色上的 [SmallTagChip] 同源），
 * 底色带一点透明度，叠在封面上能透出一点画面，看起来更轻。
 *
 * 👉 **想调角标底色的深浅 / 透明度：改本文件末尾的 [IMAGE_BADGE_BG_ALPHA]**。
 */
@Composable
fun ImageBadge(
    text: String,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(cs.surfaceVariant.copy(alpha = IMAGE_BADGE_BG_ALPHA), shape)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/**
 * 图片角标底色的透明度 —— **想调整就改这一个值**：
 * `0f` = 完全透明（只剩文字），`1f` = 完全不透明（实底）。
 * 当前值在"轻"与"叠在花哨封面上仍看得清"之间取平衡。
 */
private const val IMAGE_BADGE_BG_ALPHA = 0.85f

/**
 * 详情页标签墙用的中等尺寸标签（可点，带命名空间前缀）。
 *
 * 尺寸与间距**对齐 comicApp 的标签墙**：圆角 4dp、底色 surfaceVariant、
 * 文字 labelSmall、内边距 10dp × 4dp。
 *
 * comicApp 的标签用 `pointerInput + detectTapGestures` 实现点击，
 * 因此不会触发 Material3 的「最小触控尺寸 48dp」；我们这里用可点击 `Surface` 更好接
 * 无障碍语义，于是改为显式关掉那个最小尺寸（见下方 `CompositionLocalProvider`），
 * 效果与 comicApp 一致：标签片按内容高度排布，行距是多少就显示多少。
 */
/**
 * 标签墙上的一个标签片。
 *
 * [onLongClick] 非空时挂了长按（详情页用它复制标签名）；为空时只有点按。
 * 用 [combinedClickable] 而不是"Surface(onClick) + 另加一个长按手势"：
 * 同一节点上叠两层手势时，更内层会先消费事件，外层就永远收不到 ——
 * 收藏夹卡片曾经因为这么写而点不开。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TagWallChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null
) {
    // ⚠️ Material3 里「可点击的 Surface」会在画背景的外层强制**最小触控尺寸 48dp**：
    //    标签片视觉高度只有 ~24dp，但布局高度被撑到 48dp，
    //    于是标签墙的行间距看起来远大于设置值（设 6dp 也像二十几 dp）。
    //    这里把该强制关掉（传 Dp.Unspecified），标签片才会按内容高度排布。
    //    代价：单个标签的触摸高度变小（标签点击尚未接入具体功能；后续接入时
    //    若觉得难点中，可用外层不可见容器单独放大触摸区，视觉紧凑度不受影响）。
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Surface(
            modifier = modifier.combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                text = text,
                // 内边距与 comicApp 一致：左右 10dp、上下 4dp
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 已选标签（高级搜索条件行里显示当前选中的标签，带清除按钮） */
@Composable
fun SelectedTagChip(
    text: String,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(2.dp))
            IconButton(
                onClick = onClear,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close_fluent),
                    contentDescription = "清除",
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
