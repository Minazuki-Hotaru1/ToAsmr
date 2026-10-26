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
 * @param apkUrl 直链（release 里的 `*.apk` 资产）。**只有走 API 才拿得到**：备用通道
 *   （atom 订阅源、网页跳转）只知道版本号，所以那里是 null，按钮退化成「前往 Release 页」。
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
 * ## 为什么要三条通道
 *
 * 只查 `api.github.com` 时，国内网络下最常见的失败不是"没网"，而是这个域名被单独
 * 阻断或限流（`github.com` 能开、`api.github.com` 连不上；或代理规则只放了前者）。
 * 于是按"信息最全 → 信息最少"的顺序依次尝试：
 *
 * 1. `api.github.com/.../releases/latest`：JSON，能拿到 APK 直链与更新说明；
 * 2. `github.com/.../releases.atom`：公开订阅源，只有版本号与发布时间；
 * 3. `github.com/.../releases/latest` 的 302 跳转：`Location` 头里带着 tag。
 *
 * 后两条拿不到直链，但至少能回答"有没有新版本"，且入口是 `github.com` 而不是
 * `api.github.com` —— 这恰好绕开最常见的那个阻断点。
 *
 * 不带 token：公开接口每小时 60 次/IP，一次点击一次请求，够用；为看个版本号把用户
 * 凭据塞进请求里不值得。
 */
object UpdateChecker {

    const val REPO = "Minazuki-Hotaru1/ToAsmr"

    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val ATOM = "https://github.com/$REPO/releases.atom"
    private const val WEB_LATEST = "https://github.com/$REPO/releases/latest"
    private const val WEB_PREFIX = "https://github.com/$REPO/releases/tag/"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val USER_AGENT = "ToAsmr-Android"

    /** 说明文字太长会把对话框撑爆炸，截到这个长度足够看"这版改了什么"。 */
    private const val MAX_NOTES = 600

    suspend fun check(currentVersion: String): UpdateResult = withContext(Dispatchers.IO) {
        val reasons = ArrayList<String>(3)

        // ① API：只有这里能拿到 APK 直链
        val api = runCatching { fetchFromApi() }
            .onFailure { reasons += "api:" + shortReason(it) }
            .getOrNull()
        if (api != null) return@withContext decide(api, currentVersion)

        // ② atom 订阅源：版本号 + 发布时间
        runCatching { fetchFromAtom() }
            .onSuccess { return@withContext decide(it, currentVersion) }
            .onFailure { reasons += "atom:" + shortReason(it) }

        // ③ 网页 302：Location 里带 tag
        runCatching { fetchFromRedirect() }
            .onSuccess { return@withContext decide(it, currentVersion) }
            .onFailure { reasons += "网页:" + shortReason(it) }

        UpdateResult.Failed("三条通道都没连上 GitHub（${reasons.joinToString("；")}）")
    }

    /** 拿到 ReleaseInfo 之后唯一的判断题，三条通道共用。 */
    private fun decide(info: ReleaseInfo, currentVersion: String): UpdateResult =
        if (isNewer(info.versionName, currentVersion)) UpdateResult.Available(info) else UpdateResult.UpToDate

    // ─── ① API ───────────────────────────────────────────────────────────────

    private fun fetchFromApi(): ReleaseInfo {
        val (code, text) = get(API, json = true)
        if (code != 200) throw HttpStatus(code, "api")
        return parseJson(text) ?: error("JSON 里没有 tag_name")
    }

