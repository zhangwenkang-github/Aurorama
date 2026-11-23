package com.zhangwenkang.cinefin.setup.presentation.users

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.identity.IdentityTransitionResult
import com.zhangwenkang.cinefin.setup.domain.IdentityAttemptStaleException
import com.zhangwenkang.cinefin.setup.domain.IdentityTransitionFailedException
import com.zhangwenkang.cinefin.setup.domain.OfflineIdentityCoordinator
import com.zhangwenkang.cinefin.setup.domain.SavedUserActivationFailure
import com.zhangwenkang.cinefin.setup.domain.SavedUserActivationResult
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
class UsersViewModel @Inject constructor(private val repository: SetupRepository) : ViewModel() {
    private val _state = MutableStateFlow(UsersState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<UsersEvent>()
    val events = eventsChannel.receiveAsFlow()

    private val offlineIdentityCoordinator =
        OfflineIdentityCoordinator(repository::activateSavedUser)

    fun loadUsers() {
        viewModelScope.launch {
            val server = repository.getCurrentServer() ?: return@launch
            val users = repository.getUsers(server.id)
            val userIds = users.map { it.id }
            val currentUser = repository.getCurrentUser()

            _state.emit(
                UsersState(
                    users = users,
                    serverName = server.name,
                    currentUserId = currentUser?.id,
                )
            )

            try {
                val publicUsers = repository.getPublicUsers(server.id)
                _state.emit(
                    _state.value.copy(
                        publicUsers = publicUsers.filterNot { userIds.contains(it.id) }
                    )
                )
            } catch (_: Exception) {}
        }
    }

    /**
     * Delete user from database
     *
     * @param userId The id of the user
     */
    private fun deleteUser(userId: UUID) {
        viewModelScope.launch {
            repository.deleteUser(userId)
            loadUsers()
        }
    }

    private fun loginAsUser(userId: UUID) {
        if (_state.value.activatingUserId != null) return
        viewModelScope.launch {
            _state.emit(
                _state.value.copy(
                    activatingUserId = userId,
                    activationFailure = null,
                )
            )
            val result =
                try {
                    offlineIdentityCoordinator.activateSavedUser(userId)
                } catch (_: IdentityAttemptStaleException) {
                    _state.emit(
                        _state.value.copy(
                            activatingUserId = null,
                            activationFailure = SavedUserActivationFailure.INTERRUPTED,
                        )
                    )
                    return@launch
                } catch (error: IdentityTransitionFailedException) {
                    _state.emit(
                        _state.value.copy(
                            activatingUserId = null,
                            activationFailure =
                                when (error.result) {
                                    IdentityTransitionResult.RECOVERING ->
                                        SavedUserActivationFailure.IDENTITY_RECOVERING
                                    else -> SavedUserActivationFailure.UNKNOWN
                                },
                        )
                    )
                    return@launch
                }
            when (result) {
                is SavedUserActivationResult.Activated -> {
                    _state.emit(
                        _state.value.copy(
                            currentUserId = result.user.id,
                            activatingUserId = null,
                            activationFailure = null,
                        )
                    )
                    eventsChannel.send(UsersEvent.NavigateToHome)
                }
                is SavedUserActivationResult.Rejected -> {
                    _state.emit(
                        _state.value.copy(
                            activatingUserId = null,
                            activationFailure = result.failure,
                        )
                    )
                }
            }
        }
    }

    fun onAction(action: UsersAction) {
        when (action) {
            is UsersAction.OnUserClick -> {
                loginAsUser(action.userId)
            }
            is UsersAction.OnDeleteUser -> {
                deleteUser(action.userId)
            }
            else -> Unit
        }
    }
}
