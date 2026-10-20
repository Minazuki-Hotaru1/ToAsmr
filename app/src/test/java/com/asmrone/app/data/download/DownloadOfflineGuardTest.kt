package com.asmrone.app.data.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 下载功能"离线优先"的接线守卫。
 *
 * ## 为什么要守这些
 *
 * 下载的全部意义就是**离线可用**。而离线优先这件事在代码里处处表现为
 * "某一个查找必须排在联网之前" —— 顺序写反了，编译器不会说话，
 * 单测也跑不到（那条路径要先有网络失败的现场），但在用户手机上就是
 * "明明下载了，进详情页转圈 / 断网就什么都看不了"。
 *
 * 所以按本工程既有做法（`PlaybackIntegrationGuardTest` 等）用源码级断言
 * 把这些顺序钉住。
 */
class DownloadOfflineGuardTest {

    private fun src(path: String): String =
        File("src/main/java/com/asmrone/app/$path").readText()

    private val repository by lazy { src("data/repo/AsmrRepository.kt") }
    private val controller by lazy { src("player/AudioController.kt") }
    private val lyrics by lazy { src("data/lyrics/LyricsLoader.kt") }
    private val manager by lazy { src("data/download/DownloadManager.kt") }
    private val navigation by lazy { src("ui/navigation/AppNavigation.kt") }
    private val activity by lazy { src("MainActivity.kt") }
    private val application by lazy { src("AsmrOneApplication.kt") }
    private val detailScreen by lazy { src("ui/screen/detail/DetailScreen.kt") }
    private val detailViewModel by lazy { src("ui/screen/detail/DetailViewModel.kt") }

    /** 取 [marker] 之后 [chars] 个字符，用来只看某个函数体内发生了什么 */
    private fun bodyOf(source: String, marker: String, chars: Int = 2600): String {
        val at = source.indexOf(marker)
        assertTrue("源码里找不到「$marker」——它被改名或删掉了，守卫需要同步更新", at >= 0)
        return source.substring(at, (at + chars).coerceAtMost(source.length))
    }

    // ─────────────────────────── 仓库：已下载就走本地 ───────────────────────────

    @Test
    fun `★ getWorkMeta 命中下载清单时直接返回，不联网`() {
        val body = bodyOf(repository, "suspend fun getWorkMeta(")
        val local = body.indexOf("DownloadSources.manifest(")
        assertTrue("getWorkMeta 没有查下载清单", local >= 0)
        // 第一次联网必须在本地检查之后
        val network = body.indexOf("api.getWorkJson(")
        assertTrue("getWorkMeta 里找不到联网取元数据", network >= 0)
        assertTrue(
            "本地清单必须在联网之前查：否则下载过的作品在断网时打不开",
            local < network
        )
        assertTrue(
            "已下载作品的元数据要走 Work.fromJson(原始 JSON)，不能另写一份解析",
            body.contains("Work.fromJson(")
        )
    }

    @Test
    fun `★ getTracks 命中下载清单时直接返回，不联网`() {
        val body = bodyOf(repository, "suspend fun getTracks(")
        val local = body.indexOf("DownloadSources.manifest(")
        val network = body.indexOf("TrackTreeStore.save(")
        assertTrue("getTracks 没有查下载清单", local >= 0)
        assertTrue(
            "本地清单必须在落盘/联网之前查",
            network >= 0 && local < network
        )
    }

    @Test
    fun `★ 播放已下载的音频时不走本地代理`() {
        val body = bodyOf(controller, "private fun startCurrent()", 4200)
        val local = body.indexOf("DownloadSources.localFileFor(")
        val proxy = body.indexOf("playbackUrlFor(")
        assertTrue("startCurrent 没有查本地下载文件", local >= 0)
        assertTrue("startCurrent 里找不到代理地址构造", proxy >= 0)
        assertTrue(
            "本地文件判断必须排在代理之前：否则下载过的音频还在走网络代理",
            local < proxy
        )
        assertTrue(
            "本地文件要直接交给播放内核（setDataSource 绝对路径）",
            body.contains("localFile.absolutePath")
        )
    }

    @Test
    fun `★ 已下载的歌词文件从本地读`() {
        val body = bodyOf(lyrics, "suspend fun load(", 3200)
        assertTrue(
            "LyricsLoader 没有查本地歌词文件",
            body.contains("DownloadSources.localFileFor(")
        )
        assertTrue("本地歌词要按字节读出来再交给 TextDecoder", body.contains(".readBytes()"))
    }

    // ─────────────────────────── 下载器 ───────────────────────────

