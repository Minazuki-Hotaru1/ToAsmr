package com.asmrone.app.data.cache

import com.asmrone.app.data.model.Circle
import com.asmrone.app.data.model.NamedEntry
import com.asmrone.app.data.model.VoiceActor
import android.content.Context
import org.json.JSONObject

/**
 * 声优字典（`GET /api/vas/`）。
 *
 * 5552 项 / 480KB，实测 `i18n` 全空（没有译名可挑，显示名就是 `name`）。
 * 与标签一样是"取一次长期用"，由用户在设置里手动更新；搜索路径只读本地。
 *
 * 接口顺序是**随机序**（按 UUID 排），所以候选列表必须**自己按 count 倒序**。
 */
object VoiceActorDictionary {

    private val store = IdDictionaryStore("vas", Codec)

    private object Codec : IdDictionaryStore.EntryCodec<VoiceActor> {
        override fun encode(value: VoiceActor): JSONObject? {
            if (value.id.isBlank() || value.name.isBlank()) return null
            return JSONObject().apply {
                put("id", value.id)
                put("name", value.name)
                put("count", value.count)
            }
        }

        override fun decode(json: JSONObject): VoiceActor? = VoiceActor.fromJson(json)
    }

    fun init(context: Context) = store.init(context)

    fun entries(host: String): List<VoiceActor> = store.entries(host)

    fun lastUpdatedAt(host: String): Long? = store.updatedAt(host)

    fun save(host: String, entries: List<VoiceActor>, now: Long = System.currentTimeMillis()): Boolean =
        store.save(host, entries, now)

    fun clear(host: String) = store.clear(host)

    // 仅测试
    fun installForTest() = store.installForTest()
    fun uninstallForTest() = store.uninstallForTest()
    fun simulateRestartForTest() = store.simulateRestartForTest()
    fun corruptForTest(host: String, raw: String) = store.corruptForTest(host, raw)
    fun diskReadCountForTest(): Int = store.diskReadCountForTest()
}

/**
 * 社团字典（`GET /api/circles/`）。
 *
 * 5882 项 / 350KB，实测 `i18n` 全空。
 * 接口顺序是**名称字母序**，同样需要自己按 count 倒序排。
 */
object CircleDictionary {

    private val store = IdDictionaryStore("circles", Codec)

    private object Codec : IdDictionaryStore.EntryCodec<Circle> {
        override fun encode(value: Circle): JSONObject? {
            if (value.id.isBlank() || value.name.isBlank()) return null
            return JSONObject().apply {
                put("id", value.id)
                put("name", value.name)
                put("count", value.count)
            }
        }

        override fun decode(json: JSONObject): Circle? = Circle.fromJson(json)
    }

    fun init(context: Context) = store.init(context)

    fun entries(host: String): List<Circle> = store.entries(host)

    fun lastUpdatedAt(host: String): Long? = store.updatedAt(host)

    fun save(host: String, entries: List<Circle>, now: Long = System.currentTimeMillis()): Boolean =
        store.save(host, entries, now)

    fun clear(host: String) = store.clear(host)

    // 仅测试
    fun installForTest() = store.installForTest()
    fun uninstallForTest() = store.uninstallForTest()
    fun simulateRestartForTest() = store.simulateRestartForTest()
    fun corruptForTest(host: String, raw: String) = store.corruptForTest(host, raw)
    fun diskReadCountForTest(): Int = store.diskReadCountForTest()
}
