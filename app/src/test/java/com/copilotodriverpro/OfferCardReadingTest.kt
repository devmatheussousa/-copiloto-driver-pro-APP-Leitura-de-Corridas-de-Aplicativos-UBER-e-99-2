package com.copilotodriverpro

import com.copilotodriverpro.analyzer.ProfitabilityCalculator
import com.copilotodriverpro.analyzer.RecommendationState
import com.copilotodriverpro.analyzer.RideAnalyzer
import com.copilotodriverpro.models.RideOffer
import com.copilotodriverpro.models.RideSource
import com.copilotodriverpro.models.SupportedPlatform
import com.copilotodriverpro.recognition.OfferConsistency
import com.copilotodriverpro.recognition.RideTextParser
import com.copilotodriverpro.settings.DriverSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressão da leitura do card da Uber e do cálculo de rentabilidade (oferta das duas imagens de
 * referência: R$ 33,04 · 6 min (3.0 km) até o passageiro · 37 minutos (25.4 km) de viagem).
 *
 * O erro original: o selo "Viagem longa (mais de 30 min)" era lido como um trecho da corrida e,
 * conforme a ordem dos nós da tela, trocava embarque e viagem. Resultado: "Viagem: 3,0 km",
 * R$ 11,01/km (33,04 / 3,0) e "Total: 36 min" (6 + 30), em vez de 28,4 km e 43 min.
 */
class OfferCardReadingTest {

    private val settings = DriverSettings(includePickupInKm = true)

    /** Textos do card, na ordem visual. */
    private val card = listOf(
        "UberX",
        "Exclusivo",
        "R$ 33,04",
        "R$1,16/km aprox.",
        "4,96 (326)",
        "+R$ 1,00 incluído",
        "6 min (3.0 km)",
        "Av. Sete de Setembro, Senador Canedo",
        "37 minutos (25.4 km)",
        "Rodoviária de Goiânia, Rodoviária de Goiânia, Setor Central",
        "Viagem longa (mais de 30 min)",
        "Aceitar"
    )

    /** Painel do PRÓPRIO app, com os valores errados da imagem 1, como o OCR da tela o enxerga. */
    private val ownPanel = listOf(
        "R$ 33,04 REPROVADA",
        "$ R$/km",
        "R$ 11,01",
        "$ R$/hora",
        "R$ 55,07",
        "$ R$/min",
        "R$ 0,92",
        "Viagem: 3,0 km",
        "Total: 36 min",
        "5,0 ★"
    )

    private fun parse(
        lines: List<String>,
        source: RideSource = RideSource.ACCESSIBILITY,
        platform: SupportedPlatform = SupportedPlatform.UBER
    ): RideOffer = RideTextParser.parse(lines.joinToString("\n"), source, platform)!!

    private fun assertReferenceCard(o: RideOffer, label: String) {
        assertEquals("$label fare", 33.04, o.fare!!, 0.001)
        assertEquals("$label embarque km", 3.0, o.distanceToPickupKm!!, 0.001)
        assertEquals("$label embarque min", 6.0, o.timeToPickupMin!!, 0.001)
        assertEquals("$label viagem km", 25.4, o.tripDistanceKm!!, 0.001)
        assertEquals("$label viagem min", 37.0, o.durationMin!!, 0.001)
        assertEquals("$label total km", 28.4, o.totalDistanceKm!!, 0.001)
        assertEquals("$label total min", 43.0, o.totalDurationMin!!, 0.001)
        assertFalse("$label nao deve ficar pendente: ${o.issues}", o.needsValidation)
    }

    // ---------------------------------------------------------------- 1. oferta completa

    @Test
    fun `cartao de referencia - todos os campos separados e totais corretos`() {
        val o = parse(card)
        assertReferenceCard(o, "referencia")
        assertEquals(1.0, o.bonusConfirmed!!, 0.001)
        assertTrue("bonus incluido no valor", o.bonusIncludedInFare)
        assertEquals("UberX", o.rideType)
        assertEquals(4.96, o.passengerRating!!, 0.001)
        assertEquals(326, o.ratingCount)
        assertEquals(1.16, o.platformPerKmHint!!, 0.001)
        assertTrue("confianca alta quando nada conflita", o.confidenceScore >= 0.9)
        assertTrue(o.issues.isEmpty())
    }

