package com.asmrone.app.data.cache

import com.asmrone.app.data.model.Work
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「加入 / 移出收藏夹」对本地内容缓存的影响 —— **行为测试**。
 *
 * 这组用例替代了原先那批源码级断言（"某个兜底方法必须存在"）。
 * 后者锁定的是实现方式，重构时必须连测试一起改，而且证明不了行为对不对；
 * 现在被测的是同一份决策逻辑的**输入输出**。
 *
 * 覆盖的是 R1 那条最容易出事的规则：
 * **本地没有基线时，`totalCount` 必须取自收藏夹列表里的件数，不能写成 0 或 1。**
 * 写小了会让详情页的 `hasMore`（`size < totalCount`）立刻变 false，
 * 那个收藏夹的「加载更多」就永久失效。
 */
class PlaylistPatchOpsTest {

    private fun work(id: Int, title: String = "作品$id") =
        Work(id = id, title = title, circleName = "社团$id", durationSec = id * 60)

    private fun page(vararg ids: Int, total: Int, loadedPages: Int = 1) =
        CachedWorkPage(
            items = ids.map { CachedWork.fromWork(work(it)) },
            totalCount = total,
            loadedPages = loadedPages
        )

    // ═══════════════ 没有本地基线（从没打开过这个收藏夹） ═══════════════

    @Test
    fun `★ 无基线时加入：总数取自列表件数，不是 1`() {
        // 收藏夹实际有 200 部，本地从没拉过。加一部之后若是 totalCount = 1，
        // 详情页 hasMore 立刻为 false，「加载更多」就永久失效了。
        val plan = PlaylistPatchOps.compute(
            current = null,
            baselineTotal = 200,
            workId = 50,
            work = work(50),
            remove = false
        )
        assertNotNull("应当产生一份内容缓存（否则用户看不到刚加的作品）", plan)
        val content = plan!!.content
        assertNotNull(content)
        assertEquals("只装这一条", 1, content!!.items.size)
        assertEquals(50, content.items.single().id)
        assertEquals("总数必须是 201，而不是 1", 201, content.totalCount)
        assertEquals("件数 +1", 1, plan.countDelta)
    }

    @Test
    fun `★ 无基线时加入：作品插在最前面（与云端"最新加入"的顺序一致）`() {
        val plan = PlaylistPatchOps.compute(
            current = null,
            baselineTotal = 0,
            workId = 7,
            work = work(7),
            remove = false
        )
        assertEquals(7, plan!!.content!!.items.first().id)
        assertEquals(1, plan.content.totalCount)
    }

    @Test
    fun `★ 无基线时移出：不写内容缓存，只改件数`() {
        // 本地本来就没有这份内容。写一份空列表等于宣称"这个收藏夹是空的"。
        val plan = PlaylistPatchOps.compute(
            current = null,
            baselineTotal = 42,
            workId = 9,
            work = null,
            remove = true
        )
        assertNotNull("件数仍然要减", plan)
        assertNull("绝不凭空写一份空内容缓存", plan!!.content)
        assertEquals(-1, plan.countDelta)
    }

    @Test
    fun `★ 无基线且没有作品快照时加入：什么都不改`() {
        // 没有标题/封面插不进列表卡片；硬把件数加一就是凭空多一条。
        val plan = PlaylistPatchOps.compute(
            current = null,
            baselineTotal = 100,
            workId = 3,
            work = null,
            remove = false
        )
        assertNull(plan)
    }

    @Test
    fun `无基线时列表件数也拿不到：退回 0，仍然能显示这一条`() {
        val plan = PlaylistPatchOps.compute(
            current = null,
            baselineTotal = 0,
            workId = 1,
            work = work(1),
            remove = false
        )
        assertEquals(1, plan!!.content!!.totalCount)
        assertEquals(1, plan.content.items.size)
    }

    // ═══════════════ 有本地基线 ═══════════════

    @Test
    fun `★ 有基线时加入：插到首位、总数加一、件数加一`() {
        val current = page(1, 2, 3, total = 200)
        val plan = PlaylistPatchOps.compute(
            current = current,
            baselineTotal = 200,
            workId = 99,
            work = work(99),
            remove = false
        )
        val content = plan!!.content!!
        assertEquals(listOf(99, 1, 2, 3), content.items.map { it.id })
        assertEquals(201, content.totalCount)
        assertEquals(1, plan.countDelta)
    }

