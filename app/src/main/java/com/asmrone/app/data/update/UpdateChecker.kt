package com.asmrone.app.data.update

import com.asmrone.app.data.diag.HttpLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 一次更新的结果。
 *
 * 刻意不用 `Throwable` 传递失败：调用方只有三种反应（升级 / 没事 / 说一句原因），
 * 把异常转成人能看懂的一句话，界面就不用再翻译一次网络错误。
 */
sealed interface UpdateResult {
    data class Available(val info: ReleaseInfo) : UpdateResult
    data object UpToDate : UpdateResult
    data class Failed(val message: String) : UpdateResult
}

/**
 * GitHub Release 里跟"要不要更新"有关的那几个字段。
 *
 * @param apkUrl 直链（release 里的 `*.apk` 资产），没有资产时为 null —— 只有源码包的
 *   release 也能提示升级，只是按钮要跳到网页。
 */
data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String?,
    val apkBytes: Long,
    val publishedAt: String
)

/**
 * 检查 GitHub 上有没有比本机更新的 Release。
 *
 * 只读公开接口，不带 token：
 * - 未登录时 GitHub 允许每小时 60 次/IP，一次点击一次请求，够用；
 * - 换成带 token 反而要把用户凭据塞进这个"看一眼版本号"的请求里，不值得。
 */
object UpdateChecker {

    const val REPO = "Minazuki-Hotaru1/ToAsmr"

    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val USER_AGENT = "ToAsmr-Android"

    /** 说明文字太长会把对话框撑爆炸，截到这个长度足够看"这版改了什么"。 */
    private const val MAX_NOTES = 600

    suspend fun check(currentVersion: String): UpdateResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = conn.responseCode
            HttpLog.response("GET $API → $code")

            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                // 仓库还没有任何 release —— 不是错误，只是没什么可比的
                return@withContext UpdateResult.Failed("仓库里还没有发布版本")
            }
            if (code !in 200..299) {
                return@withContext UpdateResult.Failed("检查更新失败（HTTP $code）")
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val info = parse(body) ?: return@withContext UpdateResult.Failed("看不懂返回的版本信息")

            if (isNewer(info.versionName, currentVersion)) {
                UpdateResult.Available(info)
            } else {
                UpdateResult.UpToDate
            }
        } catch (e: Exception) {
            UpdateResult.Failed(describe(e))
        } finally {
            conn?.disconnect()
        }
    }

    private fun parse(body: String): ReleaseInfo? = runCatching {
        val o = JSONObject(body)
        val apk = o.optJSONArray("assets")?.let { assets ->
            (0 until assets.length())
                .mapNotNull { assets.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
        }
        ReleaseInfo(
            tagName = o.optString("tag_name").ifBlank { "unknown" },
            versionName = o.optString("tag_name").trim().removePrefix("v").removePrefix("V"),
            title = o.optString("name").ifBlank { o.optString("tag_name") },
            notes = o.optString("body").trim().take(MAX_NOTES),
            pageUrl = o.optString("html_url"),
            apkUrl = apk?.optString("browser_download_url")?.takeIf { it.isNotBlank() },
            apkBytes = apk?.optLong("size", 0L) ?: 0L,
            publishedAt = o.optString("published_at").take(10)
        )
    }.getOrNull()

    /**
     * 远端版本比本机新吗。
     *
     * 按 `.` 切段逐段比数字（`0.5.10` 必须大于 `0.5.9`，字符串比较会反过来）；
     * 缺失的段当 0，`v` 前缀与非数字后缀（`-beta`、`  `）都忽略。
     */
    internal fun isNewer(remote: String, local: String): Boolean {
        val a = segments(remote)
        val b = segments(local)
        if (a.isEmpty() || b.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun segments(version: String): List<Int> =
        version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .split('.', '-', '+', ' ')
            .mapNotNull { part ->
                val digits = part.takeWhile { it.isDigit() }
                digits.toIntOrNull()
            }
            .ifEmpty { emptyList() }

    private fun describe(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "连不上 GitHub，检查一下网络"
        is java.net.SocketTimeoutException -> "GitHub 响应超时，稍后再试"
        is java.io.IOException -> "网络异常，稍后再试"
        else -> "检查更新失败：${e.javaClass.simpleName}"
    }
}