    @Test
    fun `cartao de referencia - R por km e R por hora corretos`() {
        val m = ProfitabilityCalculator.calculate(parse(card), settings)
        assertEquals(28.4, m.totalDistanceKm!!, 0.001)
        assertEquals(33.04 / 28.4, m.grossPerKm!!, 0.0001)
        assertEquals(1.16, m.grossPerKm!!, 0.005)               // igual ao "R$1,16/km aprox." do card
        assertEquals(43.0, m.totalDurationMin!!, 0.001)
        assertEquals(46.10, m.grossPerHour!!, 0.01)             // 33,04 / 43 min
    }

    // ---------------------------------------------------------------- causa raiz: ordem dos nos

    @Test
    fun `selo de viagem longa antes dos trechos nao troca embarque e viagem`() {
        val badgeFirst = card.filterNot { it.startsWith("Viagem longa") }
            .toMutableList().also { it.add(6, "Viagem longa (mais de 30 min)") }
        val o = parse(badgeFirst)
        assertReferenceCard(o, "selo antes")
        // Os sintomas exatos da imagem 1 NAO podem voltar:
        assertTrue("nao pode virar 3,0 km", o.totalDistanceKm!! > 28.0)
        assertTrue("nao pode virar 36 min (6 + 30 do selo)", o.totalDurationMin!! > 40.0)
    }

    @Test
    fun `selo de viagem longa no meio ou no fim da lista da o mesmo resultado`() {
        val noMeio = card.filterNot { it.startsWith("Viagem longa") }
            .toMutableList().also { it.add(8, "Viagem longa (mais de 30 min)") }
        assertReferenceCard(parse(noMeio), "selo no meio")
        assertReferenceCard(parse(card), "selo no fim")
    }

    // ---------------------------------------------------------------- 2. distancia da viagem ausente

    @Test
    fun `so o trecho de embarque lido - nao usa o embarque como total`() {
        val o = parse(listOf("UberX", "R$ 33,04", "R$1,16/km aprox.", "4,96 (326)", "6 min (3.0 km)", "Av. Sete", "Aceitar"))
        assertNull("sem rotulo nao se adivinha viagem", o.tripDistanceKm)
        assertTrue(o.needsValidation)
        assertTrue(o.issues.any { it.code == "READ_AMBIGUOUS_LEG" })

        val ev = RideAnalyzer.evaluateOffer(o, settings)
        assertTrue(ev.pending)
        assertNull("R$/km nao pode sair de 33,04 / 3,0", ev.perKm.value)
        assertNull(ev.perHour.value)
        assertEquals(RecommendationState.DADOS_INSUFFICIENTES, ev.recommendationResult!!.state)
        assertTrue(ev.recommendationResult!!.justification.contains("PENDENTE"))
    }

    // ---------------------------------------------------------------- 3. tempo total ausente

    @Test
    fun `tempo total ausente - R por hora indisponivel e nao inventado`() {
        val o = parse(listOf("UberX", "R$ 33,04", "3.0 km", "25.4 km", "Aceitar"))
        assertNull(o.totalDurationMin)
        assertNotNull(o.totalDistanceKm)
        val ev = RideAnalyzer.evaluateOffer(o, settings)
        assertNull("sem tempo nao ha R$/hora", ev.perHour.value)
        assertEquals(33.04 / 28.4, ev.perKm.value!!, 0.0001)
        assertTrue("avisa o dado ausente", ev.issueMessages.isNotEmpty())
        assertFalse("falta de tempo nao bloqueia o R$/km", ev.pending)
    }

    // ---------------------------------------------------------------- 4. OCR invalido

