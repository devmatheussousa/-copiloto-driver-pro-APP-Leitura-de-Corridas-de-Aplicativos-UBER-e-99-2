package com.copilotodriverpro.recognition

import com.copilotodriverpro.models.RideOffer
import com.copilotodriverpro.models.RideSource
import com.copilotodriverpro.models.SupportedPlatform

/**
 * Interpretador das ofertas do 99 Motorista (com.app99.driver / com.taxis99 / br.com.taxis99).
 * Usa o mesmo [OfferTextExtractor] do Uber: uma única fonte de verdade para os números.
 */
object NineNineRideOfferParser {
    // Linha só com o valor ("R$13,90"): na lista "Solicitações" cada card tem a sua.
    private val FARE_LINE = Regex("""^\s*R\$\s*[0-9]+[.,][0-9]{2}\s*$""")

    /**
     * A lista "Solicitações" pode mostrar vários cards de uma vez. Lê só o primeiro (o de cima),
     * para o valor de um card não se misturar com os km e minutos de outro.
     */
    private fun firstCard(text: String): String {
        val lines = text.lines()
        val fareLines = lines.indices.filter { FARE_LINE.matches(lines[it]) }
        if (fareLines.size < 2) return text
        return lines.take(fareLines[1]).joinToString("\n")
    }

    fun parse(text: String, source: RideSource): RideOffer? =
        OfferTextExtractor.build(
            // Card achado pela estrutura (ignora mapa/ganhos ao fundo); senão, o 1º card da lista.
            NineNineCard.extract(text) ?: firstCard(text),
            source, SupportedPlatform.NINE_NINE, baseConfidence = 0.90
        )
}
