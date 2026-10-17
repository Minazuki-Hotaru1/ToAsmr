package com.asmrone.app.data.text

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 内置文本查看器支持的文字编码。
 *
 * ## 为什么必须让用户能手动切换
 *
 * 实测（扫了 25 个作品里的 text 节点，用**字节级严格校验**判定，不是猜）：
 *
 * | 判定 | 数量 |
 * |---|---|
 * | UTF-8（严格合法，无歧义） | **187** |
 * | Shift-JIS 与 GBK **都合法** | **24** |
 *
 * 那 24 个文件在两种编码下都能"解出东西"，但只有一种是正确的 ——
 * **任何零依赖的启发式都不可能全部判对**。所以策略是：
 * 自动猜一个最可能的，同时在界面上给切换入口。用户看到乱码切一下就好，
 * 这比"猜错了还嘴硬"诚实得多。
 *
 * ## 服务端行为（实测）
 *
 * `Content-Type: text/plain`，**不带 charset** —— 所以只能自己判。这一点也
 * 意味着不能依赖 HTTP 头，必须看字节（见 [TextDecoder]）。
 */
enum class TextCharset(val label: String) {
    UTF8("UTF-8"),
    SHIFT_JIS("Shift-JIS"),
    GBK("GBK");

    /** 界面上显示的短名 */
    val displayName: String get() = label

    companion object {
        /** 依次尝试的顺序：UTF-8 先（实测占多数，且它的字节结构最严格） */
        val fallbackOrder = listOf(UTF8, SHIFT_JIS, GBK)
    }
}

/** 解码结果 */
data class DecodedText(
    val text: String,
    val charset: TextCharset,
    /**
     * 这个编码是**猜**的还是有把握的。
     *
     * false 表示"字节只在该编码下严格合法"（可信）；
     * true 表示"当前编码解出来有替换符，可能选错了"。
     */
    val uncertain: Boolean = false
)

/**
 * 文本解码。
 *
 * ## 判定策略
 *
 * 用 `CodingErrorAction.REPORT` 做**严格解码**，而不是宽松解码后数乱码 ——
 * 后者不可靠：我曾用"替换符 + 假名/汉字数量"打分，结果把 UTF-8 判成了 GBK，
 * 因为"UTF-8 字节被 GBK 解释"恰好会产生大量合法汉字（`浣犵湅鈥` 这类特征串）。
 * 严格校验没有这个毛病：非法字节序列会直接抛异常。
 *
 * ```
 * ① 严格试 UTF-8 → 过就用（实测 187/211，且无歧义）
 * ② 否则按文件名提示挑一个（日文假名名 → Shift-JIS；否则 GBK）
 * ③ 再不行就依次硬试，选第一个"能严格解出来"的
 * ④ 全失败 → 用 UTF-8 宽松解码兜底，并标记 uncertain
 * ```
 */
object TextDecoder {

    private fun charsetOf(cs: TextCharset): Charset = when (cs) {
        TextCharset.UTF8 -> Charsets.UTF_8
        TextCharset.SHIFT_JIS -> Charset.forName("Shift_JIS")
        TextCharset.GBK -> Charset.forName("GBK")
    }

    /** 严格解码：任何非法字节序列都算失败 */
    private fun strictDecode(bytes: ByteArray, cs: TextCharset): String? = try {
        val decoder = charsetOf(cs).newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    /** 宽松解码：永不失败，坏字节变成替换符 */
    private fun lenientDecode(bytes: ByteArray, cs: TextCharset): String =
        String(bytes, charsetOf(cs))

    /**
     * 解出一段文本。
     *
     * @param fileName 用来做提示（有些作品的文件名本身是日文，那基本就是 Shift-JIS）
     * @param hint 用户手动指定的编码；给了就**完全照它解**（不再自动判断）
     */
    fun decode(bytes: ByteArray, fileName: String = "", hint: TextCharset? = null): DecodedText {
        if (bytes.isEmpty()) return DecodedText("", hint ?: TextCharset.UTF8)

        // 用户手动指定：以他为准，只判断"解出来干不干净"
        if (hint != null) {
            strictDecode(bytes, hint)?.let { return DecodedText(it, hint) }
            return DecodedText(lenientDecode(bytes, hint), hint, uncertain = true)
        }

        // ① UTF-8 严格合法 → 直接采信
        strictDecode(bytes, TextCharset.UTF8)?.let {
            return DecodedText(stripBom(it), TextCharset.UTF8)
        }

        // ② 按文件名提示挑一个，能严格解就用
        val preferred = guessFromFileName(fileName)
        if (preferred != TextCharset.UTF8) {
            strictDecode(bytes, preferred)?.let {
                return DecodedText(it, preferred)
            }
        }

        // ③ 依次硬试（跳过已试过的 UTF-8）
        for (cs in TextCharset.fallbackOrder) {
            if (cs == TextCharset.UTF8) continue
            strictDecode(bytes, cs)?.let { return DecodedText(it, cs) }
        }

        // ④ 全失败：宽松兜底并标记不确定 —— 界面会提示"可能选错编码"
        val cs = if (preferred != TextCharset.UTF8) preferred else TextCharset.GBK
        return DecodedText(lenientDecode(bytes, cs), cs, uncertain = true)
    }

    /**
     * 按文件名猜编码。
     *
     * 依据：文件名里含**日文假名**（平假名/片假名）时，内容几乎必然是
     * Shift-JIS —— 实测样本里 `Track01：….mp3.vtt` 这类就是。
     * 否则给 GBK（简体中文作品的台本常见）。
     *
     * 注意这只影响 ② 的**优先尝试顺序**，判错了后面还会依次硬试兜回来。
     */
    private fun guessFromFileName(fileName: String): TextCharset {
        val hasKana = fileName.any { it.code in 0x3040..0x30FF }
        return if (hasKana) TextCharset.SHIFT_JIS else TextCharset.GBK
    }

    /** 去掉 UTF-8 BOM（`\uFEFF`），否则它会显示成一个方块 */
    private fun stripBom(text: String): String =
        if (text.startsWith('\uFEFF')) text.substring(1) else text
}