    @Test
    fun `OCR com distancia absurda - descarta e marca pendente`() {
        val o = parse(
            listOf("UberX", "R$ 33,04", "6 min (3.0 km)", "37 minutos (2540 km)", "Aceitar"),
            source = RideSource.OCR_IMAGE
        )
        assertTrue(o.needsValidation)
        assertTrue(o.issues.any { it.code == "READ_INVALID_KM" })
        assertNull("2540 km nao entra em nenhum calculo", o.tripDistanceKm)
        assertNull(RideAnalyzer.evaluateOffer(o, settings).perKm.value)
    }

    @Test
    fun `OCR com valor zero ou absurdo - sem valor da corrida`() {
        val zero = parse(listOf("UberX", "R$ 0,00", "6 min (3.0 km)", "37 minutos (25.4 km)"), RideSource.OCR_IMAGE)
        assertNull(zero.fare)
        assertTrue(zero.needsValidation)
        assertNull(RideAnalyzer.evaluateOffer(zero, settings).perKm.value)

        val huge = parse(listOf("UberX", "R$ 933.040,00", "6 min (3.0 km)", "37 minutos (25.4 km)"), RideSource.OCR_IMAGE)
        assertNull(huge.fare)
        assertTrue(huge.issues.any { it.code == "READ_INVALID_FARE" })
        assertTrue(huge.needsValidation)
    }

    // ---------------------------------------------------------------- 5. bonus incluido

    @Test
    fun `bonus incluido no card nao e somado duas vezes`() {
        val included = parse(card)
        assertEquals("o valor da corrida continua 33,04", 33.04, included.fare!!, 0.001)
        assertTrue(included.bonusIncludedInFare)

        val netIncluded = ProfitabilityCalculator.calculate(included, settings).netEarnings!!
        val netIfNotIncluded = ProfitabilityCalculator.calculate(
            included.copy(bonusIncludedInFare = false), settings
        ).netEarnings!!
        // Somente quando o bonus NAO esta incluido ele entra por fora (+1,00).
        assertEquals(1.0, netIfNotIncluded - netIncluded, 1e-9)
    }

    // ---------------------------------------------------------------- 6. painel x card divergentes

    @Test
    fun `leitura que gera 11,01 por km contra 1,16 do card fica pendente em vez de reprovada`() {
        // Estado exato da imagem 1: viagem = 3,0 km / 6 min e "embarque" = só os 30 min do selo.
        val bug = OfferConsistency.validate(
            RideOffer(
                platform = SupportedPlatform.UBER,
                fare = 33.04,
                tripDistanceKm = 3.0, durationMin = 6.0,
                timeToPickupMin = 30.0,
                platformPerKmHint = 1.16
            )
        )
        assertTrue(bug.needsValidation)
        assertTrue(bug.issues.any { it.code == "PER_KM_MISMATCH" })

        val ev = RideAnalyzer.evaluateOffer(bug, settings)
        assertTrue(ev.pending)
        assertNull(ev.perKm.value)
        assertNull(ev.perHour.value)
        assertNull(ev.financialMetrics!!.grossPerMinute)
        assertEquals(RecommendationState.DADOS_INSUFFICIENTES, ev.recommendationResult!!.state)
    }

    @Test
    fun `painel e card leem o mesmo resultado - fonte unica de calculo`() {
        val o = parse(card)
        val ev = RideAnalyzer.evaluateOffer(o, settings)
        val m = ProfitabilityCalculator.calculate(o, settings)
        // O que o painel e o card exibem (OfferEvaluation) é exatamente o cálculo único.
        assertEquals(m.grossPerKm!!, ev.perKm.value!!, 1e-12)
        assertEquals(m.grossPerHour!!, ev.perHour.value!!, 1e-12)
        assertEquals(m.grossPerMinute!!, ev.financialMetrics!!.grossPerMinute!!, 1e-12)
        assertEquals(28.4, ev.totalDistanceKm!!, 0.001)
        assertEquals(43.0, ev.totalDurationMin!!, 0.001)
        assertFalse(ev.pending)
        // R$/min e R$/hora saem da MESMA base de tempo.
        assertEquals(ev.perHour.value!!, ev.financialMetrics!!.grossPerMinute!! * 60.0, 1e-9)
    }

