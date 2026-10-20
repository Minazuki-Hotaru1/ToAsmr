package com.asmrone.app.data.links

import android.net.Uri

/**
 * 作品链接 / RJ 号的解析。
 *
 * 有两条进来的路，解析出来的都是同一种东西 —— **一个"作品线索"字符串**：
 *  - 站内链接：`https://asmr.one/work/RJ01700554`、`https://asmr.one/work/1700554`
 *  - 搜索框里直接敲：`rj01700554`、`RJ1700554`
 *
 * 线索本身不一定是数据库 id（RJ 号就不是），所以解析完之后还要走
 * [com.asmrone.app.data.repo.AsmrRepository.resolveWorkId] 换成 workId。
 *
 * ## 为什么 RJ 号要保留前导零
 *
 * 实测（api.asmr-200.com，`GET /api/search/{keyword}`）：
 *  - `RJ01700554` → 1 条命中
 *  - `RJ1700554`（少一位 0）→ **0 条**
 *  - `1700554`（纯数字）→ 1 条
 *
 * 所以这里**只改前缀的大小写，数字部分一个字符都不动**；用户少打了 0 的情况
 * 由 resolver 用去掉前导零的纯数字再查一次兜底。
 */
object WorkLink {

    /** 自定义 scheme：`toasmr://work/RJ01700554`。站内其它 App 分享链接时用得上。 */
    const val SCHEME = "toasmr"

    /**
     * 认的域名。
     *
     * 只放 asmr.one 系列：api.* 不是给人点链接用的（它返回 JSON），但它确实
     * 出现在部分第三方站点的"打开方式"里，顺手认掉不会有副作用。
     */
    private val HOSTS = setOf(
        "asmr.one",
        "www.asmr.one",
        "beta.asmr.one",
        "api.asmr.one"
    )

    /** 本站的作品页固定是 `/work/<RJ 或 id>`。 */
    private const val WORK_SEGMENT = "work"

    /** RJ 号的数字部分最多 9 位（DLSite 目前是 8 位，留一位余量）。 */
    private const val MAX_DIGITS = 9

    /**
     * 把用户输入识别成 RJ 号。
     *
     * 只认 `rj` + 纯数字（大小写不敏感），数字原样保留；其它输入一律返回 null，
     * 让调用方走正常搜索。
     *
     * ```
     * rjCodeOf("rj01700554")  // "RJ01700554"
     * rjCodeOf(" RJ243448 ")  // "RJ243448"
     * rjCodeOf("rj")          // null
     * rjCodeOf("rj123x")      // null
     * rjCodeOf("耳かき")       // null
     * ```
     */
    fun rjCodeOf(input: String): String? {
        val text = input.trim()
        if (text.length < 3) return null
        // 第 3 个字符必须是数字：`rj` 开头的普通英文词（如 "rja"）不该被当成 RJ 号
        if (!text[2].isDigit()) return null
        val prefix = text.substring(0, 2)
        if (!prefix.equals("rj", ignoreCase = true)) return null
        val digits = text.substring(2)
        if (digits.length > MAX_DIGITS) return null
        if (digits.any { !it.isDigit() }) return null
        return "RJ" + digits
    }

    /**
     * 从 `Uri` 里取作品线索；不是作品链接就返回 null。
     *
     * 支持的形式：
     *  - `https://asmr.one/work/RJ01700554`
     *  - `https://asmr.one/work/1700554?…`
     *  - `toasmr://work/RJ01700554`
     *  - `toasmr:///work/RJ01700554`
     */
    fun tokenFromUri(uri: Uri?): String? {
        if (uri == null) return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val segments = uri.pathSegments.orEmpty()
        val token = when (scheme) {
            "http", "https" -> {
                val host = uri.host?.lowercase()
                if (host == null || host !in HOSTS) return null
                workSegmentToken(segments)
            }
            SCHEME -> {
                // `toasmr://work/RJ…`：host = "work"，线索在 path 第 1 段
                if (uri.host.equals(WORK_SEGMENT, ignoreCase = true)) {
                    segments.firstOrNull()
                } else {
                    workSegmentToken(segments)
                }
            }
            else -> null
        }
        return token?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * `String` 版的 [tokenFromUri]。
     *
     * 额外容忍**没有写协议**的写法（`asmr.one/work/RJ01700554`）—— 从聊天软件里
     * 复制出来的链接常被吃掉了 `https://`。补完协议仍然要过 [HOSTS] 校验，
     * 所以随便一段文字（比如 `rj123`）不会被误判成链接。
     */
    fun tokenFromUrl(url: String?): String? {
        val text = url?.trim().orEmpty()
        if (text.isEmpty()) return null
        val candidate = if (text.contains("://")) text else "https://$text"
        val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
        return tokenFromUri(uri)
    }

    /** `/work/<token>` → `<token>`；不是这种形状返回 null。 */
    private fun workSegmentToken(segments: List<String>): String? {
        if (segments.size < 2) return null
        if (!segments[0].equals(WORK_SEGMENT, ignoreCase = true)) return null
        return segments[1]
    }
}
