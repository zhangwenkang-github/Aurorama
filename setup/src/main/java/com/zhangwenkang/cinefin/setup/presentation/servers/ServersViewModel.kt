package com.zhangwenkang.cinefin.setup.presentation.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.identity.IdentityReadPort
import com.zhangwenkang.cinefin.identity.IdentitySelection
import com.zhangwenkang.cinefin.identity.IdentityTransitionResult
import com.zhangwenkang.cinefin.identity.IdentityWritePort
import com.zhangwenkang.cinefin.setup.domain.SetupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ServersViewModel
@Inject
constructor(
    private val repository: SetupRepository,
    private val identityReadPort: IdentityReadPort,
    private val identityWritePort: IdentityWritePort,
) : ViewModel() {
    private val _state = MutableStateFlow(ServersState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<ServersEvent>()
    val events = eventsChannel.receiveAsFlow()

    fun loadServers() {
        viewModelScope.launch {
            val servers = repository.getServers()
            _state.emit(ServersState(servers = servers))
        }
    }

    fun loadTrustedCertificates() {
        viewModelScope.launch {
            val trustedCertificates = repository.getTrustedCertificates()
            _state.emit(_state.value.copy(trustedCertificates = trustedCertificates))
        }
    }

    private fun clearTrustedCertificate(trustKey: String) {
        viewModelScope.launch {
            repository.clearTrustedCertificate(trustKey)
            loadTrustedCertificates()
        }
    }

    private fun setCurrentServer(serverId: String) {
        viewModelScope.launch {
            val server =
                _state.value.servers.firstOrNull { server -> server.server.id == serverId }
                    ?: return@launch
            val currentIdentity = identityReadPort.current()
            val selection =
                IdentitySelection(
                    serverId = serverId,
                    userId = server.user?.id?.toString(),
                    addressId =
                        (server.server.currentServerAddressId ?: server.addresses.firstOrNull()?.id)
                            ?.toString(),
                    offlineMode = currentIdentity.selection.offlineMode,
                )
            val result =
                identityWritePort.selectServer(selection) { _, permit ->
                    permit.commit { repository.setCurrentServer(serverId) }
                }
            if (
                result == IdentityTransitionResult.APPLIED ||
                    result == IdentityTransitionResult.NO_OP
            ) {
                eventsChannel.send(ServersEvent.ServerChanged)
            }
        }
    }

    private fun setCurrentAddress(addressId: UUID) {
        viewModelScope.launch {
            repository.setCurrentAddress(addressId)

            eventsChannel.send(ServersEvent.AddressChanged)
        }
    }

    private fun deleteServer(serverId: String) {
        viewModelScope.launch {
            repository.deleteServer(serverId)
            loadServers()
        }
    }

    fun onAction(action: ServersAction) {
        when (action) {
            is ServersAction.OnServerClick -> {
                setCurrentServer(action.serverId)
            }
            is ServersAction.OnAddressClick -> {
                setCurrentAddress(action.addressId)
            }
            is ServersAction.DeleteServer -> {
                deleteServer(action.serverId)
            }
            is ServersAction.LoadTrustedCertificates -> {
                loadTrustedCertificates()
            }
            is ServersAction.ClearTrustedCertificate -> {
                clearTrustedCertificate(action.trustKey)
            }
            else -> Unit
        }
    }
}
