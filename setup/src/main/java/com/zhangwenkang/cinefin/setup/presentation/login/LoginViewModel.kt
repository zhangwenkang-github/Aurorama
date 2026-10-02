package com.zhangwenkang.cinefin.setup.presentation.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.models.UiText
import com.zhangwenkang.cinefin.network.CertificateTrustRequiredException
import com.zhangwenkang.cinefin.setup.R as SetupR
import com.zhangwenkang.cinefin.setup.domain.SetupRepository
import com.zhangwenkang.cinefin.setup.presentation.certificate.toPrompt
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@HiltViewModel
class LoginViewModel @Inject constructor(private val repository: SetupRepository) : ViewModel() {
    private val _state = MutableStateFlow(LoginState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<LoginEvent>()
    val events = eventsChannel.receiveAsFlow()

    private var quickConnectJob: Job? = null

    /** 等待用户确认证书后自动重试的凭据（只在内存里短暂保存，不落盘）。 */
    private var pendingLogin: Pair<String, String>? = null
    private var pendingQuickConnect: Boolean = false

    fun loadServer() {
        viewModelScope.launch {
            try {
                val server = repository.getCurrentServer()
                _state.emit(_state.value.copy(serverName = server?.name))
            } catch (_: Exception) {}
        }
    }

    fun loadDisclaimer() {
        viewModelScope.launch {
            try {
                val loginDisclaimer = repository.loadDisclaimer()
                _state.emit(_state.value.copy(disclaimer = loginDisclaimer))
            } catch (_: Exception) {}
        }
    }

    fun loadQuickConnectEnabled() {
        viewModelScope.launch {
            try {
                val isEnabled = repository.getIsQuickConnectEnabled()
                _state.emit(_state.value.copy(quickConnectEnabled = isEnabled))
            } catch (_: Exception) {}
        }
    }

    private fun login(username: String, password: String) {
        viewModelScope.launch {
            try {
                _state.emit(
                    _state.value.copy(
                        isLoading = true,
                        error = null,
                        certificatePrompt = null,
                    )
                )
                repository.login(username, password)
                pendingLogin = null
                _state.emit(_state.value.copy(isLoading = false))
                eventsChannel.send(LoginEvent.Success)
            } catch (e: CertificateTrustRequiredException) {
                pendingLogin = username to password
                _state.emit(_state.value.copy(isLoading = false, certificatePrompt = e.toPrompt()))
            } catch (e: Exception) {
                val message =
                    if (e.message?.contains("401") == true) {
                        UiText.StringResource(SetupR.string.login_error_wrong_username_password)
                    } else {
                        UiText.StringResource(CoreR.string.unknown_error)
                    }
                _state.emit(_state.value.copy(isLoading = false, error = message))
            }
        }
    }

    private fun quickConnect() {
        if (quickConnectJob?.isActive == true) {
            quickConnectJob?.cancel()
            return
        }
        quickConnectJob = viewModelScope.launch {
            try {
                var quickConnectState = repository.initiateQuickConnect()
                _state.emit(_state.value.copy(quickConnectCode = quickConnectState.code))

                while (!quickConnectState.authenticated) {
                    delay(5000L)
                    quickConnectState = repository.getQuickConnectState(quickConnectState.secret)
                }

                repository.loginWithSecret(quickConnectState.secret)

                _state.emit(_state.value.copy(quickConnectCode = null))
                eventsChannel.send(LoginEvent.Success)
            } catch (e: CertificateTrustRequiredException) {
                pendingQuickConnect = true
                _state.emit(
                    _state.value.copy(
                        quickConnectCode = null,
                        certificatePrompt = e.toPrompt(),
                    )
                )
            } catch (_: Exception) {
                _state.emit(_state.value.copy(quickConnectCode = null))
            }
        }
    }

    /** 用户确认指纹：写入信任后按原路径重试（密码登录 / Quick Connect）。 */
    private fun trustCertificate() {
        val prompt = _state.value.certificatePrompt ?: return
        viewModelScope.launch {
            repository.trustCertificate(prompt.trustKey, prompt.fingerprint)
            _state.emit(_state.value.copy(certificatePrompt = null))

            val credentials = pendingLogin
            when {
                credentials != null -> {
                    pendingLogin = null
                    this@LoginViewModel.login(credentials.first, credentials.second)
                }
                pendingQuickConnect -> {
                    pendingQuickConnect = false
                    quickConnect()
                }
            }
        }
    }

    private fun dismissCertificatePrompt() {
        pendingLogin = null
        pendingQuickConnect = false
        _state.value = _state.value.copy(certificatePrompt = null)
    }

    fun onAction(action: LoginAction) {
        when (action) {
            is LoginAction.OnLoginClick -> {
                login(action.username, action.password)
            }
            is LoginAction.OnQuickConnectClick -> {
                quickConnect()
            }
            is LoginAction.OnTrustCertificate -> {
                trustCertificate()
            }
            is LoginAction.OnDismissCertificatePrompt -> {
                dismissCertificatePrompt()
            }
            else -> Unit
        }
    }
}
