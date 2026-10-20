package com.asmrone.app.ui.screen.settings

import com.asmrone.app.data.cache.CacheManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「缓存管理」的守卫（源码 + 纯函数级断言）。
 *
 * ## 为什么这里必须钉死
 *
 * 收藏镜像和「用户已表达、云端还没收到」的待补写存在**同一个目录**
 * （`asmrone_sync_cache`），清镜像如果顺手用了整目录清理，删掉的就是
 * 用户刚点的收藏 / 取消收藏 —— 下次联网也不会补写了，因为本地根本没有记录。
 * 这类丢失无声无息（界面显示"已清除缓存"），只能靠测试拦。
 */
class CacheSettingsGuardTest {

    private val cacheManagerSource: String by lazy {
        File("src/main/java/com/asmrone/app/data/cache/CacheManager.kt").readText()
    }

    private val screenSource: String by lazy {
        File("src/main/java/com/asmrone/app/ui/screen/settings/CacheSettingsScreen.kt").readText()
    }

    @Test
    fun `清镜像绝不能用整目录清空`() {
        // 只看代码行：KDoc 里为了解释"为什么不用它"会提到这两个 API，不能算违规
        val code = cacheManagerSource.lines()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertFalse(
            "CacheFiles.clearAll 会连待补写一起删掉",
            code.contains("CacheFiles.clearAll(")
        )
        assertFalse(
            "SyncCacheStore.clearAll 同样会删待补写",
            code.contains("SyncCacheStore.clearAll(")
        )
    }

    @Test
    fun `待补写按 key 前缀被排除在清理之外`() {
        assertTrue(cacheManagerSource.contains("PENDING_PREFIX = \"pending|\""))
        assertTrue(
            "★ 清镜像后必须重置『今天已自动对齐』的记账，否则收藏页会空到第二天",
            cacheManagerSource.contains("AppPrefs.clearAutoSyncDays()")
        )
    }

    @Test
    fun `镜像判定只认收藏镜像，字典与待补写都不能算`() {
        assertFalse("待补写是用户还没同步的改动", CacheManager.isMirrorKey("pending|https://api.asmr.one|user"))
        assertTrue(CacheManager.isMirrorKey("collection|https://api.asmr.one|user|all"))
        assertTrue(CacheManager.isMirrorKey("playlists|https://api.asmr.one|user"))
        assertTrue(CacheManager.isMirrorKey("playlistWorks|https://api.asmr.one|user|abc"))
        assertTrue(CacheManager.isMirrorKey("playlistMeta|https://api.asmr.one|user|abc"))
        assertFalse(
            "搜索字典删了不会自己回来，不能当镜像清理",
            CacheManager.isMirrorKey("tag|https://api.asmr.one")
        )
        assertFalse(CacheManager.isMirrorKey("voiceActor|https://api.asmr.one"))
    }

    @Test
    fun `页面把每种缓存都列出来，且清除前要确认`() {
        CacheManager.Kind.entries.forEach { kind ->
            assertTrue(
                "CLEAR_ORDER 漏了 $kind，用户就看不到这一类占用",
                screenSource.contains("CacheManager.Kind.$kind")
            )
        }
        assertTrue("清除是破坏性操作，必须先确认", screenSource.contains("AlertDialog("))
        assertTrue(
            "待补写不能被静默清掉，界面要明确告知",
            screenSource.contains("还没同步到云端")
        )
    }

    @Test
    fun `数据页有进入缓存管理的入口`() {
        val dataSource = File("src/main/java/com/asmrone/app/ui/screen/settings/DataSettingsScreen.kt").readText()
        assertTrue(dataSource.contains("缓存管理"))
        assertTrue(dataSource.contains("onOpenCache"))
    }
}