    @Test
    fun `★ 每下载完一个文件就写一次清单`() {
        val body = bodyOf(manager, "private suspend fun runDownload", 5000)
        val loop = body.indexOf("leaves.forEach {")
        val save = body.indexOf("saveManifest(manifest)")
        assertTrue("runDownload 里找不到逐个文件下载的循环", loop >= 0)
        assertTrue(
            "每完成一个文件就要落一次清单：否则中途退出/被杀，已下好的文件在界面上不算数",
            save > loop
        )
    }

    @Test
    fun `取消下载会保留 part 文件（可续传）`() {
        val api = src("data/remote/AsmrOneApi.kt")
        val body = bodyOf(api, "suspend fun downloadToFile(", 4000)
        assertTrue("取消要用 CancellationException，否则会被当成失败重试", body.contains("CancellationException"))
        assertTrue("续传要发 Range 头", body.contains("Range"))
        assertTrue("要有 .part → 正式文件的重命名", body.contains("renameTo("))
    }

    // ─────────────────────────── 导航与入口 ───────────────────────────

    @Test
    fun `★ 下载页是顶级页签`() {
        assertTrue(
            "Routes 里缺 DOWNLOADS",
            navigation.contains("const val DOWNLOADS")
        )
        val topLevel = bodyOf(navigation, "val topLevel = setOf(", 200)
        assertTrue(
            "DOWNLOADS 必须进 topLevel，否则底部导航栏在下载页会消失",
            topLevel.contains("DOWNLOADS")
        )
        assertTrue(
            "导航图里缺 DownloadsScreen 的 composable",
            navigation.contains("composable(Routes.DOWNLOADS)")
        )
    }

    @Test
    fun `★ 底部导航栏有下载入口`() {
        val items = bodyOf(activity, "private val bottomNavItems = listOf(", 900)
        assertTrue("底栏没有下载页签", items.contains("Routes.DOWNLOADS"))
        // 2026-10 修正：原先借用「带勾的文件夹」，在底栏里和收藏夹/本地文件分不清
        assertTrue("下载页签用 Fluent arrow-download", items.contains("ic_arrow_download_fluent"))
        assertFalse("不要再借用文件夹图标", items.contains("ic_folder_check_fluent"))
    }

    @Test
    fun `★ 应用启动时就初始化下载存储`() {
        val body = bodyOf(application, "override fun onCreate()", 4000)
        val store = body.indexOf("DownloadStore.init(")
        val managerInit = body.indexOf("DownloadManager.init(")
        assertTrue(
            "DownloadStore 必须在 onCreate 里初始化：首个组合就调用它，晚了会以为本地没有而联网",
            store >= 0
        )
        assertTrue("DownloadManager 要预热已下载列表", managerInit >= 0)
    }

    @Test
    fun `★ 详情页的文件树只显示已下载内容，选择器拿完整树`() {
        assertTrue(
            "详情页要按下载清单过滤展示树",
            detailViewModel.contains("filterTreeTo(")
        )
        val sheet = bodyOf(detailScreen, "DownloadPickerSheet(", 500)
        assertTrue("选择器要收完整树（pickerTree），否则没下载的内容选不到", sheet.contains("tree = state.pickerTree"))
        assertTrue("选择器要知道哪些已下载", sheet.contains("downloadedPaths = state.downloadedPaths"))
    }

    private val pickerSheet by lazy { src("ui/screen/download/DownloadPickerSheet.kt") }

    @Test
    fun `下载入口收进顶栏胶囊，用图标而不是文字按钮`() {
        // 2026-10 需求：详情页的动作统一放顶栏，「下载」不再占用「文件」小标题行的右侧。
        // 这里同时钉住"搬到了哪里"和"文件区表头不再有文字按钮"。
        val actions = bodyOf(detailScreen, "private fun DetailTopActions(", 2000)
        assertTrue("顶栏胶囊里要有下载动作", actions.contains("onDownload"))
        assertTrue("顶栏下载用 Fluent 的 arrow-download 图标", actions.contains("ic_arrow_download_fluent"))

        val header = bodyOf(detailScreen, "private fun TrackTreeHeader(", 2000)
        assertFalse("文件区表头不应再出现下载回调", header.contains("onDownload"))
        assertFalse("也不应再有「下载 / 继续下载」的文字按钮", header.contains("继续下载"))
    }

