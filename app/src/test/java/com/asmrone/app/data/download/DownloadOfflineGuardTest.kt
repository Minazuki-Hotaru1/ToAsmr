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
        val local = body.indexOf("DownloadStore.load(")
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
        val local = body.indexOf("DownloadStore.load(")
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
        val local = body.indexOf("DownloadStore.localFileFor(")
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
        assertTrue("LyricsLoader 没有查本地歌词文件", body.contains("DownloadStore.localFileFor("))
        assertTrue("本地歌词要按字节读出来再交给 TextDecoder", body.contains(".readBytes()"))
    }

    // ─────────────────────────── 下载器 ───────────────────────────

    @Test
    fun `★ 每下载完一个文件就写一次清单`() {
        val body = bodyOf(manager, "private suspend fun runDownload", 5000)
        val loop = body.indexOf("leaves.forEach {")
        val save = body.indexOf("DownloadStore.save(")
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

    @Test
    fun `下载入口是文字按钮，不是图标`() {
        val body = bodyOf(detailScreen, "private fun TrackTreeHeader(", 2000)
        assertTrue("文件区表头没有下载按钮", body.contains("onDownload"))
        assertFalse(
            "按需求先做文字按钮，别引入图标资源",
            body.contains("Icon(")
        )
    }
}
