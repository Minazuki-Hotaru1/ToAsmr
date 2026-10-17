package com.asmrone.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.asmrone.app.data.model.SearchCondition
import com.asmrone.app.data.model.SearchNamespaces
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条搜索记录。
 *
 * ## 存三样东西，而不是一个字符串
 *
 * 一次检索可能是三种形态，只存字符串就没法"重放"：
 *
 * | 形态 | 例子 | [query] | [conditions] |
 * |---|---|---|---|
 * | 纯文字 | `耳かき` | `耳かき` | 空 |
 * | 纯条件 | `$tag:ASMR$` | 空 | 1 个标签 |
 * | 文字 + 条件 | `耳かき $tag:ASMR$` | `耳かき` | 1 个标签 |
 *
 * 所以 [query] 与 [conditions] **分开存** —— 点历史时要能把它们各归各位地
 * 填回界面（文字回输入框、条件回条件栏），而不是塞一整句进搜索框。
 *
 * ## 显示的文本是**算出来**的，不是存的
 *
 * [summary] 由 [query] 与 [conditions] 现场拼装。这样以后改了摘要的写法，
 * 历史里的旧记录也会跟着变，而不是冻结在写入那天的格式。
 *
 * ## 条件里为什么要连 displayName / kindLabel 一起存
 *
 * 标签在界面上显示中文译名（`环绕音` → `双声道立体声/人头麦`），
 * 而译名来自本地字典 —— 字典没更新、或以后改了，历史里的标签就会显示成
 * 用户没见过的原始名。把当时的显示名一起存下来最稳。
 */
data class SearchHistoryEntry(
    /** 裸关键词（可以是空串） */
    val query: String,
    /** 标签 / 声优 / 社团条件（可以是空列表） */
    val conditions: List<SearchCondition>,
    val at: Long
) {
    /** 界面上的展示文本 */
    val summary: String
        get() {
            val kw = query.trim()
            val conditionText = conditions.joinToString(" 且 ") {
                "${it.kindLabel}「${it.displayName}」"
            }
            return when {
                conditions.isEmpty() -> kw
                kw.isEmpty() -> conditionText
                else -> "$conditionText + 关键词「$kw」"
            }
        }

    /** 有没有条件（决定点历史时要不要填条件栏） */
    val hasConditions: Boolean get() = conditions.isNotEmpty()

    /** 含标签条件 —— "点带标签的记录就填标签"据此判断 */
    val tagConditions: List<SearchCondition>
        get() = conditions.filter { it.namespace == SearchNamespaces.TAG }

    /** 去重身份：文字与条件都相同才算同一次检索 */
    val dedupeKey: String
        get() = query.trim() + "\u0000" + conditions.joinToString(",") { it.dedupeKey }

    fun toJson(): JSONObject = JSONObject().apply {
        put("q", query)
        put("at", at)
        if (conditions.isNotEmpty()) {
            put("conds", JSONArray().apply { conditions.forEach { put(it.toJson()) } })
        }
    }

    companion object {
        /** 从磁盘读回；文字与条件都没有就返回 null（跳过这条） */
        fun fromJson(o: JSONObject): SearchHistoryEntry? {
            val query = o.optString("q", "")
            val conditions = o.optJSONArray("conds")?.let { arr ->
                buildList {
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { c ->
                            SearchCondition.fromJson(c)?.let(::add)
                        }
                    }
                }
            }.orEmpty()

            if (query.isBlank() && conditions.isEmpty()) return null
            return SearchHistoryEntry(
                query = query,
                conditions = conditions,
                at = o.optLong("at", 0L)
            )
        }
    }
}

/**
 * 搜索历史的**本地**存储。
 *
 * ## 放在 SharedPreferences 而不是缓存文件
 *
 * 和 [LibraryStore] 一个道理：这是**用户自己产生的**小数据（最多 15 条），
 * 丢了会让用户觉得"我的记录不见了"，所以用 SharedPreferences ——
 * 写盘是原子的、不需要清理策略，也不会被"清空缓存"误伤
 * （缓存目录里的东西随时可以丢，这个不行）。
 *
 * ## 与 API 主机无关
 *
 * 条件里带的是各站的 id，但历史记的是用户的检索意图；换镜像站之后
 * 关键词照常可用，条件由各自的字典重新解析。所以**不按 host 分桶**。
 * 这一点与三本搜索字典（按 host 分文件）不同。
 */
object SearchHistoryStore {

    private const val FILE = "asmrone_search_history"
    private const val KEY_ENTRIES = "entries"