    @Test
    fun `★ 有基线时移出：剔除、总数减一、件数减一`() {
        val current = page(1, 2, 3, total = 200)
        val plan = PlaylistPatchOps.compute(
            current = current,
            baselineTotal = 200,
            workId = 2,
            work = work(2),
            remove = true
        )
        val content = plan!!.content!!
        assertEquals(listOf(1, 3), content.items.map { it.id })
        assertEquals(199, content.totalCount)
        assertEquals(-1, plan.countDelta)
    }

    @Test
    fun `★ 加入一个本来就在列表里的作品：什么都不改（否则件数会一直涨）`() {
        // 旧实现会 filterNot 之后再插到首位 —— 列表看着没变，件数被 +1。
        // 重复收藏同一部作品几次，数字就偏出去，要到下一次列表同步才自愈。
        val current = page(1, 2, 3, total = 3)
        val plan = PlaylistPatchOps.compute(
            current = current,
            baselineTotal = 3,
            workId = 2,
            work = work(2),
            remove = false
        )
        assertNull("既然没变化，就不该写缓存、也不该动件数", plan)
    }

    @Test
    fun `移出一个不在本地列表里的作品：条数不变，但总数与件数仍要减`() {
        // 作品可能在后面几页没缓存到，或已在网页端删过。
        // 总数减一才是符合用户这次意图的结果。
        val current = page(1, 2, 3, total = 200)
        val plan = PlaylistPatchOps.compute(
            current = current,
            baselineTotal = 200,
            workId = 77,
            work = null,
            remove = true
        )
        val content = plan!!.content!!
        assertEquals(3, content.items.size)
        assertEquals(199, content.totalCount)
        assertEquals(-1, plan.countDelta)
    }

    // ═══════════════ 分页信息与去重 ═══════════════

    @Test
    fun `已加载到第 3 页时改写，loadPages 必须保留`() {
        val current = page(1, 2, 3, total = 200, loadedPages = 3)
        val plan = PlaylistPatchOps.compute(
            current = current,
            baselineTotal = 200,
            workId = 4,
            work = work(4),
            remove = false
        )
        assertEquals(3, plan!!.content!!.loadedPages)
    }

    @Test
    fun `没有基线时 loadedPages 从第 1 页起算`() {
        val plan = PlaylistPatchOps.compute(
            current = null,
            baselineTotal = 5,
            workId = 1,
            work = work(1),
            remove = false
        )
        assertEquals(1, plan!!.content!!.loadedPages)
    }

    @Test
    fun `加入时按 id 去重，不会因为快照不同而插出两条`() {
        val current = page(1, 2, total = 2)
        // 同一部作品、不同的快照（例如标题在网页端改过）
        val plan = PlaylistPatchOps.compute(
            current = current,
            baselineTotal = 2,
            workId = 2,
            work = work(2, title = "改过的标题"),
            remove = false
        )
        // 已经在列表里 → 连内容都不改（见上一条用例），保证不会重复
        assertNull(plan)
    }

    @Test
    fun `总数不会小于手里实际有的条数`() {
        // 异常数据：本地有 5 条，但总数记成了 2。移出后不该出现"总数 1、却有 4 条"。
        val current = page(1, 2, 3, 4, 5, total = 2)
        val plan = PlaylistPatchOps.compute(
            current = current,
            baselineTotal = 2,
            workId = 3,
            work = work(3),
            remove = true
        )
        val content = plan!!.content!!
        assertEquals(4, content.items.size)
        assertTrue(
            "总数 ${content.totalCount} 不该小于实际条数 ${content.items.size}",
            content.totalCount >= content.items.size
        )
    }

    @Test
    fun `非法作品 id 一律不动`() {
        listOf(0, -1).forEach { badId ->
            assertNull(
                PlaylistPatchOps.compute(
                    current = page(1, total = 1),
                    baselineTotal = 1,
                    workId = badId,
                    work = work(badId),
                    remove = false
                )
            )
        }
    }
}
