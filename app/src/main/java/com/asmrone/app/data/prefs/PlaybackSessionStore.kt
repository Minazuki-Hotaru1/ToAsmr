package com.asmrone.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.asmrone.app.data.cache.TrackJson
import com.asmrone.app.data.model.Track
import com.asmrone.app.player.PlayerState
import org.json.JSONObject

/** 从 SharedPreferences 里恢复出来的一次播放会话 */
data class SavedPlayback(
    val workId: Int,
    /** RJ 号；旧版本快照里没有，为 null */
    val workSourceId: String? = null,
    val workTitle: String,
    val coverUrl: String?,
    val tracks: List<Track>,
    val index: Int,
    val positionMs: Int,
    val durationMs: Int
)

/**
 * 播放会话的本地快照。
 *
 * 只保存「重新展示迷你播放器 / 从上次位置继续」所需的最小信息：
 * 作品、封面、曲目队列、当前曲目、播放位置与总时长。
 *
 * - 不保存 `isPlaying`：冷启动恢复为暂停态，避免一打开 App 就自动出声；
 * - `stop()` 会显式清空；普通退出只写快照，不删除。
 */
object PlaybackSessionStore {

    private const val FILE = "asmrone_playback"
    private const val KEY_SESSION = "last_session"
    private const val VERSION = 1

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    fun save(state: PlayerState, synchronous: Boolean = false) {
        if (!::sp.isInitialized) return
        if (!state.isActive || state.workId <= 0) return

        val root = JSONObject().apply {
            put("version", VERSION)
            put("workId", state.workId)
            put("sourceId", state.workSourceId ?: "")
            put("workTitle", state.workTitle)
            put("coverUrl", state.coverUrl ?: "")
            put("index", state.index)
            put("positionMs", state.positionMs)
            put("durationMs", state.durationMs)
            put("savedAt", System.currentTimeMillis())
            // 曲目字段映射与文件树缓存共用同一个编解码（TrackJson），
            // 避免两处各写一套、字段读错位
            put("tracks", TrackJson.encode(state.tracks))
        }
        val editor = sp.edit().putString(KEY_SESSION, root.toString())
        if (synchronous) {
            editor.commit()
        } else {
            editor.apply()
        }
    }

    fun load(): SavedPlayback? {
        if (!::sp.isInitialized) return null
        val raw = sp.getString(KEY_SESSION, null) ?: return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val workId = root.optInt("workId", 0)
        if (workId <= 0) return null

        val tracks = TrackJson.decode(root.optJSONArray("tracks"))
        if (tracks.isEmpty()) return null

        val index = root.optInt("index", 0).coerceIn(0, tracks.lastIndex)
        val savedDuration = root.optInt("durationMs", 0).coerceAtLeast(0)
        val duration = savedDuration.takeIf { it > 0 }
            ?: ((tracks[index].durationSec ?: 0.0) * 1000.0).toInt().coerceAtLeast(0)
        val position = root.optInt("positionMs", 0).coerceAtLeast(0).let {
            if (duration > 0) it.coerceAtMost(duration) else it
        }

        return SavedPlayback(
            workId = workId,
            workSourceId = root.optString("sourceId").ifBlank { null },
            workTitle = root.optString("workTitle"),
            coverUrl = root.optString("coverUrl").ifBlank { null },
            tracks = tracks,
            index = index,
            positionMs = position,
            durationMs = duration
        )
    }

    fun clear() {
        if (!::sp.isInitialized) return
        sp.edit().remove(KEY_SESSION).apply()
    }
}
