package com.asmrone.app.player

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.asmrone.app.data.lyrics.LyricLine
import com.asmrone.app.data.lyrics.LyricsLoad
import com.asmrone.app.data.lyrics.LyricsLoader
import com.asmrone.app.data.prefs.AppPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 悬浮窗歌词。
 *
 * ## 为什么是"窗口"而不是"页面里的浮层"
 *
 * 这个功能的用途是**一边用别的 App 一边看歌词**，所以它必须挂在系统窗口上
 * （`TYPE_APPLICATION_OVERLAY`），而不是画在播放页里 —— 页面里的浮层一退到
 * 后台就跟着没了。
 *
 * ## 为什么不用 Service
 *
 * 悬浮窗只需要一个 `WindowManager` 和一个 `applicationContext`，
 * 不需要独立 Service：真正让进程活着的是已经在跑的前台服务
 * [PlaybackService]（媒体播放）。这里只负责"当前这一行歌词是什么"，
 * 进程被杀时窗口自然消失 —— 那时也没有声音了，不需要歌词。
 *
 * ## 外观
 *
 * 歌词本身**不带底色**（用户明确要求去掉灰黑背景），所以文字带一层黑色阴影
 * 保证在浅色壁纸上也读得清。窗口是**固定尺寸**的：换句、换字号都不会改变
 * 窗口大小，避免它在屏幕上"跳"。
 *
 * 点一下歌词区域会展开一条工具条：锁定 / 颜色 / 字号 / 透明度 / 删除。
 * 锁定后窗口不能再拖，工具条里只剩「解锁」；删除会直接关掉悬浮窗并把开关
 * 存成关闭。这些设置都存在 [AppPrefs] 里，下次打开沿用。
 *
 * ## 数据从哪来
 *
 * 直接观察 [AudioController.state]（里面已经带 `positionMs`，播放时每 500ms
 * 发一次），曲目变了就按 [LyricsLoader] 的规则取歌词（内存 → 磁盘 → 网络）。
 * 歌词列表只在这个对象里存一份，换曲目才重新取。
 *
 * ## 权限
 *
 * 需要 `SYSTEM_ALERT_WINDOW`（用户手动授予）。调用方先用 [canDraw] 判断，
 * 没授权就去系统设置页；本对象自己也会在挂窗前再检查一次，
 * 权限被撤销时 [refresh] 会顺手把窗口摘掉。
 */
object LyricsOverlay {

    /** 歌词区固定尺寸：宽 280dp、高 76dp（两行，最大字号也放得下） */
    private const val BOX_WIDTH_DP = 280
    private const val BOX_HEIGHT_DP = 76

    /** 可选的文字颜色（循环切换）：白、黑、暖黄、青、粉 */
    private val TEXT_COLORS = intArrayOf(
        Color.WHITE,
        Color.BLACK,
        0xFFFFD54F.toInt(),
        0xFF80DEEA.toInt(),
        0xFFF48FB1.toInt()
    )

    /** 可选字号（sp） */
    private val TEXT_SIZES = intArrayOf(14, 16, 18, 21, 25)

    /** 可选不透明度（0..100） */
    private val TEXT_ALPHAS = intArrayOf(100, 85, 70, 55, 40)

    private var windowManager: WindowManager? = null

    /** 整个窗口的根容器 */
    private var container: LinearLayout? = null

    /** 固定尺寸的歌词区 */
    private var lyricBox: LinearLayout? = null

    /** 工具条（默认隐藏，点歌词区才出现） */
    private var panel: LinearLayout? = null
    private var currentView: TextView? = null
    private var subtitleView: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var scope: CoroutineScope? = null
    private var loadJob: Job? = null

    /** 当前曲目的歌词（换曲目时整体替换） */
    private var lines: List<LyricLine> = emptyList()
    private var untimed = false

    /** 正在显示的曲目键（workId + trackPath），避免重复加载 */
    private var loadedKey: String = ""

    /** 歌词是否还在取（换曲目后的几百毫秒里要显示提示，不能空白） */
    private var lyricsLoading = false

