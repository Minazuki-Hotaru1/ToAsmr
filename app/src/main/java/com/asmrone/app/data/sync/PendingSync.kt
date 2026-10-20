package com.asmrone.app.data.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「反悔窗口」里挂着的待发送动作（收藏 / 标记）。
 *
 * ## 为什么需要它
 *
 * 详情页的收藏与标记都是**本地先行**：点下去界面立刻变，网络请求晚一步。
 * 但"晚一步"不能是"点了就发"——用户看到「已收藏至「x」 修改」这条提示后，
 * 常常会马上点「修改」换个收藏夹 / 改个星级。要是请求已经出去了，
 * 就得先发一次错的、再发一次对的（还会让云端抖一下）。
 *
 * 所以约定成：
 * 1. 点击时 [hold] 住"要发什么"，随后立刻 [schedule] 开始倒计时
 *    （时长由界面给，详情页是 `QUICK_SEND_DELAY_MS`：提示 4 秒 + 1 秒反应 + 1 秒缓冲）；
 * 2. 倒计时结束才真正发请求。计时**从点击那一刻起算**，不跟提示的动画/消失事件绑定，
 *    所以用户提前退出详情页也照样发得出去；
 * 3. 期间用户点了「修改」→ [pause]（保留动作、取消计时）；
 * 4. 弹窗确认 → [clear]（以弹窗的选择为准，另一次写入会自己发）；
 *    弹窗取消 → [schedule] 0ms（用户没改，就按原来的发）。
 *
 * ## 为什么放在进程级 scope
 *
 * 用户可能在倒计时结束前就返回上一页。若计时挂在 ViewModel 的 scope 里，
 * 那次操作会随界面一起被取消 —— 本地已经改了、云端却永远不会知道。
 * 这里用一个进程级的 scope，界面没了请求照样发得出去。
 *
 * 所有方法都只在主线程调用（Compose 事件、ViewModel 主线程），
 * 因此内部不做加锁。
 */
object PendingSync {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 已经登记、还没发出去的动作（key → 动作） */
    private val pending = mutableMapOf<String, suspend () -> Unit>()

    /** 正在倒计时的任务（key → job） */
    private val timers = mutableMapOf<String, Job>()

    /**
     * 登记一次待发送动作（本身不计时，由 [schedule] 开始倒计时）。
     *
     * 同一个 [key] 再登记一次会覆盖前一次 —— 连点两次时只发最后那次的状态。
     */
    fun hold(key: String, action: suspend () -> Unit) {
        timers.remove(key)?.cancel()
        pending[key] = action
    }

    /**
     * 开始倒计时（点击后立刻调用；弹窗取消时传 `delayMs = 0` 立刻发）。
     *
     * 没有登记过动作时什么都不做，所以界面可以无脑对多个 key 都调一遍。
     */
    fun schedule(key: String, delayMs: Long) {
        val action = pending[key] ?: return
        timers.remove(key)?.cancel()
        timers[key] = scope.launch {
            delay(delayMs)
            // 先摘掉再执行：动作里若又触发一次 hold，不会被这里误删
            pending.remove(key)
            timers.remove(key)
            runCatching { action() }
        }
    }

    /** 暂停：取消倒计时，但**保留**动作（用户点了「修改」，等他选完） */
    fun pause(key: String) {
        timers.remove(key)?.cancel()
    }

    /** 丢弃：用户已经在弹窗里做了选择，这次快捷操作不必再发 */
    fun clear(key: String) {
        timers.remove(key)?.cancel()
        pending.remove(key)
    }

    /** 这个 key 是否还有登记着的动作（调试/守卫用） */
    fun isHeld(key: String): Boolean = pending.containsKey(key)
}
