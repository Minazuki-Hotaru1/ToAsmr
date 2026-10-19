package com.asmrone.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.asmrone.app.data.auth.AuthManager
import com.asmrone.app.data.lyrics.LyricsLoader
import com.asmrone.app.data.prefs.AccountStore
import com.asmrone.app.data.prefs.AppPrefs
import com.asmrone.app.data.prefs.LibraryStore
import com.asmrone.app.data.prefs.PlaybackSessionStore
import com.asmrone.app.data.repo.AsmrRepository
import com.asmrone.app.player.AudioController
import com.asmrone.app.ui.component.BottomBarState
import com.asmrone.app.ui.component.FloatingGlassBottomBar
import com.asmrone.app.ui.component.GlassTabItem
import com.asmrone.app.ui.component.LocalBottomBarState
import com.asmrone.app.ui.component.LocalStableNavigationBarBottom
import com.asmrone.app.ui.component.LocalStableStatusBarTop
import com.asmrone.app.ui.component.MiniPlayerBar
import com.asmrone.app.ui.navigation.AppNavGraph
import com.asmrone.app.ui.navigation.Routes
import com.asmrone.app.ui.screen.home.HomeViewModel
import com.asmrone.app.ui.screen.player.PlayerSlideOverlay
import com.asmrone.app.ui.screen.search.SearchViewModel
import com.asmrone.app.ui.screen.startup.StartupScreen
import com.asmrone.app.ui.theme.AsmrOneTheme
import com.asmrone.app.ui.theme.GlassStylePrefs
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch

/** 底部导航项（4 项：音声库 / 搜索 / 收藏 / 设置；图标为 Fluent 矢量图资源） */
private val bottomNavItems = listOf(
    GlassTabItem(Routes.HOME, "音声库", R.drawable.ic_home_fluent, R.drawable.ic_home_outline_fluent),
    GlassTabItem(Routes.SEARCH, "搜索", R.drawable.ic_search_fluent, R.drawable.ic_search_outline_fluent),
    GlassTabItem(
        // 「收藏」：用书签图标（与详情页收藏按钮语义一致），选中实心 / 未选线框
        Routes.LIBRARY, "收藏",
        R.drawable.ic_bookmark_fluent, R.drawable.ic_bookmark_outline_fluent
    ),
    GlassTabItem(
        Routes.SETTINGS, "设置",
        R.drawable.ic_settings_fluent, R.drawable.ic_settings_outline_fluent
    )
)

/**
 * 播放器展开/收起动画的**停止阈值**（进度 0..1）。
 *
 * ⚠️ 这是必须显式给的：`spring()` 默认的 `visibilityThreshold` 是 **0.01f**，
 * 而这里的进度是 0..1 的比例 —— 等价于「动画走到离终点还差 1% 就直接补到终点」。
 * 对 2400px 高的屏幕，1% = 24px，于是表现为：
 *
 *  - 展开：快贴到顶部时，最后 24px 会**瞬间跳上去**；
 *  - 收起：快收完时，底部最后一小条内容**瞬间消失**。
 *
 * 压到 0.0002（2400px 上约 0.5px）之后，收尾就是连续的，肉眼看不到跳变。
 * 其它地方若也用 0..1 的进度做 `spring`，同样要给这个参数。
 */
private const val OVERLAY_VISIBILITY_THRESHOLD = 0.0002f

class MainActivity : ComponentActivity() {

    /**
     * "请求展开播放器"的计数。
     *
     * 通知栏 / 锁屏点进来时，希望直接看到播放器而不是停在首页。Activity 是
     * `singleTask`，已经在运行时系统走 `onNewIntent` 而**不会**重建 onCreate，
     * 所以两条入口都要处理。用计数而不是 Boolean：连点两次也要能各触发一次。
     */
    private var openPlayerRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppPrefs.init(this)
        // 液态玻璃的全局参数（模糊 / 折射 / 透明度 / 色散）必须在首帧前读出来，
        // 否则底栏与顶栏会先用默认值画一帧、再跳到用户保存的样式
        GlassStylePrefs.init(this)
        LibraryStore.init(this)
        // 账号表必须在 AuthManager 之前初始化（AuthManager 会读它拿"当前激活账号"）
        AccountStore.init(this)
        AuthManager.init(this)
        PlaybackSessionStore.init(this)
        AudioController.init(this)
        AudioController.restoreSession()

