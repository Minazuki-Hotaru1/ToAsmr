package com.asmrone.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.asmrone.app.data.model.Work
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地收藏：收藏、收听历史、收听进度。
 *
 * 之所以把作品的「轻量快照」一起存下来（[SavedWork]），而不是只存 id：
 * 收藏页 / 历史页在离线、或 API 主机不可达时必须还能正常展示封面与标题。
 */
data class SavedWork(
    val id: Int,
    val title: String,
    val coverUrl: String?,
    val circleName: String?,
    val durationSec: Int,
    val hasSubtitle: Boolean,
    val savedAt: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("cover", coverUrl ?: "")
        put("circle", circleName ?: "")
        put("duration", durationSec)
        put("subtitle", hasSubtitle)
        put("at", savedAt)
    }

    companion object {
        fun fromWork(work: Work, coverUrl: String?, now: Long = System.currentTimeMillis()) = SavedWork(
            id = work.id,
            title = work.title,
            coverUrl = coverUrl,
            circleName = work.circleName,
            durationSec = work.durationSec,
            hasSubtitle = work.hasSubtitle,
            savedAt = now
        )

        fun fromJson(o: JSONObject) = SavedWork(
            id = o.optInt("id"),
            title = o.optString("title"),
            coverUrl = o.optString("cover").takeIf { it.isNotBlank() },
            circleName = o.optString("circle").takeIf { it.isNotBlank() },
            durationSec = o.optInt("duration"),
            hasSubtitle = o.optBoolean("subtitle"),
            savedAt = o.optLong("at")
        )
    }

    val durationText: String get() = Work.formatDuration(durationSec.toLong())
}

object LibraryStore {

    private const val FILE = "asmrone_library"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_HISTORY = "history"
    private const val KEY_PROGRESS = "progress"
    private const val MAX_HISTORY = 200

    private lateinit var sp: SharedPreferences

    /**
     * **本地**收藏（设备上的书签）。
     *
     * 注意与「我的收藏」（云端状态标记）的区别：
     *  - 本地收藏：不需要登录，只存一份轻量快照，断网也能看（就是这个列表）；
     *  - 我的收藏：登录后的服务端数据，带五种收听状态与用户评分，
     *    因为服务端每次都会返回完整的作品对象，所以**不做本地镜像**，
     *    由 `CollectionViewModel` 直接持有（见 ui/screen/library/CollectionViewModel.kt）。
     *
     * 两者在收藏页是**两个独立页签**，不混在一起 —— 它们的语义本来就不同
     * （一个是"我存了这本"，一个是"我听到哪了 / 打了几分"）。
     */
    private val _favorites = MutableStateFlow<List<SavedWork>>(emptyList())
    val favorites: StateFlow<List<SavedWork>> = _favorites.asStateFlow()

    private val _history = MutableStateFlow<List<SavedWork>>(emptyList())
    val history: StateFlow<List<SavedWork>> = _history.asStateFlow()

    /** workId → 收听进度（0f..1f） */
    private val _progress = MutableStateFlow<Map<Int, Float>>(emptyMap())
    val progress: StateFlow<Map<Int, Float>> = _progress.asStateFlow()

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _favorites.value = readList(KEY_FAVORITES)
        _history.value = readList(KEY_HISTORY)
        _progress.value = readProgress()
    }

    // ─────────────────────────── 收藏 ───────────────────────────

    fun isFavorite(workId: Int): Boolean = _favorites.value.any { it.id == workId }

    /** 切换本地收藏 */
    fun toggleFavorite(work: Work, coverUrl: String?) {
        val list = _favorites.value
        _favorites.value = if (list.any { it.id == work.id }) {
            list.filterNot { it.id == work.id }
        } else {
            listOf(SavedWork.fromWork(work, coverUrl)) + list
        }
        writeList(KEY_FAVORITES, _favorites.value)
    }

    // ─────────────────────────── 历史 ───────────────────────────

    fun pushHistory(work: Work, coverUrl: String?) {
        val entry = SavedWork.fromWork(work, coverUrl)
        val list = _history.value.filterNot { it.id == work.id }
        _history.value = (listOf(entry) + list).take(MAX_HISTORY)
        writeList(KEY_HISTORY, _history.value)
    }

    fun clearHistory() {
        _history.value = emptyList()
        sp.edit().remove(KEY_HISTORY).apply()
    }

    fun removeHistory(workId: Int) {
        _history.value = _history.value.filterNot { it.id == workId }
        writeList(KEY_HISTORY, _history.value)
    }

    // ─────────────────────────── 进度 ───────────────────────────

    fun progressOf(workId: Int): Float? = _progress.value[workId]

    fun setProgress(workId: Int, fraction: Float) {
        val map = _progress.value.toMutableMap()
        map[workId] = fraction.coerceIn(0f, 1f)
        _progress.value = map
        val json = JSONObject()
        map.forEach { (k, v) -> json.put(k.toString(), v.toDouble()) }
        sp.edit().putString(KEY_PROGRESS, json.toString()).apply()
    }

    // ─────────────────────────── 读写 ───────────────────────────

    private fun readList(key: String): List<SavedWork> {
        val raw = sp.getString(key, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { add(SavedWork.fromJson(it)) }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeList(key: String, list: List<SavedWork>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        sp.edit().putString(key, array.toString()).apply()
    }

    private fun readProgress(): Map<Int, Float> {
        val raw = sp.getString(KEY_PROGRESS, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                json.keys().forEach { key ->
                    key.toIntOrNull()?.let { put(it, json.optDouble(key, 0.0).toFloat()) }
                }
            }
        }.getOrDefault(emptyMap())
    }
}
