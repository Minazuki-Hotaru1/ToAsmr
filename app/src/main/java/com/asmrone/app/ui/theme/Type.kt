package com.asmrone.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.asmrone.app.R

/**
 * 字体系统 —— 与 comicApp 完全一致的排版比例（此处保持一致，是"风格相同"的关键之一：
 * 同一套字号/字重/行高，换到音频内容上依然是同一种克制、干净的气质）。
 */
val AppTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)

// ─────────────── 作品标题样式（对齐 KikoFlu 详情页的标题） ───────────────

/**
 * 作品标题样式 —— 照搬参考项目 **KikoFlu** 详情页标题的排版。
 *
 * 它的定义在 `testAsmrOne/lib/src/widgets/work_detail/work_title_header.dart`：
 * ```dart
 * final titleStyle = style ??
 *     Theme.of(context).textTheme.titleMedium?.copyWith(
 *           fontWeight: FontWeight.bold,   // 粗体
 *           fontSize: 16,                  // 16sp
 *         );
 * ```
 * 即「Flutter Material 3 的 titleMedium 基准 + 覆盖为 16 / Bold」。
 * Flutter M3 的 titleMedium 基准是：fontSize 16、height 1.5（→ 行高 24）、letterSpacing 0.15，
 * 因此这里等价地写成下面这份 Compose 样式。
 *
 * 想调就直接改数值（例如只想变轻，把 [FontWeight.Bold] 换成 Medium）。
 */
val WorkTitleStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Bold,
    fontSize = 16.sp,
    lineHeight = 24.sp,
    letterSpacing = 0.15.sp
)

// ───────────────────── 备用：圆体（当前未使用，已移出安装包） ─────────────────────
// 上一个版本详情页标题用的是圆体 M PLUS Rounded 1c（3.3MB，OFL 1.1）。
// 现在标题用默认字族，它完全没被引用 —— 而资源收缩也没能把它删掉
// （`R.font.*` 字段被保留，资源收缩据此判定"还在用"），
// 于是它白白占了正式包 45% 的体积（7.35MB 里的 3.3MB）。
//
// 所以字体与它的授权文本已移出打包目录，仓库里仍然保留：
//
//     design-assets/fonts/m_plus_rounded_1c_regular.ttf
//     design-assets/fonts/OFL-MPLUSRounded1c.txt
//
// 想切回圆体（两步）：
//   ① 把 ttf 复制回 app/src/main/res/font/，把下面这段的注释去掉；
//   ② 把 [WorkTitleStyle]（或任意样式）的 fontFamily 换成 RoundedFontFamily。
//
// ⚠️ 注意：一旦触发编译，APK 就会重新变大 3.3MB —— 只有真要长期用圆体时才值得。
//
// /** 圆体字族 —— M PLUS Rounded 1c（SIL OFL 1.1），备用 */
// val RoundedFontFamily = FontFamily(
//     Font(R.font.m_plus_rounded_1c_regular, FontWeight.Normal)
// )
