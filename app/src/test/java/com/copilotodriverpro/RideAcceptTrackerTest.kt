package com.copilotodriverpro

import com.copilotodriverpro.models.RideOffer
import com.copilotodriverpro.models.SupportedPlatform
import com.copilotodriverpro.recognition.RideAcceptTracker
import com.copilotodriverpro.recognition.TripScreenMarkers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quando uma oferta vira corrida ACEITA (e vai para Histórico + Lançamentos). */
class RideAcceptTrackerTest {

    private var now = 1_000_000L
    private val accepted = mutableListOf<RideOffer>()
    private val tracker = RideAcceptTracker(clock = { now }) { o, _ -> accepted.add(o) }

    private fun offer(id: String = "o1") =
        RideOffer(id = id, platform = SupportedPlatform.UBER, fare = 18.5, tripDistanceKm = 7.0)

    @Test
    fun `toque em aceitar e tela de corrida em andamento confirma`() {
        val o = offer()
        tracker.onOfferShown(o)
        tracker.onAcceptClick(o, null)
        tracker.onOfferGone(o, null)
        now += 1_500
        tracker.onScreenText("Cheguei\nCancelar viagem")
        assertEquals(listOf(o), accepted)
    }

    @Test
    fun `toque em aceitar sem aviso de falha confirma depois de alguns segundos`() {
        val o = offer()
        tracker.onOfferShown(o)
        tracker.onAcceptClick(o, null)
        tracker.onOfferGone(o, null)
        now += 2_000; tracker.tick()
        assertTrue(accepted.isEmpty())
        now += 3_500; tracker.tick()
        assertEquals(1, accepted.size)
    }

    @Test
    fun `aviso de falha depois do toque cancela`() {
        val o = offer()
        tracker.onOfferShown(o)
        tracker.onAcceptClick(o, null)
        tracker.onOfferGone(o, null)
        now += 1_000
        tracker.onScreenText("Esta viagem não está mais disponível")
        now += 10_000; tracker.tick()
        assertTrue(accepted.isEmpty())
    }

    @Test
    fun `recusar nunca vira corrida`() {
        val o = offer()
        tracker.onOfferShown(o)
        tracker.onDeclineClick(o)
        tracker.onOfferGone(o, null)
        now += 2_000
        tracker.onScreenText("Cheguei")
        now += 10_000; tracker.tick()
        assertTrue(accepted.isEmpty())
    }

    @Test
    fun `sem toque, oferta some e corrida em andamento aparece - confirma (99 sobre outro app)`() {
        val o = offer()
        tracker.onOfferShown(o)
        tracker.onOfferGone(o, null)
        now += 3_000
        tracker.onScreenText("Ir buscar passageiro\nCheguei ao local")
        assertEquals(1, accepted.size)
    }

    @Test
    fun `oferta expirada durante corrida ja em andamento nao e aceite`() {
        tracker.onScreenText("Finalizar viagem")          // já está numa corrida
        now += 2_000
        val o = offer("o2")
        tracker.onOfferShown(o)
        tracker.onOfferGone(o, null)                       // expirou sem toque
        now += 2_000
        tracker.onScreenText("Finalizar viagem")
        assertTrue(accepted.isEmpty())
    }

    @Test
    fun `mesma corrida nao e registrada duas vezes`() {
        val o = offer()
        tracker.onOfferShown(o)
        tracker.onAcceptClick(o, null)
        tracker.onOfferGone(o, null)
        tracker.onScreenText("Cheguei")
        tracker.onAcceptClick(o, null)
        tracker.onOfferGone(o, null)
        now += 6_000; tracker.tick()
        tracker.onScreenText("Cheguei")
        assertEquals(1, accepted.size)
    }

    @Test
    fun `fora da janela nao confirma`() {
        val o = offer()
        tracker.onOfferShown(o)
        tracker.onOfferGone(o, null)
        now += 40_000
        tracker.onScreenText("Cheguei")
        assertTrue(accepted.isEmpty())
    }