    /**
     * 最多留这么多条。
     *
     * 15 条是"够翻"与"不淹没输入框"之间的折中：搜索页的历史列表是整屏展示的，
     * 再多就得滚动，而滚动找一条自己搜过的词并不比重新打一遍快。
     */
    const val MAX_ENTRIES = 15

    private lateinit var sp: SharedPreferences

    private val _entries = MutableStateFlow<List<SearchHistoryEntry>>(emptyList())
    val entries: StateFlow<List<SearchHistoryEntry>> = _entries.asStateFlow()

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _entries.value = read()
    }

    /**
     * 记一条搜索。
     *
     * 文字与条件**都空**时不记（比如用户什么都没填就点了搜索）。
     * 去重看 [SearchHistoryEntry.dedupeKey]：同一次检索反复搜只会把那条提到最前。
     */
    fun push(
        query: String,
        conditions: List<SearchCondition>,
        now: Long = System.currentTimeMillis()
    ) {
        val trimmed = query.trim()
        if (trimmed.isEmpty() && conditions.isEmpty()) return

        val entry = SearchHistoryEntry(query = trimmed, conditions = conditions, at = now)
        val rest = _entries.value.filterNot { it.dedupeKey == entry.dedupeKey }
        _entries.value = (listOf(entry) + rest).take(MAX_ENTRIES)
        write()
    }

    fun remove(entry: SearchHistoryEntry) {
        val next = _entries.value.filterNot { it.dedupeKey == entry.dedupeKey }
        if (next.size == _entries.value.size) return
        _entries.value = next
        write()
    }

    fun clear() {
        if (_entries.value.isEmpty()) return
        _entries.value = emptyList()
        testStorage?.let { it.remove(KEY_ENTRIES); return }
        if (::sp.isInitialized) sp.edit().remove(KEY_ENTRIES).apply()
    }

    // ─────────────────────────── 读写 ───────────────────────────
    //
    // 统一走 readRaw / writeRaw：该用真实 SharedPreferences 还是测试替身由它们决定。
    // 早期标签字典就在这上面栽过 —— save 双写磁盘与内存、但读取只查内存，
    // 于是冷启动永远读不回。把"走哪套存储"收在一处，这类不一致就没有藏身处。

    private fun read(): List<SearchHistoryEntry> {
        val raw = readRaw() ?: return emptyList()
        return runCatching { parse(raw) }.getOrElse {
            // 数据坏了就当没有 —— 搜索历史不值得为它崩一次启动
            emptyList()
        }
    }

    private fun parse(raw: String): List<SearchHistoryEntry> {
        val array = JSONArray(raw)
        return buildList {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { o ->
                    SearchHistoryEntry.fromJson(o)?.let(::add)
                }
            }
        }
    }

    private fun write() {
        val array = JSONArray()
        _entries.value.forEach { array.put(it.toJson()) }
        writeRaw(array.toString())
    }

    private fun readRaw(): String? {
        testStorage?.let { return it[KEY_ENTRIES] }
        if (!::sp.isInitialized) return null
        return sp.getString(KEY_ENTRIES, null)
    }

    private fun writeRaw(json: String) {
        testStorage?.let { it[KEY_ENTRIES] = json; return }
        if (!::sp.isInitialized) return
        sp.edit().putString(KEY_ENTRIES, json).apply()
    }

    // ─────────────────────────── 仅测试 ───────────────────────────

    /**
     * 用内存实现替换真实存储。
     *
     * 搜索历史是"写了就要能读回来"的那类数据 —— 早期标签字典就在这上面栽过。
     * 所以这里留一个替身，让往返能被真正断言。
     */
    private var testStorage: MutableMap<String, String>? = null

    fun installForTest() {
        testStorage = HashMap()
        _entries.value = emptyList()
    }

    fun uninstallForTest() {
        testStorage = null
        _entries.value = emptyList()
    }

    /** 丢内存、保留"磁盘"，用来模拟**进程重启** */
    fun simulateRestartForTest() {
        _entries.value = readRaw()?.let { raw ->
            runCatching { parse(raw) }.getOrElse { emptyList() }
        }.orEmpty()
    }

    /** 直接塞入原始文本（造坏数据用） */
    fun corruptForTest(raw: String) {
        testStorage?.put(KEY_ENTRIES, raw)
    }

    /** 读出"磁盘"上的原始文本 */
    fun rawForTest(): String? = testStorage?.get(KEY_ENTRIES)
}
