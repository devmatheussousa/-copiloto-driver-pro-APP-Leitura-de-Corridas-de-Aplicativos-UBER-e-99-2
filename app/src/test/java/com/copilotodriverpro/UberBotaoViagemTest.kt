package com.copilotodriverpro

import com.copilotodriverpro.models.RideSource
import com.copilotodriverpro.models.SupportedPlatform
import com.copilotodriverpro.recognition.RideScreenClassifier
import com.copilotodriverpro.recognition.RideTextParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressão: card da Uber em que o botão principal se chama "Viagem" (e não "Aceitar"/"Selecionar"),
 * com "+R$ 1,00 incluído" e "+10% de ganhos Uber Pro". O app ignorava a corrida inteira.
 */
class UberBotaoViagemTest {

    private val card = """
        UberX
        R${'$'} 11,05
        Est. de R${'$'}1,27/km
        4,92 (299)
        Verificado
        +R${'$'} 1,00 incluído
        +10% de ganhos Uber Pro
        6 min (2.9 km)
        R. R P - Nove, Senador Canedo
        12 minutos (5.8 km)
        Rua rp18, q30 lt40, Terrabela Cerrado I, Senador Canedo
        Viagem
    """.trimIndent()

    @Test
    fun `botao Viagem conta como botao de aceite na Uber`() {
        assertTrue(RideScreenClassifier.isAcceptLabel("Viagem", SupportedPlatform.UBER))
        assertTrue(RideScreenClassifier.isAcceptLabel("  viagem ", SupportedPlatform.UBER))
        assertTrue(RideScreenClassifier.isAcceptLabel("Viagem, botão", SupportedPlatform.UBER))
        assertTrue(RideScreenClassifier.isAcceptLabel("Aceitar", SupportedPlatform.UBER))
        assertTrue(RideScreenClassifier.isAcceptLabel("Selecionar", SupportedPlatform.UBER))
    }

    @Test
    fun `selos que comecam com viagem nao sao botao`() {
        assertFalse(RideScreenClassifier.isAcceptLabel("Viagem longa", SupportedPlatform.UBER))
        assertFalse(RideScreenClassifier.isAcceptLabel("Viagem longa (mais de 30 min)", SupportedPlatform.UBER))
        assertFalse(RideScreenClassifier.isAcceptLabel("Viagem curta", SupportedPlatform.UBER))
        assertFalse(RideScreenClassifier.isAcceptLabel("Viagem de 14 minutos (6,1 km)", SupportedPlatform.UBER))
    }

    @Test
    fun `na 99 viagem nao vira botao de aceite`() {
        assertFalse(RideScreenClassifier.isAcceptLabel("Viagem", SupportedPlatform.NINE_NINE))
    }

    @Test
    fun `tela com botao Viagem e reconhecida como oferta`() {
        assertTrue(RideScreenClassifier.isOfferScreen(card, SupportedPlatform.UBER))
    }

    @Test
    fun `tela sem botao continua nao sendo oferta`() {
        val semBotao = card.lines().dropLast(1).joinToString("\n")
        assertFalse(RideScreenClassifier.isOfferScreen(semBotao, SupportedPlatform.UBER))
    }

    @Test
    fun `campos do card sao lidos corretamente`() {
        val o = RideTextParser.parse(card, RideSource.ACCESSIBILITY, SupportedPlatform.UBER)
        assertNotNull(o)
        assertEquals(11.05, o!!.fare!!, 0.001)
        assertEquals(2.9, o.distanceToPickupKm!!, 0.001)
        assertEquals(6.0, o.timeToPickupMin!!, 0.001)
        assertEquals(5.8, o.tripDistanceKm!!, 0.001)
        assertEquals(12.0, o.durationMin!!, 0.001)
        assertEquals(4.92, o.passengerRating!!, 0.001)
        assertTrue(o.bonusIncludedInFare)
        assertEquals(1.27, o.platformPerKmHint!!, 0.001)
        // R$ 1,27/km do card = 11,05 / (2,9 + 5,8): nenhum problema bloqueante de leitura.
        assertTrue(o.issues.none { it.blocking })
    }
}
