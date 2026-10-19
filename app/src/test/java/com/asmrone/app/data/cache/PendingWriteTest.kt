package com.asmrone.app.data.cache

import com.asmrone.app.data.model.CollectionStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 待补写记录的编解码与去重键测试。
 *
 * 这里装的是"用户已经表达过、但还没写到云端"的意图，是本次方案的核心数据：
 *  - 编解码出错 → 意图丢失或写错状态；
 *  - 去重键出错 → 要么用户连点产生一堆重复补写，要么不同收藏夹的操作互相挤掉。
 */
class PendingWriteTest {

    private fun roundTrip(w: PendingWrite): PendingWrite? =
        pendingWriteFromJson(w.toJson())

    @Test
    fun `标记状态往返不丢字段`() {
        val original = PendingWrite.CollectionMark(
            workId = 123,
            status = CollectionStatus.LISTENING,
            rating = 4,
            createdAt = 111L
        )
        val restored = roundTrip(original) as PendingWrite.CollectionMark
        assertEquals(123, restored.workId)
        assertEquals(CollectionStatus.LISTENING, restored.status)
        assertEquals(4, restored.rating)
        assertEquals(111L, restored.createdAt)
    }

    @Test
    fun `移除标记（status 为 null）往返仍是移除`() {
        val original = PendingWrite.CollectionMark(
            workId = 7,
            status = null,
            rating = null,
            createdAt = 5L
        )
        val restored = roundTrip(original) as PendingWrite.CollectionMark
        assertNull("null 必须保持 null，不能被读成未标记以外的状态", restored.status)
        assertNull(restored.rating)
        assertTrue("没有 clearStatus 的 null 状态就是整条移除", restored.isRemoval)
    }

    @Test
    fun `★ 只清状态的待补写往返后仍不是移除`() {
        // 这是"取消标记后云端不生效"那个 bug 的补写侧：
        // 以前 status == null 一律当移除重放，会把用户保留的评分一起删掉
        val original = PendingWrite.CollectionMark(
            workId = 1681251,
            status = null,
            rating = 5,
            createdAt = 3L,
            clearStatus = true
        )
        val restored = roundTrip(original) as PendingWrite.CollectionMark
        assertTrue("clearStatus 必须往返保留", restored.clearStatus)
        assertFalse("★ 补写时不能把它当成整条移除（那会连评分一起删掉）", restored.isRemoval)
        assertEquals("保留的评分不能丢", 5, restored.rating)
    }

    @Test
    fun `旧格式文件（没有 clearStatus 键）仍按移除解释`() {
        val legacy = JSONObject().apply {
            put("op", "mark")
            put("workId", 9)
            put("status", "")
            put("rating", 0)
            put("createdAt", 1L)
        }
        val write = pendingWriteFromJson(legacy) as PendingWrite.CollectionMark
        assertNull(write.status)
        assertTrue("升级前的数据里 status 为空就是移除，语义不能漂移", write.isRemoval)
    }

    @Test
    fun `收藏夹加入与移除往返正确`() {
        val add = roundTrip(
            PendingWrite.PlaylistAdd("pl-1", 42, createdAt = 9L)
        ) as PendingWrite.PlaylistAdd
        assertEquals("pl-1", add.playlistId)
        assertEquals(42, add.workId)

        val remove = roundTrip(
            PendingWrite.PlaylistRemove("pl-2", 43, createdAt = 10L)
        ) as PendingWrite.PlaylistRemove
        assertEquals("pl-2", remove.playlistId)
        assertEquals(43, remove.workId)
    }

    @Test
    fun `缺少 workId 的记录被丢弃`() {
        val bad = org.json.JSONObject().apply {
            put("op", "mark")
            put("status", "marked")
        }
        assertNull("没有 workId 无法补写，必须丢弃", pendingWriteFromJson(bad))
    }

    @Test
    fun `未知操作类型被丢弃`() {
        val unknown = org.json.JSONObject().apply {
            put("op", "somethingNew")
            put("workId", 1)
        }
        assertNull("版本变化时宁可丢弃也不要写错东西", pendingWriteFromJson(unknown))
    }

    // ─────────────────────────── 去重键 ───────────────────────────

    @Test
    fun `同一作品同一操作的去重键相同`() {
        val a = PendingWrite.CollectionMark(1, CollectionStatus.MARKED, null, 100L)
        val b = PendingWrite.CollectionMark(1, CollectionStatus.LISTENED, null, 200L)
        assertEquals(
            "用户连点几次只应保留最后一次意图",
            a.dedupeKey, b.dedupeKey
        )
    }

    @Test
    fun `不同作品或不同收藏夹不可互相挤掉`() {
        val workA = PendingWrite.CollectionMark(1, CollectionStatus.MARKED, null, 1L)
        val workB = PendingWrite.CollectionMark(2, CollectionStatus.MARKED, null, 1L)
        assertNotEquals("不同作品必须各自排队", workA.dedupeKey, workB.dedupeKey)

        val addToP1 = PendingWrite.PlaylistAdd("p1", 1, 1L)
        val addToP2 = PendingWrite.PlaylistAdd("p2", 1, 1L)
        assertNotEquals(
            "同一作品加入不同收藏夹是两件事，不能只留一条",
            addToP1.dedupeKey, addToP2.dedupeKey
        )
    }

    @Test
    fun `加入与移除同一收藏夹是不同的操作`() {
        val add = PendingWrite.PlaylistAdd("p1", 1, 1L)
        val remove = PendingWrite.PlaylistRemove("p1", 1, 2L)
        assertNotEquals(
            "方向不同，不能互相覆盖（否则先加后删会只剩一条、净效果错）",
            add.dedupeKey, remove.dedupeKey
        )
    }

    @Test
    fun `标记与收藏夹操作互不影响`() {
        val mark = PendingWrite.CollectionMark(1, CollectionStatus.MARKED, null, 1L)
        val add = PendingWrite.PlaylistAdd("p1", 1, 1L)
        assertNotEquals(
            "标记和收藏夹是两个维度，同一作品可以同时有待补写",
            mark.dedupeKey, add.dedupeKey
        )
        assertTrue(mark.dedupeKey.startsWith("mark"))
        assertTrue(add.dedupeKey.startsWith("plAdd"))
    }

    @Test
    fun `workId 属性可直接用于界面标记待同步`() {
        val w: PendingWrite = PendingWrite.PlaylistRemove("p", 99, 1L)
        assertEquals(99, w.workId)
    }
}
