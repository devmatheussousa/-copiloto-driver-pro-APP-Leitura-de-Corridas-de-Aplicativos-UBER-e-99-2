package com.copilotodriverpro.finance

import com.copilotodriverpro.database.EntryDao
import com.copilotodriverpro.database.EntryEntity
import com.copilotodriverpro.database.EntryType
import kotlinx.coroutines.flow.Flow

/** Período de filtro do histórico. */
enum class HistoryPeriod { DAY, WEEK, MONTH, ALL }

data class EntryTotals(
    val earnings: Double,
    val expenses: Double,
    val hasExpenseData: Boolean   // false => sem despesas registradas, resultado não concluído
) {
    val result: Double? get() = if (hasExpenseData) earnings - expenses else null
}

/**
 * Repositório de lançamentos financeiros.
 */
class EntryRepository(private val dao: EntryDao) {

    val all: Flow<List<EntryEntity>> = dao.observeAll()

    fun byShift(shiftId: Long): Flow<List<EntryEntity>> = dao.observeByShift(shiftId)

    suspend fun add(
        type: EntryType,
        category: String,
        amount: Double,
        timestamp: Long = System.currentTimeMillis(),
        note: String = "",
        shiftId: Long? = null
    ): Long = dao.insert(
        EntryEntity(
            type = type,
            category = category,
            amount = amount,
            timestamp = timestamp,
            note = note,
            shiftId = shiftId
        )
    )

    suspend fun update(entry: EntryEntity) = dao.update(entry)

    suspend fun delete(entry: EntryEntity) = dao.delete(entry)

    suspend fun entriesBetween(from: Long, to: Long): List<EntryEntity> = dao.between(from, to)

    /**
     * Totais de um conjunto de lançamentos.
     * Sem nenhuma despesa registrada, o resultado NÃO é apresentado como
     * lucro — apenas os ganhos, com a marcação de dados incompletos.
     */
    fun totalsOf(entries: List<EntryEntity>): EntryTotals {
        val earnings = entries.filter { it.type == EntryType.GANHO }.sumOf { it.amount }
        val expenseList = entries.filter { it.type == EntryType.DESPESA }
        return EntryTotals(
            earnings = earnings,
            expenses = expenseList.sumOf { it.amount },
            hasExpenseData = expenseList.isNotEmpty()
        )
    }

    /** Totais dentro de um período (dia/semana/mês) até agora. */
    suspend fun totalsFor(period: HistoryPeriod): EntryTotals {
        val now = System.currentTimeMillis()
        val from = when (period) {
            HistoryPeriod.DAY -> startOfDay(now)
            HistoryPeriod.WEEK -> now - 7L * 24 * 60 * 60 * 1000
            HistoryPeriod.MONTH -> now - 30L * 24 * 60 * 60 * 1000
            HistoryPeriod.ALL -> 0L
        }
        return totalsOf(dao.between(from, now))
    }

    private fun startOfDay(millis: Long): Long {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = millis
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }
}
