package com.asmrone.app.data.model

import org.json.JSONObject

/**
 * 服务端「具名资源」的公共形态 —— 声优与社团共用。
 *
 * ## 实测结构（2026-10 对着 api.asmr-200.com）
 *
 * ```jsonc
 * // GET /api/vas/    —— id 是 UUID 字符串，共 5552 项（480KB）
 * { "id": "c75838bb-1c4d-5850-8a93-b9a56593d511", "name": "야디", "count": 1, "i18n": {} }
 *
 * // GET /api/circles/ —— id 是整数，共 5882 项（350KB）
 * { "id": 37739, "name": "072LABO", "count": 108, "i18n": {} }
 * ```
 *
 * ## ⚠️ `i18n` 永远是空的
 *
 * 这两个接口的 `i18n` 实测**全部为空对象**（11434 项里 0 项有内容），
 * 所以不存在"多语言译名"这回事，显示名就是 [name] 本身。
 * 这一点和标签不同 —— 标签的 `i18n` 是有内容的（422 项里 396 项带 `zh-cn`）。
 *
 * 因此声优/社团**不需要** [TagNameResolver] 那套解析逻辑。
 *
 * ## ⚠️ 接口顺序不是按热度
 *
 *  - 声优：按 id 排序，而 id 是 UUID → 等同于**随机序**
 *  - 社团：按名称**字母序**
 *
 * 两个都不是按作品数。[count] 拿到之后要**自己按它倒序排**，
 * 否则候选列表里会先出现 `야디`(1) 这种冷门条目，而 `陽向葵ゅか`(2337) 排在很后面。
 */
data class NamedEntry(
    /** 声优是 UUID 字符串、社团是整数字符串 —— 统一按字符串保存 */
    val id: String,
    val name: String,
    /** 作品数（用于候选列表排序与显示） */
    val count: Int = 0
) {
    companion object {
        fun fromJson(o: JSONObject): NamedEntry? {
            val id = o.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: return null
            val name = o.optString("name", "")
            if (name.isBlank()) return null
            return NamedEntry(id = id, name = name, count = o.optInt("count", 0))
        }
    }
}

/**
 * 声优字典的一项。
 *
 * 与 [Circle] 结构相同但**刻意分成两个类型**：它们会走进不同的字典文件、
 * 对应不同的接口与检索参数。共用一个类型的话，"把社团当成声优"
 * 这种错误不会在编译期被发现，只会在运行时显示错数据。
 */
data class VoiceActor(
    /** UUID 字符串 */
    val id: String,
    val name: String,
    val count: Int = 0
) {
    companion object {
        fun fromJson(o: JSONObject): VoiceActor? =
            NamedEntry.fromJson(o)?.let { VoiceActor(it.id, it.name, it.count) }
    }
}

/** 社团字典的一项；理由同 [VoiceActor]（社团 id 是整数） */
data class Circle(
    val id: String,
    val name: String,
    val count: Int = 0
) {
    companion object {
        fun fromJson(o: JSONObject): Circle? =
            NamedEntry.fromJson(o)?.let { Circle(it.id, it.name, it.count) }
    }
}

/**
 * 一次列表检索的筛选条件 —— 标签 / 声优 / 社团**至多一个**生效。
 *
 * 服务端的三个端点是互斥的（`/api/tags/{id}/works`、`/api/vas/{id}/works`、
 * `/api/circles/{id}/works`），所以这里不表达"同时按标签和声优筛"这种
 * 服务端并不支持的状态。
 *
 * `vaId` 是 UUID 字符串（声优），`tagId` / `circleId` 是整数。
 */
data class WorkFilter(
    val tagId: Int? = null,
    val vaId: String? = null,
    val circleId: Int? = null
) {
    /** 有没有实际条件（全空表示不带筛选） */
    val isEmpty: Boolean get() = tagId == null && vaId == null && circleId == null

    companion object {
        val NONE = WorkFilter()
    }
}