    @Test
    fun `下载选择器滚到顶不会误关整个弹窗`() {
        // 2026-10 修正：列表滚回顶部后继续拖动时，剩余位移会冒泡到 ModalBottomSheet，
        // 被当成"下拉收起"，导致整个选择器被关掉。守卫钉住那段 nestedScroll 拦截。
        val guard = bodyOf(pickerSheet, "val blockPullToDismiss =", 1400)
        assertTrue("要拦截「已到顶 + 仍向下拖」的剩余位移", guard.contains("canScrollBackward"))
        assertTrue("位移之外还要吃掉惯性", guard.contains("onPostFling"))
        val list = bodyOf(pickerSheet, "LazyColumn(", 600)
        assertTrue("拦截器必须挂到列表上才会生效", list.contains("nestedScroll(blockPullToDismiss)"))
        assertTrue("列表要拿同一个 state 才能判断是否到顶", list.contains("state = listState"))
    }

    @Test
    fun `底栏顺序是收藏在前、下载在后`() {
        val items = bodyOf(activity, "private val bottomNavItems = listOf(", 900)
        val library = items.indexOf("Routes.LIBRARY")
        val downloads = items.indexOf("Routes.DOWNLOADS")
        assertTrue("底栏里找不到收藏页签", library >= 0)
        assertTrue("底栏里找不到下载页签", downloads >= 0)
        assertTrue("2026-10 需求：收藏在下载左边", library < downloads)
    }

    // ─────────────────────── 下载选择器的交互（2026-10 需求） ───────────────────────

    @Test
    fun `选择器默认全部收起`() {
        // 一进来就铺开几百行，用户根本不知道这个作品有几个文件夹；先收起，
        // 由用户决定展开哪个。（旧默认是全部展开）
        val body = bodyOf(pickerSheet, "var expanded by remember(tree)", 200)
        assertTrue("默认展开集合必须是空的", body.contains("mutableStateOf(emptySet<String>())"))
        assertFalse("不能再用 collectFolderPaths(...) 当初始值", body.contains("collectFolderPaths(tree"))
    }

    @Test
    fun `文件夹点行只展开，勾选框才选中`() {
        // 大目录里找文件时，点行就勾上整目录 = 一下子拖下 200 个文件。
        // 行点击负责展开，选择只有勾选框一条路。
        val rowClick = bodyOf(pickerSheet, ".clickable(enabled = rowClickable)", 200)
        assertTrue("文件夹点行要展开收起", rowClick.contains("toggleFolder(row)"))
        assertTrue("文件点行才是选择", rowClick.contains("toggleSelection(row)"))

        val checkbox = bodyOf(pickerSheet, "PickerCheckbox(", 400)
        assertTrue("勾选框负责选择", checkbox.contains("onClick = { toggleSelection(row) }"))
        assertFalse("行点击不能再调 toggleSelection 选文件夹", rowClick.contains("if (isFolder) toggleSelection"))
    }

    @Test
    fun `选择器按层级缩进，展开有动画`() {
        // 没有缩进时子项和父文件夹左边缘对齐，看不出谁属于谁。
        val row = bodyOf(pickerSheet, ".animateItem(", 400)
        assertTrue("要按 depth 缩进", row.contains("row.depth"))
        assertTrue("展开/收起要淡入并让位", row.contains("fadeInSpec") && row.contains("placementSpec"))
        assertTrue(
            "箭头旋转要有过渡",
            pickerSheet.contains("animateFloatAsState")
        )
    }

    @Test
    fun `全部展开与全部收起的文案和动作一致`() {
        // 旧实现用 expanded.isEmpty() 判文案：展开其中一个文件夹后，
        // 文案变成「全部收起」，点下去做的却是全展开 —— 正好相反。
        val body = bodyOf(pickerSheet, "val allExpanded =", 200)
        assertTrue("要用「是否真的全展开」来判断", body.contains("expanded.containsAll(allFolderPaths)"))

        val button = bodyOf(pickerSheet, "expanded = if (allExpanded)", 300)
        assertTrue("全展开时才清空", button.contains("emptySet()"))
        assertTrue("否则全部展开", button.contains("allFolderPaths.toHashSet()"))
        assertTrue("文案同样按 allExpanded 取", bodyOf(pickerSheet, "if (allExpanded) \"全部收起\"", 120)
            .contains("\"全部收起\""))
    }

    @Test
    fun `选择器不再有底部说明文字`() {
        // 2026-10 需求：底部那块灰色说明（共 xxx。下载后可离线播放…）删掉，
        // 弹窗底部直接就是列表，不再留一个占位的 summary item。
        assertFalse("底部说明已删除", pickerSheet.contains("下载后可离线播放"))
        assertFalse("不再引用 trackTreeSummary", pickerSheet.contains("trackTreeSummary"))
        assertFalse("不应该再有 summary 占位 item", pickerSheet.contains("item(key = \"summary\")"))
    }
}
