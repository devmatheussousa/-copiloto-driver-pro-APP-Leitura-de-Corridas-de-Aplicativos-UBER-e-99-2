package com.copilotodriverpro.finance

import java.util.Calendar

/** Períodos do resumo da aba Lançamentos (calendário: hoje, semana atual, mês atual). */
enum class SummaryPeriod { DAY, WEEK, MONTH }

/**
 * Totais de um período já com os custos fixos diários.
 *
 * Regra do app mantida: sem NENHUM custo (nem despesa lançada, nem custo fixo configurado) o
 * líquido não é apresentado como lucro ([net] fica nulo).
 */
data class PeriodTotals(
    val earnings: Double,
    val expenses: Double,
    val fixedTotal: Double,
    val hasCostData: Boolean
) {
    val costs: Double get() = expenses + fixedTotal
    val net: Double? get() = if (hasCostData) earnings - costs else null
}

object EntrySummary {

    private const val DAY_MS = 86_400_000.0

    fun startOfDay(millis: Long): Long =
        Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** Início do período que contém [now] (00:00 do dia, do 1º dia da semana ou do dia 1º do mês). */
    fun periodStart(
        period: SummaryPeriod,
        now: Long,
        firstDayOfWeek: Int = Calendar.getInstance().firstDayOfWeek
    ): Long {
        val cal = Calendar.getInstance().apply {
            firstDayOfWeek.let { this.firstDayOfWeek = it }
            timeInMillis = startOfDay(now)
        }
        when (period) {
            SummaryPeriod.DAY -> Unit
            SummaryPeriod.WEEK -> cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
            SummaryPeriod.MONTH -> cal.set(Calendar.DAY_OF_MONTH, 1)
        }
        return cal.timeInMillis
    }

    /** Fim (exclusivo) do período que começa em [start]. */
    fun periodEnd(period: SummaryPeriod, start: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = start }
        when (period) {
            SummaryPeriod.DAY -> cal.add(Calendar.DAY_OF_YEAR, 1)
            SummaryPeriod.WEEK -> cal.add(Calendar.DAY_OF_YEAR, 7)
            SummaryPeriod.MONTH -> cal.add(Calendar.MONTH, 1)
        }
        return cal.timeInMillis
    }

    /** Dias do período já decorridos, contando hoje (Hoje = 1). */
    fun daysElapsed(start: Long, now: Long): Int {
        val diff = (startOfDay(now) - startOfDay(start)) / DAY_MS
        return Math.round(diff).toInt() + 1
    }

    fun totals(
        earnings: Double,
        expenses: Double,
        hasExpenseEntries: Boolean,
        fixedCostPerDay: Double,
        daysElapsed: Int
    ): PeriodTotals {
        val fixed = if (fixedCostPerDay > 0.0) fixedCostPerDay * daysElapsed else 0.0
        return PeriodTotals(
            earnings = earnings,
            expenses = expenses,
            fixedTotal = fixed,
            hasCostData = hasExpenseEntries || fixed > 0.0
        )
    }
}
