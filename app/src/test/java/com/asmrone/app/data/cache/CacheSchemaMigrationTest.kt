package com.asmrone.app.data.cache

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 缓存结构升级的迁移规则。
 *
 * ## 为什么需要它
 *
 * 同步缓存是**条件请求**的本地副本：内容没变 → 304 → 不重写。
 * 所以给 `CachedWork` 加字段（这次是声优名）之后，旧 JSON 会一直缺字段，
 * 下拉刷新多少次都拿不到 —— 用户看到的就是"声优一栏永远是空的"。
 *
 * 迁移要同时做两件事：作废旧缓存 + 重置"今天已自动对齐"的记账。
 * 只做前者的话，当天自动同步会被闸门挡下，收藏页空白到第二天。
 */
class CacheSchemaMigrationTest {

    @Test
    fun `版本一致时不迁移`() {
        assertFalse(CacheFiles.shouldMigrate(stored = 2, current = 2, hasCacheFiles = true))
    }

    @Test
    fun `旧版本升级时迁移`() {
        assertTrue(CacheFiles.shouldMigrate(stored = 1, current = 2, hasCacheFiles = true))
    }

    @Test
    fun `★ 从未记录过版本但目录里已有缓存（旧版本升上来）也要迁移`() {
        // 首个带版本号的版本之前，用户设备上没有任何版本记录。
        // 如果这时不迁移，旧 JSON 会被忽略、而"今天已对齐"的记账还在 → 空白一天。
        assertTrue(CacheFiles.shouldMigrate(stored = 0, current = 2, hasCacheFiles = true))
    }

    @Test
    fun `全新安装不迁移`() {
        assertFalse(CacheFiles.shouldMigrate(stored = 0, current = 2, hasCacheFiles = false))
    }

    @Test
    fun `★ 应用启动时真的会执行迁移`() {
        val app = File("src/main/java/com/asmrone/app/AsmrOneApplication.kt").readText()
        assertTrue(
            "必须在 SyncCacheStore 之前校准版本",
            app.contains("CacheFiles.ensureSchema(this)")
        )
        assertTrue(
            "初始化顺序：AppPrefs → ensureSchema → SyncCacheStore",
            app.indexOf("AppPrefs.init(this)") < app.indexOf("CacheFiles.ensureSchema(this)") &&
                app.indexOf("CacheFiles.ensureSchema(this)") < app.indexOf("SyncCacheStore.init(this)")
        )
    }

    @Test
    fun `★ 待补写队列不跟着缓存版本一起作废`() {
        // 里面装的是"用户已表达、还没送达云端"的意图，不能因为缓存结构变化被清掉。
        val pending = File("src/main/java/com/asmrone/app/data/cache/PendingWrites.kt").readText()
        assertFalse(
            "不能再借用 CacheFiles.SCHEMA_VERSION",
            pending.contains("CacheFiles.SCHEMA_VERSION")
        )
        assertTrue("要有自己的版本号", pending.contains("FORMAT_VERSION"))
    }
}
