package com.zhangwenkang.cinefin.setup.presentation.addserver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.models.DiscoveredServer
import com.zhangwenkang.cinefin.models.ExceptionUiText
import com.zhangwenkang.cinefin.models.ExceptionUiTexts
import com.zhangwenkang.cinefin.models.UiText
import com.zhangwenkang.cinefin.network.CertificateTrustRequiredException
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.setup.domain.SetupRepository
import com.zhangwenkang.cinefin.setup.presentation.certificate.toPrompt
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@HiltViewModel
class AddServerViewModel
@Inject
constructor(private val repository: SetupRepository, private val appPreferences: AppPreferences) :
    ViewModel() {
    private val _state = MutableStateFlow(AddServerState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<AddServerEvent>()
    val events = eventsChannel.receiveAsFlow()

    /** 最近一次待连接地址：用户确认证书后需要原地重试。 */
    private var lastAddress: String? = null

    fun discoverServers() {
        viewModelScope.launch {
            val discoveredServers = mutableListOf<DiscoveredServer>()
            val serversDiscovery = repository.discoverServers()
            serversDiscovery.collect { serverDiscoveryInfo ->
                discoveredServers.add(
                    DiscoveredServer(
                        serverDiscoveryInfo.id,
                        serverDiscoveryInfo.name,
                        serverDiscoveryInfo.address,
                    )
                )
                _state.emit(_state.value.copy(discoveredServers = discoveredServers))
            }
        }
    }

    private fun connectToServer(address: String) {
        lastAddress = address
        viewModelScope.launch {
            _state.emit(
                _state.value.copy(
                    isLoading = true,
                    error = null,
                    certificatePrompt = null,
                )
            )

            try {
                val server = repository.addServer(address)
                appPreferences.setValue(appPreferences.currentServer, server.id)
                _state.emit(_state.value.copy(isLoading = false, error = null))
                eventsChannel.send(AddServerEvent.Success)
            } catch (_: CancellationException) {} catch (e: CertificateTrustRequiredException) {
                _state.emit(_state.value.copy(isLoading = false, certificatePrompt = e.toPrompt()))
            } catch (e: ExceptionUiText) {
                _state.emit(_state.value.copy(isLoading = false, error = listOf(e.uiText)))
            } catch (e: ExceptionUiTexts) {
                _state.emit(_state.value.copy(isLoading = false, error = e.uiTexts))
            } catch (e: Exception) {
                _state.emit(
                    _state.value.copy(
                        isLoading = false,
                        error =
                            listOf(
                                if (e.message != null) UiText.DynamicString(e.message!!)
                                else UiText.StringResource(CoreR.string.unknown_error)
                            ),
                    )
                )
            }
        }
    }

    /** 用户确认指纹：写入信任后重试连接。 */
    private fun trustCertificate() {
        val prompt = _state.value.certificatePrompt ?: return
        viewModelScope.launch {
            repository.trustCertificate(prompt.trustKey, prompt.fingerprint)
            _state.emit(_state.value.copy(certificatePrompt = null))
            lastAddress?.let { connectToServer(it) }
        }
    }

    fun onAction(action: AddServerAction) {
        when (action) {
            is AddServerAction.OnConnectClick -> {
                connectToServer(address = action.address)
            }
            is AddServerAction.OnTrustCertificate -> {
                trustCertificate()
            }
            is AddServerAction.OnDismissCertificatePrompt -> {
                _state.value = _state.value.copy(certificatePrompt = null)
            }
            else -> Unit
        }
    }
}
