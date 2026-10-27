package com.copilotodriverpro

import com.copilotodriverpro.analyzer.ProfitabilityCalculator
import com.copilotodriverpro.analyzer.RideAnalyzer
import com.copilotodriverpro.models.RideOffer
import com.copilotodriverpro.models.RideSource
import com.copilotodriverpro.models.SupportedPlatform
import com.copilotodriverpro.recognition.OfferStabilizer
import com.copilotodriverpro.recognition.OfferStabilizer.Decision
import com.copilotodriverpro.recognition.OfferTextExtractor
import com.copilotodriverpro.recognition.RideTextParser
import com.copilotodriverpro.settings.DriverSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressão dos defeitos relatados: "na 99 lê e fica subindo vários valores diferentes" e
 * "km e ganho por hora errados" (Uber e 99).
 */
class LeituraEstavelTest {

    private val N = SupportedPlatform.NINE_NINE
    private val U = SupportedPlatform.UBER
    private val settings = DriverSettings(includePickupInKm = true)

    private val card99 = "Pgto. no app\nR\$11,60\nR\$1,82/km\nR\$2,18 por espera longa incl.\n5,00\n•\n132 corridas\n" +
        "Perfil Premium\n5 min (978 m)\nRua Belo Horizonte, 150\n10 min (5,4 km)\nRua MB-5, 493, Res. Morada do Bosque II"

    private fun parse(text: String, p: SupportedPlatform = N): RideOffer =
        RideTextParser.parse(text, RideSource.ACCESSIBILITY, p)!!

    // ------------------------------------------------------------ 1. leitura parcial na 99

    @Test
    fun `99 com so o embarque carregado nao vira R por km de 11 reais`() {
        // Antes: o trecho unico virava "viagem" -> R$ 11,60 / 0,978 km = R$ 11,86/km e R$ 139/h.
        val o = parse("R\$11,60\n5,00\n132 corridas\n5 min (978 m)\nRua Belo Horizonte, 150")
        assertNull("trecho unico nao e adivinhado como viagem", o.tripDistanceKm)
        assertTrue(o.needsValidation)
        assertFalse(o.isCompleteRead)
        assertNull(RideAnalyzer.evaluateOffer(o, settings).perKm.value)
        assertNull(RideAnalyzer.evaluateOffer(o, settings).perHour.value)
    }

    @Test
    fun `99 completa continua correta`() {
        val o = parse(card99)
        assertTrue(o.isCompleteRead)
        assertFalse(o.needsValidation)
        val m = ProfitabilityCalculator.calculate(o, settings)
        assertEquals(1.82, m.grossPerKm!!, 0.005)          // igual ao "R$1,82/km" do card
        assertEquals(11.60 / (15.0 / 60.0), m.grossPerHour!!, 0.01)
    }

    @Test
    fun `uber com um trecho so continua ambiguo`() {
        val o = parse("UberX\nR\$ 33,04\n6 min (3.0 km)\nAv. Sete\nAceitar", U)
        assertNull(o.tripDistanceKm)
        assertTrue(o.needsValidation)
    }

    // ------------------------------------------------------------ 2. o app lendo o proprio painel

    @Test
    fun `painel do proprio app em 3 colunas nao vira valor da corrida`() {
        // OCR do print: rótulos em sequência e depois os 3 valores (R$/km, R$/hora, R$/min).
        val withOwnPanel = "R\$ 11,60\nREPROVADA\n\$ R\$/km\n\$ R\$/hora\n\$ R\$/min\nR\$ 1,82\nR\$ 46,40\nR\$ 0,77\n" +
            "Viagem: 5,4 km\nTotal: 15 min\n$card99"
        val cleaned = OfferTextExtractor.cleanScreenText(OfferTextExtractor.normalize(withOwnPanel))
        assertFalse("R$ 46,40 nao pode sobrar", cleaned.contains("46,40"))
        assertFalse(cleaned.contains("0,77"))
        assertFalse(cleaned.contains("REPROVADA"))
        val o = parse(cleaned)
        assertEquals(11.60, o.fare!!, 0.001)
        assertEquals(5.4, o.tripDistanceKm!!, 0.001)
        assertEquals(0.978, o.distanceToPickupKm!!, 0.001)
        assertFalse(o.needsValidation)
    }

    @Test
    fun `valor logo apos um unico rotulo continua sendo descartado e o resto fica`() {
        val cleaned = OfferTextExtractor.cleanScreenText("\$ R\$/km\nR\$ 1,82\nR\$11,60\nR\$1,82/km")
        assertEquals("R\$11,60\nR\$1,82/km", cleaned)
    }

    // ------------------------------------------------------------ 3. estabilizador

    private class Clock(var t: Long = 1_000L) : () -> Long { override fun invoke() = t }

    @Test
    fun `leitura limpa sem card na tela aparece na hora`() {
        val st = OfferStabilizer()
        assertEquals(Decision.PUBLISH, st.decide(parse(card99), shown = null))
    }

