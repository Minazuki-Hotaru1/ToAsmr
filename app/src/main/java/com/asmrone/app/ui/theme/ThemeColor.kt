package com.asmrone.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 一套主题色在两种模式下的取值。
 *
 * 只覆盖 Material 里"有色彩"的那几个槽位（primary 家族 + secondary），
 * 表面色、正文色、分割线这些中性色**不跟着换** —— 它们已经压过饱和度，
 * 换主题色时留着反而是稳定的背景，一换色整个 App 的明暗关系也不会跳。
 */
data class ThemePalette(
    val lightPrimary: Color,
    val lightOnPrimary: Color,
    val lightPrimaryContainer: Color,
    val lightOnPrimaryContainer: Color,
    val lightSecondary: Color,
    val darkPrimary: Color,
    val darkOnPrimary: Color,
    val darkPrimaryContainer: Color,
    val darkOnPrimaryContainer: Color,
    val darkSecondary: Color
)

/**
 * 预设主题色。
 *
 * 全部是低饱和取色，和原来的青绿一样"不抢内容"：
 * 主色只出现在标题、按钮、选中态、进度条这些地方，一旦鲜艳就会变成噪音。
 *
 * 顺序即界面上的顺序，[ordinal] 会被持久化 —— **只能在末尾追加**，
 * 不能插入或调序，否则老用户的设置会指向另一个颜色。
 */
enum class ThemeColor(val label: String, val palette: ThemePalette) {

    /** 青绿 —— 原始配色，默认值 */
    MINT(
        label = "青绿",
        palette = ThemePalette(
            lightPrimary = Color(0xFF17817A),
            lightOnPrimary = Color(0xFFFFFFFF),
            lightPrimaryContainer = Color(0xFFCFEEE9),
            lightOnPrimaryContainer = Color(0xFF0F423E),
            lightSecondary = Color(0xFF6E9791),
            darkPrimary = Color(0xFF7FD4C8),
            darkOnPrimary = Color(0xFF073630),
            darkPrimaryContainer = Color(0xFF0F5B54),
            darkOnPrimaryContainer = Color(0xFFD7F2ED),
            darkSecondary = Color(0xFF9CBDB8)
        )
    ),

    /** 海蓝 —— 比青绿冷一档，夜里看更沉 */
    OCEAN(
        label = "海蓝",
        palette = ThemePalette(
            lightPrimary = Color(0xFF2A6F97),
            lightOnPrimary = Color(0xFFFFFFFF),
            lightPrimaryContainer = Color(0xFFD6E8F3),
            lightOnPrimaryContainer = Color(0xFF123B52),
            lightSecondary = Color(0xFF6F8FA3),
            darkPrimary = Color(0xFF8FC7E8),
            darkOnPrimary = Color(0xFF06263A),
            darkPrimaryContainer = Color(0xFF1B4C6B),
            darkOnPrimaryContainer = Color(0xFFD5EAF7),
            darkSecondary = Color(0xFF9DB9CA)
        )
    ),

    /** 靛紫 —— 偏冷偏静 */
    INDIGO(
        label = "靛紫",
        palette = ThemePalette(
            lightPrimary = Color(0xFF5A63B8),
            lightOnPrimary = Color(0xFFFFFFFF),
            lightPrimaryContainer = Color(0xFFE0E1F6),
            lightOnPrimaryContainer = Color(0xFF262A57),
            lightSecondary = Color(0xFF8188B0),
            darkPrimary = Color(0xFFAFB4EE),
            darkOnPrimary = Color(0xFF1B1E3D),
            darkPrimaryContainer = Color(0xFF3A3F7D),
            darkOnPrimaryContainer = Color(0xFFE3E4FA),
            darkSecondary = Color(0xFFADB2D6)
        )
    ),

    /** 樱粉 —— 唯一的暖色相，配封面时比其他几个更跳 */
    ROSE(
        label = "樱粉",
        palette = ThemePalette(
            lightPrimary = Color(0xFFB0547A),
            lightOnPrimary = Color(0xFFFFFFFF),
            lightPrimaryContainer = Color(0xFFF6DEE8),
            lightOnPrimaryContainer = Color(0xFF4C1F31),
            lightSecondary = Color(0xFFA8808F),
            darkPrimary = Color(0xFFEDA6C2),
            darkOnPrimary = Color(0xFF3B1223),
            darkPrimaryContainer = Color(0xFF7C3A56),
            darkOnPrimaryContainer = Color(0xFFF8E2EA),
            darkSecondary = Color(0xFFCFAAB9)
        )
    ),

