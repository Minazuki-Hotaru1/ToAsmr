package com.asmrone.app.ui.navigation

import com.asmrone.app.ui.screen.dictionary.DictionaryResultScreen
import com.asmrone.app.data.repo.DictionaryQuery
import com.asmrone.app.data.repo.AsmrRepository
import com.asmrone.app.data.model.SearchNamespaces
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.asmrone.app.ui.screen.auth.AccountSettingsScreen
import com.asmrone.app.ui.screen.auth.LoginScreen
import com.asmrone.app.ui.screen.detail.DetailScreen
import com.asmrone.app.ui.screen.detail.DetailViewModel
import com.asmrone.app.ui.screen.download.DownloadsScreen
import com.asmrone.app.ui.screen.home.HomeScreen
import com.asmrone.app.ui.screen.home.HomeViewModel
import com.asmrone.app.ui.screen.library.LibraryScreen
import com.asmrone.app.ui.screen.library.PlaylistDetailScreen
import com.asmrone.app.ui.screen.link.WorkLinkScreen
import com.asmrone.app.ui.screen.player.PlayerScreen
import com.asmrone.app.ui.screen.search.SearchResultScreen
import com.asmrone.app.ui.screen.search.SearchScreen
import com.asmrone.app.ui.screen.search.SearchViewModel
import com.asmrone.app.ui.screen.settings.AboutSettingsScreen
import com.asmrone.app.ui.screen.settings.AppearanceSettingsScreen
import com.asmrone.app.ui.screen.settings.CacheSettingsScreen
import com.asmrone.app.ui.screen.settings.DataSettingsScreen
import com.asmrone.app.ui.screen.settings.GlassSettingsScreen
import com.asmrone.app.ui.screen.settings.PlaybackSettingsScreen
import com.asmrone.app.ui.screen.settings.ServerSettingsScreen
import com.asmrone.app.ui.screen.settings.SettingsScreen
import androidx.lifecycle.viewmodel.compose.viewModel

/** 路由表（字符串常量 + 构造辅助，与 comicApp 的组织方式一致） */
object Routes {
    const val HOME = "home"
    const val SEARCH = "search"

    /** 搜索结果页（搜索之后跳进来的独立展示界面） */
    const val SEARCH_RESULT = "search/result"

    /**
     * 外部链接 / RJ 号的落地页：进来把线索换成 workId 就立刻跳详情。
     *
     * 它不是给人看的页面（只在换 id 的那一两百毫秒里可见），存在的意义是
     * 让"冷启动被链接拉起"与"搜索框敲 RJ 号"共用同一条解析 + 报错路径。
     */
    const val WORK_LINK = "work-link/{token}"

    /** [WORK_LINK] 的构造辅助；token 可能是 `RJ01700554` 也可能是一串数字。 */
    fun workLink(token: String) = "work-link/${android.net.Uri.encode(token)}"

    /**
     * 「某一项字典资源的作品」页 —— 详情页点标签 / 声优 / 社团**直达**。
     *
     * ## 为什么名字也放路由里
     *
     * 这一页要做到**自包含**：从详情页点一下就能进，不依赖搜索页的状态。
     * 而顶栏要显示"这是按什么筛的"，所以名字必须跟着走。
     *
     * [kind] 取值 `tag` / `va` / `circle`（与 [com.asmrone.app.data.model.SearchNamespaces]
     * 一致）。名字可能含空格与 `/`，所以调用方要用
     * [android.net.Uri.encode] 编过再拼。
     */
    const val DICTIONARY_RESULT = "dictionary/{kind}/{id}/{name}"
    fun dictionaryResult(kind: String, id: String, name: String) =
        "dictionary/$kind/${android.net.Uri.encode(id)}/${android.net.Uri.encode(name)}"

    const val LIBRARY = "library"
    const val SETTINGS = "settings"

    /**
     * 下载（本地已下载的作品）。
     *
     * 它是**顶级页签**：不依赖登录、不依赖网络，所以和首页一样常驻底栏。
     */
    const val DOWNLOADS = "downloads"

    const val DETAIL = "detail/{workId}"
    fun detail(workId: Int) = "detail/$workId"

    const val PLAYER = "player"

