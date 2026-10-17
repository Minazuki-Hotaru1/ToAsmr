package com.asmrone.app.data.model

import org.json.JSONObject

/**
 * 登录后的账号信息（对齐 KikoFlu 的 `User` 模型，见 testAsmrOne/lib/src/models/user.dart）。
 *
 * 服务端 `/api/auth/me` 返回的用户对象关键字段：
 * ```jsonc
 * { "name": "…", "loggedIn": true, "group": "user",
 *   "email": "…", "recommenderUuid": "…" }
 * ```
 * 登录接口 `POST /api/auth/me` 除了这些字段外**还会带一个 `token`**。
 *
 * 注意 [password] / [token] 属于**本地凭据**，服务端不会返回；
 * 它们只在本地保存，用于「静默重登」。
 */
data class AuthUser(
    val name: String,
    /** 服务端确认的登录态；自建服务端可能不返回这个字段（此时默认 true 信任本地令牌） */
    val loggedIn: Boolean = false,
    /** 用户组：user / admin / guest … */
    val group: String? = null,
    val email: String? = null,
    val recommenderUuid: String? = null,
    /** 本地保存的密码（用于静默重登；接口不返回） */
    val password: String? = null,
    /** 本地保存的令牌（接口只在登录/注册时返回一次） */
    val token: String? = null,
    /** 本地保存的主机（接口不返回） */
    val host: String? = null
) {
    val isGuest: Boolean get() = name.equals("guest", ignoreCase = true) || group == "guest"

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("loggedIn", loggedIn)
        put("group", group ?: "")
        put("email", email ?: "")
        put("recommenderUuid", recommenderUuid ?: "")
        put("password", password ?: "")
        put("token", token ?: "")
        put("host", host ?: "")
    }

    companion object {
        /**
         * 解析服务端响应。参照 KikoFlu 的 `User.fromJson`：
         * 响应可能是 `{user:{…}}` 也可能是**扁平**的 `{name:…,loggedIn:…}`，
         * 两种都要认。
         *
         * [trustWhenMissing]：服务端没给 `loggedIn` 时是否视为已登录。
         * 自建 Kikoeru 通常不返回该字段，此时应当信任本地令牌。
         */
        fun fromJson(
            o: JSONObject,
            trustWhenMissing: Boolean = true
        ): AuthUser {
            val user = o.optJSONObject("user") ?: o
            // loggedIn 可能在 user 里，也可能在外层（登录响应里两者都有）
            val loggedIn = when {
                user.has("loggedIn") && !user.isNull("loggedIn") -> user.optBoolean("loggedIn")
                o.has("loggedIn") && !o.isNull("loggedIn") -> o.optBoolean("loggedIn")
                else -> trustWhenMissing
            }
            return AuthUser(
                name = user.optString("name", "").ifBlank { o.optString("name", "") },
                loggedIn = loggedIn,
                group = user.optString("group", "").takeIf { it.isNotBlank() },
                email = user.optString("email", "").takeIf { it.isNotBlank() },
                recommenderUuid = user.optString("recommenderUuid", "").takeIf { it.isNotBlank() }
                    ?: o.optString("recommenderUuid", "").takeIf { it.isNotBlank() }
            )
        }
    }
}

/**
 * 本地保存的一个账号（对齐 KikoFlu 的 `Account`，见 testAsmrOne/lib/src/models/account.dart）。
 *
 * 与 [AuthUser] 的区别：`AuthUser` 是**服务端返回的画像**，
 * `Account` 是**本地凭据记录**（用户名 + 密码 + 主机），用于多账户切换与静默重登。
 *
 * ⚠️ 密码以明文保存在 SharedPreferences 中（本工程坚持零新增依赖，
 * 未引入 `androidx.security` 加密存储）。请在系统层依赖应用沙箱保护。
 */
data class Account(
    val username: String,
    val password: String,
    val host: String,
    /** 自建服务端需要的额外 Cookie 头（可为空） */
    val serverCookie: String? = null,
    /** 上次成功登录的时间戳，用于列表排序 */
    val lastUsedAt: Long = 0L
) {
    /** 「用户名 @ 主机」 —— 同一主机可以有多個账号 */
    val key: String get() = "$username@$host"

    /** 主机去掉协议，列表里显示更干净 */
    val displayHost: String get() = host.removePrefix("https://").removePrefix("http://")

    fun toJson(): JSONObject = JSONObject().apply {
        put("username", username)
        put("password", password)
        put("host", host)
        put("serverCookie", serverCookie ?: "")
        put("lastUsedAt", lastUsedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = Account(
            username = o.optString("username"),
            password = o.optString("password"),
            host = o.optString("host"),
            serverCookie = o.optString("serverCookie", "").takeIf { it.isNotBlank() },
            lastUsedAt = o.optLong("lastUsedAt", 0L)
        )
    }
}
