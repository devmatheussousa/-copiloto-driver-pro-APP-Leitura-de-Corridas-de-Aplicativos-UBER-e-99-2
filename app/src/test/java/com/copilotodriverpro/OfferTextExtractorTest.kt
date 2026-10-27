package com.copilotodriverpro

import com.copilotodriverpro.models.RideOffer
import com.copilotodriverpro.models.RideSource
import com.copilotodriverpro.models.SupportedPlatform
import com.copilotodriverpro.recognition.RideOfferValidator
import com.copilotodriverpro.recognition.RideScreenClassifier
import com.copilotodriverpro.recognition.RideTextParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Testes da leitura de ofertas Uber/99 (embarque x viagem, tempo total, formatos). */
class OfferTextExtractorTest {

    @Before
    fun setup() {
        RideOfferValidator.reset()
    }

    @Test
    fun `uber separa embarque e viagem e soma o tempo`() {
        val text = "UberX\nR$ 14,52\n★ 4,92\n6 minutos (2,3 km) de distância\nRua A, 100\nViagem de 14 minutos (6,1 km)\nRua B, 200\nSelecionar"
        val offer = RideTextParser.parse(text, RideSource.ACCESSIBILITY, SupportedPlatform.UBER)!!
        assertEquals(14.52, offer.fare!!, 0.001)
        assertEquals(2.3, offer.distanceToPickupKm!!, 0.001)
        assertEquals(6.0, offer.timeToPickupMin!!, 0.001)
        assertEquals(6.1, offer.tripDistanceKm!!, 0.001)
        assertEquals(14.0, offer.durationMin!!, 0.001)
        assertEquals(20.0, offer.totalDurationMin!!, 0.001)
        assertEquals(4.92, offer.passengerRating!!, 0.001)
    }

    @Test
    fun `99 com km e minutos na mesma linha`() {
        val text = "R$ 11,10\nPassageiro 4,95\n1,8 km • 6 min\n4,2 km • 12 min\nAceitar"
        val offer = RideTextParser.parse(text, RideSource.ACCESSIBILITY, SupportedPlatform.NINE_NINE)!!
        assertEquals(SupportedPlatform.NINE_NINE, offer.platform)
        assertEquals(11.10, offer.fare!!, 0.001)
        assertEquals(1.8, offer.distanceToPickupKm!!, 0.001)
        assertEquals(6.0, offer.timeToPickupMin!!, 0.001)
        assertEquals(4.2, offer.tripDistanceKm!!, 0.001)
        assertEquals(12.0, offer.durationMin!!, 0.001)
        assertEquals(4.95, offer.passengerRating!!, 0.001)
    }

    @Test
    fun `decimal com ponto nao vira milhar`() {
        val offer = RideTextParser.parse("R$ 9.50\n4 min (1.9 km) de distância\nViagem de 11 min (3.2 km)", RideSource.MANUAL, SupportedPlatform.UBER)!!
        assertEquals(9.5, offer.fare!!, 0.001)
        assertEquals(1.9, offer.distanceToPickupKm!!, 0.001)
        assertEquals(3.2, offer.tripDistanceKm!!, 0.001)
    }

    @Test
    fun `ignora valor por km e bonus ao escolher o valor da corrida`() {
        val text = "+R$ 3,00\nR$ 2,38 por km\nR$ 14,52\n6 min (2,3 km)\nViagem de 14 min (6,1 km)"
        val offer = RideTextParser.parse(text, RideSource.MANUAL, SupportedPlatform.UBER)!!
        assertEquals(14.52, offer.fare!!, 0.001)
        assertEquals(3.0, offer.bonusConfirmed!!, 0.001)
    }

    @Test
    fun `nota nao e confundida com distancia nem com preco`() {
        val a = RideTextParser.parse("R$ 20,00\nAté o passageiro\n4,5 km\n8,1 km", RideSource.MANUAL)!!
        assertNull(a.passengerRating)
        val b = RideTextParser.parse("R$ 14,52\n★\n4,92\n3 min (1 km)\n10 min (4 km)", RideSource.MANUAL)!!
        assertEquals(4.92, b.passengerRating!!, 0.001)
    }

