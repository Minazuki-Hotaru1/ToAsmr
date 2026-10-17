package com.asmrone.app.data.model

import androidx.annotation.DrawableRes
import com.asmrone.app.R

/**
 * 「我的收藏」里的收听状态。
 *
 * asmr.one / Kikoeru 的收藏不是简单的「是/否」，而是**五种收听状态 + 一个用户评分**，
 * 这套字段来自 `/api/review`（作品元数据里的 `progress` / `userRating`）：
 *
 * | 接口值 | 界面名 | 含义 |
 * |---|---|---|
 * | `marked` | 想听 | 收藏待听 |
 * | `listening` | 在听 | 正在听 |
 * | `listened` | 听过 | 已听完 |
 * | `replay` | 重听 | 想再听一遍 |
 * | `postponed` | 搁置 | 暂时放着 |
 *
 * 取值与参考实现 KikoFlu 的 `MyReviewFilter`（testAsmrOne/lib/src/providers/my_reviews_provider.dart）
 * 以及 `review_progress_dialog.dart` 完全一致。
 */
enum class CollectionStatus(
    /** 接口使用的字符串值 */
    val apiValue: String,
    /** 界面显示名 */
    val label: String,
    /**
     * 状态图标（Fluent 矢量图，与全应用图标体系一致）。
     *
     * `@get:DrawableRes` 而不是裸 `@DrawableRes`：后者只会作用在构造参数上，
     * 编译器会提示将来还会作用到字段，这里明确标在 getter 上消除歧义。
     */
    @get:DrawableRes val iconRes: Int
) {
    MARKED("marked", "想听", R.drawable.ic_bookmark_fluent),
    LISTENING("listening", "在听", R.drawable.ic_headphones_fluent),
    LISTENED("listened", "听过", R.drawable.ic_check_circle_fluent),
    REPLAY("replay", "重听", R.drawable.ic_replay_fluent),
    POSTPONED("postponed", "搁置", R.drawable.ic_schedule_fluent);

    companion object {
        /**
         * 解析接口返回的 `progress`。
         *
         * 认不出来（服务端加了新状态、或字段为空）时返回 null ——
         * 界面上就会显示成"未标记"，而**不会**猜一个状态出来。
         */
        fun fromApi(value: String?): CollectionStatus? {
            if (value.isNullOrBlank()) return null
            return entries.firstOrNull { it.apiValue == value.trim().lowercase() }
        }
    }
}
