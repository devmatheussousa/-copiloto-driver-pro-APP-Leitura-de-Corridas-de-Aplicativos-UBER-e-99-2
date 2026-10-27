package com.copilotodriverpro.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.copilotodriverpro.CopilotApp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val app: CopilotApp) : ViewModel() {

    val settings = app.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, DriverSettings())

    fun update(transform: (DriverSettings) -> DriverSettings) {
        viewModelScope.launch {
            app.settingsRepository.save(transform(settings.value))
        }
    }

    fun restoreDefaults() {
        viewModelScope.launch {
            app.settingsRepository.save(DriverSettings())
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(context.applicationContext as CopilotApp) as T
        }
    }
}