    /** 暖沙 —— 和评分星、收藏夹的暖色系同一个家族 */
    AMBER(
        label = "暖沙",
        palette = ThemePalette(
            lightPrimary = Color(0xFFA2752F),
            lightOnPrimary = Color(0xFFFFFFFF),
            lightPrimaryContainer = Color(0xFFF3E6D0),
            lightOnPrimaryContainer = Color(0xFF402C0E),
            lightSecondary = Color(0xFF9C8A6E),
            darkPrimary = Color(0xFFE8C48A),
            darkOnPrimary = Color(0xFF3A2708),
            darkPrimaryContainer = Color(0xFF6D4E1C),
            darkOnPrimaryContainer = Color(0xFFF6E7CE),
            darkSecondary = Color(0xFFC9B797)
        )
    ),

    /** 石墨 —— 近乎无彩，想让界面完全退到内容后面时用 */
    SLATE(
        label = "石墨",
        palette = ThemePalette(
            lightPrimary = Color(0xFF4E5A66),
            lightOnPrimary = Color(0xFFFFFFFF),
            lightPrimaryContainer = Color(0xFFE2E7EC),
            lightOnPrimaryContainer = Color(0xFF232B33),
            lightSecondary = Color(0xFF7C878F),
            darkPrimary = Color(0xFFB6C2CD),
            darkOnPrimary = Color(0xFF1B2229),
            darkPrimaryContainer = Color(0xFF3A4551),
            darkOnPrimaryContainer = Color(0xFFE4E9EE),
            darkSecondary = Color(0xFFAEB8C1)
        )
    ),

    // ─── 第一排剩下的这一个：低饱和但色相独立，补满七个 ───

    /** 藕 —— 灰粉紫之间，比樱粉淡、比石墨有色 */
    MAUVE(
        label = "藕",
        palette = ThemePalette(
            lightPrimary = Color(0xFF7E6080),
            lightOnPrimary = Color(0xFFFFFFFF),
            lightPrimaryContainer = Color(0xFFEEE2EF),
            lightOnPrimaryContainer = Color(0xFF35233A),
            lightSecondary = Color(0xFF9A869B),
            darkPrimary = Color(0xFFD2B6D3),
            darkOnPrimary = Color(0xFF2C1B31),
            darkPrimaryContainer = Color(0xFF5B4160),
            darkOnPrimaryContainer = Color(0xFFEFE2F0),
            darkSecondary = Color(0xFFBDA5BE)
        )
    ),

    // ─── 以下七个是「活泼」那一排：饱和度更高、色相更明确 ───
    // 亮色主题里压到能承白字（L 足够低），暗色主题里抬到能在深底上发光。
    // 容器色仍是浅底 + 深字，因为标题、卡片角标这些地方要能读。

    /** 橘 —— 黄昏的暖 */
    TANGERINE(
        label = "橘",
        palette = ThemePalette(
            lightPrimary = Color(0xFFD96320),
            lightOnPrimary = Color(0xFFFFFFFF),
            lightPrimaryContainer = Color(0xFFFBE3D3),
            lightOnPrimaryContainer = Color(0xFF4A1E06),
            lightSecondary = Color(0xFFA97B5E),
            darkPrimary = Color(0xFFFFB27A),
            darkOnPrimary = Color(0xFF4A2103),
            darkPrimaryContainer = Color(0xFF8A4415),
            darkOnPrimaryContainer = Color(0xFFFBE3D3),
            darkSecondary = Color(0xFFD3A98D)
        )
    ),

    /** 青柠 —— 唯一偏黄绿的，和青绿分得开是因为明度高很多 */
    LIME(
        label = "青柠",
        palette = ThemePalette(
            lightPrimary = Color(0xFF5E8C1F),
            lightOnPrimary = Color(0xFFFFFFFF),
            lightPrimaryContainer = Color(0xFFE9F3D2),
            lightOnPrimaryContainer = Color(0xFF24380A),
            lightSecondary = Color(0xFF85996B),
            darkPrimary = Color(0xFFBDDC7C),
            darkOnPrimary = Color(0xFF243A05),
            darkPrimaryContainer = Color(0xFF44661A),
            darkOnPrimaryContainer = Color(0xFFEAF4D5),
            darkSecondary = Color(0xFFA9BE8A)
        )
    ),

