package com.copilotodriverpro.finance

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.copilotodriverpro.CopilotApp
import com.copilotodriverpro.database.EntryEntity
import com.copilotodriverpro.database.EntryType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel dos lançamentos e do histórico financeiro.
 */
class EntryViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as CopilotApp).entryRepository

    val entries: StateFlow<List<EntryEntity>> = repository.all
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun add(
        type: EntryType,
        category: String,
        amount: Double,
        timestamp: Long,
        note: String,
        shiftId: Long?
    ) = viewModelScope.launch {
        repository.add(type, category, amount, timestamp, note, shiftId)
    }

    fun update(entry: EntryEntity) = viewModelScope.launch { repository.update(entry) }

    fun delete(entry: EntryEntity) = viewModelScope.launch { repository.delete(entry) }

    /** Totais dos lançamentos filtrados — usados pela tela de Histórico. */
    fun totalsOf(entries: List<EntryEntity>): EntryTotals = repository.totalsOf(entries)

    companion object {
        fun factory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = CopilotApp.instance
                return EntryViewModel(app) as T
            }
        }
    }
}
