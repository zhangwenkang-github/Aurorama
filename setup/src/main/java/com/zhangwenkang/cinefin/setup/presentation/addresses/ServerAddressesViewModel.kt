package com.zhangwenkang.cinefin.setup.presentation.addresses

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.models.ServerAddress
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class ServerAddressesViewModel
@Inject
constructor(val application: Application, private val database: ServerDatabaseDao) : ViewModel() {
    private val _state = MutableStateFlow(ServerAddressesState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<ServerAddressesEvent>(Channel.BUFFERED)
    val events = eventsChannel.receiveAsFlow()

    private var currentServerId: String = ""

    fun loadAddresses(serverId: String) {
        currentServerId = serverId
        viewModelScope.launch {
            try {
                val serverWithAddresses = database.getServerWithAddresses(serverId)
                _state.emit(ServerAddressesState(addresses = serverWithAddresses.addresses))
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    fun deleteAddress(addressId: UUID) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentAddress = database.getServerCurrentAddress(currentServerId)
            if (addressId == currentAddress?.id) {
                Timber.w("Refused to delete the current address: %s", addressId)
                // 会话7 事故的直接诱因之一：被拒时只有日志，用户以为删除已生效。
                eventsChannel.send(ServerAddressesEvent.CannotDeleteCurrentAddress)
                return@launch
            }
            database.deleteServerAddress(addressId)
            loadAddresses(currentServerId)
        }
    }

    fun addAddress(address: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jellyfinApi = JellyfinApi(application.applicationContext)
                jellyfinApi.api.update(baseUrl = address)
                val systemInfo by jellyfinApi.systemApi.getPublicSystemInfo()
                if (systemInfo.id != currentServerId) {
                    return@launch
                }
                val serverAddress = ServerAddress(UUID.randomUUID(), currentServerId, address)
                database.insertServerAddress(serverAddress)
                loadAddresses(currentServerId)
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    fun onAction(action: ServerAddressesAction) {
        when (action) {
            is ServerAddressesAction.AddAddress -> {
                addAddress(action.address)
            }
            is ServerAddressesAction.DeleteAddress -> {
                deleteAddress(action.addressId)
            }
            else -> Unit
        }
    }
}