    @Test
    fun `embarque e viagem identicos indicam trecho lido duas vezes`() {
        val o = OfferConsistency.validate(
            RideOffer(
                platform = SupportedPlatform.UBER, fare = 33.04,
                distanceToPickupKm = 3.0, timeToPickupMin = 6.0,
                tripDistanceKm = 3.0, durationMin = 6.0
            )
        )
        assertTrue(o.issues.any { it.code == "DUPLICATE_LEG" })
        assertTrue(o.needsValidation)
    }

    // ---------------------------------------------------------------- 7. variacoes de layout e resolucao

    @Test
    fun `variacoes de layout e de captura mantem a mesma leitura`() {
        val nb = "\u00A0"
        val variants = mapOf(
            "espaco inquebravel" to card.map { it.replace("R$ 33,04", "R$${nb}33,04").replace("3.0 km", "3.0${nb}km") },
            "virgula decimal" to card.map { it.replace("3.0 km", "3,0 km").replace("25.4 km", "25,4 km") },
            "botao selecionar" to card.map { it.replace("Aceitar", "Selecionar") },
            "sem etiqueta exclusivo" to card.filterNot { it == "Exclusivo" },
            "ocr com espacos extras" to card.map {
                it.replace("(3.0 km)", "( 3.0 km )").replace("(25.4 km)", "( 25.4  km )").replace("37 minutos", "37  minutos")
            },
            "texto em blocos unicos" to listOf(
                "UberX R$ 33,04 R$1,16/km aprox.",
                "6 min (3.0 km) Av. Sete de Setembro",
                "37 minutos (25.4 km) Rodoviária de Goiânia",
                "Viagem longa (mais de 30 min)",
                "Aceitar"
            )
        )
        variants.forEach { (name, lines) -> assertReferenceCard(parse(lines), name) }
    }

    @Test
    fun `OCR da tela inteira ignora o painel do proprio app sobre o card`() {
        val painelAntes = parse(ownPanel + card, RideSource.OCR_IMAGE)
        assertReferenceCard(painelAntes, "painel antes")
        assertEquals("nota vem do card, nao do painel (5,0)", 4.96, painelAntes.passengerRating!!, 0.001)

        val painelDepois = parse(card + ownPanel, RideSource.OCR_IMAGE)
        assertReferenceCard(painelDepois, "painel depois")
        assertEquals(4.96, painelDepois.passengerRating!!, 0.001)
    }

    // ---------------------------------------------------------------- formula (valores de referencia)

    @Test
    fun `R por hora - 35, 36 e 43 minutos`() {
        fun perHour(totalMin: Double): Double {
            val o = RideOffer(
                fare = 33.04, distanceToPickupKm = 3.0, timeToPickupMin = 6.0,
                tripDistanceKm = 25.4, durationMin = totalMin - 6.0
            )
            return ProfitabilityCalculator.calculate(o, settings).grossPerHour!!
        }
        assertEquals(56.64, perHour(35.0), 0.01)
        assertEquals(55.07, perHour(36.0), 0.01)
        assertEquals(46.10, perHour(43.0), 0.01)
    }

    @Test
    fun `leitura de ofertas antigas continua igual`() {
        val o = parse(
            listOf(
                "UberX", "R$ 6,07", "R$1,56/km aprox.", "4,97 (530)", "Verificado",
                "3 min (1.1 km)", "Av. Campina Verde, Goiânia", "6 minutos (2.8 km)", "R. Sen. Osires", "Selecionar"
            )
        )
        assertEquals(3.9, o.totalDistanceKm!!, 0.001)
        assertEquals(9.0, o.totalDurationMin!!, 0.001)
        assertFalse(o.needsValidation)
    }
}