    // ---------- aceite pelo ENDEREÇO (99 sem toque detectado e sem texto de botão conhecido) ----------

    private fun offer99(id: String = "n1") = RideOffer(
        id = id, platform = SupportedPlatform.NINE_NINE, fare = 11.6, tripDistanceKm = 5.4,
        pickupText = "Rua das Flores, 150", destinationText = "Avenida Brasil, 2000"
    )

    @Test
    fun `99 - oferta some e o endereco do embarque aparece na tela - confirma`() {
        val o = offer99()
        tracker.onOfferShown(o)
        tracker.onOfferGone(o, null)
        now += 2_000
        tracker.onScreenText("Maria\n4,9\nrua das flores, 150 - Centro\nNavegar")
        assertEquals(listOf(o), accepted)
    }

    @Test
    fun `endereco ignora acento, caixa e pontuacao`() {
        val o = offer99().copy(pickupText = "Avenida São João, 33")
        tracker.onOfferShown(o)
        tracker.onOfferGone(o, null)
        now += 2_000
        tracker.onScreenText("AVENIDA SAO JOAO 33 - Setor Sul")
        assertEquals(1, accepted.size)
    }

    @Test
    fun `endereco na tela logo apos sumir ainda nao confirma - espera assentar`() {
        val o = offer99()
        tracker.onOfferShown(o)
        tracker.onOfferGone(o, null)
        now += 400
        tracker.onScreenText("Rua das Flores, 150")
        assertTrue(accepted.isEmpty())
        now += 1_000
        tracker.onScreenText("Rua das Flores, 150")
        assertEquals(1, accepted.size)
    }

    @Test
    fun `endereco com o card da oferta ainda na tela nao confirma`() {
        val o = offer99()
        tracker.onOfferShown(o)
        tracker.onOfferGone(o, null)
        now += 2_000
        tracker.onScreenText("R\$11,60\nR\$1,82/km\n5 min (978 m)\nRua das Flores, 150\n10 min (5,4 km)\nAvenida Brasil, 2000\nAceitar")
        assertTrue(accepted.isEmpty())
    }

    @Test
    fun `recusada nunca vira corrida mesmo com o endereco na tela`() {
        val o = offer99()
        tracker.onOfferShown(o)
        tracker.onDeclineClick(o)
        tracker.onOfferGone(o, null)
        now += 2_000
        tracker.onScreenText("Rua das Flores, 150")
        assertTrue(accepted.isEmpty())
    }

    @Test
    fun `tela sem o endereco da oferta nao confirma`() {
        val o = offer99()
        tracker.onOfferShown(o)
        tracker.onOfferGone(o, null)
        now += 2_000
        tracker.onScreenText("Procurando corridas\nVocê está online")
        assertTrue(accepted.isEmpty())
    }

    @Test
    fun `endereco curto demais nao e usado para confirmar`() {
        val o = offer99().copy(pickupText = "Rua 7", destinationText = null)
        tracker.onOfferShown(o)
        tracker.onOfferGone(o, null)
        now += 2_000
        tracker.onScreenText("Rua 7")
        assertTrue(accepted.isEmpty())
    }

    @Test
    fun `endereco fora da janela de tempo nao confirma`() {
        val o = offer99()
        tracker.onOfferShown(o)
        tracker.onOfferGone(o, null)
        now += 40_000
        tracker.onScreenText("Rua das Flores, 150")
        assertTrue(accepted.isEmpty())
    }

    @Test
    fun `marcadores de tela`() {
        assertTrue(TripScreenMarkers.tripInProgress("Iniciar viagem"))
        assertFalse(TripScreenMarkers.tripInProgress("Procurando viagens\nR\$ 12,00"))
        assertTrue(TripScreenMarkers.failed("A oferta expirou"))
    }
}
