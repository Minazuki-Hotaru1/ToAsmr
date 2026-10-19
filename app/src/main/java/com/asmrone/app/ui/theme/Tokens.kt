package com.asmrone.app.ui.theme

import androidx.compose.ui.unit.dp

// ═══════════════════════════════════════════════════════
// AsmrOne 设计 Token —— 间距 / 圆角 / 阴影 / 触控 / 动效 / 液态玻璃几何
//
// 与 comicApp（ToComic）的 Token 表保持一致；差异只有两处：
//  1. 玻璃的**材质参数**不在 Token 里，而是放在 `GlassStylePrefs`（可在设置里实时调）；
//  2. 这里只登记玻璃元素的**几何尺寸**（底栏 64dp、选中块 56dp、控件 48dp…）。
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
    val md = 8.dp       // 输入框、曲目缩略图
    val lg = 12.dp      // 主卡片、骨架屏
    val xl = 16.dp      // 弹窗、大容器、FlatPlate 分组
    val xxl = 20.dp     // 搜索按钮、胶囊
    val pill = 28.dp    // 胶囊按钮、FAB
}

// ── 阴影/海拔系统 ───────────────────────────────────
// 注意：液态玻璃的投影由 Backdrop 的 `Shadow` 效果自己画，
// 不叠 Compose 的 `shadow`（小尺寸节点上会出现白色渲染杂讯）。
object Elevation {
    val none = 0.dp
    val card = 2.dp     // 卡片默认
    val raised = 4.dp   // 悬浮元素
    val dropdown = 8.dp // 下拉菜单
}

// ── 触摸目标 ──────────────────────────────────────────
object TouchTarget {
    val minimum = 48.dp // Material Design 3 最低要求
    val compact = 44.dp // iOS 标准 / 紧凑布局
}

// ── 动效参数 ──────────────────────────────────────────
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

// ── 液态玻璃元素的几何尺寸（材质参数见 GlassStylePrefs） ──
object Liquid {
    /** 底部导航胶囊高度（与 Backdrop 示例的 LiquidBottomTabs 一致） */
    val BOTTOM_BAR_HEIGHT = 64.dp

    /** 底栏内边距：玻璃胶囊与内容行之间 */
    val BOTTOM_BAR_INSET = 4.dp

    /** 选中胶囊高度（比底栏矮 8dp，四周各留 4dp） */
    val BOTTOM_PILL_HEIGHT = 56.dp

    /**
     * 按住选中胶囊时的放大比例：78 / 56。
     * 来源是示例里的 `pressedScale = 78f / 56f` —— 胶囊会"鼓"到接近底栏的内高。
     */
    const val BOTTOM_PILL_PRESSED_SCALE = 78f / 56f

    /**
     * 顶栏上各类控件的**统一高度**：
     * 返回 / 收藏等圆形药丸、中间的标题药丸都用它，整条顶栏才能上下对齐。
     */
    val CONTROL_HEIGHT = 48.dp

    /**
     * 顶栏内容的**上下内边距**。
     *
     * ⚠️ 全应用只有这一个值：`TabTopBar`（音声库 / 收藏）、`GlassTopBar`（二级页）、
     * `SettingsScaffold`、搜索页的悬浮栏都必须用它。
     * 曾经主页面用 2dp、玻璃栏用 6dp，两边顶栏差 8dp，内容起始位置也跟着差 8dp。
     */
    val TOP_BAR_VERTICAL_PADDING = 6.dp

    /**
     * 顶栏总高度（**不含状态栏**）= 内容高度 + 上下内边距。
     *
     * 任何页面的内容留白都应该是 `状态栏高 + TOP_BAR_HEIGHT + CONTENT_TOP_GAP`。
     */
    val TOP_BAR_HEIGHT = CONTROL_HEIGHT + TOP_BAR_VERTICAL_PADDING * 2

    /**
     * 顶栏与内容之间的间距（所有内容页共用）。
     *
     * 内容起始位置 = 状态栏高 + [TOP_BAR_HEIGHT] + 本值。
     *
     * 16dp 是"再拉高一点"之后的值：内容离顶栏再远一些，首行不会贴着玻璃栏。
     */
    val CONTENT_TOP_GAP = 5 .dp

    /** 顶栏右侧动作按钮（图标 / 图标+文字）的高度 */
    val ACTION_HEIGHT = 40.dp

    /** 主操作玻璃按钮（如「开始播放」）的按压缩放 */
    const val ACTION_PRESSED_SCALE = 0.97f

    /** 圆形药丸按压缩放（回到 0.94，比大按钮更"弹"） */
    const val CIRCLE_PRESSED_SCALE = 0.94f

    /** 顶部底衬渐隐的默认最大不透明度（主页面大标题没有玻璃底，需要更实的压底） */
    const val TOP_FADE_ALPHA = 0.65f

    /** 二级页顶栏的底衬渐隐（药丸本身已提供可读性，这里只留一点过渡） */
    const val TOP_FADE_ALPHA_SECONDARY = 0.35f

    /** 玻璃元素下方的内容留白：让内容能滚到悬浮元素下面而不是被挡住 */
    val CONTENT_BOTTOM_INSET = 112.dp

    /**
     * **带迷你播放器**的主页面底部留白（固定值）。
     *
     * 主页面底部可能同时叠着「导航栏 64dp + 8dp 间距 + 迷你播放器 60dp」，
     * 再加上外边距与系统导航栏，所以比 [CONTENT_BOTTOM_INSET] 多出迷你播放器那一截：
     * 112（原导航栏预留）+ 68（迷你播放器 + 间距）= 180dp。
     *
     * 这里刻意用**固定值**而不是实测：主页面列表的底部留白不随播放器出现/消失而跳动，
     * 没有迷你播放器时多出的那段也只是列表末尾的空白，不会挡住内容。
     */
    val CONTENT_BOTTOM_INSET_WITH_PLAYER = 180.dp
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

    /** 迷你播放器高度（与底栏同一族材质，比底栏略矮） */
    val MINI_PLAYER_HEIGHT = 60.dp

    /** 播放器大封面最大边长（Apple Music 风格：尽量接近屏宽） */
    val PLAYER_COVER_MAX = 360.dp
}