        // 通知 / 锁屏入口：先把"已存在实例被再次唤醒"的情况接住，
        // 再消费本次启动 Intent 里可能带着的同一个标记。
        addOnNewIntentListener { incoming ->
            if (incoming.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) openPlayerRequest++
        }
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) openPlayerRequest++

        setContent {
            val themeMode by AppPrefs.themeMode.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                1 -> false
                2 -> true
                else -> systemDark
            }

            // 稳定系统栏高度：只增不减，避免沉浸页面进出时整页跳动
            val density = LocalDensity.current
            val liveStatusTop = with(density) {
                WindowInsets.navigationBars.asPaddingValues().calculateTopPadding()
            }
            val liveStatusBarTop = with(density) {
                WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            }
            val liveNavBottom = with(density) {
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            }
            var stableStatusTop by remember { mutableStateOf(if (liveStatusBarTop > 0.dp) liveStatusBarTop else liveStatusTop) }
            if (liveStatusBarTop > stableStatusTop) stableStatusTop = liveStatusBarTop
            var stableNavBottom by remember { mutableStateOf(liveNavBottom) }
            if (liveNavBottom > stableNavBottom) stableNavBottom = liveNavBottom

            AsmrOneTheme(darkTheme = darkTheme) {
                CompositionLocalProvider(
                    LocalStableStatusBarTop provides stableStatusTop,
                    LocalStableNavigationBarBottom provides stableNavBottom
                ) {
                    MainApp(openPlayerRequest = openPlayerRequest)
                }
            }
        }
    }

    override fun onStop() {
        // 退到后台 / 被系统回收前落一次最终位置，冷启动恢复迷你播放器时用它。
        AudioController.persistSession(synchronous = true)
        super.onStop()
    }

    companion object {
        /**
         * 通知栏 / 锁屏点击进入时带上的标记：[PlaybackService] 用它让 App 直接展开播放器。
         */
        const val EXTRA_OPEN_PLAYER = "com.asmrone.app.extra.OPEN_PLAYER"
    }
}

