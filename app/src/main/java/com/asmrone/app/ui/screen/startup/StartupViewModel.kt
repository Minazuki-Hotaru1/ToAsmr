package com.asmrone.app.ui.screen.startup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.asmrone.app.data.prefs.AppPrefs
import com.asmrone.app.data.repo.AsmrRepository
import com.asmrone.app.data.repo.updateAllDictionaries
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 首次进入 App 时的字典准备阶段。
 *
 * ## 为什么要有这一步
 *
 * 三本搜索字典（标签索引 / 声优 / 社团）是**高级搜索的前提**：没有它们，
 * 搜索页的候选是空的，"按标签 / 声优 / 社团搜"整条路都走不通。
 * 让用户自己去设置里点一次更新，等于把"App 能不能用"变成需要读文档才知道的事。
 *
 * 所以第一次进来时自动拉一次，并**明说在干什么**（转圈 + 进度 + 结果提示）。
 *
 * ## 什么时候跳过
 *
 * [AppPrefs.dictionariesInitialized] 为 true **且**三本数据都在 → 直接放行。
 * 两个条件缺一不可：
 *  - 只看标记：换过 API 主机、或用户在设置里清过某一本之后，标记还是 true 但数据没了
 *  - 只看数据：离线用户每次冷启动都会重试一次、每次都要等一次超时
 *
 * ## 失败不拦路，但**必须由用户决定**
 *
 * 拉取失败时给出提示并**停住**，由用户选「重试」或「暂不更新」——
 * 搜索页本身还能按关键词用，为一个可选的增强功能把人挡在门外说不过去。
 *
 * ⚠️ 这一条是踩过坑之后强化的：早期版本在"结果已出"之后只看 `readyOk`
 * 决定要不要自动放行，而失败时 `readyOk` 为 false —— 逻辑上没错，
 * 但**失败态没有任何"停留"的保证**：卡片刚出现，用户还没看清就被后续状态覆盖，
 * 观感上就是"失败页面闪一下就进了首页"。现在：
 *
 *  - 成功 → 自动放行（本来就没什么可操作的）
 *  - 失败 → **只能**由用户点按钮离开，代码里没有任何自动跳转路径
 *  - 失败原因**常驻卡片**（不只靠 1.5 秒的 toast）
 */
class StartupViewModel : ViewModel() {

    private val _state = MutableStateFlow(StartupUiState())
    val state: StateFlow<StartupUiState> = _state.asStateFlow()

    /** 只跑一次（配置变更 / 重组时会再次进入组合，但不该重新拉） */
    private var started = false

    /** 慢网提示的计时器；拉取结束或重试时都要取消 */
    private var slowHintJob: Job? = null

    /**
     * 第几次拉取。
     *
     * ## 为什么需要它
     *
     * 用户点「重试」时会起一个**新的**拉取协程，但**上一个不会被自动取消** ——
     * 于是两个协程都在往同一个 `_state` 写：旧的那次可能晚一步返回，
     * 把新一次的结果**覆盖**掉。表现就是"结果页面闪一下又变了"
     * 或者"明明刚成功，界面却显示了上一次的失败"。
     *
     * 所以每次拉取领一个号，只有**当前号**的协程才有资格写状态。
     */
    private var fetchGeneration = 0

    fun start() {
        if (started) return
        started = true

        // 已经初始化过、且数据确实都在 → 不打扰用户，直接放行
        if (AppPrefs.dictionariesInitialized.value && AsmrRepository.dictionariesReady()) {
            _state.value = StartupUiState(stage = StartupStage.DONE)
            return
        }

        fetch()
    }

    /** 失败后用户点「重试」 */
    fun retry() {
        if (_state.value.stage == StartupStage.FETCHING) return
        fetch()
    }

    /**
     * 用户主动离开这一屏（「暂不更新」）。
     *
     * **拉取中也接受这个调用** —— 慢网时用户不该被迫干等，界面在拉取中
     * 与慢网提示下都会给出这个入口。
     *
     * 这里曾经"拉取中拒绝放行"，理由是"用户还不知道结果"；但那条约束
     * 与"给慢网用户一条出路"直接冲突。而且真正要防的是**自动跳转**，
     * 不是用户主动点击 —— 自动路径的约束留在 [fetch] 里（只有成功才自动放行），
     * 这个入口对用户完全放开。
     */
    fun proceed() {
        _state.value = _state.value.copy(stage = StartupStage.DONE)
    }

