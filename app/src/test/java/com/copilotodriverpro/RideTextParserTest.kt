package com.copilotodriverpro

import com.copilotodriverpro.models.RideSource
import com.copilotodriverpro.models.StopVerdict
import com.copilotodriverpro.recognition.RideTextParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes do parser de texto de ofertas (formato brasileiro).
 */
class RideTextParserTest {

    @Test
    fun `extrai valor tempo e nota`() {
        val text = """
            R$ 27,50
            4,8 km
            18 min
            ★ 4,92
        """.trimIndent()
        val offer = RideTextParser.parse(text, RideSource.MANUAL)
        assertNotNull(offer)
        assertEquals(27.50, offer!!.fare!!, 0.001)
        assertEquals(18.0, offer.durationMin!!, 0.001)
        assertEquals(4.92, offer.passengerRating!!, 0.001)
    }

    @Test
    fun `parada confirmada por frase explicita`() {
        val offer = RideTextParser.parse("R$ 19,90\n1 parada\n9 min", RideSource.MANUAL)
        assertEquals(StopVerdict.CONFIRMED, offer!!.stopVerdict)
    }

    @Test
    fun `parada multipla confirmada`() {
        val offer = RideTextParser.parse("R$ 32,00\nmúltiplas paradas\n25 min", RideSource.MANUAL)
        assertEquals(StopVerdict.CONFIRMED, offer!!.stopVerdict)
    }

    @Test
    fun `parada ambigua vira possivel`() {
        val offer = RideTextParser.parse("R$ 15,00\nparadas diversas\n12 min", RideSource.MANUAL)
        assertEquals(StopVerdict.POSSIBLE, offer!!.stopVerdict)
    }

    @Test
    fun `sem parada`() {
        val offer = RideTextParser.parse("R$ 22,00\n7,3 km\n15 min", RideSource.MANUAL)
        assertEquals(StopVerdict.NONE, offer!!.stopVerdict)
    }

    @Test
    fun `dois valores de km separados`() {
        val offer = RideTextParser.parse("R$ 25,00\n1,2 km até o passageiro\n8,5 km de viagem\n20 min", RideSource.MANUAL)
        assertEquals(1.2, offer!!.distanceToPickupKm!!, 0.001)
        assertEquals(8.5, offer.tripDistanceKm!!, 0.001)
    }

    @Test
    fun `tempo em horas e minutos`() {
        val offer = RideTextParser.parse("R$ 80,00\n1 h 20\n40 km", RideSource.MANUAL)
        assertEquals(80.0, offer!!.durationMin!!, 0.001)
    }

    @Test
    fun `texto sem oferta retorna nulo`() {
        assertNull(RideTextParser.parse("Bom dia\nComo vai você hoje?", RideSource.MANUAL))
    }

    @Test
    fun `numero brasileiro com ponto de milhar`() {
        val offer = RideTextParser.parse("R$ 1.234,56\n10 km\n30 min", RideSource.MANUAL)
        assertEquals(1234.56, offer!!.fare!!, 0.001)
    }

    @Test
    fun `nota baixa detectada`() {
        val offer = RideTextParser.parse("R$ 18,00\n★ 4,10\n10 min", RideSource.MANUAL)
        assertTrue(offer!!.passengerRating!! < 4.8)
    }
}