    /** 上次取歌词是否失败（与"本来就没有歌词"区分开） */
    private var lyricsFailed = false

    // ── 外观 / 锁定状态（从 AppPrefs 读入，改动即刻写回） ──
    private var locked = false
    private var textColor = Color.WHITE
    private var textSizeSp = 15
    private var textAlpha = 100

    /** 拖动用的临时状态 */
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragged = false

    val isShowing: Boolean get() = container != null

    /** 是否已获得「显示在其他应用上层」权限 */
    fun canDraw(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /**
     * 打开悬浮窗；已经打开时返回 true。
     *
     * @return 是否成功挂上（没有权限 / 系统拒绝时返回 false）
     */
    fun show(context: Context): Boolean {
        if (isShowing) return true
        if (!canDraw(context)) return false

        val app = context.applicationContext
        val manager = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false

        readPrefs()

        val view = buildView(app)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不抢焦点（不挡输入法/返回键），但要能接受拖动与点击
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // 回到上次拖到的位置：关掉再开、甚至重启后都不该跳回默认点。
            // 没记录过时才用状态栏下方 88dp 这个初值。
            // 夹一下范围：换分辨率/横竖屏后旧偏移可能整块跑到屏幕外。
            val savedX = AppPrefs.overlayPosX()
            val savedY = AppPrefs.overlayPosY()
            val metrics = app.resources.displayMetrics
            x = if (savedX == AppPrefs.OVERLAY_POS_UNSET) {
                0
            } else {
                savedX.coerceIn(-metrics.widthPixels / 2, metrics.widthPixels / 2)
            }
            y = if (savedY == AppPrefs.OVERLAY_POS_UNSET) {
                dp(app, 88)
            } else {
                savedY.coerceIn(0, (metrics.heightPixels - dp(app, 160)).coerceAtLeast(0))
            }
        }

        val added = runCatching { manager.addView(view, lp) }.isSuccess
        if (!added) return false

        windowManager = manager
        container = view
        params = lp
        attachTouch(app, view, manager, lp)
        startObserving(app)
        return true
    }

    /** 关闭悬浮窗；没打开时什么也不做 */
    fun hide() {
        scope?.cancel()
        scope = null
        loadJob?.cancel()
        loadJob = null
        val view = container
        val manager = windowManager
        container = null
        windowManager = null
        params = null
        lyricBox = null
        panel = null
        currentView = null
        subtitleView = null
        lines = emptyList()
        untimed = false
        loadedKey = ""
        lyricsLoading = false
        lyricsFailed = false
        if (view != null && manager != null) {
            runCatching { manager.removeView(view) }
        }
    }

    /**
     * 开关：打开时返回 true，关闭时返回 false。
     *
     * 调用方负责把返回值写进 [AppPrefs.setOverlayLyrics]，
     * 这样下次冷启动还能恢复。
     */
    fun toggle(context: Context): Boolean =
        if (isShowing) {
            hide()
            false
        } else {
            show(context)
        }

    /** 冷启动/回到前台时的恢复：只有"上次开着 + 权限还在"才挂回去 */
    fun restore(context: Context) {
        if (isShowing || !canDraw(context)) return
        if (!AppPrefs.overlayLyrics.value) return
        // 冷启动时还没有在播放：不挂一个写着「暂无歌词」的空窗
        if (!AudioController.state.value.isActive) return
        show(context)
    }

    // ─────────────────────────── 视图 ───────────────────────────

