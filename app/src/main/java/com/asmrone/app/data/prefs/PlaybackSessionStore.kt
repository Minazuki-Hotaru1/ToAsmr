package com.asmrone.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.asmrone.app.data.model.Track
import com.asmrone.app.player.PlayerState
import org.json.JSONArray
import org.json.JSONObject

/** 从 SharedPreferences 里恢复出来的一次播放会话 */
data class SavedPlayback(
    val workId: Int,
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

        val trackArray = JSONArray()
        state.tracks.forEach { track ->
            trackArray.put(
                JSONObject().apply {
                    put("title", track.title)
                    put("type", track.type ?: "audio")
                    track.hash?.takeIf { it.isNotBlank() }?.let { put("hash", it) }
                    track.size?.let { put("size", it) }
                    track.durationSec?.let { put("durationSec", it) }
                    put("mediaStreamUrl", track.mediaStreamUrl ?: "")
                    put("mediaDownloadUrl", track.mediaDownloadUrl ?: "")
                    put("streamLowQualityUrl", track.streamLowQualityUrl ?: "")
                    put("path", track.path)
                }
            )
        }

        val root = JSONObject().apply {
            put("version", VERSION)
            put("workId", state.workId)
            put("workTitle", state.workTitle)
            put("coverUrl", state.coverUrl ?: "")
            put("index", state.index)
            put("positionMs", state.positionMs)
            put("durationMs", state.durationMs)
            put("savedAt", System.currentTimeMillis())
            put("tracks", trackArray)
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

        val array = root.optJSONArray("tracks") ?: return null
        val tracks = buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val title = o.optString("title").trim()
                if (title.isEmpty()) continue
                add(
                    Track(
                        title = title,
                        type = o.optString("type").ifBlank { "audio" },
                        hash = o.optString("hash").ifBlank { null },
                        size = if (o.has("size") && !o.isNull("size")) o.optLong("size") else null,
                        durationSec = if (o.has("durationSec") && !o.isNull("durationSec")) {
                            o.optDouble("durationSec")
                        } else {
                            null
                        },
                        mediaStreamUrl = o.optString("mediaStreamUrl").ifBlank { null },
                        mediaDownloadUrl = o.optString("mediaDownloadUrl").ifBlank { null },
                        streamLowQualityUrl = o.optString("streamLowQualityUrl").ifBlank { null },
                        path = o.optString("path").ifBlank { "track-$i" }
                    )
                )
            }
        }
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
