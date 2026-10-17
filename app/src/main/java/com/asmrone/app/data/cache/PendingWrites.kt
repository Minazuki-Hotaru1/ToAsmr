package com.asmrone.app.data.cache

import android.content.Context
import com.asmrone.app.data.model.CollectionStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条**待补写**的操作：本地已经改了，但服务端没收到（或没接受）。
 *
 * 为什么需要它：如果写入失败就只把本地改掉，那这条意图会在下次核对云端时
 * 被服务端的旧数据静默覆盖 —— 用户看到状态"自己跳回去了"。
 * 所以失败时把意图**记在这里**，等下次有机会再补写到云端。
 */
sealed interface PendingWrite {
    /** 作品在 `/api/review` 上的标记状态；[status] 为 null 表示移除标记 */
    data class CollectionMark(
        override val workId: Int,
        val status: CollectionStatus?,
        val rating: Int? = null,
        override val createdAt: Long
    ) : PendingWrite {
        override val dedupeKey: String get() = "mark|$workId"
    }

    /** 把作品加入某个收藏夹 */
    data class PlaylistAdd(
        val playlistId: String,
        override val workId: Int,
        override val createdAt: Long
    ) : PendingWrite {
        override val dedupeKey: String get() = "plAdd|$playlistId|$workId"
    }

    /** 把作品移出某个收藏夹 */
    data class PlaylistRemove(
        val playlistId: String,
        override val workId: Int,
        override val createdAt: Long
    ) : PendingWrite {
        override val dedupeKey: String get() = "plRemove|$playlistId|$workId"
    }

    /**
     * 去重键。
     *
     * 同一"操作类型 + 作品 + 收藏夹"只保留**最后一次**意图：
     * 用户连点几次收藏/取消，最终只关心最后一次选了什么。
     * 收藏夹维度必须进键，否则"从 A 夹移出"会把"加入 B 夹"挤掉。
     */
    val dedupeKey: String

    /** 记录时间，重放时按它排序 */
    val createdAt: Long

    /** 涉及的作品 id（"待同步"提示按它标记作品） */
    val workId: Int
}

/** 从 JSON 读回一条待补写记录；认不出来（版本变了）返回 null */
internal fun pendingWriteFromJson(o: JSONObject): PendingWrite? {
    val op = o.optString("op", "")
    val workId = o.optInt("workId", 0)
    if (workId <= 0) return null
    val createdAt = o.optLong("createdAt", 0L)
    return when (op) {
        "mark" -> PendingWrite.CollectionMark(
            workId = workId,
            // 空串代表"移除标记"，与 null 同义
            status = CollectionStatus.fromApi(o.optString("status", "")),
            rating = o.optInt("rating", 0).takeIf { it > 0 },
            createdAt = createdAt
        )

        "plAdd" -> PendingWrite.PlaylistAdd(
            playlistId = o.optString("playlistId", ""),
            workId = workId,
            createdAt = createdAt
        )

        "plRemove" -> PendingWrite.PlaylistRemove(
            playlistId = o.optString("playlistId", ""),
            workId = workId,
            createdAt = createdAt
        )

        else -> null
    }
}

internal fun PendingWrite.toJson(): JSONObject = JSONObject().apply {
    put("workId", workId)
    put("createdAt", createdAt)
    when (this@toJson) {
        is PendingWrite.CollectionMark -> {
            put("op", "mark")
            put("status", status?.apiValue ?: "")
            put("rating", rating ?: 0)
        }

        is PendingWrite.PlaylistAdd -> {
            put("op", "plAdd")
            put("playlistId", playlistId)
        }

        is PendingWrite.PlaylistRemove -> {
            put("op", "plRemove")
            put("playlistId", playlistId)
        }
    }
}

/**
 * 待补写操作的本地存储。
 *
 * 与收藏缓存同样用**原子 JSON 文件**（写临时文件再 rename），
 * 因为这里面装的是"用户已经表达过、但还没落地"的意图 —— 掉电写坏就等于丢失用户操作。
 *
 * 按 host + 账号分文件：换账号后不该看到、也不该替上一个人补写。
 */