    private fun buildView(context: Context): LinearLayout {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // 根容器不带底色：歌词直接浮在桌面/别的应用上
        }

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(context, BOX_WIDTH_DP), dp(context, BOX_HEIGHT_DP))
        }
        lyricBox = box

        currentView = TextView(context).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.START
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = "准备中…"
        }
        subtitleView = TextView(context).apply {
            gravity = Gravity.START
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(context, 4), 0, 0)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        box.addView(currentView)
        box.addView(subtitleView)

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(dp(context, 6), dp(context, 4), dp(context, 6), dp(context, 4))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 14).toFloat()
                // 工具条自己有底：没有底的话按钮在壁纸上完全看不清
                setColor(Color.argb(0xD9, 0x18, 0x18, 0x1C))
            }
            layoutParams = LinearLayout.LayoutParams(
                dp(context, BOX_WIDTH_DP),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        panel = bar

        root.addView(box)
        root.addView(bar)

        applyTextStyle(context)
        refreshPanel(context)
        return root
    }

    /** 把颜色/字号/透明度套到两个 TextView 上（带阴影，无底色也能读） */
    private fun applyTextStyle(context: Context) {
        val alpha = (textAlpha / 100f * 255f).toInt().coerceIn(0, 255)
        val shadowRadius = dp(context, 6).toFloat()

        currentView?.apply {
            setTextColor(withAlpha(textColor, alpha))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp.toFloat())
            setShadowLayer(shadowRadius, 0f, 0f, Color.argb(0x99, 0, 0, 0))
        }
        subtitleView?.apply {
            setTextColor(withAlpha(textColor, (alpha * 0.72f).toInt().coerceIn(0, 255)))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, (textSizeSp - 3).coerceAtLeast(10).toFloat())
            setShadowLayer(shadowRadius, 0f, 0f, Color.argb(0x99, 0, 0, 0))
        }
    }

    /**
     * 重建工具条按钮。
     *
     * 锁定后只剩「解锁」——锁定的目的就是防误触，再摆一排按钮等于没锁；
     * 但仍要能点开窗口把它解掉，所以窗口本身不停止接收点击。
     */
    private fun refreshPanel(context: Context) {
        val bar = panel ?: return
        bar.removeAllViews()

        if (locked) {
            bar.addView(panelButton(context, "解锁") { setLocked(context, false) })
        } else {
            bar.addView(panelButton(context, "锁定") { setLocked(context, true) })
            bar.addView(panelButton(context, "颜色") { cycleColor(context) })
            bar.addView(panelButton(context, "字号") { cycleSize(context) })
            bar.addView(panelButton(context, "透明度") { cycleAlpha(context) })
            bar.addView(panelButton(context, "删除") {
                AppPrefs.setOverlayLyrics(false)
                hide()
            })
        }
    }

    private fun panelButton(context: Context, label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }

    // ─────────────────────────── 交互 ───────────────────────────

    /**
     * 拖动手势 + 点击展开工具条。
     *
     * 判定用系统 touch slop：移动超过阈值算拖动（`dragged = true`），
     * 抬起时没拖动过才算"点击"。这样拖窗口时不会顺手把工具条展开。
     */
    private fun attachTouch(
        context: Context,
        view: View,
        manager: WindowManager,
        lp: WindowManager.LayoutParams
    ) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    dragged = false
                    // 消费 DOWN：窗口自己的手势流才算稳定，
                    // 否则系统可能把 MOVE/UP 交给别处，拖动会断断续续
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!locked &&
                        (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)
                    ) {
                        dragged = true
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        runCatching { manager.updateViewLayout(view, lp) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        // 松手才落盘：拖动过程中每帧写一次 SharedPreferences 太浪费
                        AppPrefs.setOverlayPos(lp.x, lp.y)
                        dragged = false
                    } else {
                        togglePanel()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    dragged = false
                    true
                }

                else -> false
            }
        }
    }

    private fun togglePanel() {
        val bar = panel ?: return
        bar.visibility = if (bar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun setLocked(context: Context, value: Boolean) {
        locked = value
        AppPrefs.setOverlayLocked(value)
        refreshPanel(context)
    }

    private fun cycleColor(context: Context) {
        val index = TEXT_COLORS.indexOf(textColor).let { if (it < 0) 0 else it }
        textColor = TEXT_COLORS[(index + 1) % TEXT_COLORS.size]
        AppPrefs.setOverlayTextColor(textColor)
        applyTextStyle(context)
    }

    private fun cycleSize(context: Context) {
        val index = TEXT_SIZES.indexOf(textSizeSp).let { if (it < 0) 0 else it }
        textSizeSp = TEXT_SIZES[(index + 1) % TEXT_SIZES.size]
        AppPrefs.setOverlayTextSize(textSizeSp)
        applyTextStyle(context)
    }

    private fun cycleAlpha(context: Context) {
        val index = TEXT_ALPHAS.indexOf(textAlpha).let { if (it < 0) 0 else it }
        textAlpha = TEXT_ALPHAS[(index + 1) % TEXT_ALPHAS.size]
        AppPrefs.setOverlayTextAlpha(textAlpha)
        applyTextStyle(context)
    }

    private fun readPrefs() {
        locked = AppPrefs.overlayLocked()
        textColor = AppPrefs.overlayTextColor()
        textSizeSp = AppPrefs.overlayTextSize()
        textAlpha = AppPrefs.overlayTextAlpha()
    }

    // ─────────────────────────── 数据 ───────────────────────────

    private fun startObserving(context: Context) {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch {
            AudioController.state.collect { state ->
                // 权限被用户撤掉时窗口会被系统清掉，这里主动收尾并把开关关掉
                if (!canDraw(context)) {
                    AppPrefs.setOverlayLyrics(false)
                    hide()
                    return@collect
                }
                val track = state.currentTrack
                val key = if (track == null) "" else "${state.workId}|${track.path}"
                if (key != loadedKey) {
                    loadedKey = key
                    loadJob?.cancel()
                    lines = emptyList()
                    untimed = false
                    // 换曲目时先立起"加载中"，否则取歌词的几百毫秒里窗口是空白
                    lyricsLoading = track != null
                    lyricsFailed = false
                    if (track != null) {
                        val ctx = context
                        loadJob = launch {
                            val load = runCatching {
                                LyricsLoader.cached(state.workId, track.path)
                                    ?: LyricsLoader.load(
                                        workId = state.workId,
                                        sourceId = state.workSourceId,
                                        trackPath = track.path,
                                        trackTitle = track.title
                                    )
                            }.getOrElse { error ->
                                if (error is CancellationException) throw error
                                LyricsLoad.Failed(error.message ?: "歌词加载失败")
                            }
                            if (load is LyricsLoad.Ready) {
                                lines = load.lines
                                untimed = load.untimed
                            }
                            lyricsLoading = false
                            lyricsFailed = load is LyricsLoad.Failed
                            applyTextStyle(ctx)
                            refresh(AudioController.state.value)
                        }
                    }
                }
                refresh(state)
            }
        }
    }

    /** 按播放位置把"当前行 / 下一行"写进两个 TextView */
    private fun refresh(state: PlayerState) {
        val root = container ?: return
        val current = currentView ?: return
        val subtitle = subtitleView ?: return

        // 没有任何曲目时才整个藏起来；有曲目但暂停时保持可见 ——
        // 这时正是用户盯着"这句唱到哪"的时候，藏掉反而像坏了。
        if (state.currentTrack == null) {
            root.visibility = View.GONE
            return
        }
        root.visibility = View.VISIBLE

        if (lyricsLoading) {
            current.text = "歌词加载中…"
            subtitle.visibility = View.GONE
            return
        }
        if (lines.isEmpty()) {
            // 没有歌词也要有明确的提示：以前这里是一片空白，
            // 让人分不清是"这首歌没歌词"还是"悬浮窗坏了"
            current.text = if (lyricsFailed) "歌词加载失败" else "本作品没有歌词"
            subtitle.visibility = View.GONE
            return
        }

        val index = if (untimed) {
            0
        } else {
            lines.indexOfLast { it.startMs <= state.positionMs }.coerceAtLeast(0)
        }
        val line = lines.getOrNull(index)
        current.text = line?.original?.ifBlank { "♪" } ?: "♪"

        // 副行优先显示译文（同一时刻的另一种语言），没有译文就显示下一句，
        // 这样两行信息量最大，又不会把窗口撑得过高。
        val sub = when {
            !line?.translation.isNullOrBlank() -> line?.translation.orEmpty()
            untimed -> ""
            else -> lines.getOrNull(index + 1)?.original.orEmpty()
        }
        if (sub.isBlank()) {
            subtitle.visibility = View.GONE
        } else {
            subtitle.text = sub
            subtitle.visibility = View.VISIBLE
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