    private fun fetch() {
        slowHintJob?.cancel()
        val generation = ++fetchGeneration

        _state.value = StartupUiState(
            stage = StartupStage.FETCHING,
            progress = "正在准备搜索字典…"
        )

        // 慢网提示：10 秒还没拉完，说明当前网络很可能跑不动这 800 多 KB。
        // 不取消拉取 —— 移动网络慢但能完成的情况是存在的，提示只是"给个解释"。
        slowHintJob = viewModelScope.launch {
            delay(SLOW_NETWORK_HINT_MS)
            // 只有还在拉取、且没有被更新的一次取代时才提示
            if (generation == fetchGeneration && _state.value.stage == StartupStage.FETCHING) {
                _state.value = _state.value.copy(slowNetwork = true)
            }
        }

        viewModelScope.launch {
            val outcome = updateAllDictionaries { text ->
                // 进度也只由当前这一次写，避免旧的那次把新进度顶掉
                if (generation == fetchGeneration) {
                    _state.value = _state.value.copy(progress = text)
                }
            }

            // 被更新的一次取代了 → 直接丢弃这次的结论，别去覆盖界面
            if (generation != fetchGeneration) return@launch

            // 无论成败都记下"做过首次初始化"：
            // 否则没网的用户每次冷启动都会被拦一次、每次都要等一次超时。
            // 补救入口是设置页的「更新搜索字典」。
            AppPrefs.setDictionariesInitialized(true)

            slowHintJob?.cancel()

            val ready = AsmrRepository.dictionariesReady()
            val total = AsmrRepository.cachedSearchTags().size +
                AsmrRepository.cachedVoiceActors().size +
                AsmrRepository.cachedCircles().size

            _state.value = _state.value.copy(
                stage = StartupStage.READY,
                progress = null,
                slowNetwork = false,
                // 只有三本都拉到才算成功；部分成功也要说清缺了什么
                toastMessage = if (ready) {
                    "搜索字典已就绪，共 $total 条"
                } else {
                    outcome.message(total)
                },
                // 失败原因**留在卡片上**：toast 只有 1.5 秒，
                // 而"到底为什么没准备完"是用户决定重试还是跳过的依据
                failureDetail = if (ready) null else outcome.message(total),
                // 成功 → 自动放行；失败 → 只能由用户点按钮离开
                readyOk = ready
            )
        }
    }

    companion object {
        /**
         * 超过这个时长还没拉完就提示"网络较差"。
         *
         * 选 10 秒的依据：三本字典合计约 830KB，正常 4G 下几秒内就该完成；
         * 10 秒还没好，基本可以确定是网络环境的问题而不是服务端。
         */
        const val SLOW_NETWORK_HINT_MS = 10_000L
    }
}

enum class StartupStage {
    /** 正在拉取 */
    FETCHING,

    /** 拉取已结束（不管成没成），等界面处理 */
    READY,

    /** 放行 —— 界面切到正常 App */
    DONE
}

data class StartupUiState(
    val stage: StartupStage = StartupStage.FETCHING,
    /** 进度文案，例如"正在更新声优（2/3）…" */
    val progress: String? = null,
    /**
     * 拉了 10 秒还没完成 —— 界面据此提示"当前网络环境较差"。
     *
     * 只提示、不中断：移动网络慢但能完成的情况是存在的。
     */
    val slowNetwork: Boolean = false,
    /** 要弹一次的提示（成功或失败），由界面消费 */
    val toastMessage: String? = null,
    /**
     * 失败原因，**常驻卡片**显示。
     *
     * 与 [toastMessage] 的区别：toast 1.5 秒就没了，而失败原因是用户判断
     * "重试还是跳过"的依据，必须一直看得到。
     */
    val failureDetail: String? = null,
    /**
     * 三本都齐了 —— 界面可以短暂展示一下再自动放行。
     *
     * ⚠️ **只有成功时才会为 true**。失败路径没有任何自动跳转：
     * 用户必须显式点「重试」或「暂不更新」。
     */
    val readyOk: Boolean = false
) {
    /** 拉取已结束但数据不完整 → 显示出错卡片 */
    val showFailure: Boolean get() = stage == StartupStage.READY && !readyOk
}