    @Test
    fun `endereco com h nao vira duracao`() {
        val offer = RideTextParser.parse("R$ 20,00\nAv 12 Hospital\n3 min (1 km)\n10 min (4 km)", RideSource.MANUAL)!!
        assertEquals(3.0, offer.timeToPickupMin!!, 0.001)
        assertEquals(10.0, offer.durationMin!!, 0.001)
    }

    @Test
    fun `classificador reconhece pacote do 99 motorista`() {
        assertEquals(SupportedPlatform.NINE_NINE, RideScreenClassifier.identifyPlatform("com.app99.driver"))
        assertEquals(SupportedPlatform.UBER, RideScreenClassifier.identifyPlatform("com.ubercab.driver"))
        assertTrue(RideScreenClassifier.isTransportPackage("com.app99.driver"))
        assertFalse(RideScreenClassifier.isTransportPackage("com.waze"))
    }

    @Test
    fun `99 com pacote novo e botao com texto extra continua sendo lido`() {
        assertEquals(SupportedPlatform.NINE_NINE, RideScreenClassifier.identifyPlatform("com.app99.driver.v2"))
        assertEquals(SupportedPlatform.UBER, RideScreenClassifier.identifyPlatform("com.ubercab.driver"))
        assertTrue(RideScreenClassifier.isAcceptText("Aceitar corrida", SupportedPlatform.NINE_NINE))
        assertTrue(RideScreenClassifier.isAcceptText("Aceitar por R$ 12,40", SupportedPlatform.NINE_NINE))
        assertFalse(RideScreenClassifier.isAcceptText("Aceitar por R$ 12,40", SupportedPlatform.UBER))
        assertFalse(RideScreenClassifier.isAcceptText("Você está online", SupportedPlatform.NINE_NINE))
    }

    @Test
    fun `99 sem botao em texto e reconhecida pelo formato do card`() {
        val card = "R$ 12,40\n3 min (1,2 km)\nRua A, 10\n14 min (6,8 km)\nRua B, 20"
        assertTrue(RideScreenClassifier.isOfferScreen(card, SupportedPlatform.NINE_NINE))
        assertFalse(RideScreenClassifier.isOfferScreen(card, SupportedPlatform.UBER))
        assertFalse(
            RideScreenClassifier.isOfferScreen(
                "Ganhos de hoje R$ 120,00\n3 min (1,2 km)\n14 min (6,8 km)", SupportedPlatform.NINE_NINE
            )
        )
    }

    @Test
    fun `99 com um unico trecho e sem botao tambem e oferta`() {
        val card = "R$ 9,50\n12 min (5,1 km)\nRua B, 20"
        assertTrue(RideScreenClassifier.isOfferScreen(card, SupportedPlatform.NINE_NINE))
        assertFalse(RideScreenClassifier.isOfferScreen("Solicitações de corrida restritas\n15/10/2026 05:54:57", SupportedPlatform.NINE_NINE))
    }

    @Test
    fun `99 com embarque em metros e nota com corridas`() {
        val text = "Pgto. no app\nR$6,50\nR$2,15/km\nR$1,25 por deslocamento incl.\n" +
            "4,83 • 160 corridas •\nCPF e Cartão verif.\n" +
            "5 min (948 m)\nAvenida Dom Prudêncio, Sen. Canedo\n" +
            "4 min (2,1 km)\nResidencial Maysa 3, Avenida dos Eucaliptos, 1051 - Sítios Vale das Brisas"
        assertTrue(RideScreenClassifier.isOfferScreen(text, SupportedPlatform.NINE_NINE))
        val offer = RideTextParser.parse(text, RideSource.ACCESSIBILITY, SupportedPlatform.NINE_NINE)!!
        assertEquals(6.5, offer.fare!!, 0.001)
        assertEquals(0.948, offer.distanceToPickupKm!!, 0.001)
        assertEquals(5.0, offer.timeToPickupMin!!, 0.001)
        assertEquals(2.1, offer.tripDistanceKm!!, 0.001)
        assertEquals(4.0, offer.durationMin!!, 0.001)
        assertEquals(4.83, offer.passengerRating!!, 0.001)
        assertEquals("Avenida Dom Prudêncio, Sen. Canedo", offer.pickupText)
        assertTrue(offer.issues.none { it.blocking })
    }

