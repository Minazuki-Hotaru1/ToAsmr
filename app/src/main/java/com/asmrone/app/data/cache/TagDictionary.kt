package com.asmrone.app.data.cache

import android.content.Context
import com.asmrone.app.data.model.Tag
import org.json.JSONArray
import org.json.JSONObject

/**
 * 搜索标签的**本地索引**。
 *
 * 标签是搜索的基础数据，而且**更新频率极低**：实测线上共 422 条。所以策略是
 * "拿到一次就长期用"，由用户在设置里**手动触发更新** —— 搜索路径**永不发请求**。
 *
 * ## 索引里存的是"解析好的"标签
 *
 * 与上游 `GET /api/tags/` 的原始条目不同，落盘的是 [Tag] ——
 * 显示名、检索名、匹配别名都已经定好，**运行时不再解析**。
 *
 * 这样做的好处不只是省一次计算：
 *  - 显示名只有 [Tag.name] 一种来源，任何消费点都不可能读出第二种名字
 *    （上一版每个显示点各自调解析函数，于是出现"候选显示原始名、结果页显示译名"）
 *  - 上游 `i18n` 结构变了也不会影响已落盘的索引，只有用户点"更新"时才重新建
 *  - 文件更小：不再存 `en-us` / `ja-jp` 那两套永远不会被读到的名字
 *
 * 持久化机制（host 分文件、版本号、原子写、冷启动补读）统一在 [IdDictionaryStore]。
 */
object TagDictionary {

    /**
     * 存储种类标识。
     *
     * ⚠️ 这里**改过一次含义**：v1 存的是"原始条目 + `zh` 子对象"（运行时解析），
     * 现在存的是"已解析的索引项"。
     *
     * 两者能靠 [Tag.fromJson] 的兜底互相兼容（v1 文件里的顶层 `name` 恰好就是
     * 显示名、`query` 就是原始名），所以**没有**升版本号 ——
     * 升了会让所有老用户的索引被静默丢弃、要多点一次"更新"。
     * 若以后字段含义再变，就该认认真真地把 [IdDictionaryStore] 的版本号传进来。
     */
    private val store = IdDictionaryStore("search_tags", Codec)

    private object Codec : IdDictionaryStore.EntryCodec<Tag> {
        override fun encode(value: Tag): JSONObject? {
            if (value.id <= 0 || value.name.isBlank()) return null
            return value.toJson()
        }

        override fun decode(json: JSONObject): Tag? = Tag.fromJson(json)
    }

    fun init(context: Context) = store.init(context)

    fun entries(host: String): List<Tag> = store.entries(host)

    fun lastUpdatedAt(host: String): Long? = store.updatedAt(host)

    fun save(host: String, tags: List<Tag>, now: Long = System.currentTimeMillis()): Boolean =
        store.save(host, tags, now)

    fun clear(host: String) = store.clear(host)

    // 仅测试
    fun installForTest() = store.installForTest()
    fun uninstallForTest() = store.uninstallForTest()
    fun simulateRestartForTest() = store.simulateRestartForTest()
    fun corruptForTest(host: String, raw: String) = store.corruptForTest(host, raw)
    fun diskReadCountForTest(): Int = store.diskReadCountForTest()
}