    /** 收藏夹详情（从收藏 → 列表点进） */
    const val PLAYLIST_DETAIL = "playlist/{playlistId}"
    fun playlist(playlistId: String) = "playlist/$playlistId"

    /** 歌词效果预览（独立演示页，与真实音频无关） */

    const val SETTINGS_SERVER = "settings/server"

    /** 播放设置（设置 → 播放）：后台保护等与"播放能不能持续"相关的开关 */
    const val SETTINGS_PLAYBACK = "settings/playback"
    const val SETTINGS_APPEARANCE = "settings/appearance"
    /** 液态玻璃参数页（模糊 / 折射 / 透明度 / 色散） */
    const val SETTINGS_GLASS = "settings/glass"
    const val SETTINGS_DATA = "settings/data"

    /** 缓存管理（设置 → 数据 → 缓存管理） */
    const val SETTINGS_CACHE = "settings/cache"
    const val SETTINGS_ABOUT = "settings/about"

    /** 账号管理（设置 → 账号） */
    const val SETTINGS_ACCOUNT = "settings/account"

    /** 登录 / 注册页 */
    const val LOGIN = "login"

    /** 显示底部导航栏的顶层路由 */
    val topLevel = setOf(HOME, SEARCH, DOWNLOADS, LIBRARY, SETTINGS)
}

/**
 * 导航图 —— 页面切换动画沿用 comicApp 的三种编排：
 *  - 普通页面：淡入淡出（300ms）
 *  - 详情页：左右滑动（进 spring、回 tween），有"层级推进"的感觉
 *  - 播放器：缩放进入（scale 0.92 → 1），像一层浮起来的界面
 *
 * @param pendingWorkToken 从外部链接带进来的作品线索（`RJ01700554` / 数字 id）。
 *   非空时导航到 [Routes.WORK_LINK] 去换 workId —— 冷启动（系统点了链接拉起 App）
 *   与热启动（App 已在后台）都由 `MainActivity` 塞进这个参数。
 * @param onWorkTokenConsumed 线索已经被受理，调用方应把它清空，否则重组时会再跳一次
 */
