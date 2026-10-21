package com.asmrone.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「保存到本地」这套逻辑的源码级守卫。
 *
 * 这些约束都有过反例（都是**真的踩过**的错误做法），改代码时很容易不知不觉改回去：
 * - 只跳 `permissionIntent`：API 28/29 上设置页里没有那个开关，用户永远授权不了；
 * - 跳设置页不做兜底：部分 ROM 不认那个 Intent，点了没反应且没有任何提示；
 * - 按目录名解析 workId：目录名里放的是真 RJ 号，索引会登记到别的 id 上；
 * - 删下载时一次删两边：删「App 内」把公共目录里的文件也删了；
 * - 清单不标 LOCAL：下载页把同一份内容列成两张卡。
 */
class LocalStorageGuardTest {

    private fun source(relative: String): String =
        File("src/main/java/com/asmrone/app/$relative").readText()

    private fun body(relative: String, signature: String, length: Int = 2200): String {
        val text = source(relative)
        val start = text.indexOf(signature)
        assertTrue("找不到入口：$signature", start >= 0)
        return text.substring(start, minOf(start + length, text.length))
    }

    @Test
    fun `授权走同一个入口，不直接跳设置页`() {
        val helper = source("ui/component/LocalStoragePermission.kt")
        // 两条路都要在：API 30+ 跳设置页，API 28/29 弹运行时权限
        assertTrue(helper.contains("requiresRuntimePermission()"))
        assertTrue(helper.contains("runtimeLauncher.launch("))
        // API 30+ 的入口要带兜底：逐个候选 Intent 试，全失败就明确告诉用户去手动开
        assertTrue(helper.contains("tryOpenPermissionSettings(context)"))
        assertTrue(helper.contains("currentOnFailure()"))

        val store = source("data/download/LocalDownloadStore.kt")
        val chain = body("data/download/LocalDownloadStore.kt", "fun tryOpenPermissionSettings(", 900)
        listOf(
            "Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION",
            "Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION",
            "Settings.ACTION_APPLICATION_DETAILS_SETTINGS"
        ).forEach { action ->
            assertTrue("$action 应在兜底链里", chain.contains(action))
        }
        assertTrue("每个候选都要 runCatching", chain.contains(".isSuccess"))
        assertTrue(store.contains("FLAG_ACTIVITY_NEW_TASK"))

        listOf(
            "ui/screen/download/DownloadsScreen.kt",
            "ui/screen/download/DownloadPickerSheet.kt",
            "ui/screen/settings/DataSettingsScreen.kt"
        ).forEach { file ->
            val text = source(file)
            assertTrue("$file 应使用 rememberLocalStorageRequester", text.contains("rememberLocalStorageRequester"))
            // 这三个界面里不该再自己拼 Intent（各写一遍就会漏掉运行时权限那条路）
            assertTrue("$file 不该直接 startActivity(permissionIntent)", !text.contains("startActivity(LocalDownloadStore.permissionIntent"))
        }
    }

    @Test
    fun `扫描时用清单里的 workId 认目录`() {
        val body = body("data/download/LocalDownloadStore.kt", "suspend fun rescan()", 900)
        val manifestId = body.indexOf("manifest.workId")
        val parsedId = body.indexOf("parseWorkId(dir.name)")
        assertTrue("rescan 应按清单 workId 索引", manifestId >= 0)
        assertTrue("按名字解析只能是退路", parsedId > manifestId)
    }

    @Test
    fun `写入公共目录的清单一律标成本地`() {
        val body = body("data/download/LocalDownloadStore.kt", "suspend fun save(", 400)
        assertTrue(body.contains("copy(storage = DownloadStorage.LOCAL)"))
    }

    @Test
    fun `播放与合并路径都先问本地那一档`() {
        val sources = source("data/download/DownloadSources.kt")
        assertTrue(sources.contains("LocalDownloadStore.localFileFor("))
        assertTrue(sources.contains("LocalDownloadStore.load("))
    }

    @Test
    fun `删除只碰 App 内那份，公共目录交给文件管理器`() {
        val body = body("data/download/DownloadManager.kt", "fun remove(workId: Int)", 600)
        assertTrue(body.contains("DownloadStore.remove(workId)"))
        // 本地那一档不由 App 管理：这里不能出现删公共目录的调用
        assertTrue(!body.contains("LocalDownloadStore.remove("))
    }

    @Test
    fun `下载页只列 App 内那份`() {
        val body = body("data/download/DownloadManager.kt", "fun refreshLibrary()", 600)
        assertTrue(body.contains("DownloadStore.loadAll()"))
        assertTrue(!body.contains("LocalDownloadStore.loadAll()"))
    }

    @Test
    fun `本地目录里已有的文件不再重复下载`() {
        val body = body("data/download/DownloadManager.kt", "fun existingFile(", 500)
        assertTrue(body.contains("LocalDownloadStore.existingFileAt("))
        // 查重不能只信清单/索引：用户自己放进去的文件、清单被删的情况都要算已存在
        val store = body("data/download/LocalDownloadStore.kt", "fun existingFileAt(", 900)
        assertTrue(store.contains("findDir(workId)"))
        assertTrue(store.contains("sanitizeRelative(path)"))
    }

    @Test
    fun `本地那一档保留原来的目录层级与文件名`() {
        // fileFor 必须按树内路径拼相对路径（而不是一律摊平到作品目录里）
        val body = body("data/download/LocalDownloadStore.kt", "fun fileFor(", 900)
        assertTrue(body.contains("sanitizeRelative(path)"))
        // 下载器落盘时把标题与 RJ 号一并传下去（目录名要带 RJ）
        val manager = source("data/download/DownloadManager.kt")
        assertTrue(manager.contains("LocalDownloadStore.fileFor(work.id, work.title, path, work.sourceId)"))
        assertTrue(manager.contains("LocalDownloadStore.save(m, work.title, work.sourceId)"))
    }

    @Test
    fun `要写本地文件夹的每一处都先问权限，失败也要看得见`() {
        // 入队前拦一道：授权回来再补这一次入队（否则请求过去了、文件一个也没落）
        val detail = source("ui/screen/detail/DetailViewModel.kt")
        assertTrue(
            detail.contains("if (storage == DownloadStorage.LOCAL && !DownloadManager.canWriteLocal())")
        )
        assertTrue(detail.contains("enqueueDownload(work, tree, pending.paths, DownloadStorage.LOCAL)"))

        // 失败的任务必须留在下载页 —— 失败原因只在那里能看见
        val downloads = source("ui/screen/download/DownloadsScreen.kt")
        assertTrue(downloads.contains("it.isActive || it.status == DownloadManager.Status.FAILED"))
        assertTrue("失败行要有移除按钮", downloads.contains("TextButton(onClick = onDismiss)"))

        // 另存这一步也要检查：权限可能在下载完之后被关掉
        val copy = body("data/download/DownloadManager.kt", "fun copyToLocal(", 400)
        assertTrue(copy.contains("canWriteLocal()"))
        assertTrue(copy.contains("没有存储权限"))

        // 失败/取消的任务要能清掉，但在跑的不许被清
        val dismiss = body("data/download/DownloadManager.kt", "fun dismiss(", 300)
        assertTrue(dismiss.contains("isActive"))
    }
}
