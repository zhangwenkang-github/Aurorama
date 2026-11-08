package com.zhangwenkang.cinefin.viewmodels

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.repository.catalog.CatalogPrincipalContext
import com.zhangwenkang.cinefin.repository.catalog.CatalogReadPort
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel
@Inject
constructor(
    private val appPreferences: AppPreferences,
    private val database: ServerDatabaseDao,
    private val catalogReadPort: CatalogReadPort,
) : ViewModel() {
    private val _state = MutableStateFlow(MainState())
    val state = _state.asStateFlow()

    private val offlineModeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == appPreferences.offlineMode.backendName) check(showLoading = false)
    }

    init {
        check()
        appPreferences.sharedPreferences.registerOnSharedPreferenceChangeListener(
            offlineModeListener
        )
    }

    override fun onCleared() {
        appPreferences.sharedPreferences.unregisterOnSharedPreferenceChangeListener(
            offlineModeListener
        )
        super.onCleared()
    }

    private fun check(showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) _state.emit(MainState(isLoading = true))
            val scope = resolveScope()
            _state.emit(
                MainState(
                    isLoading = false,
                    isDynamicColors = appPreferences.getValue(appPreferences.dynamicColors),
                    hasServers = database.getServersCount() > 0,
                    hasCurrentServer = scope != null,
                    hasCurrentUser = scope != null,
                    isOfflineMode =
                        scope?.principalContext == CatalogPrincipalContext.SAVED_OFFLINE_ACCOUNT,
                )
            )
        }
    }

    private suspend fun resolveScope() =
        try {
            catalogReadPort.currentScope()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
}

data class MainState(
    val isLoading: Boolean = true,
    val isDynamicColors: Boolean = true,
    val hasServers: Boolean = false,
    val hasCurrentServer: Boolean = false,
    val hasCurrentUser: Boolean = false,
    val isOfflineMode: Boolean = false,
)
