package com.copilotodriverpro.recognition

import com.copilotodriverpro.models.RideOffer

/**
 * Decide se uma leitura nova da tela pode entrar no card ou se deve esperar.
 *
 * Problema que resolve: o card piscava com valores diferentes (principalmente na 99, lida pela
 * imagem da tela). Causas: leitura parcial (só um trecho carregado), OCR com ruído e o app
 * lendo o próprio painel. Regras:
 *
 *  - leitura IGUAL à que já está no card -> [Decision.SAME] (só mantém o card vivo);
 *  - leitura limpa (completa e sem conflito) e NENHUM card na tela -> [Decision.PUBLISH] na hora
 *    (sem atraso para o caso normal), exceto OCR sem o "R$/km" do card para conferir, que
 *    precisa aparecer em 2 leituras seguidas;
 *  - leitura limpa DIFERENTE da que está no card -> só troca se aparecer em 2 leituras seguidas;
 *  - leitura incompleta ou conflitante nunca troca um card limpo e, sem card, só aparece (como
 *    "pendente") se persistir por [PENDING_HOLD_MS].
 */
internal class OfferStabilizer(private val clock: () -> Long = { System.currentTimeMillis() }) {

    enum class Decision { SAME, PUBLISH, HOLD }

    private var candidateSig: String? = null
    private var candidateCount = 0
    private var candidateSince = 0L

    fun signature(o: RideOffer) =
        "${o.platform}|${o.fare}|${o.tripDistanceKm}|${o.distanceToPickupKm}|${o.durationMin}|${o.timeToPickupMin}"

    fun reset() {
        candidateSig = null
        candidateCount = 0
        candidateSince = 0L
    }

    fun decide(offer: RideOffer, shown: RideOffer?, fromOcr: Boolean = false): Decision {
        val sig = signature(offer)
        val now = clock()
        if (shown != null && signature(shown) == sig) {
            reset()
            return Decision.SAME
        }

        if (sig == candidateSig) {
            candidateCount += 1
        } else {
            candidateSig = sig
            candidateCount = 1
            candidateSince = now
        }

        val clean = offer.isCompleteRead && !offer.needsValidation
        val shownIsClean = shown != null && shown.isCompleteRead && !shown.needsValidation

        return when {
            // Nunca derruba um card limpo com uma leitura duvidosa.
            shownIsClean && !clean -> Decision.HOLD
            clean && shown == null -> {
                // OCR sem o R$/km do card para conferir: confirma em 2 leituras iguais.
                val unverifiedOcr = fromOcr && offer.platformPerKmHint == null
                if (unverifiedOcr && candidateCount < 2) Decision.HOLD else publish()
            }
            clean -> if (candidateCount >= 2) publish() else Decision.HOLD
            // Pendente: só aparece se a MESMA leitura ruim persistir (evita piscar com carga parcial).
            candidateCount >= 2 && now - candidateSince >= PENDING_HOLD_MS -> publish()
            else -> Decision.HOLD
        }
    }

    private fun publish(): Decision {
        reset()
        return Decision.PUBLISH
    }

    companion object {
        const val PENDING_HOLD_MS = 900L
    }
}
