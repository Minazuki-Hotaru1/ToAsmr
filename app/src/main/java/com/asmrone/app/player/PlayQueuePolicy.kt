package com.asmrone.app.player

/**
 * 播放顺序策略 —— **纯函数**，不碰 `MediaPlayer`，也不读 `SharedPreferences`。
 *
 * 抽出来的理由和 `PlaybackSessionModel` 一样：这类"下标算错"的 bug
 * 在真机上极难复现（要正好播到最后一首、正好随到同一首），
 * 放进 JVM 单测直接断言数字要快得多。
 *
 * ## 三种模式与"播完即停"的关系
 *
 * `AppPrefs.autoNext` 是**硬开关**：关掉之后任何模式都不会自动前进
 * （包括单曲循环、列表循环），一首放完就停在原地。
 * 手动的上一首 / 下一首不受它影响 —— 那是用户主动的意图。
 *
 * ## 关于随机
 *
 * 随机由调用方提供 [randomOffset]，这里只负责把它折算成"除当前曲目之外"的下标。
 * 这样测试可以传固定偏移，行为完全可复现。
 */
object PlayQueuePolicy {

    /** 列表循环（默认）：放完最后一首回到第一首 */
    const val MODE_LIST = 0

    /** 单曲循环：放完重放同一首（手动下一首仍然会换歌） */
    const val MODE_SINGLE = 1

    /** 随机播放：每次从"除当前曲目之外"的曲目里挑一首 */
    const val MODE_SHUFFLE = 2

    /** 允许的模式范围，供读取持久化值时夹取 */
    val MODE_RANGE = MODE_LIST..MODE_SHUFFLE

    /** 模式在界面上的名字（播放器主页的模式按钮直接用） */
    fun label(mode: Int): String = when (mode) {
        MODE_SINGLE -> "单曲循环"
        MODE_SHUFFLE -> "随机播放"
        else -> "列表循环"
    }

    /** 点一下切到下一个模式：列表 → 单曲 → 随机 → 列表 */
    fun nextMode(mode: Int): Int = when (mode) {
        MODE_LIST -> MODE_SINGLE
        MODE_SINGLE -> MODE_SHUFFLE
        else -> MODE_LIST
    }

    /**
     * 一首**自然放完**之后该放到哪一首；返回 `-1` 表示就停在这里。
     *
     * 注意 [MODE_LIST] 在只有一首时返回 `0`（重放）：列表循环的语义就是
     * "一直循环这个列表"，一首的列表循环就是单曲循环。想让它停下来，
     * 该关的是 [autoNext]，而不是把模式改成别的。
     */
    fun completionIndex(
        mode: Int,
        index: Int,
        size: Int,
        autoNext: Boolean,
        randomOffset: Int
    ): Int {
        if (size <= 0 || index !in 0 until size) return -1
        if (!autoNext) return -1
        return when (mode) {
            MODE_SINGLE -> index
            MODE_SHUFFLE -> if (size == 1) index else shuffledIndex(index, size, randomOffset)
            else -> (index + 1) % size
        }
    }

    /** 手动"下一首"的目标下标（三种模式都会绕回，所以只要不止一首就不会落空） */
    fun manualNextIndex(mode: Int, index: Int, size: Int, randomOffset: Int): Int {
        if (size <= 0 || index !in 0 until size) return -1
        if (size == 1) return 0
        return if (mode == MODE_SHUFFLE) {
            shuffledIndex(index, size, randomOffset)
        } else {
            (index + 1) % size
        }
    }

    /** 手动"上一首"的目标下标 */
    fun manualPrevIndex(mode: Int, index: Int, size: Int, randomOffset: Int): Int {
        if (size <= 0 || index !in 0 until size) return -1
        if (size == 1) return 0
        return if (mode == MODE_SHUFFLE) {
            shuffledIndex(index, size, randomOffset)
        } else {
            (index - 1 + size) % size
        }
    }

    /**
     * 把任意整数折算成 `0 until size` 里**不等于** [index] 的一个值。
     *
     * 做法：在 `0 until size-1` 上取模，再把"缺口"补回去 ——
     * 也就是把 `index` 从候选集合里挖掉，剩下的位置按顺序编号。
     */
    private fun shuffledIndex(index: Int, size: Int, offset: Int): Int {
        val span = size - 1
        val r = ((offset % span) + span) % span
        return if (r >= index) r + 1 else r
    }
}