    /** 明青 —— 比青绿更亮更蓝，最有"屏幕发光"感的一支 */
    CYAN(
        label = "明青",
        palette = ThemePalette(
            lightPrimary = Color(0xFF0E8FA8),
            lightOnPrimary = Color(0xFFFFFFFF),
            lightPrimaryContainer = Color(0xFFD3EEF4),
            lightOnPrimaryContainer = Color(0xFF05323C),
            lightSecondary = Color(0xFF6C9AA6),
            darkPrimary = Color(0xFF6FD8EC),
            darkOnPrimary = Color(0xFF04323C),
            darkPrimaryContainer = Color(0xFF12657A),
            darkOnPrimaryContainer = Color(0xFFD6F1F7),
            darkSecondary = Color(0xFF9BC3CC)
        )
    ),

    /** 亮紫 —— 六支里色相最"跳"的，配深色模式很好看 */
    VIOLET(
        label = "亮紫",
        palette = ThemePalette(
            lightPrimary = Color(0xFF8A4BC8),
            lightOnPrimary = Color(0xFFFFFFFF),
            lightPrimaryContainer = Color(0xFFEDE0FA),
            lightOnPrimaryContainer = Color(0xFF33124F),
            lightSecondary = Color(0xFF9A82B5),
            darkPrimary = Color(0xFFC9A0F5),
            darkOnPrimary = Color(0xFF331050),
            darkPrimaryContainer = Color(0xFF5F3291),
            darkOnPrimaryContainer = Color(0xFFEFE2FB),
            darkSecondary = Color(0xFFBBA6D6)
        )
    ),

    /** 珊瑚 —— 橘红之间，比樱粉更热 */
    CORAL(
        label = "珊瑚",
        palette = ThemePalette(
            lightPrimary = Color(0xFFD6534F),
            lightOnPrimary = Color(0xFFFFFFFF),
            lightPrimaryContainer = Color(0xFFFBDFDE),
            lightOnPrimaryContainer = Color(0xFF4C100F),
            lightSecondary = Color(0xFFB48483),
            darkPrimary = Color(0xFFFFA199),
            darkOnPrimary = Color(0xFF4C0F0C),
            darkPrimaryContainer = Color(0xFF8F332F),
            darkOnPrimaryContainer = Color(0xFFFBE0DE),
            darkSecondary = Color(0xFFD6A9A7)
        )
    ),

    /** 桃粉 —— 比樱粉更艳，是"粉"这一族里最饱和的一支 */
    FUCHSIA(
        label = "桃粉",
        palette = ThemePalette(
            lightPrimary = Color(0xFFC13E8E),
            lightOnPrimary = Color(0xFFFFFFFF),
            lightPrimaryContainer = Color(0xFFF8DEED),
            lightOnPrimaryContainer = Color(0xFF480C31),
            lightSecondary = Color(0xFFAC7E97),
            darkPrimary = Color(0xFFF597CF),
            darkOnPrimary = Color(0xFF480C31),
            darkPrimaryContainer = Color(0xFF8A2A63),
            darkOnPrimaryContainer = Color(0xFFFADFEE),
            darkSecondary = Color(0xFFCFA4BB)
        )
    ),

    /** 宝蓝 —— 高饱和的冷色，和第二排的橘正好在色环两端 */
    SAPPHIRE(
        label = "宝蓝",
        palette = ThemePalette(
            lightPrimary = Color(0xFF2F5BD0),
            lightOnPrimary = Color(0xFFFFFFFF),
            lightPrimaryContainer = Color(0xFFDCE5FC),
            lightOnPrimaryContainer = Color(0xFF0C2059),
            lightSecondary = Color(0xFF7789B7),
            darkPrimary = Color(0xFF9DB6FB),
            darkOnPrimary = Color(0xFF0C2059),
            darkPrimaryContainer = Color(0xFF2C4CA8),
            darkOnPrimaryContainer = Color(0xFFDEE7FD),
            darkSecondary = Color(0xFFA9B7DC)
        )
    );

    /**
     * 设置页那颗色点用哪个色。
     *
     * 固定取**亮色主题的主色**（而不是跟着当前明暗切换）：色点并排时，
     * 深色在浅色底上更容易分辨，而暗色主题的取值都是浅色，彼此差别很小。
     */
    val swatch: Color get() = palette.lightPrimary

    companion object {
        /** 每行的色点数。分两排是因为十四个一行在手机上每颗不到 20dp，点不准也看不出色差。 */
        const val PER_ROW = 7

        /** 越界（老版本存了新版本没有的序号）时退回默认色，而不是崩溃 */
        fun of(value: Int): ThemeColor = entries.getOrElse(value) { MINT }
    }
}