@Composable
fun AppNavGraph(
    navController: NavHostController,
    homeViewModel: HomeViewModel,
    searchViewModel: SearchViewModel,
    pendingWorkToken: String? = null,
    onWorkTokenConsumed: () -> Unit = {}
) {
    LaunchedEffect(pendingWorkToken) {
        val token = pendingWorkToken?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        navController.navigate(Routes.workLink(token)) { launchSingleTop = true }
        onWorkTokenConsumed()
    }

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        enterTransition = { fadeIn(tween(300)) },
        exitTransition = { fadeOut(tween(300)) },
        popEnterTransition = { fadeIn(tween(300)) },
        popExitTransition = { fadeOut(tween(300)) }
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                viewModel = homeViewModel,
                onWorkClick = { navController.navigate(Routes.detail(it)) }
            )
        }

        composable(Routes.SEARCH) {
            SearchScreen(
                viewModel = searchViewModel,
                // 搜索提交后进结果页。launchSingleTop 避免连点搜索堆出多层结果页。
                onOpenResults = {
                    navController.navigate(Routes.SEARCH_RESULT) {
                        launchSingleTop = true
                    }
                },
                // 搜索框里敲的是 RJ 号：不搜、也不进结果页，直接去换 workId 并打开作品
                onOpenRj = { rj -> navController.navigate(Routes.workLink(rj)) },
                onWorkClick = { navController.navigate(Routes.detail(it)) }
            )
        }

        // 搜索结果页：搜索提交之后跳进来的独立展示界面。
        // 与搜索页共用同一个 ViewModel（导航级持有），所以返回再进来结果还在。
        composable(
            route = Routes.SEARCH_RESULT,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = spring(dampingRatio = 0.85f, stiffness = 320f)
                ) + fadeIn(tween(250))
            },
            exitTransition = {
                slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) +
                    fadeOut(tween(250))
            },
            popEnterTransition = { fadeIn(tween(300)) },
            popExitTransition = {
                slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(320)) +
                    fadeOut(tween(300))
            }
        ) {
            SearchResultScreen(
                viewModel = searchViewModel,
                onBack = { navController.popBackStack() },
                onWorkClick = { navController.navigate(Routes.detail(it)) }
            )
        }

        // 「某一项字典资源的作品」页：详情页点标签 / 声优 / 社团**直达**，
        // 不经过搜索页（那一趟"先跳搜索页再触发搜索"是多余的）。
        composable(
            route = Routes.DICTIONARY_RESULT,
            arguments = listOf(
                navArgument("kind") { type = NavType.StringType },
                navArgument("id") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType }
            ),
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = spring(dampingRatio = 0.85f, stiffness = 320f)
                ) + fadeIn(tween(250))
            },
            exitTransition = {
                slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) +
                    fadeOut(tween(250))
            },
            popEnterTransition = { fadeIn(tween(300)) },
            popExitTransition = {
                slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(320)) +
                    fadeOut(tween(300))
            }
        ) { entry ->
            val kind = entry.arguments?.getString("kind").orEmpty()
            val id = entry.arguments?.getString("id").orEmpty()
            val name = entry.arguments?.getString("name").orEmpty()
            // 自包含：名字从路由里带过来；标签若在本地字典里能查到，
            // 就用字典里那份（它带**原始名**，多条件检索才需要对得上）
            val query = resolveDictionaryQuery(kind, id, name)
            if (query == null) {
                // 参数不合法（正常不会发生）——直接退回，别卡在一个空页面
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                DictionaryResultScreen(
                    query = query,
                    onBack = { navController.popBackStack() },
                    onWorkClick = { navController.navigate(Routes.detail(it)) }
                )
            }
        }

        composable(Routes.DOWNLOADS) {
            DownloadsScreen(
                onWorkClick = { navController.navigate(Routes.detail(it)) }
            )
        }

        composable(Routes.LIBRARY) {
            LibraryScreen(
                onWorkClick = { navController.navigate(Routes.detail(it)) },
                onOpenPlaylist = { navController.navigate(Routes.playlist(it.id)) }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onOpenServer = { navController.navigate(Routes.SETTINGS_SERVER) },
                onOpenPlayback = { navController.navigate(Routes.SETTINGS_PLAYBACK) },
                onOpenAppearance = { navController.navigate(Routes.SETTINGS_APPEARANCE) },
                onOpenData = { navController.navigate(Routes.SETTINGS_DATA) },
                onOpenAccount = { navController.navigate(Routes.SETTINGS_ACCOUNT) },
                onOpenAbout = { navController.navigate(Routes.SETTINGS_ABOUT) }
            )
        }

        composable(Routes.SETTINGS_ACCOUNT) {
            AccountSettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenLogin = { navController.navigate(Routes.LOGIN) }
            )
        }

        // 收藏夹详情：路由里只带 id，页面自己用 /api/playlist/get-playlist-metadata 取元数据
        composable(
            route = Routes.PLAYLIST_DETAIL,
            arguments = listOf(navArgument("playlistId") { type = NavType.StringType })
        ) { entry ->
            val playlistId = entry.arguments?.getString("playlistId") ?: ""
            PlaylistDetailScreen(
                playlistId = playlistId,
                onBack = { navController.popBackStack() },
                onWorkClick = { navController.navigate(Routes.detail(it)) }
            )
        }

        composable(Routes.LOGIN) {
            LoginScreen(
                onBack = { navController.popBackStack() },
                // 登录成功后回到账号页（登录页是从账号页进来的）
                onLoggedIn = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS_SERVER) {
            ServerSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_PLAYBACK) {
            PlaybackSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_APPEARANCE) {
            AppearanceSettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenGlass = { navController.navigate(Routes.SETTINGS_GLASS) }
            )
        }
        composable(Routes.SETTINGS_GLASS) {
            GlassSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_DATA) {
            DataSettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenCache = { navController.navigate(Routes.SETTINGS_CACHE) }
            )
        }
        composable(Routes.SETTINGS_CACHE) {
            CacheSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_ABOUT) {
            AboutSettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.WORK_LINK,
            arguments = listOf(navArgument("token") { type = NavType.StringType })
        ) { entry ->
            WorkLinkScreen(
                token = entry.arguments?.getString("token").orEmpty(),
                onResolved = { workId ->
                    // 把自己从返回栈里摘掉：从详情页返回时应该回到"点链接之前那一页"，
                    // 而不是回到这个纯转圈的中转页
                    navController.navigate(Routes.detail(workId)) {
                        popUpTo(Routes.WORK_LINK) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.DETAIL,
            arguments = listOf(navArgument("workId") { type = NavType.IntType }),
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = spring(dampingRatio = 0.85f, stiffness = 280f)
                ) + fadeIn(tween(250))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(300)
                ) + fadeOut(tween(250))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it / 3 },
                    animationSpec = tween(350)
                ) + fadeIn(tween(300))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(350)
                ) + fadeOut(tween(300))
            }
        ) { entry ->
            val workId = entry.arguments?.getInt("workId") ?: 0
            val detailViewModel: DetailViewModel = viewModel()
            DetailScreen(
                viewModel = detailViewModel,
                workId = workId,
                onBack = { navController.popBackStack() },
                onOpenPlayer = { navController.navigate(Routes.PLAYER) },
                // 点语言版本链接：跳到那个语言版本的详情页
                onOpenWork = { targetId -> navController.navigate(Routes.detail(targetId)) },
                // 点详情页的标签 / 声优 / 社团：**直达**这些资源的作品页。
                //
                // 以前是"切到搜索 tab + 把条件放进交接位"，等于让用户先走一遍
                // 搜索流程；现在结果页自包含，点一下就出结果。
                onOpenTag = { tagId, name ->
                    navController.navigate(
                        Routes.dictionaryResult(SearchNamespaces.TAG, tagId.toString(), name)
                    )
                },
                onOpenVoiceActor = { vaId, name ->
                    navController.navigate(
                        Routes.dictionaryResult(SearchNamespaces.VOICE_ACTOR, vaId, name)
                    )
                },
                onOpenCircle = { circleId, name ->
                    navController.navigate(
                        Routes.dictionaryResult(SearchNamespaces.CIRCLE, circleId.toString(), name)
                    )
                }
            )
        }

        composable(
            route = Routes.PLAYER,
            enterTransition = {
                scaleIn(
                    initialScale = 0.92f,
                    animationSpec = spring(dampingRatio = 0.85f, stiffness = 300f)
                ) + fadeIn(tween(250))
            },
            exitTransition = {
                scaleOut(targetScale = 0.92f, animationSpec = tween(300)) + fadeOut(tween(250))
            },
            popEnterTransition = {
                scaleIn(initialScale = 0.95f, animationSpec = tween(350)) + fadeIn(tween(300))
            },
            popExitTransition = {
                scaleOut(targetScale = 0.92f, animationSpec = tween(350)) + fadeOut(tween(300))
            }
        ) {
            PlayerScreen(onBack = { navController.popBackStack() })
        }
    }
}

