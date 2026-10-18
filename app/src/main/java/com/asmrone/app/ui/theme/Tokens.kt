package com.asmrone.app.ui.theme

import androidx.compose.ui.unit.dp

// ═══════════════════════════════════════════════════════
// AsmrOne 设计 Token —— 统一的间距、圆角、阴影、动效与玻璃参数
// 提炼自 comicApp（ToComic）的 ui/theme/Tokens.kt，并针对音频场景补充 Motion / Glass
// ═══════════════════════════════════════════════════════

// ── 8pt 网格间距系统 ──────────────────────────────────
object Spacing {
    val xs = 4.dp       // 极紧凑
    val sm = 8.dp       // 1 单位 — 标签内边距
    val md = 12.dp      // 1.5 单位 — 卡片内边距、列表项
    val lg = 16.dp      // 2 单位 — 屏幕边距、段落间距
    val xl = 24.dp      // 3 单位 — 区块间距
    val xxl = 32.dp     // 4 单位 — 大区块间距
    val xxxl = 48.dp    // 6 单位
}

// ── 圆角系统 ──────────────────────────────────────────
object Radius {
    val sm = 4.dp       // 标签、小徽标
    val md = 8.dp       // 输入框、评论卡片、曲目缩略图
    val lg = 12.dp      // 主卡片、骨架屏
    val xl = 16.dp      // 弹窗、大容器、FlatPlate 分组
    val xxl = 20.dp     // 搜索按钮、胶囊
    val pill = 28.dp    // 胶囊按钮、FAB、迷你播放器
}

// ── 阴影/海拔系统 ───────────────────────────────────
object Elevation {
    val none = 0.dp
    val card = 2.dp     // 卡片默认
    val raised = 4.dp   // 悬浮元素
    val dropdown = 8.dp // 下拉菜单
    val glass = 8.dp    // 玻璃胶囊 / 悬浮导航（新版配方：投影收敛到 8dp）
}

// ── 触摸目标 ──────────────────────────────────────────
object TouchTarget {
    val minimum = 48.dp // Material Design 3 最低要求
    val compact = 44.dp // iOS 标准 / 紧凑布局
}

// ── 动效参数（comicApp 的 CardEntrance 参数在此集中登记） ──
object Motion {
    /** 卡片入场淡入时长 */
    const val CARD_ENTRANCE_MS = 400

    /** 相邻卡片错峰间隔 */
    const val CARD_STAGGER_MS = 50

    /** 错峰分组：序号超过它就回绕，长列表末尾不必等太久 */
    const val CARD_STAGGER_GROUP = 10

    /** 按压缩放的目标比例与弹簧参数 */
    const val PRESS_SCALE = 0.96f
    const val SPRING_DAMPING = 0.5f
    const val SPRING_STIFFNESS = 500f

    /** 玻璃导航选中态的弹簧（更"重"一点，切换更有质感） */
    const val NAV_SPRING_DAMPING = 0.8f
    const val NAV_SPRING_STIFFNESS = 500f

    /** 输入框聚焦的边框/底色过渡 */
    const val FIELD_TRANSITION_MS = 180
}

// ── 液态玻璃材质参数（Haze） ──────────────────────────
// 配方对齐 comicApp 2026-10-14 的新版「液态玻璃」：
// 轻模糊 + 半透明色纱 + 0.8dp 发丝描边 + 无噪点。
object Glass {
    /** 玻璃胶囊本体模糊半径 —— 玻璃要"透"，糊成一片就失去玻璃感 */
    val blurRadius = 12.dp

    /** 顶部渐变模糊的半径（比胶囊略大，滚动内容才有明显的渐隐过渡） */
    val topBlurRadius = 14.dp

    /** 圆形小药丸的模糊半径（与胶囊一致） */
    val pillBlurRadius = 12.dp

    /** 半透明色纱：surface 约 60% 不透明度（参考实现 #F2F2F7 @ 63%） */
    const val TINT_ALPHA = 0.60f

    /** 圆形药丸着色（与胶囊一致，保证观感统一） */
    const val PILL_TINT_ALPHA = 0.60f

    /** 不支持真实模糊时的退化底色 */
    const val FALLBACK_ALPHA = 0.90f

    /** 噪点：新版去掉噪点，保持纯净玻璃 */
    const val NOISE = 0f

    /** 发丝描边宽度：0.8dp（早期 1dp 渐变描边偏粗、偏亮） */
    val borderWidth = 0.8.dp

    /** 发丝描边透明度：浅色主题白色 40% */
    const val HAIRLINE_LIGHT = 0.40f

    /** 发丝描边透明度：深色主题白色 20% */
    const val HAIRLINE_DARK = 0.20f

    /** 液态胶囊拉伸峰值：切换时宽度最多放大到 1.28 倍 */
    const val PILL_STRETCH = 0.28f

    /** 液态胶囊切换时上下挤压量（dp） */
    val PILL_SQUASH = 2.5.dp

    /** 液态胶囊位移动画时长 */
    const val PILL_MOVE_MS = 320

    /** 选中图标放大倍率与动画时长 */
    const val ICON_SELECTED_SCALE = 1.08f
    const val ICON_SCALE_MS = 250
}

// ── 封面与播放器尺寸 ──────────────────────────────────
object Sizes {
    /**
     * 卡片里**图片**的显示比例：宽/高 = **4:3**（横向）。
     *
     * 与网页端卡片一致 —— 图片按 4:3 裁切填满，而不是按封面原始比例完整显示
     * （音声封面多为竖版/方版，4:3 取的是画面中部）。
     */
    const val CARD_IMAGE_ASPECT = 4f / 3f

    /** 网格卡片最小宽度（自适应列数） */
    val GRID_MIN_CELL = 160.dp

    /** 列表行缩略图（同样 4:3） */
    val ROW_THUMB_WIDTH = 96.dp
    val ROW_THUMB_HEIGHT = 72.dp

    /** 迷你播放器高度 */
    val MINI_PLAYER_HEIGHT = 60.dp

    /**
     * 悬浮顶栏上各类控件的**统一高度**：
     * 返回 / 收藏等圆形药丸按钮，以及中间的标题药丸，都用这个高度，
     * 整条顶栏的控件才能上下对齐（标题药丸不会比返回按钮更高）。
     */
    val TOP_BAR_CONTROL = 44.dp

    /** 播放器大封面最大边长（Apple Music 风格：尽量接近屏宽） */
    val PLAYER_COVER_MAX = 360.dp
}
