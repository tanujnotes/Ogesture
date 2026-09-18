package com.ogesture.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ogesture.data.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository.get(app)

    val masterEnabled: StateFlow<Boolean> = repo.masterEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = false,
    )

    fun setMasterEnabled(enabled: Boolean) {
        viewModelScope.launch {
            // The accessibility service observes this flow and attaches/detaches the gesture
            // zones itself, so toggling the switch is just a datastore write — no foreground
            // service to start or stop anymore.
            repo.setMasterEnabled(enabled)
        }
    }

    val excludedApps: StateFlow<Set<String>> = repo.excludedApps.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = emptySet(),
    )

    fun setAppExcluded(packageName: String, excluded: Boolean) {
        viewModelScope.launch { repo.setAppExcluded(packageName, excluded) }
    }
}
