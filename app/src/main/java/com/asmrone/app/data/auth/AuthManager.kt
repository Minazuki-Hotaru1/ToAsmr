package com.asmrone.app.data.auth

import android.content.Context
import android.content.SharedPreferences
import com.asmrone.app.data.model.Account
import com.asmrone.app.data.model.AuthUser
import com.asmrone.app.data.prefs.AccountStore
import com.asmrone.app.data.prefs.AppPrefs
import com.asmrone.app.data.remote.AsmrApiException
import com.asmrone.app.data.remote.AsmrOneApi
import com.asmrone.app.data.remote.AuthRejectedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 一次静默登录的结果。
 *
 * [rejected] 是这里最关键的字段：它区分「服务端说凭据不对」和「压根没连上服务器」。
 * 只有前者才允许清除本地登录状态 —— 断网时把用户登出是最恼人的一类 bug。
 */
private data class LoginOutcome(
    val ok: Boolean,
    /** 需要展示给用户的原因（网络故障时为 null，因为已改成离线模式提示） */
    val message: String? = null,
    /** 是否属于"凭据被服务端拒绝" */
    val rejected: Boolean = false
)

/**
 * 认证状态。
 *
 * 与参考实现 KikoFlu 的 `AuthState`（testAsmrOne/lib/src/providers/auth_provider.dart）字段一一对应，
 * 但增加了一个 [offline] 概念：
 *
 * | 状态 | [user] | [token] | [offline] | 含义 |
 * |---|---|---|---|---|
 * | 未登录 | null | "" | false | 匿名访问，公开接口都能用 |
 * | 已登录 | 有 | 有 | false | 令牌有效，云端功能可用 |
 * | 离线 | 有 | "" | true | 有本地凭据但连不上服务器；仅本地内容可用 |
 */
data class AuthState(
    val user: AuthUser? = null,
    val token: String = "",
    val host: String = "",
    /** 正在登录 / 校验中（登录页据此显示进度） */
    val loading: Boolean = false,
    /** 启动时正在恢复会话（避免首帧闪一下"未登录"） */
    val restoring: Boolean = true,
    /** 最近一次操作的错误，展示后由 [AuthManager.clearError] 清掉 */
    val error: String? = null
) {
    val isLoggedIn: Boolean get() = user != null && token.isNotBlank()
    val isGuest: Boolean get() = user?.isGuest == true
    /** 有本地账号凭据但当前连不上服务器 → 只能看本地内容 */
    val offline: Boolean get() = user != null && token.isBlank()
    val displayName: String get() = user?.name.orEmpty()
}

/**
 * 登录会话管理器。
 *
 * 设计对齐参考实现，但按本工程的约束做了两处降级：
 *  1. **不引入 SQLite**：账号表用 [AccountStore]（SharedPreferences + JSON）实现，
 *     行为与 KikoFlu 的 `AccountDatabase` 一致（单激活账号 + 静默重登）。
 *  2. **不引入 Hive/加密库**：当前用户画像直接存 SharedPreferences 的 JSON。
 *
 * 关键行为（照搬参考实现的取舍）：
 *  - 启动时先**信任本地令牌**立刻进入已登录态，再后台校验；校验失败才处理。
 *  - 校验失败时区分「凭据被拒」(401/403) 与「网络不可用」：
 *    前者清会话，后者**保留凭据进入离线模式**，绝不因为断网把用户登出。
 *  - 已保存密码时，令牌失效可以**静默重登**，用户无感。
 *  - 未登录完全不影响浏览：公开接口都不需要令牌。
 */
object AuthManager {

    private const val FILE = "asmrone_auth"
    private const val KEY_TOKEN = "auth_token"
    private const val KEY_USER = "current_user"

    private lateinit var sp: SharedPreferences

    /**
     * 独立于 [AsmrRepository] 的 API 实例 —— 这里必须能在**任意主机**上发起
     * 登录/校验请求（登录页允许现场换主机），所以传入 Host 而不用全局配置。
     */
    private val api = AsmrOneApi { com.asmrone.app.data.remote.ApiConfig(host = AppPrefs.host.value) }

    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /**
     * 当前账号绑定的服务端 Cookie（自建服务端可能需要，官方站一般为空）。
     * 由 [com.asmrone.app.data.repo.AsmrRepository] 在每次请求时实时读取。
     */
    val activeCookie: String
        get() = AccountStore.activeAccount?.serverCookie.orEmpty()

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        AccountStore.init(context)

