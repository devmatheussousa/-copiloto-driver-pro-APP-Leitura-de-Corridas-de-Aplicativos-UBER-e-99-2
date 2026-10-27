package com.copilotodriverpro

import com.copilotodriverpro.database.EntryEntity
import com.copilotodriverpro.database.EntryType
import com.copilotodriverpro.database.RideEntity
import com.copilotodriverpro.database.ShiftEntity
import com.copilotodriverpro.finance.EarningsStats
import com.copilotodriverpro.finance.EntrySummary
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cálculos da aba Ganhos (saldo do dia, série de 7 dias, média, nota, tempo online, meta). */
class EarningsStatsTest {

    private fun ms(d: Int, h: Int, m: Int = 0) =
        Calendar.getInstance().apply { clear(); set(2026, 9, d, h, m, 0) }.timeInMillis

    private fun gain(id: Long, amount: Double, t: Long) =
        EntryEntity(id = id, type = EntryType.GANHO, category = "Corrida", amount = amount, timestamp = t)

    private val now = ms(23, 15)
    private val today = EntrySummary.startOfDay(now)

    private val entries = listOf(
        gain(1, 50.0, ms(23, 9)), gain(2, 30.5, ms(23, 14)),
        EntryEntity(id = 3, type = EntryType.DESPESA, category = "Combustível", amount = 20.0, timestamp = ms(23, 10)),
        gain(4, 40.0, ms(22, 23, 59)), gain(5, 10.0, ms(17, 10))
    )

    private fun ride(id: String, fare: Double, rating: Double?, t: Long) =
        RideEntity(id = id, platform = "UBER", fare = fare, passengerRating = rating, timestamp = t)

    @Test
    fun `saldo do dia soma so ganhos de hoje`() {
        assertEquals(80.5, EarningsStats.earningsOn(entries, today), 0.0001)
    }

    @Test
    fun `serie termina hoje e tem o tamanho pedido`() {
        val s = EarningsStats.dailySeries(entries, now, 7)
        assertEquals(7, s.size)
        assertEquals(today, s.last().dayStart)
        assertEquals(80.5, s.last().total, 0.0001)
        assertEquals(40.0, s[5].total, 0.0001)
        assertEquals(10.0, s[0].total, 0.0001)
        assertEquals(0.0, s[1].total, 0.0001)
    }

    @Test
    fun `media por corrida e nota so com dados reais`() {
        val rides = listOf(ride("a", 20.0, 4.9, ms(23, 9)), ride("b", 10.0, null, ms(23, 10)), ride("c", 99.0, 5.0, ms(22, 10)))
        val hoje = EarningsStats.ridesOn(rides, today)
        assertEquals(2, hoje.size)
        assertEquals(15.0, EarningsStats.averagePerRide(hoje)!!, 0.0001)
        assertEquals(4.9, EarningsStats.averageRating(hoje)!!, 0.0001)
        assertNull(EarningsStats.averagePerRide(emptyList()))
        assertNull(EarningsStats.averageRating(listOf(rides[1])))
    }

    @Test
    fun `tempo online soma turnos do dia, inclusive aberto e virada de dia`() {
        val shifts = listOf(
            ShiftEntity(1, ms(23, 8), ms(23, 10)),
            ShiftEntity(2, ms(23, 14), null),
            ShiftEntity(3, ms(22, 20), ms(23, 1))
        )
        assertEquals(240L, EarningsStats.onlineMinutes(shifts, today, now))
        assertNull(EarningsStats.onlineMinutes(emptyList(), today, now))
    }

    @Test
    fun `meta, progresso e quanto falta`() {
        assertEquals(0.4025, EarningsStats.goalProgress(80.5, 200.0)!!, 1e-9)
        assertEquals(1.0, EarningsStats.goalProgress(300.0, 200.0)!!, 1e-9)
        assertNull(EarningsStats.goalProgress(5.0, 0.0))
        assertEquals(119.5, EarningsStats.remaining(80.5, 200.0), 1e-9)
        assertEquals(0.0, EarningsStats.remaining(300.0, 200.0), 1e-9)
    }

    @Test
    fun `formato de tempo`() {
        assertEquals("5h 48min", EarningsStats.formatMinutes(348))
        assertEquals("45min", EarningsStats.formatMinutes(45))
        assertTrue(EarningsStats.formatMinutes(60).startsWith("1h"))
    }
}