    private fun parseJson(body: String): ReleaseInfo? = runCatching {
        val o = JSONObject(body)
        val apk = o.optJSONArray("assets")?.let { assets ->
            (0 until assets.length())
                .mapNotNull { assets.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
        }
        ReleaseInfo(
            tagName = o.optString("tag_name").ifBlank { "unknown" },
            versionName = versionOf(o.optString("tag_name")),
            title = o.optString("name").ifBlank { o.optString("tag_name") },
            notes = o.optString("body").trim().take(MAX_NOTES),
            pageUrl = o.optString("html_url").ifBlank { WEB_LATEST },
            apkUrl = apk?.optString("browser_download_url")?.takeIf { it.isNotBlank() },
            apkBytes = apk?.optLong("size", 0L) ?: 0L,
            publishedAt = o.optString("published_at").take(10)
        )
    }.getOrNull()

    // ─── ② atom ──────────────────────────────────────────────────────────────

    /**
     * 解析 Releases 的 atom 源。
     *
     * 手写扫描而不是正则：这个工程在 Android 的 ICU 上被正则坑过一次
     * （`\{[^}]*}` 在桌面 JVM 合法、在 Android 抛 `PatternSyntaxException`），
     * 凡是"能给个字符串 find 就解决"的地方都不再引入正则。
     */
    private fun fetchFromAtom(): ReleaseInfo {
        val (code, text) = get(ATOM, json = false)
        if (code != 200) throw HttpStatus(code, "atom")

        val entry = text.indexOf("<entry>").takeIf { it >= 0 } ?: error("atom 里没有 entry")
        val head = text.substring(entry)

        // <link rel="alternate" type="text/html" href="https://github.com/.../releases/tag/v0.5.4"/>
        val hrefMark = head.indexOf("/releases/tag/")
        if (hrefMark < 0) error("atom 里没有 releases/tag 链接")
        val rest = head.substring(hrefMark + "/releases/tag/".length)
        val tag = rest.takeWhile { it != '"' && it != '<' && !it.isWhitespace() }
        if (tag.isBlank()) error("atom 里的 tag 是空的")

        val title = between(head, "<title>", "</title>")
        val updated = between(head, "<updated>", "</updated>").take(10)

        return ReleaseInfo(
            tagName = tag,
            versionName = versionOf(tag),
            title = title.ifBlank { "ToAsmr $tag" },
            notes = notesFromAtom(head),
            pageUrl = WEB_PREFIX + tag,
            apkUrl = null,
            apkBytes = 0L,
            publishedAt = updated
        )
    }

    /**
     * atom 里的正文是 HTML 转义过的，剥成纯文本才放得进对话框。
     *
     * 同样是手写扫描而不是正则；`<li>` 换成 `· ` 是为了让清单还看得出层次，
     * 否则剥完就是一整段没有断句的长句。
     */
    private fun notesFromAtom(head: String): String {
        val open = head.indexOf("<content")
        if (open < 0) return ""
        val bodyStart = head.indexOf('>', open).takeIf { it > 0 } ?: return ""
        val close = head.indexOf("</content>", bodyStart)
        if (close < 0) return ""
        return stripHtml(head.substring(bodyStart + 1, close)).take(MAX_NOTES)
    }

    private fun stripHtml(html: String): String {
        val sb = StringBuilder(html.length)
        var i = 0
        while (i < html.length) {
            val c = html[i]
            when {
                c == '<' -> {
                    val end = html.indexOf('>', i)
                    if (end < 0) break
                    if (html.regionMatches(i, "<li>", 0, 4)) sb.append("· ")
                    else if (html.startsWith("</p", i) || html.startsWith("<br", i)) sb.append('\n')
                    i = end + 1
                }

                c == '&' -> {
                    val semi = html.indexOf(';', i)
                    val entity = if (semi > i && semi - i <= 8) html.substring(i, semi + 1) else ""
                    val replacement = when (entity) {
                        "&lt;" -> "<"
                        "&gt;" -> ">"
                        "&amp;" -> "&"
                        "&quot;" -> "\""
                        "&#39;", "&apos;" -> "'"
                        "&nbsp;" -> " "
                        else -> null
                    }
                    if (replacement == null) {
                        sb.append(c)
                        i += 1
                    } else {
                        sb.append(replacement)
                        i = semi + 1
                    }
                }

                else -> {
                    sb.append(c)
                    i += 1
                }
            }
        }
        // 折叠空行与行内多余空格
        return sb.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()
    }

    // ─── ③ 网页 302 ──────────────────────────────────────────────────────────

    /**
     * `github.com/.../releases/latest` 会 302 到具体 tag。
     *
     * 不跟随跳转（`instanceFollowRedirects = false`）：要的就是 `Location` 这一个头，
     * 跟着跳会多下载一整页 HTML。
     */
    private fun fetchFromRedirect(): ReleaseInfo {
        var conn: HttpURLConnection? = null
        try {
            conn = open(WEB_LATEST, json = false, follow = false)
            val code = conn.responseCode
            HttpLog.response("GET $WEB_LATEST → $code")
            val location = conn.getHeaderField("Location").orEmpty()
            if (code !in 300..399 || location.isBlank()) throw HttpStatus(code, "网页")
            val mark = location.indexOf("/releases/tag/")
            if (mark < 0) error("跳转地址里没有 tag")
            val tag = location.substring(mark + "/releases/tag/".length).trimEnd('/')
            if (tag.isBlank()) error("跳转地址里的 tag 是空的")
            return ReleaseInfo(
                tagName = tag,
                versionName = versionOf(tag),
                title = "ToAsmr $tag",
                notes = "",
                pageUrl = WEB_PREFIX + tag,
                apkUrl = null,
                apkBytes = 0L,
                publishedAt = ""
            )
        } finally {
            conn?.disconnect()
        }
    }

    // ─── HTTP ────────────────────────────────────────────────────────────────

    private fun get(url: String, json: Boolean): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        try {
            conn = open(url, json = json, follow = true)
            val code = conn.responseCode
            HttpLog.response("GET $url → $code")
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            conn?.disconnect()
        }
    }

    private fun open(url: String, json: Boolean, follow: Boolean): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = follow
            if (json) {
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            } else {
                setRequestProperty("Accept", "text/html,application/atom+xml,*/*")
            }
            setRequestProperty("User-Agent", USER_AGENT)
        }

    /** 用异常而不是返回值传"HTTP 状态不对"，这样 `runCatching` 链能统一收口。 */
    private class HttpStatus(val code: Int, val channel: String) :
        Exception("$channel 返回 HTTP $code")

    // ─── 小工具 ──────────────────────────────────────────────────────────────

    private fun between(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        if (from < 0) return ""
        val begin = from + start.length
        val to = source.indexOf(end, begin)
        if (to < 0) return ""
        return source.substring(begin, to).trim()
    }

    /** `v0.5.4` / `V0.5.4` / `0.5.4` 都归一成 `0.5.4`。 */
    private fun versionOf(tag: String): String =
        tag.trim().removePrefix("v").removePrefix("V").ifBlank { tag.trim() }

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

    /**
     * 失败原因压成几字，拼在最后那句提示里。
     *
     * 带上异常类名是有意的：用户截图反馈时，"UnknownHostException" 和
     * "SocketTimeoutException" 指向完全不同的两件事（域名被墙 / 链路太慢），
     * 只说"网络异常"就永远分不清。
     */
    private fun shortReason(t: Throwable): String = when (t) {
        is HttpStatus -> "HTTP ${t.code}"
        is java.net.UnknownHostException -> "域名解析失败"
        is java.net.SocketTimeoutException -> "超时"
        is java.net.ConnectException -> "连接被拒"
        is javax.net.ssl.SSLException -> "TLS 握手失败"
        is java.io.IOException -> "网络异常"
        else -> t.message ?: t.javaClass.simpleName
    }
}
