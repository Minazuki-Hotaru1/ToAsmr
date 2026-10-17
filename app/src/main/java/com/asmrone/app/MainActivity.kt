package com.asmrone.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.asmrone.app.data.auth.AuthManager
import com.asmrone.app.data.prefs.AccountStore
import com.asmrone.app.data.prefs.AppPrefs
import com.asmrone.app.data.prefs.LibraryStore
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
import com.asmrone.app.ui.screen.search.SearchViewModel
import com.asmrone.app.ui.screen.startup.StartupScreen
import com.asmrone.app.ui.theme.AsmrOneTheme
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

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

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppPrefs.init(this)
        LibraryStore.init(this)
        // 账号表必须在 AuthManager 之前初始化（AuthManager 会读它拿"当前激活账号"）
        AccountStore.init(this)
        AuthManager.init(this)
        AudioController.init(this)

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
                    MainApp()
                }
            }
        }
    }
}

@Composable
fun MainApp() {
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

    // 悬浮玻璃元素的统一模糊源：内容层挂 hazeSource，底栏/迷你播放器挂 hazeEffect
    val hazeState = rememberHazeState()
    val bottomBarState = remember { BottomBarState() }
    val collapseBottomBar by AppPrefs.collapseBottomBar.collectAsState()
    val playerState by AudioController.state.collectAsState()

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomBar = currentRoute in Routes.topLevel
    val showMiniPlayer = playerState.isActive && currentRoute != Routes.PLAYER

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
                            onClick = { navController.navigate(Routes.PLAYER) },
                            onToggle = { AudioController.togglePlayPause() },
                            onNext = { AudioController.next() },
                            hazeState = hazeState
                        )
                    }
                    if (showMiniPlayer) Spacer(Modifier.height(8.dp))

                    // ── 悬浮玻璃底部导航栏 ──
                    AnimatedVisibility(
                        visible = showBottomBar && (!collapseBottomBar || !bottomBarState.collapsed),
                        enter = slideInVertically(
                            initialOffsetY = { it },
                            animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f)
                        ) + fadeIn(),
                        exit = slideOutVertically(
                            targetOffsetY = { it },
                            animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f)
                        ) + fadeOut()
                    ) {
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
                            hazeState = hazeState
                        )
                    }
                }
            }
        ) {
            // 内容层作为 Haze 模糊源，供悬浮底栏/迷你播放器实时取下方内容做毛玻璃
            Box(modifier = Modifier.fillMaxSize().hazeSource(hazeState)) {
                CompositionLocalProvider(LocalBottomBarState provides bottomBarState) {
                    AppNavGraph(
                        navController = navController,
                        homeViewModel = homeViewModel,
                        searchViewModel = searchViewModel
                    )
                }
            }
        }
    }
}