    @Test
    fun `99 lista de solicitacoes com botao Escolher e dois cards`() {
        val text = "Solicitações\nCorridas\nEntregas\nEscolha uma\nKm até o embarque\nPop Nova\nR$13,90\n" +
            "4,61\n• 33 corridas •\nCartão verif.\n" +
            "(9 min 2,9 km) Supermercado Lar Feliz, Avenida Progresso, Quadra 05\n" +
            "(16 min 9,8 km) Rua Goiás, Estrela do Sul\nEscolher\n" +
            "Pop Nova\nR$7,20\n4,90\n• 10 corridas •\n(3 min 1,1 km) Rua A\n(6 min 2,0 km) Rua B\nEscolher"
        assertTrue(RideScreenClassifier.isOfferScreen(text, SupportedPlatform.NINE_NINE))
        assertTrue(RideScreenClassifier.isAcceptText("Escolher", SupportedPlatform.NINE_NINE))
        val offer = RideTextParser.parse(text, RideSource.ACCESSIBILITY, SupportedPlatform.NINE_NINE)!!
        assertEquals(13.9, offer.fare!!, 0.001)
        assertEquals(2.9, offer.distanceToPickupKm!!, 0.001)
        assertEquals(9.0, offer.timeToPickupMin!!, 0.001)
        assertEquals(9.8, offer.tripDistanceKm!!, 0.001)
        assertEquals(16.0, offer.durationMin!!, 0.001)
        assertEquals(4.61, offer.passengerRating!!, 0.001)
        assertEquals("Supermercado Lar Feliz, Avenida Progresso, Quadra 05", offer.pickupText)
        assertEquals("Rua Goiás, Estrela do Sul", offer.destinationText)
    }

    @Test
    fun `tela de ganhos nao e oferta`() {
        assertFalse(RideScreenClassifier.isOfferScreen("Ganhos de hoje R$ 120,00\n5 h 20 min", SupportedPlatform.UBER))
        assertTrue(RideScreenClassifier.isOfferScreen("R$ 14,52\n6 min (2,3 km)\nSelecionar", SupportedPlatform.UBER))
    }

    @Test
    fun `oferta identica depois da janela de duplicidade e aceita`() {
        val now = System.currentTimeMillis()
        val first = RideOffer(platform = SupportedPlatform.UBER, fare = 12.0, tripDistanceKm = 4.0, durationMin = 9.0, timestamp = now - 30_000L)
        val second = RideOffer(platform = SupportedPlatform.UBER, fare = 12.0, tripDistanceKm = 4.0, durationMin = 9.0, timestamp = now)
        assertTrue(RideOfferValidator.isValidAndNew(first))
        assertTrue(RideOfferValidator.isValidAndNew(second))
        // Colada na anterior, continua sendo repetição do mesmo evento.
        val third = second.copy(id = "outro", timestamp = now + 1_000L)
        assertFalse(RideOfferValidator.isValidAndNew(third))
        assertNotNull(second)
    }

    @Test
    fun `oferta real do uber com espaco inquebravel e sem rotulos`() {
        val nb = "\u00A0"
        val text = "UberX\nR$${nb}6,07\nR$1,56/km aprox.\n4,97 (530)\nVerificado\n3 min (1.1${nb}km)\nAv. Campina Verde, Goiânia\n6 minutos (2.8${nb}km)\nR. Sen. Osires Teixeira, Senador Canedo\nSelecionar"
        assertTrue(RideScreenClassifier.isOfferScreen(text, SupportedPlatform.UBER))
        val offer = RideTextParser.parse(text, RideSource.ACCESSIBILITY, SupportedPlatform.UBER)!!
        assertEquals(6.07, offer.fare!!, 0.001)
        assertEquals(1.1, offer.distanceToPickupKm!!, 0.001)
        assertEquals(3.0, offer.timeToPickupMin!!, 0.001)
        assertEquals(2.8, offer.tripDistanceKm!!, 0.001)
        assertEquals(6.0, offer.durationMin!!, 0.001)
        assertEquals(4.97, offer.passengerRating!!, 0.001)
    }
}
