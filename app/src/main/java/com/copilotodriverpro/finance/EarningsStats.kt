package com.copilotodriverpro.finance

import com.copilotodriverpro.database.EntryEntity
import com.copilotodriverpro.database.EntryType
import com.copilotodriverpro.database.RideEntity
import com.copilotodriverpro.database.ShiftEntity

/** Ganho total de um dia da série do gráfico. */
data class DayEarning(val dayStart: Long, val total: Double)

/**
 * Cálculos da aba Ganhos, todos sobre os registros reais do app:
 * ganhos = lançamentos do tipo GANHO (automáticos das corridas aceitas + manuais),
 * corridas/avaliação = tabela de corridas aceitas, tempo online = turnos.
 * Nada é estimado: sem dado, o resultado é nulo/zero e a tela mostra estado vazio.
 */
object EarningsStats {

    fun dayEnd(dayStart: Long): Long = EntrySummary.periodEnd(SummaryPeriod.DAY, dayStart)

    fun earningsOn(entries: List<EntryEntity>, dayStart: Long): Double {
        val end = dayEnd(dayStart)
        return entries.filter { it.type == EntryType.GANHO && it.timestamp >= dayStart && it.timestamp < end }
            .sumOf { it.amount }
    }

    /** Últimos [days] dias terminando hoje (o último item é hoje), do mais antigo ao mais novo. */
    fun dailySeries(entries: List<EntryEntity>, now: Long, days: Int): List<DayEarning> {
        val today = EntrySummary.startOfDay(now)
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = today }
        val starts = ArrayList<Long>()
        for (i in 0 until days) {
            starts.add(0, cal.timeInMillis)
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        }
        return starts.map { DayEarning(it, earningsOn(entries, it)) }
    }

    fun ridesOn(rides: List<RideEntity>, dayStart: Long): List<RideEntity> {
        val end = dayEnd(dayStart)
        return rides.filter { it.timestamp >= dayStart && it.timestamp < end }
    }

    /** Média por corrida (ganho das corridas / quantidade); nula se não há corridas. */
    fun averagePerRide(rides: List<RideEntity>): Double? =
        if (rides.isEmpty()) null else rides.sumOf { it.fare } / rides.size

    /** Média das notas dos passageiros lidas nas corridas; nula se nenhuma corrida trouxe nota. */
    fun averageRating(rides: List<RideEntity>): Double? {
        val ratings = rides.mapNotNull { it.passengerRating }.filter { it > 0.0 }
        return if (ratings.isEmpty()) null else ratings.average()
    }

    /**
     * Minutos online do dia = soma dos turnos que cruzam o dia (turno aberto vai até [now]).
     * Nulo se não houve nenhum turno no dia.
     */
    fun onlineMinutes(shifts: List<ShiftEntity>, dayStart: Long, now: Long): Long? {
        val end = dayEnd(dayStart)
        var totalMs = 0L
        var any = false
        for (s in shifts) {
            val from = maxOf(s.startMillis, dayStart)
            val to = minOf(s.endMillis ?: now, end, now)
            if (to > from) { totalMs += to - from; any = true }
        }
        return if (any) totalMs / 60_000L else null
    }

    /** Progresso da meta de 0 a 1 (limitado); nulo sem meta. */
    fun goalProgress(earned: Double, goal: Double): Double? =
        if (goal <= 0.0) null else (earned / goal).coerceIn(0.0, 1.0)

    /** Quanto falta para a meta (nunca negativo). */
    fun remaining(earned: Double, goal: Double): Double = (goal - earned).coerceAtLeast(0.0)

    fun formatMinutes(min: Long): String {
        val h = min / 60
        val m = min % 60
        return if (h > 0) "${h}h %02dmin".format(m) else "${m}min"
    }
}
