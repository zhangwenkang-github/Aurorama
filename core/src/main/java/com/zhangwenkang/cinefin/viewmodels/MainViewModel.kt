package com.zhangwenkang.cinefin.viewmodels

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinThemes
import com.zhangwenkang.cinefin.core.presentation.theme.migrateThemeId
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

    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            appPreferences.offlineMode.backendName,
            appPreferences.theme.backendName,
            appPreferences.dynamicColors.backendName -> check(showLoading = false)
        }
    }

    init {
        check()
        appPreferences.sharedPreferences.registerOnSharedPreferenceChangeListener(
            preferencesListener
        )
    }

    override fun onCleared() {
        appPreferences.sharedPreferences.unregisterOnSharedPreferenceChangeListener(
            preferencesListener
        )
        super.onCleared()
    }

    private fun check(showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) _state.emit(MainState(isLoading = true))
            val scope = resolveScope()
            val isDynamicColors = appPreferences.getValue(appPreferences.dynamicColors)
            val storedThemeId = migrateThemeId(appPreferences.getValue(appPreferences.theme))
            _state.emit(
                MainState(
                    isLoading = false,
                    isDynamicColors = isDynamicColors,
                    themeId =
                        if (isDynamicColors && storedThemeId == CinefinThemes.SYSTEM) {
                            CinefinThemes.DYNAMIC
                        } else {
                            storedThemeId
                        },
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
    /** 迁移后的主题 id（[CinefinThemes] 键或 `system`）。 */
    val themeId: String = CinefinThemes.DEFAULT,
    /** 由 [themeId] 派生的明暗外观；`system` / `dynamic` 由宿主按系统明暗解析。 */
    val isDarkTheme: Boolean = true,
)
