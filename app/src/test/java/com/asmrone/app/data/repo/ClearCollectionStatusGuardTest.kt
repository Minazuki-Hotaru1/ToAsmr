package com.asmrone.app.data.repo

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「取消标记只在本地生效、云端一直留着」的守卫测试。
 *
 * ## 那个 bug 的成因
 *
 * `PUT /api/review` 是**部分更新**：body 里没有的键表示"别动这个字段"。
 * 所以"把状态清掉"这件事是表达不出来的 —— 旧代码里 `status == null` 就干脆不带
 * `progress`，于是本地清掉了、云端原样保留，下次对齐又被云端数据覆盖回来。
 * 实测确认清空必须显式送 `"progress": null`（见 `AsmrOneApi.buildReviewRequest` 的注释）。
 *
 * 评分是同一个缺陷的对称面：`"rating": null` 才能清空评分而保住状态。
 *
 * ## 为什么用源码断言
 *
 * 这条链子有四层（界面 → 仓库 → 请求体 → 待补写重放），任何一层漏掉这个意图，
 * 表现都是"看起来成功了、云上没变"—— 只有跑到真机上对着账号点一次才看得见。
 * 所以把它钉在源码里：漏了就红。
 */
class ClearCollectionStatusGuardTest {

    private val sourceDir = File("src/main/java/com/asmrone/app")

    private val repository by lazy { stripComments(File(sourceDir, "data/repo/AsmrRepository.kt").readText()) }
    private val api by lazy { stripComments(File(sourceDir, "data/remote/AsmrOneApi.kt").readText()) }
    private val detailViewModel by lazy {
        stripComments(File(sourceDir, "ui/screen/detail/DetailViewModel.kt").readText())
    }
    private val collectionViewModel by lazy {
        stripComments(File(sourceDir, "ui/screen/library/CollectionViewModel.kt").readText())
    }
    private val pendingWrites by lazy { stripComments(File(sourceDir, "data/cache/PendingWrites.kt").readText()) }

    @Test
    fun `清空状态在请求体里是字面量 null`() {
        assertTrue(
            "buildReviewRequest 必须能把 progress 写成 JSONObject.NULL",
            api.contains("clearProgress -> put(\"progress\", JSONObject.NULL)")
        )
        assertFalse(
            "不能把清空实现成空字符串（那会被服务端当成一个未知状态）",
            api.contains("put(\"progress\", \"\")")
        )
    }

    @Test
    fun `仓库真的把清空意图送进请求`() {
        assertTrue(
            "updateCollectionOrThrow 必须把 clearProgress 传给接口",
            repository.contains("clearProgress = targetClear")
        )
        assertTrue(
            "整条移除走 DELETE，就不该再谈清空",
            repository.contains("val targetClear = !remove && clearStatus")
        )
    }

    @Test
    fun `两处待补写重放都按意图区分移除与清空`() {
        val replayUses = Regex("clearProgress = write\\.clearStatus").findAll(repository).count()
        assertEquals(
            "两条重放路径（单条重试 + 批量补写）都必须透传 clearStatus",
            2,
            replayUses
        )
        assertFalse(
            "不能再靠 status == null 推断待删除，那会把只清状态的记录当成整条移除",
            repository.contains("if (write.status == null)")
        )
        assertTrue(
            "待补写记录必须提供 isRemoval 供区分",
            pendingWrites.contains("val isRemoval: Boolean get() = status == null && !clearStatus")
        )
    }

    @Test
    fun `两个界面入口都表达了清空意图`() {
        assertTrue(
            "详情页弹窗返回的是完整目标状态，状态被点掉即清空",
            detailViewModel.contains("!remove && targetStatus == null && previous.progress != null")
        )
        assertTrue(
            "列表页点掉已选中的状态即清空（同样要求原本有值）",
            collectionViewModel.contains("clearStatus = status == null && work.progress != null")
        )
    }

