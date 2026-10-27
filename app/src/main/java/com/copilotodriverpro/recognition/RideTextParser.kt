package com.copilotodriverpro.recognition

import com.copilotodriverpro.models.RideOffer
import com.copilotodriverpro.models.RideSource
import com.copilotodriverpro.models.SupportedPlatform

/**
 * Parser central de texto de ofertas, tolerante a formato brasileiro.
 * Números: aceita "1.234,56", "12,50", "12.50".
 * Não inventa valores: o que não casa com regex fica null.
 *
 * Toda a extração é feita pelo [OfferTextExtractor] (Uber, 99, OCR e entrada colada),
 * para que o R$/km e o R$/hora sempre partam dos mesmos números.
 */
object RideTextParser {

    fun parse(text: String, source: RideSource, platform: SupportedPlatform = SupportedPlatform.UNKNOWN): RideOffer? {
        if (text.isBlank()) return null
        return when (platform) {
            SupportedPlatform.UBER -> UberRideOfferParser.parse(text, source)
            SupportedPlatform.NINE_NINE -> NineNineRideOfferParser.parse(text, source)
            // Plataforma desconhecida (OCR / texto colado): mesma leitura, sem rótulo de app.
            SupportedPlatform.UNKNOWN ->
                OfferTextExtractor.build(text, source, SupportedPlatform.UNKNOWN, baseConfidence = 0.90)
        }
    }

    /** Converte "1.234,56", "12,50" e "12.50" para Double. */
    fun String.toDoublePt(): Double = OfferTextExtractor.parsePtNumber(this)
}