        // ① 先用本地缓存把状态铺出来（立即进入已登录态，不阻塞首屏）
        val token = sp.getString(KEY_TOKEN, "") ?: ""
        val user = readUser()
        if (user != null) {
            _state.value = AuthState(
                user = user,
                token = token,
                host = AppPrefs.host.value,
                restoring = true
            )
        }
        // ② 真正的校验交给调用方在协程里做（见 [restoreSession]）
    }

    /**
     * 启动时恢复会话 —— 对齐参考实现 `AuthNotifier._loadCurrentUser()` 的顺序：
     *
     * ```
     * 有缓存的 token+host ?
     *   ├─ 是 → 用它请求 /api/auth/me 校验
     *   │        ├─ 成功 → 已登录，结束
     *   │        └─ 失败 → 往下走
     *   └─ 否 → 往下走
     * 账号表里有激活账号 ?（有保存的密码）
     *   ├─ 是 → 用保存的凭据静默重登
     *   │        ├─ 成功 → 已登录，结束
     *   │        └─ 网络不可用 → 离线模式（保留凭据，不登出）
     *   │        └─ 凭据被拒 → 清掉该账号的激活标记
     *   └─ 否 → 未登录（匿名可用）
     * ```
     *
     * ## 校验的额度：**每个账号每天最多一次**（[force] 可绕过）
     *
     * 早先这里是无条件校验：只要本地有令牌，**每次冷启动**都会打一次
     * `/api/auth/me`。这与整个应用"每天只跟服务器对齐一次"的策略不一致 ——
     * 用户把「首页加载」关掉、当天也已经同步过之后重启，日志里仍然会出现
     * 这一条请求，看上去就像"关不掉的后台联网"。
     *
     * 现在的规则与 `SyncGate` 的每日额度一致：
     *  - 当天已经校验过 → 直接用本地令牌与用户信息进入已登录态，**零请求**；
     *  - 当天第一次 → 校验一次（请求前先记账，失败也算用过，避免反复重试）；
     *  - 用户主动「重试连接」→ [force] = true，不受额度限制。
     *
     * 代价（明确接受）：令牌若在当天中途失效，最多要到第二天才会被自动发现；
     * 期间任何需要鉴权的操作会拿到 401，由界面提示重新登录 —— 这条路径本来就有。
     */
    suspend fun restoreSession(force: Boolean = false) {
        val savedToken = sp.getString(KEY_TOKEN, "") ?: ""
        val savedUser = readUser()

        // ① 有令牌就校验（每天一次）
        if (savedToken.isNotBlank() && savedUser != null) {
            val domain = "auth|${currentHost()}|${savedUser.name}"
            val today = com.asmrone.app.data.cache.SyncGate.dayStamp()
            if (!force && AppPrefs.autoSyncDay(domain) == today) {
                // 今天已经校验过 —— 不再打扰服务器，直接用本地状态
                _state.value = AuthState(
                    user = savedUser,
                    token = savedToken,
                    host = currentHost(),
                    restoring = false
                )
                return
            }
            // 先记账再请求：失败同样算用过，否则每次冷启动都会重来一次
            AppPrefs.setAutoSyncDay(domain, today)

            try {
                val fresh = api.getCurrentUser(savedToken, hostOverride = currentHost())
                val merged = AuthUser.fromJson(fresh).copy(
                    password = savedUser.password,
                    token = savedToken,
                    host = currentHost()
                )
                persist(merged, savedToken)
                _state.value = AuthState(
                    user = merged,
                    token = savedToken,
                    host = currentHost(),
                    restoring = false
                )
                return
            } catch (e: AuthRejectedException) {
                // 令牌失效：先别急着登出 —— 有保存的密码就静默重登
            } catch (e: Exception) {
                // 网络问题：保留现状，按"已登录但未验证"处理，避免误登出
                _state.value = _state.value.copy(restoring = false)
                return
            }
        }

        // ② 静默重登（用账号表里保存的凭据）
        val account = AccountStore.activeAccount
        if (account != null) {
            AppPrefs.setHost(account.host)
            val outcome = silentLogin(account)
            _state.value = _state.value.copy(
                restoring = false,
                loading = false,
                error = outcome.message
            )
            return
        }

        // ③ 什么都没有 → 未登录（匿名依然可用）
        clearSession()
        _state.value = AuthState(restoring = false)
    }

    /** 手动重试连接（离线模式下的「重试连接」按钮） */
    suspend fun retryConnection() {
        _state.value = _state.value.copy(loading = true, error = null)
        // 用户主动要求重连 → 绕过"每天一次"的校验额度
        restoreSession(force = true)
        _state.value = _state.value.copy(loading = false)
    }

    // ─────────────────────────── 登录 ───────────────────────────

    /**
     * 登录。
     *
     * @param host 服务器地址，可以不带协议（会自动补 https，本机地址补 http）
     * @return 面向用户的错误说明；null 表示成功
     */
    suspend fun login(
        username: String,
        password: String,
        host: String,
        cookie: String = "",
        silent: Boolean = false
    ): String? {
        val normalized = AuthHosts.normalizeHost(host)
        if (username.isBlank()) return "请输入用户名"
        if (password.isBlank()) return "请输入密码"
        if (normalized.isBlank()) return "请输入服务器地址"

        if (!silent) _state.value = _state.value.copy(loading = true, error = null)

        return try {
            val json = api.login(username, password, hostOverride = normalized, cookie = cookie)
            val token = json.optString("token", "").takeIf { it.isNotBlank() }
                ?: throw AsmrApiException("服务端没有返回 token，可能账号或密码不正确")

            // 登录响应里如果直接带用户字段就用它，否则单独取一次
            val profile = if (json.has("user") || json.has("name")) {
                AuthUser.fromJson(json, trustWhenMissing = true)
            } else {
                AuthUser.fromJson(
                    api.getCurrentUser(token, hostOverride = normalized, cookie = cookie)
                )
            }

            // 官方站会返回 loggedIn=false 表示没真正登录上；自建服务端通常不给这个字段
            if (AuthHosts.isOfficialHost(normalized) && !profile.loggedIn && json.has("loggedIn")) {
                throw AsmrApiException("登录失败：服务端未确认登录状态")
            }

            val user = profile.copy(
                password = password,
                token = token,
                host = normalized
            )
            applySession(user, token, normalized, cookie)
            null
        } catch (e: AuthRejectedException) {
            val message = e.message ?: "登录失败"
            if (!silent) _state.value = _state.value.copy(loading = false, error = message)
            message
        } catch (e: Exception) {
            val message = e.message ?: "登录失败，请检查网络与服务器地址"
            if (!silent) _state.value = _state.value.copy(loading = false, error = message)
            message
        }
    }

    // ─────────────────────────── 注册 ───────────────────────────

    /**
     * 注册。
     *
     * 官方站需要先取 `recommenderUuid`（参考实现 `_registerOfficial` 的两步流程），
     * 取不到就退回本地已保存的 uuid / 内置兜底值。
     */
    suspend fun register(
        username: String,
        password: String,
        host: String,
        cookie: String = ""
    ): String? {
        val normalized = AuthHosts.normalizeHost(host)
        if (username.length < 5) return "用户名不能少于 5 个字符"
        if (password.length < 5) return "密码不能少于 5 个字符"
        if (normalized.isBlank()) return "请输入服务器地址"

        _state.value = _state.value.copy(loading = true, error = null)

        return try {
            // 步骤 1：拿服务端下发的 recommenderUuid（必须不带令牌）
            val recommenderUuid = api.fetchRecommenderUuid(hostOverride = normalized)
                ?: AppPrefs.recommenderUuid.value.takeIf { it.isNotBlank() }
                ?: AsmrOneApi.FALLBACK_RECOMMENDER_UUID

            // 步骤 2：注册
            val json = api.register(
                username = username,
                password = password,
                recommenderUuid = recommenderUuid,
                hostOverride = normalized,
                cookie = cookie
            )
            val token = json.optString("token", "").takeIf { it.isNotBlank() }
                ?: throw AsmrApiException("注册成功但服务端没有返回 token")

            val profile = if (json.has("user") || json.has("name")) {
                AuthUser.fromJson(json, trustWhenMissing = true)
            } else {
                AuthUser.fromJson(
                    api.getCurrentUser(token, hostOverride = normalized, cookie = cookie)
                )
            }

            val user = profile.copy(
                name = profile.name.ifBlank { username },
                password = password,
                token = token,
                host = normalized
            )
            applySession(user, token, normalized, cookie)
            null
        } catch (e: AuthRejectedException) {
            val message = e.message ?: "注册失败"
            _state.value = _state.value.copy(loading = false, error = message)
            message
        } catch (e: Exception) {
            val message = e.message ?: "注册失败，请检查网络与服务器地址"
            _state.value = _state.value.copy(loading = false, error = message)
            message
        }
    }

    /** 游客模式：官方站内置的演示账号 guest/guest（对齐参考实现的 `_loginAsGuest`） */
    suspend fun loginAsGuest(host: String, cookie: String = ""): String? =
        login(username = "guest", password = "guest", host = host, cookie = cookie)

    // ─────────────────────────── 账号切换 / 退出 ───────────────────────────

    /**
     * 切换到已保存的账号（对齐参考实现 `switchUser`）。
     * 用保存的密码重新登录一次以拿到**新的令牌** —— 令牌是服务端签发的，不能复用旧的。
     */
    suspend fun switchTo(account: Account): String? {
        _state.value = _state.value.copy(loading = true, error = null)
        AppPrefs.setHost(account.host)
        return login(
            username = account.username,
            password = account.password,
            host = account.host,
            cookie = account.serverCookie ?: "",
            silent = true
        ).also { _state.value = _state.value.copy(loading = false) }
    }

    /**
     * 退出登录。
     *
     * 只清**会话**（令牌 + 当前用户），**保留**账号表里的用户名/密码，
     * 这样设置页可以一键切回来。要彻底删除账号请用 [forgetAccount]。
     */
    fun logout() {
        clearSession()
        _state.value = AuthState(restoring = false)
    }

    /** 从账号表里彻底删除某个账号（同时清掉激活标记） */
    fun forgetAccount(account: Account) {
        AccountStore.remove(account)
        // 被删掉的正好是当前账号 → 顺带退出
        if (_state.value.user?.name == account.username &&
            _state.value.host == account.host
        ) {
            logout()
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    // ─────────────────────────── 内部实现 ───────────────────────────

    /** 用保存的凭据静默登录 */
    private suspend fun silentLogin(account: Account): LoginOutcome = try {
        val json = api.login(
            username = account.username,
            password = account.password,
            hostOverride = account.host,
            cookie = account.serverCookie ?: ""
        )
        val token = json.optString("token", "").takeIf { it.isNotBlank() }
        if (token == null) {
            LoginOutcome(ok = false, message = "服务端没有返回 token", rejected = true)
        } else {
            val profile = AuthUser.fromJson(json, trustWhenMissing = true).copy(
                password = account.password,
                token = token,
                host = account.host
            )
            applySession(profile, token, account.host, account.serverCookie ?: "")
            LoginOutcome(ok = true)
        }
    } catch (e: AuthRejectedException) {
        // 服务端**明确拒绝**了这组凭据 → 清掉激活标记，让用户重新登录
        AccountStore.setActive("")
        LoginOutcome(ok = false, message = e.message ?: "登录状态已失效", rejected = true)
    } catch (e: Exception) {
        // 连不上 / 超时 / DNS / TLS → **离线模式**：保留凭据，绝不当成"密码错误"把用户登出。
        // 这条分支正是参考实现里"网络连接失败，以离线模式启动"的对应物。
        _state.value = _state.value.copy(
            // 用户名先用本地记录顶上，让界面不至于显示为空
            user = AuthUser(
                name = account.username,
                group = "guest",
                loggedIn = false,
                password = account.password,
                token = "",
                host = account.host
            ),
            token = "",
            host = account.host,
            restoring = false,
            error = "网络连接失败，以离线模式启动"
        )
        LoginOutcome(ok = false, message = null, rejected = false)
    }

    /** 登录成功后统一落盘 + 更新状态（对齐参考实现的存储顺序） */
    private suspend fun applySession(user: AuthUser, token: String, host: String, cookie: String) {
        AppPrefs.setHost(host)
        persist(user, token)
        AccountStore.upsert(
            Account(
                username = user.name,
                password = user.password ?: "",
                host = host,
                serverCookie = cookie.takeIf { it.isNotBlank() },
                lastUsedAt = System.currentTimeMillis()
            ),
            makeActive = true
        )
        // 换了服务器或账号 → 内存里的作品/文件树缓存不能再用了
        // （收藏状态跟着账号走，旧缓存里可能带着上一个账号的 progress）。
        //
        // 注意这里**不删磁盘上的收藏缓存**：缓存键本身就含 host + 账号 + 筛选
        // （见 SyncCacheStore.SyncKeys），已经天然隔离，所以另一个账号的收藏
        // 不会被看到。删掉反而会让"切回原账号"时白白全部重下。
        // 切换 *服务器* 时才会整目录清（见 clearCaches）。
        com.asmrone.app.data.repo.AsmrRepository.onAccountChanged()
        // 这里**不需要**再"重置每日额度"了 —— 新设计里根本没有额度：
        // "本次会话第一次打开"的标记按 `数据类别|host|账号` 分别记
        // （见 SyncGate），新账号的类别本来就没被标记过，
        // 所以它天然拥有一次完整的自动机会。
        _state.value = AuthState(
            user = user,
            token = token,
            host = host,
            loading = false,
            restoring = false
        )
    }

    private fun persist(user: AuthUser, token: String) {
        sp.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USER, user.toJson().toString())
            .apply()
    }

    private fun clearSession() {
        sp.edit().remove(KEY_TOKEN).remove(KEY_USER).apply()
    }

    private fun readUser(): AuthUser? {
        val raw = sp.getString(KEY_USER, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            AuthUser(
                name = o.optString("name"),
                loggedIn = o.optBoolean("loggedIn", true),
                group = o.optString("group", "").takeIf { it.isNotBlank() },
                email = o.optString("email", "").takeIf { it.isNotBlank() },
                recommenderUuid = o.optString("recommenderUuid", "").takeIf { it.isNotBlank() },
                password = o.optString("password", "").takeIf { it.isNotBlank() },
                token = o.optString("token", "").takeIf { it.isNotBlank() },
                host = o.optString("host", "").takeIf { it.isNotBlank() }
            )
        }.getOrNull()
    }

    private fun currentHost(): String = AppPrefs.host.value

    /** 探测主机连通性并返回耗时（毫秒）；失败返回 null */
    suspend fun pingHost(host: String, cookie: String = ""): Long? = withContext(Dispatchers.IO) {
        val normalized = AuthHosts.normalizeHost(host)
        if (normalized.isBlank()) return@withContext null
        val started = System.currentTimeMillis()
        runCatching {
            val conn = java.net.URL("$normalized/api/health")
                .openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.setRequestProperty("User-Agent", "ToAsmr")
            if (cookie.isNotBlank()) conn.setRequestProperty("Cookie", cookie)
            try {
                // 只要有响应就算连通（部分自建服务端没有 /api/health，会返回 404）
                if (conn.responseCode < 500) System.currentTimeMillis() - started else null
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}

/**
 * 主机地址工具。
 *
 * 之所以放在**文件级的顶层 object** 而不是 [AuthManager] 的 `companion object`：
 * Kotlin 不允许在 `object`（单例）内部再声明 `companion object`
 * （companion 是给 class 用的），所以这里拆成独立的工具对象。
 */
object AuthHosts {

    /** 官方站判定关键字，与参考实现的 `ServerUtils.isOfficialServer` 一致 */
    private const val OFFICIAL_KEYWORD = "api.asmr"

    fun isOfficialHost(host: String): Boolean = host.contains(OFFICIAL_KEYWORD)

    /**
     * 规范化主机地址（对齐参考实现 `KikoeruApiService.init` / `login_screen.dart` 的处理）：
     *  - 无协议时补 `https://`；
     *  - `localhost` / `127.0.0.1` / `192.168.*` 补 `http://`（自建服务端一般没有证书）；
     *  - 去掉结尾的 `/`。
     */
    fun normalizeHost(raw: String): String {
        var value = raw.trim().trimEnd('/')
        if (value.isEmpty()) return ""
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            val isLocal = value.contains("localhost") ||
                value.startsWith("127.0.0.1") ||
                value.startsWith("192.168.")
            value = if (isLocal) "http://$value" else "https://$value"
        }
        return value
    }
}