/**
 * 路由参数 → 一次字典资源检索。
 *
 * ## 名字为什么优先用本地字典里的那份
 *
 * 路由里带的是**界面上显示的名字**（标签是中文译名，如 `亲热/甜蜜`）。
 * 而多条件检索串语法认的是**原始名**（`纯爱/甜蜜`），两者不同。
 *
 * 本页只按 id 查（`/api/tags/{id}/works`），所以名字其实不影响取数据；
 * 但这条检索会被**记进搜索历史**，而历史点开时会用它重建条件 ——
 * 那时多条件检索就需要原始名了。所以能从字典里查到就用字典那份，
 * 查不到（字典没更新过）就退回路由里带的显示名。
 */
private fun resolveDictionaryQuery(kind: String, id: String, name: String): DictionaryQuery? {
    if (id.isBlank()) return null
    return when (kind) {
        SearchNamespaces.TAG -> {
            val tagId = id.toIntOrNull() ?: return null
            AsmrRepository.cachedSearchTags().firstOrNull { it.id == tagId }
                ?.let { DictionaryQuery.ofTag(it) }
                ?: DictionaryQuery.ofTagItem(tagId, name)
        }

        SearchNamespaces.VOICE_ACTOR -> DictionaryQuery.ofVoiceActor(id, name)

        SearchNamespaces.CIRCLE -> {
            val circleId = id.toIntOrNull() ?: return null
            DictionaryQuery.ofCircle(circleId, name)
        }

        else -> null
    }
}
