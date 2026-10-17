package com.asmrone.app.ui.screen.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.asmrone.app.data.auth.AuthManager
import com.asmrone.app.data.model.Account
import com.asmrone.app.data.prefs.AccountStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 登录页的表单与提交状态 */
data class LoginUiState(
    /** true = 登录，false = 注册 */
    val registerMode: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    /** 连通性测试结果：null = 未测试/测试中，见 [testing] */
    val testResult: String? = null,
    val testOk: Boolean = false,
    val testing: Boolean = false,
    /** 提交成功后置为 true，界面据此返回上一页 */
    val done: Boolean = false
)

/**
 * 登录 / 注册页 ViewModel。
 *
 * 真正的会话逻辑都在 [AuthManager]（它才是单例），
 * 这里只负责表单状态、错误展示与"测试连接"这类页面级编排。
 */
class AuthViewModel : ViewModel() {

    private val _ui = MutableStateFlow(LoginUiState())
    val ui: StateFlow<LoginUiState> = _ui.asStateFlow()

    fun toggleMode() {
        _ui.value = _ui.value.copy(registerMode = !_ui.value.registerMode, error = null)
        AuthManager.clearError()
    }

    fun clearError() {
        _ui.value = _ui.value.copy(error = null, testResult = null)
    }

    /** 提交登录或注册 */
    fun submit(username: String, password: String, host: String, cookie: String) {
        if (_ui.value.submitting) return
        _ui.value = _ui.value.copy(submitting = true, error = null)

        viewModelScope.launch {
            val register = _ui.value.registerMode
            val error = if (register) {
                AuthManager.register(username.trim(), password, host, cookie.trim())
            } else {
                AuthManager.login(username.trim(), password, host, cookie.trim())
            }
            if (error == null) {
                _ui.value = _ui.value.copy(submitting = false, done = true)
            } else {
                _ui.value = _ui.value.copy(submitting = false, error = error)
            }
        }
    }

    /**
     * 游客模式：官方站内置的 guest/guest 演示账号。
     *
     * 与参考实现一致，游客账号权限有限（不能评分、不能建歌单），
     * 所以界面上要先做一次二次确认（见 LoginScreen）。
     */
    fun loginAsGuest(host: String, cookie: String) {
        if (_ui.value.submitting) return
        _ui.value = _ui.value.copy(submitting = true, error = null)
        viewModelScope.launch {
            val error = AuthManager.loginAsGuest(host, cookie.trim())
            if (error == null) {
                _ui.value = _ui.value.copy(submitting = false, done = true)
            } else {
                _ui.value = _ui.value.copy(submitting = false, error = error)
            }
        }
    }

    /** 测试某个主机是否连通（不改变当前登录状态） */
    fun testConnection(host: String, cookie: String) {
        if (_ui.value.testing) return
        _ui.value = _ui.value.copy(testing = true, testResult = null)
        viewModelScope.launch {
            val latency = AuthManager.pingHost(host, cookie.trim())
            _ui.value = _ui.value.copy(
                testing = false,
                testOk = latency != null,
                testResult = if (latency != null) "连接成功（${latency} ms）" else "连接失败，请检查地址与网络"
            )
        }
    }
}

/** 账号管理页的状态 */
data class AccountUiState(
    val accounts: List<Account> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null
)

/**
 * 账号管理页 ViewModel：列出已保存账号、切换、删除、退出登录。
 * 数据源是 [AccountStore]，会随 [AccountStore.accounts] 自动刷新。
 */
class AccountViewModel : ViewModel() {

    private val _ui = MutableStateFlow(AccountUiState())
    val ui: StateFlow<AccountUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            AccountStore.accounts.collect { list ->
                _ui.value = _ui.value.copy(accounts = list)
            }
        }
    }

    fun switchTo(account: Account) {
        if (_ui.value.busy) return
        _ui.value = _ui.value.copy(busy = true, error = null)
        viewModelScope.launch {
            val error = AuthManager.switchTo(account)
            _ui.value = _ui.value.copy(busy = false, error = error)
        }
    }

    fun remove(account: Account) {
        AuthManager.forgetAccount(account)
    }

    fun logout() {
        AuthManager.logout()
        _ui.value = _ui.value.copy(error = null)
    }
}