    @Test
    fun `leitura parcial nao aparece e a completa depois aparece`() {
        val clock = Clock()
        val st = OfferStabilizer(clock)
        val partial = parse("R\$11,60\nR\$1,82/km\n132 corridas\n5 min (978 m)\nRua A")
        assertEquals(Decision.HOLD, st.decide(partial, null))
        clock.t += 150
        assertEquals(Decision.HOLD, st.decide(partial, null))      // ainda dentro dos 900 ms
        clock.t += 150
        assertEquals(Decision.PUBLISH, st.decide(parse(card99), null)) // card completou: entra na hora
    }

    @Test
    fun `leitura ruim que persiste aparece como pendente depois da espera`() {
        val clock = Clock()
        val st = OfferStabilizer(clock)
        val partial = parse("R\$11,60\n132 corridas\n5 min (978 m)\nRua A")
        assertEquals(Decision.HOLD, st.decide(partial, null))
        clock.t += OfferStabilizer.PENDING_HOLD_MS + 50
        assertEquals(Decision.PUBLISH, st.decide(partial, null))
    }

    @Test
    fun `leitura duvidosa nunca derruba um card limpo`() {
        val st = OfferStabilizer()
        val good = parse(card99)
        val noisy = parse(card99.replace("5,4 km", "54 km"))        // OCR leu 54 km
        assertTrue(noisy.needsValidation)
        repeat(5) { assertEquals(Decision.HOLD, st.decide(noisy, shown = good)) }
    }

    @Test
    fun `oferta diferente so troca o card depois de 2 leituras iguais`() {
        val st = OfferStabilizer()
        val a = parse(card99)
        val b = parse(card99.replace("R\$11,60", "R\$14,20").replace("R\$1,82/km", "R\$2,23/km"))
        assertFalse(b.needsValidation)
        assertEquals(Decision.SAME, st.decide(a, shown = a))
        assertEquals(Decision.HOLD, st.decide(b, shown = a))
        assertEquals(Decision.PUBLISH, st.decide(b, shown = a))
    }

    @Test
    fun `oscilacao entre duas leituras nunca troca o card`() {
        val st = OfferStabilizer()
        val a = parse(card99)
        val b = parse(card99.replace("R\$11,60", "R\$14,20").replace("R\$1,82/km", "R\$2,23/km"))
        val c = parse(card99.replace("R\$11,60", "R\$12,10").replace("R\$1,82/km", "R\$1,90/km"))
        // a está no card; as leituras alternam b, c, b, c... (OCR instável): nenhuma se confirma.
        listOf(b, c, b, c, b, c).forEach { assertEquals(Decision.HOLD, st.decide(it, shown = a)) }
    }

    @Test
    fun `OCR sem o R por km do card precisa de 2 leituras iguais`() {
        val st = OfferStabilizer()
        val noHint = parse(card99.replace("R\$1,82/km\n", ""))
        assertNull(noHint.platformPerKmHint)
        assertFalse(noHint.needsValidation)
        assertEquals(Decision.HOLD, st.decide(noHint, null, fromOcr = true))
        assertEquals(Decision.PUBLISH, st.decide(noHint, null, fromOcr = true))
        // Pela arvore de acessibilidade (texto exato) nao espera.
        assertEquals(Decision.PUBLISH, OfferStabilizer().decide(noHint, null, fromOcr = false))
    }

    @Test
    fun `OCR com o R por km conferindo aparece na hora`() {
        assertEquals(Decision.PUBLISH, OfferStabilizer().decide(parse(card99), null, fromOcr = true))
    }

    @Test
    fun `sequencia real - carga parcial, completa e cronometro mudando - mostra so os valores certos`() {
        val clock = Clock()
        val st = OfferStabilizer(clock)
        val shownValues = ArrayList<Pair<Double?, Double?>>()
        var shown: RideOffer? = null
        fun feed(text: String) {
            val o = parse(text)
            clock.t += 120
            when (st.decide(o, shown)) {
                Decision.PUBLISH -> {
                    shown = o
                    val m = ProfitabilityCalculator.calculate(o, settings)
                    shownValues.add(m.grossPerKm to m.grossPerHour)
                }
                else -> Unit
            }
        }
        feed("R\$11,60\nR\$1,82/km\n132 corridas\n5 min (978 m)\nRua A")        // parcial
        feed("R\$11,60\n132 corridas\n5 min (978 m)\nRua A")                      // parcial sem R$/km
        feed(card99)                                                               // completa
        feed(card99 + "\n12 s")                                                    // cronômetro
        feed(card99 + "\n11 s")
        assertEquals("só uma publicação, a correta", 1, shownValues.size)
        assertEquals(1.82, shownValues[0].first!!, 0.005)
    }

    // ------------------------------------------------------------ 4. rotulos de embarque/viagem na Uber

    @Test
    fun `uber com rotulos de distancia e viagem separa embarque e viagem`() {
        val o = parse(
            "UberX\nR\$ 12,50\n4,96 (326)\n6 minutos (2.3 km) de distância\nRua A\n12 minutos (5.1 km) de viagem\nRua B\nAceitar",
            U
        )
        assertEquals(2.3, o.distanceToPickupKm!!, 0.001)
        assertEquals(5.1, o.tripDistanceKm!!, 0.001)
        assertTrue(o.isCompleteRead)
        assertNotNull(o.platformPerKmHint ?: 0.0)
    }
}