@Composable
fun MainApp(openPlayerRequest: Int = 0) {
    val navController = rememberNavController()
    val homeViewModel: HomeViewModel = viewModel()
    val searchViewModel: SearchViewModel = viewModel()

    /**
     * 首次进入时的字典准备门。
     *
     * 放在 AppNavGraph **外面**：它是"能不能开始用"的前置条件，
     * 而不是导航图上的一页 —— 否则用户能用返回键绕过它。
     *
     * 判断逻辑在 StartupViewModel.start()：只有"从没初始化过、或数据不全"
     * 才会真的去拉，老用户这一屏一帧都不会看到。
     */
    var dictionariesReady by remember { mutableStateOf(false) }
    if (!dictionariesReady) {
        StartupScreen(onFinished = { dictionariesReady = true })
        return
    }

    // 悬浮玻璃元素的统一取样源：内容层挂 layerBackdrop，底栏/迷你播放器挂 drawBackdrop
    val backdrop = rememberLayerBackdrop()
    val bottomBarState = remember { BottomBarState() }
    val collapseBottomBar by AppPrefs.collapseBottomBar.collectAsState()
    val playerState by AudioController.state.collectAsState()

    /**
     * 通知权限（Android 13+）。
     *
     * 放在"第一次真的开始播放"这一刻问，而不是一打开 App 就弹：那时用户还不知道
     * 这个权限是干嘛用的。没有它的话前台服务仍然在跑、声音照旧，但通知与系统
     * 媒体卡片不显示 —— 等于白接了一套媒体会话。
     */
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(playerState.isPlaying) {
        if (!playerState.isPlaying) return@LaunchedEffect
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomBar = currentRoute in Routes.topLevel
    val showMiniPlayer = playerState.isActive && currentRoute != Routes.PLAYER

    // ── 迷你播放器 → 全屏播放器的展开动画 ──
    // 不走 Routes.PLAYER：播放器作为覆盖层从迷你栏的矩形长到全屏，
    // 这样返回时也能原路缩回迷你栏，而不是 NavHost 的整页 scale/fade。
    val overlayScope = rememberCoroutineScope()
    val overlayProgress = remember { Animatable(0f) }
    var playerOverlayVisible by remember { mutableStateOf(false) }

    /**
     * 播放器覆盖层是否已经"预热挂载"过。
     *
     * 常驻组合要花一次组合 + 测量（含 44 行歌词），如果放在**冷启动第一帧**里，
     * 会把首页的首帧推后 —— 那就把卡顿从"点播放器"搬到了"打开 App"。
     *
     * 所以等首帧画完、再等一小会儿（让首页的卡片入场动画先跑），才真正挂上去。
     * 用户从看到首页到去点迷你播放器，远不止 500ms，所以正常情况下等他点的时候
     * 早就预热完了。
     */
    var playerOverlayWarm by remember { mutableStateOf(false) }

    fun openPlayerOverlay() {
        if (playerOverlayVisible) return
        playerOverlayVisible = true
        overlayScope.launch {
            overlayProgress.snapTo(0f)
            overlayProgress.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 1f,
                    stiffness = 320f,
                    visibilityThreshold = OVERLAY_VISIBILITY_THRESHOLD
                )
            )
        }
    }

    fun closePlayerOverlay() {
        overlayScope.launch {
            overlayProgress.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = 1f,
                    stiffness = 380f,
                    visibilityThreshold = OVERLAY_VISIBILITY_THRESHOLD
                )
            )
            playerOverlayVisible = false
        }
    }

    // 从通知 / 锁屏点进来：直接展开播放器，而不是停在首页让用户自己点迷你栏
    LaunchedEffect(openPlayerRequest) {
        if (openPlayerRequest > 0 && playerState.isActive) openPlayerOverlay()
    }

    // 注意：返回键处理放在 PlayerSlideOverlay 内部。
    // 这里注册会早于 NavHost，返回手势会先被底层导航消费，导致播放页不关闭、
    // 反而把播放页下面的页面 pop 掉。覆盖层在 NavHost 之后组合，才能拿到更高优先级。

    /**
     * 预热：首帧画完之后再挂播放器覆盖层。
     *
     * `withFrameNanos` 会等到下一帧的时间点 —— 也就是首帧真的画完了，
     * 这样挂载成本不会计入冷启动的首帧。随后再等 500ms，让首页的卡片入场
     * 动画（400ms + 错峰）先跑完，避免这次挂载抢帧。
     */
    LaunchedEffect(Unit) {
        withFrameNanos { }
        kotlinx.coroutines.delay(500)
        playerOverlayWarm = true
    }

    // 启动时恢复登录会话：先用本地令牌铺出已登录态，再在后台校验/静默重登。
    // 放在 Compose 里而不是 Application 中，是为了不拖慢首帧 —— 未登录也完全不阻塞浏览。
    LaunchedEffect(Unit) {
        AuthManager.restoreSession()
    }

    // 播放开始时写入收听历史；进度节流写盘（每变化 2% 才落一次）
    LaunchedEffect(playerState.workId) {
        if (playerState.workId != 0) {
            AsmrRepository.peekWork(playerState.workId)?.let { work ->
                LibraryStore.pushHistory(work, work.coverUrl(AsmrRepository.host))
            }
        }
    }
    var lastSavedProgress by remember { mutableStateOf(0f) }
    LaunchedEffect(playerState.progress, playerState.workId) {
        if (playerState.workId != 0 && playerState.durationMs > 0) {
            if (kotlin.math.abs(playerState.progress - lastSavedProgress) >= 0.02f) {
                lastSavedProgress = playerState.progress
                LibraryStore.setProgress(playerState.workId, playerState.progress)
            }
        }
    }

    /**
     * 歌词预热 —— **播放开始时就准备**，而不是等播放器打开。
     *
     * 这是"点迷你播放器卡顿"的修法：旧实现把"取文件树 → 找歌词 → 下载 → 解析"
     * 全塞在播放器组合期间的 LaunchedEffect 里，冷启动时还要拉最大 6MB 的树。
     *
     * 这里监听「作品 + 当前曲目」的变化，在后台把结果算好放进 LyricsLoader 的内存缓存；
     * 播放器的歌词页首帧直接读缓存，组合期间零网络、零解析。
     *
     * 注意 key 用 `index` 而不是 `trackTitle`：同一作品连续两首同名文件时，
     * 只按标题变化会漏掉预热。`workSourceId` 也进 key，因为它决定能不能命中
     * 按 RJ 号落盘的文件树与歌词缓存。
     */
    LaunchedEffect(playerState.workId, playerState.index, playerState.workSourceId) {
        val track = playerState.currentTrack ?: return@LaunchedEffect
        LyricsLoader.prefetch(
            workId = playerState.workId,
            sourceId = playerState.workSourceId,
            trackPath = track.path,
            trackTitle = track.title
        )
    }

    /**
     * 封面加载不需要在这里预热。
     *
     * 播放器覆盖层现在**常驻组合**（见下方 `playerOverlayWarm`），它的
     * `AsyncImage` 在挂载那一刻就自己发起加载了 —— 比手动 enqueue 更准，
     * 因为它用的是真实的请求尺寸（底图只要 96px 的小图，大封面才要原尺寸）。
     * 手动再 enqueue 一份反而会多解码一次、多占一份缓存。
     */

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()

                        // 用「稳定」的导航栏高度让位：播放器隐藏/恢复系统栏时底栏不再上下移动
                        .then(
                            LocalStableNavigationBarBottom.current?.let { stable ->
                                Modifier.padding(bottom = stable)
                            } ?: Modifier.navigationBarsPadding()
                        )
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 10.dp)
                ) {
                    // ── 迷你播放器（悬浮玻璃胶囊，材质与底栏一致）──
                    AnimatedVisibility(
                        visible = showMiniPlayer,
                        enter = slideInVertically(
                            initialOffsetY = { it },
                            animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f)
                        ) + fadeIn(),
                        exit = slideOutVertically(
                            targetOffsetY = { it },
                            animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f)
                        ) + fadeOut()
                    ) {
                        MiniPlayerBar(
                            title = playerState.trackTitle,
                            subtitle = playerState.workTitle,
                            coverUrl = playerState.coverUrl,
                            isPlaying = playerState.isPlaying,
                            progress = playerState.progress,
                            hasNext = playerState.hasNext,
                            onClick = { openPlayerOverlay() },
                            onToggle = { AudioController.togglePlayPause() },
                            onNext = { AudioController.next() },
                            backdrop = backdrop
                        )
                    }
                    // 迷你播放器与底栏之间的 8dp 间距放进底栏的动画块里：
                    // 底栏收起时这段间距一起收掉，迷你播放器才是"跟着滑下去"，
                    // 不会留下一条悬空的缝。
                    //
                    // ── 悬浮玻璃底部导航栏 ──
                    AnimatedVisibility(
                        visible = showBottomBar && (!collapseBottomBar || !bottomBarState.collapsed),
                        // ⚠️ 必须带 expandVertically / shrinkVertically：
                        // 只有 slide + fade 时，底栏在动画期间仍占满 64dp，动画结束才从 Column 里
                        // 移除 —— 于是迷你播放器**瞬间**下移 64dp（就是那个"闪现换位置"）。
                        // 加上尺寸过渡后，Column 每帧都在改高度，迷你播放器会平滑跟随。
                        enter = slideInVertically(
                            initialOffsetY = { it },
                            animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f)
                        ) + fadeIn() + expandVertically(
                            animationSpec = tween(260),
                            expandFrom = Alignment.Top
                        ),
                        exit = slideOutVertically(
                            targetOffsetY = { it },
                            animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f)
                        ) + fadeOut() + shrinkVertically(
                            animationSpec = tween(260),
                            shrinkTowards = Alignment.Top
                        )
                    ) {
                        Column {
                            if (showMiniPlayer) Spacer(Modifier.height(8.dp))
                            FloatingGlassBottomBar(
                                tabs = bottomNavItems,
                                currentRoute = currentRoute,
                                onSelect = { route ->
                                    navController.navigate(route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                backdrop = backdrop
                            )
                        }
                    }
                }
            }
        ) {
            // 内容层作为 Backdrop 取样源，供悬浮底栏/迷你播放器实时取下方内容做折射
            Box(modifier = Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                CompositionLocalProvider(LocalBottomBarState provides bottomBarState) {
                    AppNavGraph(
                        navController = navController,
                        homeViewModel = homeViewModel,
                        searchViewModel = searchViewModel
                    )
                }
            }
        }

        // ── 播放器覆盖层（**常驻组合**）──
        //
        // 只要"有播放内容"就挂上它，而不是等第一次点迷你播放器。
        // 展开时它整块在屏幕下方（progress = 0）、不绘制、不接收触摸，
        // 所以挂着的代价几乎为零；换来的是第一次展开只跑位移动画，
        // 不再在点击那一帧组合"全屏模糊底图 + Pager + 44 行歌词"。
        //
        // 这也是"首次点迷你播放器卡顿"的正解：数据预取（LyricsLoader.prefetch）
        // 只解决了网络与解析，组件本身的组合/测量得靠常驻组合来预热。
        //
        // `|| playerOverlayVisible` 是兜底：万一下手很快，预热还没完成就点了，
        // 也要能正常展开（此时按老路径付一次组合成本，行为与改动前一致）。
        if (playerState.isActive && (playerOverlayWarm || playerOverlayVisible)) {
            PlayerSlideOverlay(
                progress = { overlayProgress.value },
                visible = playerOverlayVisible,
                onBack = { closePlayerOverlay() }
            )
        }
    }
}
