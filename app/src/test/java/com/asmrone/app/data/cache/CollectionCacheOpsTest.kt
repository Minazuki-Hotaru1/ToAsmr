package com.asmrone.app.data.cache

import com.asmrone.app.data.model.CollectionStatus
import com.asmrone.app.data.model.Work
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「标记」列表本地增删的测试。
 *
 * ## 对应的需求
 *
 * 点完标记/取消标记后，收藏应当**立刻**显示变化 —— 这是本地写入的结果，
 * 不该等下一次云端核对。实现方式是直接改本地缓存里的列表，
 * 而"怎么改"这件事全在 [CollectionCacheOps.apply] 里，所以重点测它。
 *
 * 之前的行为是：只有内存快照非空时才改缓存，而快照只在"进过收藏"后才有 ——
 * 于是从首页直接进详情页标记、再回收藏就看不到新内容。
 */
class CollectionCacheOpsTest {

    /**
     * 注意：`collectionStatus` 是**派生属性**（由 `progress` 解析而来），
     * 所以这里通过 `progress` 构造。
     */
    private fun work(id: Int, status: CollectionStatus? = null) = Work(
        id = id,
        title = "作品 $id",
        progress = status?.apiValue
    )

    // ───────────────────────── 「全部」列表 ─────────────────────────

    @Test
    fun `新标记的作品要插到全部列表最前面`() {
        val existing = listOf(work(1), work(2))
        val updated = CollectionCacheOps.apply(
            items = existing,
            filter = null,
            work = work(99, CollectionStatus.LISTENING),
            remove = false
        )
        assertEquals("新加的要排最前（与服务端 order=updated_at desc 一致）", 99, updated.first().id)
        assertEquals("原有条目不能丢", 3, updated.size)
        assertEquals(listOf(99, 1, 2), updated.map { it.id })
    }

    @Test
    fun `已在列表里的作品改状态是原地更新而不是重复插入`() {
        val existing = listOf(work(1, CollectionStatus.MARKED), work(2))
        val updated = CollectionCacheOps.apply(
            items = existing,
            filter = null,
            work = work(1, CollectionStatus.LISTENED),
            remove = false
        )
        assertEquals("不能变成两条", 2, updated.size)
        assertEquals(1, updated.count { it.id == 1 })
        assertEquals(
            "状态要更新成新值",
            CollectionStatus.LISTENED,
            updated.first { it.id == 1 }.collectionStatus
        )
    }

    @Test
    fun `移除标记后该作品从全部列表消失`() {
        val existing = listOf(work(1, CollectionStatus.MARKED), work(2))
        val updated = CollectionCacheOps.apply(
            items = existing,
            filter = null,
            work = work(1),
            remove = true
        )
        assertEquals(listOf(2), updated.map { it.id })
    }

    // ───────────────────────── 状态筛选列表 ─────────────────────────

    @Test
    fun `在听列表里改成听过之后就不再属于这个列表`() {
        val existing = listOf(work(1, CollectionStatus.LISTENING), work(2, CollectionStatus.LISTENING))
        val updated = CollectionCacheOps.apply(
            items = existing,
            filter = CollectionStatus.LISTENING,
            work = work(1, CollectionStatus.LISTENED),
            remove = false
        )
        assertEquals("状态变了就不该留在「在听」里", listOf(2), updated.map { it.id })
    }

    @Test
    fun `在听列表里新标记为在听的要加进来`() {
        val existing = listOf(work(2, CollectionStatus.LISTENING))
        val updated = CollectionCacheOps.apply(
            items = existing,
            filter = CollectionStatus.LISTENING,
            work = work(1, CollectionStatus.LISTENING),
            remove = false
        )
        assertEquals(listOf(1, 2), updated.map { it.id })
    }

    @Test
    fun `听过列表不会因为标记成在听而被污染`() {
        val existing = listOf(work(2, CollectionStatus.LISTENED))
        val updated = CollectionCacheOps.apply(
            items = existing,
            filter = CollectionStatus.LISTENED,
            work = work(1, CollectionStatus.LISTENING),
            remove = false
        )
        assertEquals("状态不匹配就不该插入「听过」", listOf(2), updated.map { it.id })
    }

    @Test
    fun `移除标记时从所有状态列表都会消失`() {
        val existing = listOf(work(1, CollectionStatus.LISTENING), work(2, CollectionStatus.LISTENING))
        val updated = CollectionCacheOps.apply(
            items = existing,
            filter = CollectionStatus.LISTENING,
            work = work(1, CollectionStatus.LISTENING),
            remove = true
        )
        assertEquals(listOf(2), updated.map { it.id })
    }

    // ───────────────────────── 边界 ─────────────────────────

    @Test
    fun `空列表上标记会得到一个元素的列表`() {
        val updated = CollectionCacheOps.apply(
            items = emptyList(),
            filter = null,
            work = work(7, CollectionStatus.MARKED),
            remove = false
        )
        assertEquals(listOf(7), updated.map { it.id })
    }

    @Test
    fun `空列表上移除不会崩`() {
        val updated = CollectionCacheOps.apply(
            items = emptyList(),
            filter = null,
            work = work(7),
            remove = true
        )
        assertTrue(updated.isEmpty())
    }

