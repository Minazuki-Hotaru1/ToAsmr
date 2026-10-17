package com.asmrone.app.ui.screen.settings

import com.asmrone.app.data.repo.AsmrRepository

/**
 * 搜索字典在设置页里显示的状态。
 *
 * 更新动作本身在 `data/repo/DictionarySync.kt`（首次进入 App 也要用同一套），
 * 这里只负责把它读成"设置页那一行要显示什么"。
 */
data class DictionaryUpdateState(
    /** 三本合起来的本地条数 */
    val totalCount: Int = 0,
    /** 最近一次成功更新的时间（取三本里最新的那个） */
    val updatedAt: Long? = null,
    val updating: Boolean = false,
    /** 进度文案，例如"正在更新声优（2/3）…" */
    val progress: String? = null,
    /** 结果或失败提示 */
    val message: String? = null
)

/**
 * 从本地三本字典读初始状态。
 *
 * 三本都要读 —— 少了任何一本，"本地已有 N 条"就会偏小，
 * 而这个数字正是用户判断"要不要点更新"的依据。
 */
fun readDictionaryUpdateState(): DictionaryUpdateState {
    val counts = listOf(
        AsmrRepository.cachedSearchTags().size,
        AsmrRepository.cachedVoiceActors().size,
        AsmrRepository.cachedCircles().size
    )
    val times = listOfNotNull(
        AsmrRepository.searchTagsUpdatedAt(),
        AsmrRepository.voiceActorsUpdatedAt(),
        AsmrRepository.circlesUpdatedAt()
    )
    return DictionaryUpdateState(
        totalCount = counts.sum(),
        updatedAt = times.maxOrNull()
    )
}