    @Test
    fun `★ 清空只在「原本有值」时才成立`() {
        // 用「目标值为空」推断清空是危险的：本地用过期的离线副本时，
        // 界面显示"无状态/无评分"而云端其实有，一次普通操作就会静默删掉用户的真实标记。
        assertTrue(
            "状态侧必须看 previous.progress",
            detailViewModel.contains("targetStatus == null && previous.progress != null")
        )
        assertTrue(
            "评分侧必须看 previous.userRating",
            detailViewModel.contains("targetRating == null && previous.userRating != null")
        )
    }

    @Test
    fun `★ 重试沿用上次算好的清空意图`() {
        // 重试时界面上的 work 已经被乐观更新成目标值，
        // 再推断一次就会得出"原本没有"，本次重试什么也不发 —— 意图静默丢失
        assertTrue(
            "retryCollectionWrite 必须传回上次的 clearStatus",
            detailViewModel.contains("clearStatusOverride = attempt.clearStatus")
        )
        assertTrue(
            "retryCollectionWrite 必须传回上次的 clearRating",
            detailViewModel.contains("clearRatingOverride = attempt.clearRating")
        )
    }

    @Test
    fun `待同步集合按移除语义划分`() {
        assertTrue(
            "「只清状态」的作品还在收藏里，pendingAddIds 必须包含它",
            repository.contains("filter { !it.isRemoval }")
        )
        assertTrue(
            "pendingRemoveIds 只应包含整条移除",
            repository.contains("filter { it.isRemoval }")
        )
    }

    // ─────────────────── 对称的另一半：清空评分、保留状态 ───────────────────

    @Test
    fun `清空评分在请求体里也是字面量 null`() {
        assertTrue(
            "buildReviewRequest 必须能把 rating 写成 JSONObject.NULL",
            api.contains("clearRating -> put(\"rating\", JSONObject.NULL)")
        )
        assertFalse(
            "清空评分不能写成 0（那是差评，语义完全不同）",
            api.contains("put(\"rating\", 0)")
        )
    }

    @Test
    fun `仓库真的把清空评分意图送进请求`() {
        assertTrue(
            "updateCollectionOrThrow 必须把 clearRating 传给接口",
            repository.contains("clearRating = targetClearRating")
        )
        assertTrue(
            "整条移除走 DELETE，就不该再谈清空字段",
            repository.contains("val targetClearRating = !remove && clearRating")
        )
    }

    @Test
    fun `两处待补写重放都透传 clearRating`() {
        val replayUses = Regex("clearRating = write\\.clearRating").findAll(repository).count()
        assertEquals(
            "两条重放路径（单条重试 + 批量补写）都必须透传 clearRating",
            2,
            replayUses
        )
    }

    @Test
    fun `详情页表达了清空评分的意图`() {
        assertTrue(
            "星级被点掉即清空评分（状态仍按选中值提交）",
            detailViewModel.contains("!remove && targetRating == null && previous.userRating != null")
        )
    }

    @Test
    fun `本地缓存与乐观更新不再把 null 回填成旧评分`() {
        // 这两处回填正是"点了取消星级、星星又跳回来"的成因：
        // 网络层清干净了，本地却把它覆盖成旧值，两边不一致且用户无法复核
        assertTrue(
            "仓库的本地补丁必须认 clearRating",
            repository.contains("if (remove || clearRating) null else (rating ?: work.userRating)")
        )
        assertTrue(
            "详情页乐观更新必须直接用目标值",
            detailViewModel.contains("userRating = targetRating")
        )
        assertFalse(
            "不能再回落到旧评分",
            detailViewModel.contains("targetRating ?: work.userRating")
        )
    }

    @Test
    fun `待补写记录保留清空评分的意图`() {
        assertTrue(
            "CollectionMark 必须有 clearRating 字段",
            pendingWrites.contains("val clearRating: Boolean = false")
        )
        assertTrue(
            "序列化时只在 true 时写键",
            pendingWrites.contains("if (clearRating) put(\"clearRating\", true)")
        )
        assertTrue(
            "读旧文件时缺键要默认 false",
            pendingWrites.contains("clearRating = o.optBoolean(\"clearRating\", false)")
        )
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /** 去掉 `//` 行注释与 `/* … */` 块注释（够用即可：本项目没有字符串里带注释符号的行） */
    private fun stripComments(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .joinToString("\n") { line -> line.substringBefore("//") }
}
