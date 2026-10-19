package com.asmrone.app.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全应用液态玻璃样式 —— 与 comicApp 的 `GlassStylePrefs` 同一套参数与默认值。
 *
 * 数值直接对应 Backdrop 的效果参数：
 *  - [blurRadius] 高斯模糊半径（dp）
 *  - [refractionHeight] 边缘折射高度（dp）
 *  - [refractionAmount] 折射位移量（dp）
 *  - [surfaceAlpha] 玻璃表面色纱的不透明度
 *  - [chromaticAberration] 是否启用色散
 *
 * 各组件可以按自身尺寸对模糊/折射做比例缩放，但基准值统一来自这里，
 * 所以在「设置 → 外观 → 液态玻璃」里调整后，全应用的玻璃会一起变化。
 */
data class GlassStyle(
    // 默认值 = 预设「强」：
    // blur 6dp / lens 8dp,28dp / surface 38% / 开启色散
    val blurRadius: Float = 6f,
    val refractionHeight: Float = 8f,
    val refractionAmount: Float = 28f,
    val surfaceAlpha: Float = 0.38f,
    val chromaticAberration: Boolean = true
) {
    companion object {
        val Default = GlassStyle()
    }
}

/**
 * 四档玻璃预设：从最弱到最强。
 *
 * 每档是一组完整参数，而不是简单倍率；用户选中后四个滑杆会同步到该档数值。
 * 手动拖动任一滑杆后，当前参数不再等于任何预设，预设按钮会取消选中。
 */
enum class GlassPreset(
    val label: String,
    val style: GlassStyle
) {
    WEAK(
        label = "弱",
        style = GlassStyle(
            // 弱玻璃：强模糊、弱折射、低透明度（更实的磨砂感）
            blurRadius = 12f,
            refractionHeight = 1f,
            refractionAmount = 4f,
            surfaceAlpha = 0.72f,
            chromaticAberration = false
        )
    ),
    MEDIUM(
        label = "中",
        style = GlassStyle(
            blurRadius = 9f,
            refractionHeight = 4f,
            refractionAmount = 12f,
            surfaceAlpha = 0.56f,
            chromaticAberration = false
        )
    ),
    STRONG(
        label = "强",
        // 强玻璃：低模糊、强折射、高透明度（更透的折射感）
        style = GlassStyle.Default
    ),
    EXTREME(
        label = "极强",
        style = GlassStyle(
            // 极强：最低模糊、最高折射、最高透明度
            blurRadius = 3f,
            refractionHeight = 12f,
            refractionAmount = 46f,
            surfaceAlpha = 0.20f,
            chromaticAberration = true
        )
    );

    companion object {
        /** 当前参数对应的预设；手动微调过参数时返回 null。 */
        fun fromStyle(style: GlassStyle): GlassPreset? =
            entries.firstOrNull { it.style == style }
    }
}

/**
 * 玻璃样式的全局偏好。
 *
 * 与 [AppPrefs] 一样用 SharedPreferences 持久化；[style] 是 StateFlow，
 * 设置页修改后所有正在显示的玻璃组件会在下一帧重组。
 */
object GlassStylePrefs {

    private const val PREFS = "glass_style_prefs"
    private const val KEY_BLUR = "blur_radius"
    private const val KEY_REFRACTION_HEIGHT = "refraction_height"
    private const val KEY_REFRACTION_AMOUNT = "refraction_amount"
    private const val KEY_SURFACE_ALPHA = "surface_alpha"
    private const val KEY_CHROMATIC = "chromatic_aberration"

    private val _style = MutableStateFlow(GlassStyle.Default)
    val style: StateFlow<GlassStyle> = _style.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _style.value = GlassStyle(
            blurRadius = prefs.getFloat(KEY_BLUR, GlassStyle.Default.blurRadius),
            refractionHeight = prefs.getFloat(
                KEY_REFRACTION_HEIGHT,
                GlassStyle.Default.refractionHeight
            ),
            refractionAmount = prefs.getFloat(
                KEY_REFRACTION_AMOUNT,
                GlassStyle.Default.refractionAmount
            ),
            surfaceAlpha = prefs.getFloat(KEY_SURFACE_ALPHA, GlassStyle.Default.surfaceAlpha),
            chromaticAberration = prefs.getBoolean(
                KEY_CHROMATIC,
                GlassStyle.Default.chromaticAberration
            )
        )
    }

    /** 只更新内存中的样式，用于拖动 Slider 时实时预览。 */
    fun set(style: GlassStyle) {
        _style.value = style
    }

    /** 把当前样式写入 SharedPreferences。 */
    fun persist(context: Context, style: GlassStyle) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_BLUR, style.blurRadius)
            .putFloat(KEY_REFRACTION_HEIGHT, style.refractionHeight)
            .putFloat(KEY_REFRACTION_AMOUNT, style.refractionAmount)
            .putFloat(KEY_SURFACE_ALPHA, style.surfaceAlpha)
            .putBoolean(KEY_CHROMATIC, style.chromaticAberration)
            .apply()
    }

    fun update(context: Context, style: GlassStyle) {
        set(style)
        persist(context, style)
    }

    fun applyPreset(context: Context, preset: GlassPreset) = update(context, preset.style)

    fun reset(context: Context) = update(context, GlassStyle.Default)
}
