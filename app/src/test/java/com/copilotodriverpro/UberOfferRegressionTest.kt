package com.copilotodriverpro

import com.copilotodriverpro.analyzer.ProfitabilityCalculator
import com.copilotodriverpro.models.RideSource
import com.copilotodriverpro.models.SupportedPlatform
import com.copilotodriverpro.recognition.RideTextParser
import com.copilotodriverpro.settings.DriverSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Regressão: oferta real da Uber com ponto decimal nos km e dois tempos (busca + viagem). */
class UberOfferRegressionTest {

    private val text = """
        UberX
        Exclusivo
        R${'$'} 13,01
        R${'$'}1,13/km aprox.
        4,89 (19)
        5 min (2.1 km)
        Rua Benjamin Santos, Senador Canedo
        13 minutos (9.4 km)
        R. JO-47, Jardim das Oliveiras, Sen. Canedo
        Aceitar
    """.trimIndent()

    @Test
    fun `km com ponto decimal nao viram multiplos de 10`() {
        val o = RideTextParser.parse(text, RideSource.ACCESSIBILITY, SupportedPlatform.UBER)
        assertNotNull(o)
        assertEquals(13.01, o!!.fare!!, 0.001)
        assertEquals(2.1, o.distanceToPickupKm!!, 0.001)
        assertEquals(9.4, o.tripDistanceKm!!, 0.001)
        assertEquals(4.89, o.passengerRating!!, 0.001)
    }

    @Test
    fun `tempo de busca e de viagem sao separados e somados`() {
        val o = RideTextParser.parse(text, RideSource.ACCESSIBILITY, SupportedPlatform.UBER)!!
        assertEquals(5.0, o.timeToPickupMin!!, 0.001)
        assertEquals(13.0, o.durationMin!!, 0.001)
        assertEquals(18.0, o.totalDurationMin!!, 0.001)
        assertEquals(11.5, o.totalDistanceKm!!, 0.001)
    }

    @Test
    fun `rentabilidade usa totais`() {
        val o = RideTextParser.parse(text, RideSource.ACCESSIBILITY, SupportedPlatform.UBER)!!
        val m = ProfitabilityCalculator.calculate(o, DriverSettings(includePickupInKm = true))
        assertEquals(13.01 / 11.5, m.grossPerKm!!, 0.001)          // ~1,13
        assertEquals(13.01 / 18.0 * 60.0, m.grossPerHour!!, 0.001) // ~43,37
        assertEquals(13.01 / 18.0, m.grossPerMinute!!, 0.001)      // ~0,72
    }

    @Test
    fun `valores em reais com milhar continuam corretos`() {
        val o = RideTextParser.parse("R${'$'} 1.234,56\n10 km\n30 min\nAceitar", RideSource.MANUAL)!!
        assertEquals(1234.56, o.fare!!, 0.001)
    }
}