object PendingWriteStore {

    private lateinit var appContext: Context

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
    }

    private fun keyFor(host: String, user: String): String =
        SyncCacheStore.SyncKeys.pendingWrites(host, user)

    /** 读取当前账号的全部待补写操作（按记录时间升序） */
    fun read(host: String, user: String): List<PendingWrite> {
        if (!::appContext.isInitialized) return emptyList()
        if (host.isBlank() || user.isBlank()) return emptyList()
        val raw = CacheFiles.read(appContext, keyFor(host, user)) ?: return emptyList()
        return runCatching {
            val o = JSONObject(raw)
            if (o.optInt("v", 0) != CacheFiles.SCHEMA_VERSION) return emptyList()
            val array = o.optJSONArray("items") ?: JSONArray()
            buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { item ->
                        pendingWriteFromJson(item)?.let(::add)
                    }
                }
            }.sortedBy { it.createdAt }
        }.getOrDefault(emptyList())
    }

    private fun write(host: String, user: String, items: List<PendingWrite>): Boolean {
        if (!::appContext.isInitialized) return false
        if (host.isBlank() || user.isBlank()) return false
        val json = JSONObject().apply {
            put("v", CacheFiles.SCHEMA_VERSION)
            put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
        }
        return CacheFiles.write(appContext, keyFor(host, user), json.toString())
    }

    /**
     * 记入一条待补写操作（同 [PendingWrite.dedupeKey] 的旧记录会被替换）。
     *
     * 替换而不是追加：用户连点几次，只有**最后一次**意图是有意义的。
     */
    fun add(host: String, user: String, write: PendingWrite) {
        val existing = read(host, user).filterNot { it.dedupeKey == write.dedupeKey }
        this.write(host, user, existing + write)
    }

    /** 补写成功后移除 */
    fun remove(host: String, user: String, write: PendingWrite) {
        write(host, user, read(host, user).filterNot { it.dedupeKey == write.dedupeKey })
    }

    /**
     * 丢掉某个收藏夹的全部待补写操作。
     *
     * ## 为什么用户"删除收藏夹"时要调用它
     *
     * 删除收藏夹意味着"我不要这个收藏夹里的任何内容了"，
     * 于是那些"加入该收藏夹"的待补写意图**已经失去意义**：
     * 继续留着只会让每次对齐都为它多打一次注定失败的请求
     * （收藏夹本身已经不存在了）。
     *
     * 注意只清**这个收藏夹**的，不动别的收藏夹和标记类的待补写。
     */
    fun removeAllForPlaylist(host: String, user: String, playlistId: String) {
        if (!::appContext.isInitialized) return
        if (host.isBlank() || user.isBlank() || playlistId.isBlank()) return
        write(host, user, withoutPlaylist(read(host, user), playlistId))
    }
}

/**
 * 从一组待补写操作里剔掉**指定收藏夹**的那些（纯函数，便于单测）。
 *
 * 与 [PendingWriteStore.removeAllForPlaylist] 分开是为了能在纯 JVM 上验证：
 * 那个方法依赖 Android 的 Context 与文件缓存，测不了。
 *
 * 只剔 [PendingWrite.PlaylistAdd] / [PendingWrite.PlaylistRemove] 中
 * `playlistId` 相同的；标记类（[PendingWrite.CollectionMark]）与其它收藏夹
 * 的记录一律保留 —— 删一个收藏夹不该影响别处的未送达意图。
 */
internal fun withoutPlaylist(items: List<PendingWrite>, playlistId: String): List<PendingWrite> =
    items.filterNot { write ->
        when (write) {
            is PendingWrite.PlaylistAdd -> write.playlistId == playlistId
            is PendingWrite.PlaylistRemove -> write.playlistId == playlistId
            is PendingWrite.CollectionMark -> false
        }
    }
