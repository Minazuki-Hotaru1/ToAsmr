package com.asmrone.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.asmrone.app.data.model.Account
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * 本地账号表（多账户）。
 *
 * 对齐 KikoFlu 的 `AccountDatabase`（testAsmrOne/lib/src/services/account_database.dart），
 * 但这里**不引入 SQLite**：本工程坚持零新增依赖，而账号表数据量极小
 * （通常是 1~5 条），直接用一个 JSON 数组存进 SharedPreferences 即可，
 * 语义与 `AccountDatabase` 一一对应：
 *
 * | AccountDatabase | 这里 |
 * |---|---|
 * | `createAccount` | [upsert] |
 * | `getAllAccounts`（按 lastUsedAt 倒序） | [accounts] |
 * | `getActiveAccount` | [activeAccount] |
 * | `setActiveAccount` | [setActive] |
 * | `deleteAccount` | [remove] |
 *
 * ⚠️ 与参考实现一致，**密码是明文存储**的（未引入加密库）。
 * 我们只把「同一用户名 + 同一主机」视为同一个账号，登录成功后覆盖其密码。
 */
object AccountStore {

    private const val FILE = "asmrone_accounts"
    private const val KEY_ACCOUNTS = "accounts"
    private const val KEY_ACTIVE = "active_key"

    private lateinit var sp: SharedPreferences

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _activeKey = MutableStateFlow("")
    val activeKey: StateFlow<String> = _activeKey.asStateFlow()

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _accounts.value = read()
        _activeKey.value = sp.getString(KEY_ACTIVE, "") ?: ""
    }

    /** 当前激活的账号（没有则为 null） */
    val activeAccount: Account?
        get() = _accounts.value.firstOrNull { it.key == _activeKey.value }

    fun find(username: String, host: String): Account? =
        _accounts.value.firstOrNull { it.username == username && it.host == host }

    /**
     * 新增或更新一个账号（对齐 `createAccount` / `updateAccount` 的合并行为）。
     *
     * @param makeActive 是否把它设为当前激活账号（登录/注册成功时为 true）
     */
    fun upsert(account: Account, makeActive: Boolean = true) {
        val list = _accounts.value.toMutableList()
        val index = list.indexOfFirst { it.key == account.key }
        if (index >= 0) {
            // 保留原有的创建信息，只更新凭据与使用时间
            list[index] = account
        } else {
            list.add(account)
        }
        persist(list)
        if (makeActive) setActive(account.key)
    }

    /** 设置当前激活账号（对齐 `setActiveAccount`） */
    fun setActive(key: String) {
        _activeKey.value = key
        sp.edit().putString(KEY_ACTIVE, key).apply()
    }

    fun remove(account: Account) {
        val list = _accounts.value.filterNot { it.key == account.key }
        persist(list)
        if (_activeKey.value == account.key) {
            _activeKey.value = ""
            sp.edit().remove(KEY_ACTIVE).apply()
        }
    }

    /** 清掉所有账号（退出登录时**不**调用 —— 退出只清会话，账号留着方便下次一键登录） */
    fun clear() {
        persist(emptyList())
        _activeKey.value = ""
        sp.edit().remove(KEY_ACTIVE).apply()
    }

    // ─────────────────────────── 读写 ───────────────────────────

    private fun persist(list: List<Account>) {
        // 按上次使用时间倒序，列表与 AccountDatabase.getAllAccounts 的顺序一致
        val sorted = list.sortedByDescending { it.lastUsedAt }
        _accounts.value = sorted
        val array = JSONArray()
        sorted.forEach { array.put(it.toJson()) }
        sp.edit().putString(KEY_ACCOUNTS, array.toString()).apply()
    }

    private fun read(): List<Account> {
        val raw = sp.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val account = Account.fromJson(o)
                    // 丢弃损坏/空记录，避免在列表里出现空白项
                    if (account.username.isNotBlank() && account.host.isNotBlank()) add(account)
                }
            }.sortedByDescending { it.lastUsedAt }
        }.getOrDefault(emptyList())
    }
}
