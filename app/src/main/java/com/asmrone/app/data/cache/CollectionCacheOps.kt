package com.asmrone.app.data.cache

import com.asmrone.app.data.model.CollectionStatus
import com.asmrone.app.data.model.Work

/**
 * 「标记」列表在本地缓存里的**增删改**（纯函数，便于单测）。
 *
 * ## 为什么需要它
 *
 * 用户点了标记/取消标记之后，收藏必须**立刻**能看到变化 ——
 * 这是"本地写入"的结果，不应该等下一次云端核对才发现。
 *
 * 之前的实现只在**内存快照非空**时才改缓存，而快照只有在"进过收藏"之后才有。
 * 于是从首页直接进详情页标记、再回收藏，就看不到刚加的内容 ——
 * 因为本地根本没被改过，只能靠一次云端同步来"发现"它。
 *
 * 现在改成：不管有没有快照，都直接对缓存里的列表做增删。
 */
object CollectionCacheOps {

    /**
     * 把一次标记/取消标记应用到列表上。
     *
     * @param items 当前列表（来自缓存）
     * @param filter 这个列表对应的筛选；`null` = 全部。用于判断"改完之后它还属不属于这个列表"
     * @param work 被操作的作品；**新标记时用它的标题/封面等字段插入到最前面**
     * @param remove true = 移除整条标记
     * @return 新的列表
     */
    fun apply(
        items: List<Work>,
        filter: CollectionStatus?,
        work: Work,
        remove: Boolean
    ): List<Work> {
        val without = items.filterNot { it.id == work.id }

        // 移除整条标记 → 从所有列表里消失
        if (remove) return without

        // 改了状态：如果这个列表是按状态筛选的，而新状态不是它 → 不再属于这里
        val newStatus = work.collectionStatus
        if (filter != null && filter != newStatus) return without

        // 否则插入/更新。
        // "全部"列表按最近更新排序（服务端 order=updated_at desc），
        // 所以刚改过的应当排在最前 —— 放到首位的观感与云端一致。
        val updated = work.copy(progress = newStatus?.apiValue)
        return listOf(updated) + without
    }

    /**
     * 去掉列表里的重复项并保持顺序（分页边界上偶尔会重复）。
     *
     * 单列出来是因为"插入到首位"之后容易与后续追加的页出现同一个 id。
     */
    fun dedupe(items: List<Work>): List<Work> = items.distinctBy { it.id }

    // ───────────────────── 与云端结果合并（重构后的核心）─────────────────────
    //
    // 方向变了：**本地是主数据源**，云端只是用来"对齐"的参考。
    // 所以合并时不是简单拿云端覆盖本地，而要按三条规则处理待同步项：
    //
    //   1. **待删除**的作品 → 一律不出现（用户已经删了，云端还没收到）
    //   2. **待上传**的作品 → 保留本地那份（含状态与评分），不用云端的旧值
    //   3. 其余 → 以云端为准（顺序也以云端为准）
    //
    // 只有 1、2 是"本地优先"的例外，所以不会变成"永远不听云端"。

    /**
     * 把云端返回的列表与本地待同步状态合并。
     *
     * @param remote 云端返回的**权威顺序与内容**
     * @param local 本地缓存里的内容（待上传条目的最新样子从这里取）
     * @param pendingAdds 本地已改、云端还没收到的作品 id
     * @param pendingRemoves 本地已删、云端还没删掉的作品 id
     */
    fun resolveAgainstCloud(
        remote: List<Work>,
        local: List<Work>,
        pendingAdds: Set<Int>,
        pendingRemoves: Set<Int>
    ): List<Work> {
        val localById = local.associateBy { it.id }

        // 云端列表打底：待上传的换成本地版本，待删除的直接剔除
        val fromCloud = remote
            .filterNot { it.id in pendingRemoves }
            .map { work ->
                if (work.id in pendingAdds) localById[work.id] ?: work else work
            }

        // 云端**还没有**的待上传条目：保留在末尾，不能因为一次对齐就让它消失
        // ——那正是用户刚加的内容，只是网络请求没成功
        val remoteIds = remote.map { it.id }.toSet()
        val localOnly = pendingAdds
            .filterNot { it in remoteIds }
            .filterNot { it in pendingRemoves }
            .mapNotNull { localById[it] }

        return dedupe(fromCloud + localOnly)
    }
}
