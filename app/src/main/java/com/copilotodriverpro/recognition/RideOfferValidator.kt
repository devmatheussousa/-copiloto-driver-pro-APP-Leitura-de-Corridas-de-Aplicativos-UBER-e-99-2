package com.copilotodriverpro.recognition

import com.copilotodriverpro.models.RideOffer

/**
 * Validador de ofertas para eliminar duplicidades, descartar ofertas antigas
 * e calcular o nível de confiança das informações.
 */
object RideOfferValidator {

    private const val STALE_THRESHOLD_MS = 60_000L // 1 minuto
    private const val DUP_WINDOW_MS = 10_000L // 10 segundos
    private var lastOffer: RideOffer? = null

    /**
     * Valida se a oferta é nova e consistente.
     * Retorna false se for duplicada ou antiga.
     */
    fun isValidAndNew(offer: RideOffer): Boolean {
        if (!offer.hasAnyField()) return false

        val now = System.currentTimeMillis()
        if (now - offer.timestamp > STALE_THRESHOLD_MS) return false

        val previous = lastOffer
        if (previous != null && isDuplicate(offer, previous)) {
            if (offer.timestamp - previous.timestamp < DUP_WINDOW_MS) {
                return false
            }
        }

        lastOffer = offer
        return true
    }

    /** Compara duas ofertas para verificar se são exatamente a mesma. */
    fun isDuplicate(a: RideOffer, b: RideOffer): Boolean {
        if (a.platform != b.platform) return false
        val sameFare = a.fare == b.fare
        val sameTripKm = a.tripDistanceKm == b.tripDistanceKm
        val sameDuration = a.durationMin == b.durationMin
        return sameFare && sameTripKm && sameDuration
    }

    fun reset() {
        lastOffer = null
    }
}
