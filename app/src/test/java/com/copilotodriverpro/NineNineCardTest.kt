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
 * Regressão: card da 99 (print real) com mapa/ganhos ao fundo. O card é achado pela estrutura
 * (valor + R$/km + dois trechos), não pela tela inteira.
 */
class NineNineCardTest {

    private val p = SupportedPlatform.NINE_NINE

    private val card = "Pgto. no app\nR${'$'}11,60\nR${'$'}1,82/km\nR${'$'}2,18 por espera longa incl.\n" +
        "Taxa de parada de R${'$'}0,80 inclusa\n5,00\n•\n132 corridas\nPerfil Premium\n" +
        "5 min (978 m)\nRua Belo Horizonte, 150 Rua Belo Horizonte, 150\n1 parada(s)\n" +
        "10 min (5,4 km)\nRua MB-5, 493, Res. Morada do Bosque II"

    private fun assertCard(text: String) {
        assertTrue(RideScreenClassifier.isOfferScreen(text, p))
        val o = RideTextParser.parse(text, RideSource.ACCESSIBILITY, p)
        assertNotNull(o)
        assertEquals(11.60, o!!.fare!!, 0.001)
        assertEquals(0.978, o.distanceToPickupKm!!, 0.001)
        assertEquals(5.0, o.timeToPickupMin!!, 0.001)
        assertEquals(5.4, o.tripDistanceKm!!, 0.001)
        assertEquals(10.0, o.durationMin!!, 0.001)
        assertTrue(o.issues.none { it.blocking })
    }

    @Test
    fun `card da 99 limpo`() = assertCard(card)

    @Test
    fun `card da 99 com ganhos saldo e procurando ao fundo`() =
        assertCard("R${'$'} 85,40\nGanhos de hoje\nProcurando passageiros\nSaldo\n$card\nGanhos\nCarteira")

    @Test
    fun `card da 99 com nos de texto quebrados`() =
        assertCard(
            card.replace("R${'$'}11,60", "R${'$'}\n11,60")
                .replace("5 min (978 m)", "5 min\n(978 m)")
                .replace("10 min (5,4 km)", "10 min\n(5,4\nkm)")
        )

    @Test
    fun `taxas em linhas separadas nao viram o valor da corrida`() =
        assertCard(
            card.replace("R${'$'}2,18 por espera longa incl.", "R${'$'}2,18\npor espera longa incl.")
                .replace("Taxa de parada de R${'$'}0,80 inclusa", "Taxa de parada de\nR${'$'}0,80\ninclusa")
        )

    @Test
    fun `tela de ganhos e corrida em andamento nao sao oferta`() {
        assertFalse(RideScreenClassifier.isOfferScreen("R${'$'} 85,40\nGanhos de hoje\nProcurando passageiros", p))
        assertFalse(
            RideScreenClassifier.isOfferScreen(
                "Em andamento\nR${'$'}11,60\nR${'$'}1,82/km\n5 min (978 m)\nRua A\n10 min (5,4 km)\nRua B\nFinalizar corrida", p
            )
        )
    }
}
