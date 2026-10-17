package com.asmrone.app.ui.error

import com.asmrone.app.data.remote.AsmrApiException
import com.asmrone.app.data.remote.AuthRejectedException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 失败原因的**分类**。
 *
 * 为什么要分类：以前失败时统一显示一句"收藏操作失败：<原始异常>"，
 * 用户看不出到底是没登录、还是网络不通、还是服务端拒绝 —— 也没法据此决定下一步。
 * 分类之后每一类都有明确的**原因**与**该怎么办**。
 *
 * 这是"排查线上问题只能靠猜"的直接对策：把原因显式呈现出来。
 */
enum class MarkFailureKind {
    /** 本地就没有有效令牌 —— 压根没发请求 */
    NOT_LOGGED_IN,

    /** 服务端拒绝令牌（401：`No authorization token was found` / `invalid token`） */
    UNAUTHORIZED,

    /** 已认证但没有权限（403） */
    FORBIDDEN,

    /** 超时（连接或读取） */
    TIMEOUT,

    /** 连不上 / 域名解析失败 / TLS 握手失败 */
    NETWORK,

    /** 服务端返回 5xx 或其它非 2xx */
    SERVER,

    /** 响应无法解析（自建服务端返回了 HTML 等） */
    RESPONSE_FORMAT,

    /** 本地没有登录到当前主机所需的凭据 */
    UNKNOWN
}

/**
 * 一次标记操作的失败结果：给用户看的标题 + 原因 + 该怎么办。
 *
 * [detail] 保留原始异常文本，用于"复制给开发者"或日志排查 ——
 * 界面上默认只显示前两行，避免技术细节糊在脸上。
 */
data class MarkFailure(
    val kind: MarkFailureKind,
    val message: String,
    val hint: String,
    val detail: String? = null
)

/**
 * 把异常翻译成面向用户的失败说明。
 *
 * 顺序很重要：`AsmrApiException.message` 在 API 层已经被包装成友好文案
 * （见 `AsmrOneApi.friendlyMessage`），但对 401/403 是 [AuthRejectedException]，
 * 所以先按**类型**判断，再退回按消息关键字判断。
 */
fun classifyMarkFailure(error: Throwable?): MarkFailure {
    val detail = error?.message?.takeIf { it.isNotBlank() }
        ?: error?.javaClass?.simpleName

    when (error) {
        null -> return MarkFailure(
            kind = MarkFailureKind.UNKNOWN,
            message = "操作没有完成",
            hint = "请重试一次；若反复失败，请把下面的详情反馈给开发者。",
            detail = null
        )

        is AuthRejectedException -> {
            val kind = if (error.statusCode == 403) {
                MarkFailureKind.FORBIDDEN
            } else {
                MarkFailureKind.UNAUTHORIZED
            }
            return MarkFailure(
                kind = kind,
                message = if (kind == MarkFailureKind.FORBIDDEN) {
                    "账号没有权限完成这个操作"
                } else {
                    "登录状态已失效"
                },
                hint = if (kind == MarkFailureKind.FORBIDDEN) {
                    "该账号在服务器上没有标记作品的权限，请确认账号权限或联系服务器管理员。"
                } else {
                    "请在「设置 → 账号」重新登录后再试。"
                },
                detail = detail
            )
        }

        is SocketTimeoutException -> return MarkFailure(
            kind = MarkFailureKind.TIMEOUT,
            message = "连接服务器超时",
            hint = "网络较慢或服务器繁忙。请检查网络后在「设置 → 服务器」测试连通性，然后重试。",
            detail = detail
        )

        is UnknownHostException -> return MarkFailure(
            kind = MarkFailureKind.NETWORK,
            message = "找不到服务器地址（域名解析失败）",
            hint = "请检查网络，或在「设置 → 服务器」换一个 API 主机。",
            detail = detail
        )

        is SSLException -> return MarkFailure(
            kind = MarkFailureKind.NETWORK,
            message = "TLS 握手失败，连接可能被中断",
            hint = "常见于网络被干扰。建议在系统层开启代理/VPN（需用 VPN/TUN 模式）后重试。",
            detail = detail
        )

        is java.net.ConnectException -> return MarkFailure(
            kind = MarkFailureKind.NETWORK,
            message = "无法连接到服务器",
            hint = "请检查网络，或在「设置 → 服务器」换一个 API 主机。",
            detail = detail
        )

        is AsmrApiException -> {
            val raw = error.message.orEmpty()
            // API 层对 401/403 之外的错误会拼上"服务器返回 <code>"
            val code = Regex("""服务器返回\s*(\d{3})""").find(raw)?.groupValues?.get(1)?.toIntOrNull()
            return when {
                code != null -> MarkFailure(
                    kind = MarkFailureKind.SERVER,
                    message = "服务器返回错误（HTTP $code）",
                    hint = if (code >= 500) {
                        "这是服务端的问题，稍后重试通常即可恢复。"
                    } else {
                        "请求被服务器拒绝。若反复出现，请把下面的详情反馈给开发者。"
                    },
                    detail = detail
                )

                raw.contains("解析") || raw.contains("空响应") -> MarkFailure(
                    kind = MarkFailureKind.RESPONSE_FORMAT,
                    message = "服务器返回的内容无法识别",
                    hint = "如果是自建 Kikoeru 服务端，请确认版本与接口兼容。",
                    detail = detail
                )

                else -> MarkFailure(
                    kind = MarkFailureKind.NETWORK,
                    message = raw.ifBlank { "网络请求失败" },
                    hint = "请检查网络后重试。",
                    detail = detail
                )
            }
        }
    }

    return MarkFailure(
        kind = MarkFailureKind.UNKNOWN,
        message = error.message?.takeIf { it.isNotBlank() } ?: "未知错误",
        hint = "请重试一次；若反复失败，请把下面的详情反馈给开发者。",
        detail = detail
    )
}

/** 「未登录」不是异常，而是一种明确状态，单独构造 */
fun notLoggedInFailure(): MarkFailure = MarkFailure(
    kind = MarkFailureKind.NOT_LOGGED_IN,
    message = "尚未登录",
    hint = "标记需要登录账号。请到「设置 → 账号」登录后再试。",
    detail = null
)
