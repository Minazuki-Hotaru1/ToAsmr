package com.asmrone.app.data.cache

import com.asmrone.app.data.model.Work

/**
 * 「加入 / 移出收藏夹」对**本地内容缓存**应有的改动 —— 纯函数，便于单测。
 *
 * ## 为什么单独抽出来
 *
 * 这段判断原先埋在 `AsmrRepository.patchPlaylistWorksCache` 里，与"读缓存、
 * 写缓存、改件数"混在一起，于是只能靠**源码级字符串断言**来测（"函数里必须
 * 出现某个方法名"）。那种测试锁定的是实现方式，一旦重构就得连测试一起改，
 * 而且它根本证明不了行为对不对。
 *
 * 抽出来之后，`AsmrRepository` 只剩"读 → 算 → 写"三步，
 * 真正需要判断的东西（插到哪、总数怎么变、要不要动）都在这里被穷举验证。
 *
 * ## 三条规则
 *
 * 1. **没有本地基线时的"移出"** → 不写内容缓存（写了就等于宣称"这个收藏夹是空的"），
 *    只把件数减一。
 * 2. **拿不到作品快照的"加入"** → 什么也不改（没有标题/封面插不进列表卡片，
 *    硬把件数加一就是凭空多一条）。
 * 3. **作品本来就在列表里** → 什么也不改（服务端也会回 `rowCount = 0`）。
 *
 * 其余情况下就地增删一条，并把总数按服务端语义修正。
 */
internal object PlaylistPatchOps {

    /**
     * 一次改动对本地缓存的后果。
     *
     * @param content 要写入的内容缓存；**null 表示不动内容缓存**
     * @param countDelta 收藏夹列表里 `worksCount` 的增量（0 = 不动）
     */
    data class Plan(
        val content: CachedWorkPage?,
        val countDelta: Int
    )

    /**
     * 算出这次改动该落成什么样。
     *
     * @param current 本地现有的内容缓存；null = 这个收藏夹本地还没有基线
     * @param baselineTotal 没有基线时用的**总数基线**（取自收藏夹列表里的 `worksCount`）。
     *   它不能省：写 0 会让详情页的 `hasMore`（`size < totalCount`）立刻变 false，
     *   「加载更多」在这个收藏夹上就永久失效了 —— 而"给从没打开过的收藏夹加作品"
     *   恰恰是最常见的路径。
     * @param workId 被操作的作品 id（必须有：移出时即使没有作品快照也要能摘掉它）
     * @param work 作品快照；加入时需要它才能渲染卡片，移出时可传 null
     * @param remove true = 移出
     * @return null 表示**什么都不用改**
     */
    fun compute(
        current: CachedWorkPage?,
        baselineTotal: Int,
        workId: Int,
        work: Work?,
        remove: Boolean
    ): Plan? {
        if (workId <= 0) return null

        // ① 没有基线时的移出：本地本来就没有这份内容，凭空写一份空列表
        //    会在下次读取时冒充"这个收藏夹是空的"。只改件数。
        if (current == null && remove) {
            return Plan(content = null, countDelta = -1)
        }

        // ② 加入却拿不到作品快照：插不进列表（没有标题/封面可渲染）。
        if (current == null && work == null) return null
        if (!remove && work == null) return null

        val items = current?.items.orEmpty()

        // ③ 作品本来就在里面：不动内容，也不动件数。
        //    以前这里会 filterNot 之后再插到首位 —— 列表看起来没变，
        //    但件数被加了 1，于是"重复收藏同一个作品"会让数字越走越偏。
        if (!remove && work != null && items.any { it.id == workId }) return null

        val updated: List<CachedWork> = if (remove) {
            items.filterNot { it.id == workId }
        } else {
            val snapshot = CachedWork.fromWork(work!!)
            dedupeWorks(listOf(snapshot) + items.filterNot { it.id == workId })
        }

        // 总数：本地有缓存就用缓存里的，没有就用列表里的件数基线。
        // 加入 +1、移出 -1，同时不允许小于"手里实际有的条数"。
        val baseline = current?.totalCount ?: baselineTotal
        val totalCount = if (remove) {
            (baseline - 1).coerceAtLeast(updated.size)
        } else {
            maxOf(baseline + 1, updated.size)
        }

        return Plan(
            content = CachedWorkPage(
                items = updated,
                totalCount = totalCount,
                loadedPages = current?.loadedPages ?: 1
            ),
            countDelta = if (remove) -1 else 1
        )
    }
}

/** 按 id 去重并保持顺序（分页边界与"插到首位"之后都会产生重复） */
private fun dedupeWorks(items: List<CachedWork>): List<CachedWork> = items.distinctBy { it.id }
