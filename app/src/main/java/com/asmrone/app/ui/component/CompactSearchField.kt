package com.asmrone.app.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.asmrone.app.ui.theme.Motion

/**
 * 紧凑输入框 —— 全应用统一使用（提炼自 comicApp 的 CompactTextField.kt）
 *
 * 40dp 高 + 14sp 正文，圆角 12，聚焦时边框/底色平滑过渡；比 Material 默认的 56dp 更小巧。
 * 搜索框、筛选条件、设置里的输入、弹窗命名全部复用它，保证"全应用只有一个输入框样式"。
 *
 * 前置图标为 Fluent 矢量图资源（`@DrawableRes`）。
 */
@Composable
fun CompactSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    @DrawableRes leadingIconRes: Int? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    imeAction: ImeAction = ImeAction.Done,
    keyboardType: KeyboardType = KeyboardType.Text,
    /**
     * 文本呈现方式。密码框传 `PasswordVisualTransformation()` 即可 ——
     * 这样全应用仍然只有这一种输入框样式，不必为登录页另造一个组件。
     */
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onImeAction: () -> Unit = {},
    onFocusChange: (Boolean) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }

    val borderColor by animateColorAsState(
        targetValue = if (focused) cs.primary else cs.outline.copy(alpha = 0.65f),
        animationSpec = tween(Motion.FIELD_TRANSITION_MS), label = "fieldBorder"
    )
    val containerColor by animateColorAsState(
        targetValue = if (focused) cs.primary.copy(alpha = 0.04f) else cs.surface,
        animationSpec = tween(Motion.FIELD_TRANSITION_MS), label = "fieldContainer"
    )

    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .background(containerColor)
            .border(1.dp, borderColor, shape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIconRes != null) {
            Icon(
                painter = painterResource(leadingIconRes), contentDescription = null,
                modifier = Modifier.size(18.dp), tint = cs.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().onFocusChanged {
                    focused = it.isFocused
                    onFocusChange(it.isFocused)
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onBackground),
                cursorBrush = SolidColor(cs.primary),
                singleLine = true,
                visualTransformation = visualTransformation,
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = imeAction
                ),
                keyboardActions = KeyboardActions(
                    onSearch = { onImeAction() },
                    onDone = { onImeAction() },
                    onGo = { onImeAction() },
                    onSend = { onImeAction() }
                )
            )
        }
        trailingIcon?.invoke()
    }
}
