package com.asmrone.app.data.audio

import com.asmrone.app.data.diag.HttpLog
import com.asmrone.app.data.prefs.AppPrefs
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **本地音频代理** —— "边播边缓存"的实现。
 *
 * ## 为什么要有一个代理
 *
 * Android 的 `MediaPlayer` 只接受 URL（或只读的 `MediaDataSource`），
 * **没有** Flutter `just_audio` 那种"自定义 `StreamAudioSource`"的钩子，
 * 也没有"把流写进磁盘"的开关。所以拿不到它正在读的字节。
 *
 * 但它的请求是可以被**指向**别处的：让它连 `127.0.0.1`，由我们来转发。
 * 转发时数据必然经过我们手里 —— 那就顺手写一份到磁盘。
 *
 * ```
 * MediaPlayer ──Range 请求──> 127.0.0.1:PORT ──转发──> CDN
 *                  ↑                            │
 *                  └──── 字节同时喂给播放器 ──────┘
 *                                 └─ 同时追加写 .part（边播边缓存）
 * ```
 *
 * 这与参考实现（`testAsmrOne/lib/src/services/caching_stream_audio_source.dart`）
 * 是**同一套机制**，只是 Flutter 那边在进程内做，我们这边要过一层回环 HTTP。
 *
 * ## 核心规则：只缓存**顺序**读取
 *
 * 只有当"本次请求的起点 == 已缓存前缀的长度"时才写入磁盘。
 * 否则（用户往回拖、跳到中段）**只透传、不写**。
 *
 * 为什么必须这样：一旦允许随机偏移写入，`.part` 里就会出现**空洞**，
 * 而文件长度再也反映不出真实完整性 —— 后续的长度校验会把残缺文件
 * 判为完整。那是**静默的数据损坏**，比不缓存糟得多。
 * 放弃一次写入的代价只是"这段没缓存上"，下次顺序播到那里会补上。
 *
 * ## 缓存量由播放器驱动（天然限速）
 *
 * 代理**不会**抢在播放器前面把整首拉完：喂给播放器的 `output.write` 会阻塞，
 * 直到它把数据取走（受它自己的缓冲大小约束）。所以"播放器读多少，
 * 就跟着缓存多少" —— 这是天然的限速，不需要任何额度去模拟它。
 *
 * 唯一的封顶是**用户可配的单曲上限**（见 [TrackCacheLedger]）：
 * 一首曲子最多缓存多少。大体积音轨（WAV/FLAC 实测到 92MB）需要它足够大
 * 才能在一次播放里缓存完整。
 */
