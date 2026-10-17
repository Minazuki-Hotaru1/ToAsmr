package com.asmrone.app.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.asmrone.app.R
import com.asmrone.app.data.model.Work
import com.asmrone.app.ui.theme.Motion
import com.asmrone.app.ui.theme.Sizes

/**
 * 作品卡片（网格用）。
 *
 * ## 展示规则（按需求重新定过）
 *
 * - **图片区固定 4:3**（横向、裁切填满）—— 与网页端卡片一致，
 *   不再使用「原比例完整显示 + 模糊底图」那套；
 * - 图片左上角两枚标识：**全年龄**（成人作品不标）与 **字幕**，两枚**并排**；
 *   样式统一为浅色底 + 同色系文字（见 [ImageBadge]）；
 * - 信息区**只保留四项**：标题、社团、声优、标签
 *   （不再显示评分、价格、销量、时长）；
 * - 图片底部保留收听进度线；
 * - 按压时轻微缩放（`0.96`，spring）+ 主色涟漪。
 *
 * @param host API 主机，用于在作品没有封面字段时回退到 `/api/cover/{id}`
 * @param progress 0f..1f 的收听进度；null 表示没听过
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkCard(
    work: Work,
    host: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    onLongClick: (() -> Unit)? = null
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.PRESS_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = Motion.SPRING_DAMPING,
            stiffness = Motion.SPRING_STIFFNESS
        ),
        label = "cardScale"
    )
    val shape = RoundedCornerShape(12.dp)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                onClick = onClick
            ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            // ── 图片区：4:3 ──────────────────────────────────
            Box {
                CoverImage(
                    url = work.coverUrl(host),
                    contentDescription = work.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(Sizes.CARD_IMAGE_ASPECT),
                    shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                    // 4:3 裁切填满，不需要模糊底图
                    contentScale = ContentScale.Crop,
                    showBackdrop = false
                )

                // 左上角标识：全年龄 + 字幕 —— 两个角标**并排**放，共用同一套浅色底样式
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 只标「全年龄」；成人作品不做年龄标记
                    if (!work.isAdult) {
                        ImageBadge(text = "全年龄")
                    }
                    if (work.hasSubtitle) {
                        ImageBadge(text = "字幕")
                    }
                }

                // 收听进度线
                if (progress != null && progress > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .height(3.dp)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }

            // ── 信息区：标题 / 社团 / 声优 / 标签 ──────────────
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = work.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                val circle = work.circleName?.takeIf { it.isNotBlank() }
                if (circle != null) {
                    Spacer(Modifier.height(6.dp))
                    Row { SmallTagChip(text = circle, tone = TagTone.Primary) }
                }

                val va = work.vaText.takeIf { it.isNotBlank() }
                if (va != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_person_voice_fluent),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = va,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                val tags = work.tags.take(3)
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        tags.forEach { tag ->
                            SmallTagChip(text = tag.displayName, tone = TagTone.Neutral)
                        }
                        if (work.tags.size > tags.size) {
                            SmallTagChip(
                                text = "+${work.tags.size - tags.size}",
                                tone = TagTone.Neutral
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 横向列表行卡片（每行一部作品）。
 *
 * 与 [WorkCard] 同一套信息规则：**标题 / 社团 / 声优 / 标签**，
 * 左侧缩略图同样是 **4:3**（[Sizes.ROW_THUMB_WIDTH] × [Sizes.ROW_THUMB_HEIGHT]）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkRowCard(
    work: Work,
    host: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val shape = RoundedCornerShape(12.dp)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                onClick = onClick
            ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 4:3 缩略图
            Box {
                CoverImage(
                    url = work.coverUrl(host),
                    contentDescription = work.title,
                    modifier = Modifier
                        .width(Sizes.ROW_THUMB_WIDTH)
                        .height(Sizes.ROW_THUMB_HEIGHT),
                    shape = RoundedCornerShape(8.dp),
                    contentScale = ContentScale.Crop,
                    showBackdrop = false
                )
                if (progress != null && progress > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .width(Sizes.ROW_THUMB_WIDTH)
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .height(3.dp)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = work.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                val circle = work.circleName?.takeIf { it.isNotBlank() }
                    ?: work.vaText.takeIf { it.isNotBlank() }
                if (circle != null) {
                    Spacer(Modifier.height(4.dp))
                    Row { SmallTagChip(text = circle, tone = TagTone.Primary) }
                }

                val va = work.vaText.takeIf { it.isNotBlank() }
                if (va != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = va,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val tags = work.tags.take(2)
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        tags.forEach { tag ->
                            SmallTagChip(text = tag.displayName, tone = TagTone.Neutral)
                        }
                    }
                }
            }

            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
    }
}
