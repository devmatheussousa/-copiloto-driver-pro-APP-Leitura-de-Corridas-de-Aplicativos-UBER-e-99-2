package com.copilotodriverpro.shifts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.copilotodriverpro.CopilotApp
import com.copilotodriverpro.database.EntryDao
import com.copilotodriverpro.database.EntryType
import com.copilotodriverpro.database.ShiftEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Resumo exibido na lista e no detalhe do turno. */
data class ShiftSummary(
    val shift: ShiftEntity,
    val earnings: Double,
    val expenses: Double,
    val hasExpenseData: Boolean,
    val durationMillis: Long
) {
    val result: Double? get() = if (hasExpenseData) earnings - expenses else null
}

class ShiftViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as CopilotApp).shiftRepository
    private val entryDao: EntryDao = (app as CopilotApp).appDatabase.entryDao()

    val activeShift: StateFlow<ShiftEntity?> = repository.active
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val shifts: StateFlow<List<ShiftSummary>> = repository.all
        .map { list -> list.map { summarize(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun start() = viewModelScope.launch { repository.start() }

    fun end(distanceKm: Double?) = viewModelScope.launch { repository.end(distanceKm) }

    fun delete(shift: ShiftEntity) = viewModelScope.launch { repository.delete(shift) }

    private suspend fun summarize(shift: ShiftEntity): ShiftSummary {
        val entries = if (shift.id != 0L) entryDao.byShift(shift.id) else emptyList()
        val earnings = entries.filter { it.type == EntryType.GANHO }.sumOf { it.amount }
        val expensesList = entries.filter { it.type == EntryType.DESPESA }
        val end = shift.endMillis ?: System.currentTimeMillis()
        return ShiftSummary(
            shift = shift,
            earnings = earnings,
            expenses = expensesList.sumOf { it.amount },
            hasExpenseData = expensesList.isNotEmpty(),
            durationMillis = end - shift.startMillis
        )
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = com.copilotodriverpro.CopilotApp.instance
                return ShiftViewModel(app) as T
            }
        }
    }
}