class LocalAudioProxy(private val store: AudioCacheStore) {

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "audio-proxy").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)

    /** 本机回环地址上的端口；未启动时为 0 */
    @Volatile
    var port: Int = 0
        private set

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (running.get()) return
        val socket = ServerSocket(0, 50, InetAddress.getByName(LOOPBACK))
        server = socket
        port = socket.localPort
        running.set(true)
        pool.execute {
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    // 停止时 accept 必然抛异常，这是正常的退出路径
                    break
                }
                pool.execute { handleClient(client) }
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        port = 0
    }

    /**
     * 生成交给 `MediaPlayer.setDataSource` 的本地地址。
     *
     * 上游 URL 放进查询参数（URL-safe base64）。为什么要带上游 URL 而不是
     * 服务端给的那个 `hash`：实测同一个作品的不同音轨 URL 前缀可能完全不同
     * （`/media/stream/voice/…` 与 `/media/stream/daily/2026-10-16/…`），
     * 而我们要用**同一份字节**去归并缓存，直接带 URL 最没有歧义。
     */
    fun localUrlFor(upstreamUrl: String): String {
        val id = AudioCacheStore.idFor(upstreamUrl)
        val src = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(upstreamUrl.toByteArray(Charsets.UTF_8))
        return "http://$LOOPBACK:$port$PATH_PREFIX/$id?src=$src"
    }

    // ─────────────────────────── 请求处理 ───────────────────────────

    private fun handleClient(client: Socket) {
        // 调试期把日志落盘，便于用 adb 验证代理究竟收到了什么请求
        // （内存缓冲无法从外部读取）。release 下 flushToFile 是空操作。
        try {
            handleClientInner(client)
        } finally {
            HttpLog.flushToFile()
        }
    }

    private fun handleClientInner(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = SOCKET_TIMEOUT_MS
                val input = socket.getInputStream().buffered()
                val output = BufferedOutputStream(socket.getOutputStream())

                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) {
                    writeSimple(output, 400, "Bad Request")
                    return
                }
                val method = parts[0].uppercase()
                val path = parts[1]

                // 读掉请求头（我们需要 Range）
                var rangeHeader: String? = null
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = line.substringAfter(':').trim()
                    }
                }

                HttpLog.request("代理 $method $path  Range=${rangeHeader ?: "(无)"}")

                val target = parseTarget(path)
                if (target == null) {
                    writeSimple(output, 404, "Not Found")
                    return
                }

                // ① 已有完整缓存 → 直接读磁盘，一个字节都不走网络
                val complete = store.completeFile(target.id)
                if (complete != null && complete.exists()) {
                    HttpLog.request("代理命中完整缓存：${complete.name}")
                    store.touch(target.id, target.upstreamUrl, complete.length(), System.currentTimeMillis())
                    serveFromFile(complete, rangeHeader, output, method)
                    return
                }

                // ② 否则走上游，同时按"顺序"写入缓存
                streamFromUpstream(target, rangeHeader, output, method)
            }
        } catch (e: Exception) {
            HttpLog.request("代理处理请求失败：${e.message}")
        }
    }

    /** 从本地文件服务（可能带 Range） */
    private fun serveFromFile(file: File, rangeHeader: String?, output: OutputStream, method: String) {
        val total = file.length()
        val range = ByteRange.parse(rangeHeader)

        val start = (range?.from ?: 0L).coerceIn(0L, maxOf(total - 1, 0L))
        val end = (range?.to ?: (total - 1)).coerceIn(start, maxOf(total - 1, 0L))
        val length = end - start + 1

        val headers = StringBuilder()
        if (range != null) {
            headers.append("HTTP/1.1 206 Partial Content\r\n")
            headers.append("Content-Range: bytes $start-$end/$total\r\n")
        } else {
            headers.append("HTTP/1.1 200 OK\r\n")
        }
        headers.append("Content-Length: $length\r\n")
        headers.append("Accept-Ranges: bytes\r\n")
        headers.append("Content-Type: application/octet-stream\r\n")
        headers.append("Connection: close\r\n\r\n")
        output.write(headers.toString().toByteArray(Charsets.ISO_8859_1))
        output.flush()

        if (method == "HEAD") return

        file.inputStream().use { input ->
            input.skip(start)
            copyExactly(input, output, length)
        }
        output.flush()
    }

    /** 转发上游，并按顺序规则把字节追加进缓存 */
    private fun streamFromUpstream(
        target: Target,
        rangeHeader: String?,
        output: OutputStream,
        method: String
    ) {
        val requested = ByteRange.parse(rangeHeader)
        val upstream = openUpstream(target.upstreamUrl, rangeHeader)
        if (upstream == null) {
            writeSimple(output, 502, "Bad Gateway")
            return
        }

        try {
            val code = upstream.responseCode
            if (code !in 200..299) {
                HttpLog.request("代理上游返回 HTTP $code")
                writeSimple(output, 502, "Upstream $code")
                return
            }

            val contentType = upstream.contentType ?: "application/octet-stream"
            val contentRange = upstream.getHeaderField("Content-Range")
            val totalBytes = parseTotalFromContentRange(contentRange)
                ?: upstream.contentLengthLong.takeIf { it > 0 && code == 200 }
                ?: target.expectedTotal

            // 上游实际从哪开始（有 Content-Range 时以它为准）
            val actualStart = parseStartFromContentRange(contentRange)
                ?: (if (code == 206) (requested?.from ?: 0L) else 0L)

            // 告诉播放器什么，**以实测的上游响应为准**，而不是我们请求了什么。
            //
            // ⚠️ 这一点必须严格：如果客户端要 `bytes=0-1023` 而上游忽略了 Range
            // 直接回 200 全文，我们却回一个 206 + 客户端要的长度，
            // 播放器收到的是"声明 1024 字节，实际来了 92MB" —— 会直接损坏。
            // 所以：上游 200 就回 200（长度用上游的），上游 206 才回 206（用上游的 Content-Range）。
            val headers = StringBuilder()
            if (code == 206 && contentRange != null) {
                headers.append("HTTP/1.1 206 Partial Content\r\n")
                headers.append("Content-Range: $contentRange\r\n")
            } else {
                headers.append("HTTP/1.1 200 OK\r\n")
            }
            if (upstream.contentLengthLong >= 0) {
                headers.append("Content-Length: ${upstream.contentLengthLong}\r\n")
            }
            headers.append("Accept-Ranges: bytes\r\n")
            headers.append("Content-Type: $contentType\r\n")
            headers.append("Connection: close\r\n\r\n")
            output.write(headers.toString().toByteArray(Charsets.ISO_8859_1))
            output.flush()

            if (method == "HEAD") return

            // ── 顺序写入判定 ──────────────────────────────
            //
            // 只有"这次请求的起点 == 已有 .part 的长度"才写。
            // 起点为 0 且还没有 .part 时也成立（从头开始）。
            val cacheable = !store.isComplete(target.id)
            val writeOffset = if (cacheable) store.cachedBytes(target.id) else -1L
            if (writeOffset < 0 || actualStart != writeOffset) {
                HttpLog.request(
                    "代理非顺序读取（request=${requested?.text ?: "全文"} " +
                        "actual=$actualStart part=$writeOffset）→ 只透传，不写缓存"
                )
            }

            // ── 边转发边（按需）写盘 ────────────────────────
            //
            // 顺序时**不能**先把字节喂给播放器再写盘：output.write 会阻塞
            // （播放器的读取速度由它的缓冲决定），阻塞期间缓存就停止增长了。
            // 所以顺序时先写盘、立刻再喂播放器 —— 写盘是本地追加，几乎不耗时。
            //
            // 缓存量完全由**播放器驱动**：它读多少我们才跟着写多少，
            // 所以不会出现"把整首拉完"的情况 —— 这是天然限速，不需要任何额度。
            // 唯一的封顶是用户可配的单曲上限（设置 → 服务器 → 音频缓存）。
            val perTrackCap = AppPrefs.perTrackCacheLimitMb.value.toLong() * 1024L * 1024L
            TrackCacheLedger.claimFor(target.id, perTrackCap)

            val buffer = ByteArray(BUFFER_SIZE)
            var streamed = 0L
            var cacheWritten = 0L
            var stillSequential = writeOffset >= 0 && actualStart == writeOffset
            var cappedNoted = false

            upstream.inputStream.use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break

                    if (stillSequential) {
                        val remaining = TrackCacheLedger.remaining()
                        if (remaining <= 0L) {
                            // 到顶了：剩下的只转发。写一条日志，
                            // 否则"这首怎么只缓存了这么多"没有线索可查
                            stillSequential = false
                            if (!cappedNoted) {
                                cappedNoted = true
                                HttpLog.request(
                                    "代理单曲缓存已达上限（${TrackCacheLedger.capBytes() / 1024}KB），" +
                                        "本次只转发不再写缓存"
                                )
                            }
                        } else {
                            // 追加写：写多少就长多少。最后一块可能略微超过上限
                            // （最多一个读缓冲），这无妨 —— 不值得为它引入
                            // "按剩余量截断"那层复杂度。
                            store.appendPart(target.id, buffer, 0, n)
                            TrackCacheLedger.record(n.toLong())
                            cacheWritten += n
                        }
                    }

                    output.write(buffer, 0, n)
                    streamed += n
                }
            }
            output.flush()

            // 数据下完 → 校验长度后改名成完整缓存
            if (totalBytes > 0 && cacheWritten > 0) {
                val finalized = store.finalizeIfComplete(target.id, totalBytes, System.currentTimeMillis())
                val nowCached = store.cachedBytes(target.id)
                HttpLog.request(
                    when {
                        finalized -> "代理缓存完成：${target.id}（${totalBytes / 1024}KB）"
                        // 还差多少要写清楚，否则"缓存未完成"这句话没法定位问题
                        else -> "代理缓存未完成：${target.id}" +
                            "（本次写 ${cacheWritten / 1024}KB，累计 ${nowCached / 1024}KB / " +
                            "${totalBytes / 1024}KB）"
                    }
                )
            } else {
                HttpLog.request("代理转发 ${streamed / 1024}KB（未走缓存）")
            }
        } catch (e: Exception) {
            HttpLog.request("代理转发失败：${e.message}")
        } finally {
            runCatching { upstream.disconnect() }
        }
    }

    private fun openUpstream(url: String, rangeHeader: String?): HttpURLConnection? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        // 透传播放器给的 Range；没有就不带（上游会回 200 全文）
        rangeHeader?.let { conn.setRequestProperty("Range", it) }
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn
    }.getOrNull()

    private fun parseTarget(path: String): Target? {
        val clean = path.substringBefore('?')
        if (!clean.startsWith("$PATH_PREFIX/")) return null
        val id = clean.removePrefix("$PATH_PREFIX/")
        if (id.isBlank()) return null

        val query = path.substringAfter('?', "")
        val src = query.split('&')
            .firstOrNull { it.startsWith("src=") }
            ?.removePrefix("src=")
            ?: return null

        val upstreamUrl = runCatching {
            String(Base64.getUrlDecoder().decode(src), Charsets.UTF_8)
        }.getOrNull() ?: return null

        return Target(id = id, upstreamUrl = upstreamUrl, expectedTotal = 0L)
    }

    private fun parseTotalFromContentRange(h: String?): Long? =
        h?.let { Regex("""bytes\s+\d+-\d+/(\d+)""").find(it)?.groupValues?.get(1)?.toLongOrNull() }

    private fun parseStartFromContentRange(h: String?): Long? =
        h?.let { Regex("""bytes\s+(\d+)-""").find(it)?.groupValues?.get(1)?.toLongOrNull() }

    private fun copyExactly(input: InputStream, output: OutputStream, length: Long) {
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = length
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n <= 0) break
            output.write(buffer, 0, n)
            remaining -= n
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    private fun writeSimple(output: OutputStream, code: Int, message: String) {
        val body = message.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code $message\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    private data class Target(val id: String, val upstreamUrl: String, val expectedTotal: Long)

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        const val PATH_PREFIX = "/audio"
        private const val BUFFER_SIZE = 64 * 1024
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14)"


    }
}
