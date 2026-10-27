package com.copilotodriverpro.recognition

import com.copilotodriverpro.models.OfferIssue
import com.copilotodriverpro.models.RideOffer
import com.copilotodriverpro.models.SupportedPlatform
import kotlin.math.abs
import kotlin.math.max

/**
 * Confere se os campos lidos de uma oferta fazem sentido entre si.
 *
 * Princípio: dado ausente ou conflitante NUNCA vira número "definitivo". O resultado é uma lista de
 * [OfferIssue]; qualquer problema bloqueante deixa a oferta como "pendente de validação" (o painel
 * mostra "—" nos indicadores em vez de um cálculo possivelmente errado). Problemas não bloqueantes
 * (ex.: embarque não lido, então o R$/km é parcial) só reduzem a confiança.
 *
 * Códigos que começam com "READ_" nascem na leitura do texto (OfferTextExtractor) e são preservados
 * quando a oferta é revalidada depois de um merge com OCR.
 */
internal object OfferConsistency {

    // Limites de sanidade (qualquer coisa fora disso é leitura inválida, não corrida real).
    const val MIN_FARE = 1.0
    const val MAX_FARE = 5_000.0
    const val MAX_KM = 1_500.0
    const val MAX_MIN = 24 * 60.0

    private const val MAX_SPEED_KMH = 160.0
    private const val MIN_SPEED_KMH = 2.0

    /** R$/km informado pela plataforma vs. calculado: tolerância (arredondamento de 2 casas). */
    private const val HINT_ABS_TOLERANCE = 0.03
    private const val HINT_REL_TOLERANCE = 0.08

    /** Confiança máxima conforme a gravidade dos problemas. */
    private const val CONFIDENCE_CAP_BLOCKING = 0.30
    private const val CONFIDENCE_CAP_WARNING = 0.70

    fun validate(offer: RideOffer, readIssues: List<OfferIssue> = emptyList()): RideOffer {
        val issues = ArrayList<OfferIssue>()

        // Problemas de leitura já conhecidos (ex.: valor inválido do OCR) + os desta chamada.
        val carried = (offer.issues.filter { it.code.startsWith("READ_") } + readIssues)
            .distinctBy { it.code + "|" + it.message }
            // Trecho ambíguo deixa de valer quando a viagem passou a existir (ex.: completada pelo OCR).
            .filterNot { it.code == CODE_AMBIGUOUS_LEG && offer.tripDistanceKm != null }
        issues.addAll(carried)

        val ambiguous = issues.any { it.code == CODE_AMBIGUOUS_LEG }
        val knownPlatform = offer.platform != SupportedPlatform.UNKNOWN

        val fare = offer.fare
        val trip = offer.tripDistanceKm
        val pickup = offer.distanceToPickupKm

        if (fare == null) {
            issues.add(OfferIssue("FARE_MISSING", "Valor da corrida não foi lido.", blocking = true))
        }
        if (trip == null && !ambiguous) {
            issues.add(
                OfferIssue(
                    "TRIP_DISTANCE_MISSING",
                    "Distância da viagem não foi lida; R$/km não pode ser calculado.",
                    blocking = true
                )
            )
        }

        // Em Uber/99 o card sempre traz embarque + viagem. Faltando o embarque, o total é parcial.
        if (knownPlatform && trip != null) {
            if (pickup == null) {
                issues.add(
                    OfferIssue(
                        "PICKUP_DISTANCE_MISSING",
                        "Distância até o passageiro não lida: R$/km considera só a viagem (parcial).",
                        blocking = false
                    )
                )
            }
            if (offer.timeToPickupMin == null && offer.durationMin != null) {
                issues.add(
                    OfferIssue(
                        "PICKUP_TIME_MISSING",
                        "Tempo até o passageiro não lido: R$/hora considera só a viagem (parcial).",
                        blocking = false
                    )
                )
            }
            if (offer.durationMin == null) {
                issues.add(
                    OfferIssue(
                        "TRIP_TIME_MISSING",
                        "Tempo da viagem não lido: R$/hora indisponível.",
                        blocking = false
                    )
                )
            }
        }

        // Velocidade implícita impossível = km e minutos de trechos diferentes (ou número mal lido).
        checkSpeed("viagem", trip, offer.durationMin)?.let { issues.add(it) }
        checkSpeed("embarque", pickup, offer.timeToPickupMin)?.let { issues.add(it) }

        // Mesmo trecho lido duas vezes (embarque == viagem em km e minutos).
        if (pickup != null && trip != null && pickup == trip &&
            offer.timeToPickupMin != null && offer.timeToPickupMin == offer.durationMin
        ) {
            issues.add(
                OfferIssue(
                    "DUPLICATE_LEG",
                    "Embarque e viagem saíram idênticos: o mesmo trecho foi lido duas vezes.",
                    blocking = true
                )
            )
        }

        // Prova cruzada com o "R$1,16/km aprox." que a própria plataforma exibe no card.
        checkPlatformHint(offer)?.let { issues.add(it) }

        val cap = when {
            issues.any { it.blocking } -> CONFIDENCE_CAP_BLOCKING
            issues.isNotEmpty() -> CONFIDENCE_CAP_WARNING
            else -> 1.0
        }
        return offer.copy(issues = issues, confidenceScore = minOf(offer.confidenceScore, cap))
    }

    const val CODE_AMBIGUOUS_LEG = "READ_AMBIGUOUS_LEG"

    private fun checkSpeed(label: String, km: Double?, min: Double?): OfferIssue? {
        if (km == null || min == null || km <= 0.0 || min <= 0.0) return null
        val speed = km / (min / 60.0)
        val tooFast = speed > MAX_SPEED_KMH
        val tooSlow = speed < MIN_SPEED_KMH && min >= 10.0
        if (!tooFast && !tooSlow) return null
        return OfferIssue(
            "IMPLAUSIBLE_SPEED",
            "Km e minutos da $label são incompatíveis (%.0f km/h): leitura possivelmente trocada.".format(speed),
            blocking = true
        )
    }

    /**
     * O Uber mostra "R$ x/km aprox." no card. Se o R$/km calculado não bate com ele nem na base
     * embarque+viagem nem na base só-viagem, algum campo foi lido errado (era o caso do R$ 11,01/km
     * contra os R$ 1,16/km do card).
     */
    private fun checkPlatformHint(offer: RideOffer): OfferIssue? {
        val hint = offer.platformPerKmHint ?: return null
        val fare = offer.fare ?: return null
        val trip = offer.tripDistanceKm ?: return null
        if (trip <= 0.0 || hint <= 0.0) return null

        val tripOnly = fare / trip
        // Embarque não lido NÃO dispensa a conferência: foi exatamente nesse estado que o R$ 11,01/km
        // (R$ 33,04 / 3,0 km) passou despercebido contra os R$ 1,16/km do card.
        val withPickup = offer.distanceToPickupKm?.let { fare / (trip + it) }
        if ((withPickup != null && matches(withPickup, hint)) || matches(tripOnly, hint)) return null

        return OfferIssue(
            "PER_KM_MISMATCH",
            "R$/km calculado (R$ %.2f) não bate com o R$/km do card (R$ %.2f): distância ou valor possivelmente mal lidos."
                .format(withPickup ?: tripOnly, hint),
            blocking = true
        )
    }

    private fun matches(computed: Double, hint: Double): Boolean =
        abs(computed - hint) <= max(HINT_ABS_TOLERANCE, hint * HINT_REL_TOLERANCE)
}