    @Test
    fun `去重保留首次出现的顺序`() {
        val deduped = CollectionCacheOps.dedupe(listOf(work(3), work(1), work(3), work(1), work(2)))
        assertEquals(listOf(3, 1, 2), deduped.map { it.id })
    }

    @Test
    fun `重复标记同一部作品不会产生两条`() {
        var items = emptyList<Work>()
        repeat(3) {
            items = CollectionCacheOps.apply(items, null, work(5, CollectionStatus.MARKED), remove = false)
        }
        assertEquals(1, items.size)
        assertEquals(5, items.first().id)
    }

    // ═══════════════ 与云端结果合并（本地优先，云端用来对齐）═══════════════
    //
    // 这是"不再把云端当唯一真理"的落点：云端给的列表用来对齐，
    // 但用户还没送达云端的意图不能被它抹掉。

    @Test
    fun `没有待同步项时完全听云端的`() {
        val resolved = CollectionCacheOps.resolveAgainstCloud(
            remote = listOf(work(1), work(2)),
            local = listOf(work(9), work(1), work(2)),
            pendingAdds = emptySet(),
            pendingRemoves = emptySet()
        )
        assertEquals(
            "本地多出来的（云端没有的）应当消失 —— 用户可能在网页端删过",
            listOf(1, 2),
            resolved.map { it.id }
        )
    }

    @Test
    fun `待上传的作品在云端没有时不能被抹掉`() {
        // 用户刚加的：本地有、云端（还没收到）没有
        val resolved = CollectionCacheOps.resolveAgainstCloud(
            remote = listOf(work(1)),
            local = listOf(work(9, CollectionStatus.LISTENING), work(1)),
            pendingAdds = setOf(9),
            pendingRemoves = emptySet()
        )
        assertEquals("刚加的那条必须还在", listOf(1, 9), resolved.map { it.id })
    }

    @Test
    fun `待上传的作品云端已有旧值时保留本地那份`() {
        // 用户在 App 里把状态从"想听"改成"在听"，但那次请求失败了；
        // 云端于是还是旧状态 —— 对齐时必须保留本地的新状态
        val resolved = CollectionCacheOps.resolveAgainstCloud(
            remote = listOf(work(1, CollectionStatus.MARKED)),
            local = listOf(work(1, CollectionStatus.LISTENING)),
            pendingAdds = setOf(1),
            pendingRemoves = emptySet()
        )
        assertEquals(1, resolved.size)
        assertEquals(
            "★ 本地意图优先：不能被云端的旧状态覆盖回去",
            CollectionStatus.LISTENING,
            resolved.first().collectionStatus
        )
    }

    @Test
    fun `待删除的作品即便云端还返回也不能出现`() {
        // 用户在 App 里删了，但删除请求失败；云端仍然有它
        val resolved = CollectionCacheOps.resolveAgainstCloud(
            remote = listOf(work(1), work(2)),
            local = listOf(work(2)),
            pendingAdds = emptySet(),
            pendingRemoves = setOf(1)
        )
        assertEquals(
            "★ 用户已经删了，云端还没收到 —— 界面上就不该再出现",
            listOf(2),
            resolved.map { it.id }
        )
    }

    @Test
    fun `待上传与待删除同时存在时各自生效`() {
        val resolved = CollectionCacheOps.resolveAgainstCloud(
            remote = listOf(work(1), work(2)),
            local = listOf(work(2), work(3, CollectionStatus.MARKED)),
            pendingAdds = setOf(3),
            pendingRemoves = setOf(1)
        )
        assertEquals(listOf(2, 3), resolved.map { it.id })
    }

    @Test
    fun `云端顺序被保留`() {
        val resolved = CollectionCacheOps.resolveAgainstCloud(
            remote = listOf(work(3), work(1), work(2)),
            local = listOf(work(3), work(1), work(2)),
            pendingAdds = emptySet(),
            pendingRemoves = emptySet()
        )
        assertEquals(
            "云端是权威顺序（按最近更新倒序），不要重排",
            listOf(3, 1, 2),
            resolved.map { it.id }
        )
    }

    @Test
    fun `云端返回空的而本地有待上传时仍保留待上传的那条`() {
        // 极端情况：网页端把收藏清空了，同时用户在 App 里刚加了一条（没送成功）
        val resolved = CollectionCacheOps.resolveAgainstCloud(
            remote = emptyList(),
            local = listOf(work(7, CollectionStatus.MARKED)),
            pendingAdds = setOf(7),
            pendingRemoves = emptySet()
        )
        assertEquals(listOf(7), resolved.map { it.id })
    }

    @Test
    fun `待上传但本地也找不到该条目时安全忽略`() {
        val resolved = CollectionCacheOps.resolveAgainstCloud(
            remote = listOf(work(1)),
            local = listOf(work(1)),
            pendingAdds = setOf(999),
            pendingRemoves = emptySet()
        )
        assertEquals("没有内容可补，不该凭空造一条", listOf(1), resolved.map { it.id })
    }

    @Test
    fun `合并结果不含重复 id`() {
        val resolved = CollectionCacheOps.resolveAgainstCloud(
            remote = listOf(work(1), work(1)),
            local = listOf(work(1)),
            pendingAdds = setOf(1),
            pendingRemoves = emptySet()
        )
        assertEquals(listOf(1), resolved.map { it.id })
    }
}
