package com.asmrone.app.ui.theme

import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════
// ToAsmr 配色系统 —— 直接继承 comicApp 的「青绿/薄荷 · 极简干净风 · 明暗双主题」
// 低饱和 + 浅色表面 + 克制点缀；新增少量音频场景专用色（波形、播放中、字幕、下载）
// ═══════════════════════════════════════════════════════

// ── 主色调：青绿 ────────────────────────────────────
val Primary = Color(0xFF17817A)              // 主色 — 按钮、选中态、标题
val PrimaryDark = Color(0xFF0F5B54)          // 深青绿变体 — 按压态、容器
val OnPrimary = Color(0xFFFFFFFF)            // 主色上的文字/图标
val PrimaryContainer = Color(0xFFCFEEE9)     // 主色浅底容器（亮色）— 淡薄荷
val OnPrimaryContainer = Color(0xFF0F423E)   // 主色容器上的文字
val DarkPrimary = Color(0xFF7FD4C8)          // 暗色主题主色 — 亮薄荷
val DarkOnPrimary = Color(0xFF073630)        // 暗色主题主色上的文字

// ── 辅助色：灰绿 ──────────────────────────────────
val Secondary = Color(0xFF6E9791)            // 辅助色 — 标签、次要信息
val SecondaryDark = Color(0xFF9CBDB8)        // 辅助色变体
val OnSecondary = Color(0xFFFFFFFF)

// ── 强调色：暖沙 ──────────────────────────────────
val Tertiary = Color(0xFFC9A57B)             // 强调色 — 收藏图标、评分星（柔和暖沙）
val OnTertiary = Color(0xFFFFFFFF)

// ── 亮色主题表面色 ──────────────────────────────────
val LightBackground = Color(0xFFF4F8F7)      // 页面背景 — 极淡青白
val LightSurface = Color(0xFFFFFFFF)         // 卡片/组件背景 — 纯白
val LightSurfaceVariant = Color(0xFFE8EFED)  // 输入框/骨架屏底 — 淡灰绿
val LightOnBackground = Color(0xFF1A2825)    // 正文 — 深青灰
val LightOnSurface = Color(0xFF202E2B)       // 表面文字 — 深灰
val LightOnSurfaceVariant = Color(0xFF687975)// 辅助文字 — 中灰
val LightDivider = Color(0xFFDCE6E3)         // 分割线 — 浅灰绿

// ── 暗色主题表面色 ──────────────────────────────────
val DarkBackground = Color(0xFF0F1A18)       // 深青黑底
val DarkSurface = Color(0xFF162321)          // 卡片底 — 稍亮于背景
val DarkSurfaceVariant = Color(0xFF1F2E2B)   // 输入框/骨架屏底
val DarkOnBackground = Color(0xFFE1E9E7)     // 正文 — 浅灰白
val DarkOnSurface = Color(0xFFE1E9E7)        // 表面文字
val DarkOnSurfaceVariant = Color(0xFF93A5A1) // 辅助文字 — 中浅灰
val DarkDivider = Color(0xFF2A3A36)          // 分割线

// ── 功能色（亮色+暗色通用） ───────────────────────
val RatingStar = Color(0xFFE5B25E)           // 柔和琥珀评分星
val TagBackground = Color(0xFFE8EFED)        // 标签底色（亮色）
val TagBackgroundDark = Color(0xFF1F2E2B)    // 标签底色（暗色）
val ErrorColor = Color(0xFFD96C6C)           // 错误/删除红（低饱和）
val SuccessColor = Color(0xFF5EA57A)         // 成功绿（低饱和）

// ── 音频场景专用色（本次新增） ─────────────────────
/** 播放中指示：比主色略亮的薄荷，用于频谱 / 播放中曲目高亮 */
val PlayingAccent = Color(0xFF2FA79B)

/** 频谱条渐变的起止色（播放器可视化） */
val WaveformStart = Color(0xFF7FD4C8)
val WaveformEnd = Color(0xFF17817A)

/** 收听进度条底色（卡片底部的细进度线） */
val ProgressTrack = Color(0x337FD4C8)

/** 字幕可用标记 */
val SubtitleBadge = Color(0xFF6E9791)

/** 离线下载完成标记 */
val DownloadDone = Color(0xFF5EA57A)

/** 睡眠定时器 / 定时关闭强调色 */
val SleepTimerAccent = Color(0xFF8E86C9)

/** 迷你播放器与播放页的深色底（封面取色失败时的兜底渐变） */
val PlayerGradientTop = Color(0xFF162321)
val PlayerGradientBottom = Color(0xFF0F1A18)
