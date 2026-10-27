package com.copilotodriverpro.recognition

import com.copilotodriverpro.models.RideOffer
import com.copilotodriverpro.models.RideSource
import com.copilotodriverpro.models.SupportedPlatform

/**
 * Interpretador das ofertas do Uber Driver (com.ubercab.driver).
 *
 * Toda a leitura (valor, embarque x viagem, tempos, nota, parada) fica no [OfferTextExtractor],
 * que pareia cada distância com o tempo colado nela. Antes existia uma cópia simplificada da
 * lógica aqui, que tratava "o 1º km" como embarque e "o 2º km" como viagem sem conferir rótulos
 * (e, com um único km, usava o MESMO valor como embarque e viagem, dobrando a distância).
 */
object UberRideOfferParser {
    fun parse(text: String, source: RideSource): RideOffer? =
        OfferTextExtractor.build(text, source, SupportedPlatform.UBER, baseConfidence = 0.95)
}
