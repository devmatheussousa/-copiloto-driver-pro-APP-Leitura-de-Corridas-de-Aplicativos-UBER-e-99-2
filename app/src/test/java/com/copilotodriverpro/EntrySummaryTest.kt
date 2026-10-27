package com.copilotodriverpro

import com.copilotodriverpro.finance.EntrySummary
import com.copilotodriverpro.finance.SummaryPeriod
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Períodos (Hoje/Semana/Mês) e totais do resumo da aba Lançamentos. */
class EntrySummaryTest {

    private fun ms(y: Int, m: Int, d: Int, h: Int = 12) =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, 0, 0) }.timeInMillis

    private fun ymd(t: Long): String = Calendar.getInstance().apply { timeInMillis = t }.let {
        "%04d-%02d-%02d".format(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH))
    }

    private val quarta = ms(2026, 10, 21, 15)

    @Test
    fun `inicio dos periodos`() {
        assertEquals("2026-10-21", ymd(EntrySummary.periodStart(SummaryPeriod.DAY, quarta)))
        assertEquals("2026-10-18", ymd(EntrySummary.periodStart(SummaryPeriod.WEEK, quarta, Calendar.SUNDAY)))
        assertEquals("2026-10-01", ymd(EntrySummary.periodStart(SummaryPeriod.MONTH, quarta)))
    }

    @Test
    fun `domingo pertence a semana certa conforme o primeiro dia da semana`() {
        val domingo = ms(2026, 10, 25, 9)
        assertEquals("2026-10-19", ymd(EntrySummary.periodStart(SummaryPeriod.WEEK, domingo, Calendar.MONDAY)))
        assertEquals("2026-10-25", ymd(EntrySummary.periodStart(SummaryPeriod.WEEK, domingo, Calendar.SUNDAY)))
    }

    @Test
    fun `fim dos periodos e exclusivo`() {
        val dia = EntrySummary.periodStart(SummaryPeriod.DAY, quarta)
        assertEquals("2026-10-22", ymd(EntrySummary.periodEnd(SummaryPeriod.DAY, dia)))
        val mes = EntrySummary.periodStart(SummaryPeriod.MONTH, quarta)
        assertEquals("2026-11-01", ymd(EntrySummary.periodEnd(SummaryPeriod.MONTH, mes)))
        assertTrue(ms(2026, 10, 21, 0) in dia until EntrySummary.periodEnd(SummaryPeriod.DAY, dia))
        assertFalse(dia - 60_000 in dia until EntrySummary.periodEnd(SummaryPeriod.DAY, dia))
    }

    @Test
    fun `dias decorridos contam hoje`() {
        assertEquals(1, EntrySummary.daysElapsed(EntrySummary.periodStart(SummaryPeriod.DAY, quarta), quarta))
        val semana = EntrySummary.periodStart(SummaryPeriod.WEEK, quarta, Calendar.SUNDAY)
        assertEquals(4, EntrySummary.daysElapsed(semana, quarta))
        assertEquals(21, EntrySummary.daysElapsed(EntrySummary.periodStart(SummaryPeriod.MONTH, quarta), quarta))
    }

    @Test
    fun `custos fixos entram no liquido`() {
        val t = EntrySummary.totals(15000.0, 500.0, true, 250.0, 1)
        assertEquals(250.0, t.fixedTotal, 0.001)
        assertEquals(750.0, t.costs, 0.001)
        assertEquals(14250.0, t.net!!, 0.001)
        assertEquals(1000.0, EntrySummary.totals(0.0, 0.0, false, 250.0, 4).fixedTotal, 0.001)
    }

    @Test
    fun `sem nenhum custo o liquido nao e apresentado`() {
        val t = EntrySummary.totals(120.0, 0.0, false, 0.0, 1)
        assertNull(t.net)
        assertFalse(t.hasCostData)
        // só o custo fixo já conta como dado de custo
        assertEquals(70.0, EntrySummary.totals(100.0, 0.0, false, 30.0, 1).net!!, 0.001)
    }
}
