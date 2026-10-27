package com.copilotodriverpro.analyzer

import com.copilotodriverpro.models.RegionCheck
import com.copilotodriverpro.models.RideOffer
import com.copilotodriverpro.models.StopVerdict
import com.copilotodriverpro.settings.DriverSettings

enum class TrafficLight { NONE, RED, YELLOW, GREEN }

enum class Recommendation { ACCEPT, REJECT, CAUTION }

data class RateResult(
    val value: Double?,
    val light: TrafficLight
)

data class OfferEvaluation(
    val perKm: RateResult,
    val perHour: RateResult,
    val totalDistanceKm: Double?,
    val totalDurationMin: Double?,
    val passengerRating: Double?,
    val ratingBelowMinimum: Boolean,
    val stopVerdict: StopVerdict,
    val recommendation: Recommendation = Recommendation.CAUTION,
    val recommendationReason: String = "",
    val financialMetrics: FinancialMetrics? = null,
    val recommendationResult: RecommendationResult? = null,
    /** Leitura incompleta/conflitante: indicadores suspensos, oferta "pendente de validação". */
    val pending: Boolean = false,
    /** Avisos e motivos (bloqueantes ou não) para mostrar ao motorista junto dos indicadores. */
    val issueMessages: List<String> = emptyList()
)

/**
 * Motor de Análise e Integração.
 */
object RideAnalyzer {

    fun evaluateOffer(
        offer: RideOffer,
        settings: DriverSettings,
        regionCheck: RegionCheck? = null
    ): OfferEvaluation {
        val calculated = ProfitabilityCalculator.calculate(offer, settings)
        val recResult = RideRecommendationEngine.evaluate(offer, calculated, settings, regionCheck)

        // Leitura pendente de validação: NÃO mostra indicadores derivados de dados duvidosos.
        // Painel, card, tela inicial e histórico leem este mesmo resultado (fonte única).
        val pending = offer.needsValidation
        val metrics = if (pending) {
            calculated.copy(
                totalDistanceKm = null, totalDurationMin = null, netEarnings = null,
                grossPerKm = null, grossPerHour = null, grossPerMinute = null,
                netPerKm = null, netPerHour = null
            )
        } else calculated

        val perKmRate = RateResult(metrics.grossPerKm, classify(metrics.grossPerKm, settings.perKmMin.toDouble(), settings.perKmMax.toDouble()))
        val perHourRate = RateResult(metrics.grossPerHour, classify(metrics.grossPerHour, settings.perHourMin.toDouble(), settings.perHourMax.toDouble()))
        val ratingBelowMin = offer.passengerRating != null && offer.passengerRating < settings.minRating

        val legacyRec = when (recResult.state) {
            RecommendationState.ACEITAR -> Recommendation.ACCEPT
            RecommendationState.RECUSAR -> Recommendation.REJECT
            RecommendationState.AVALIAR, RecommendationState.DADOS_INSUFFICIENTES -> Recommendation.CAUTION
        }

        return OfferEvaluation(
            perKm = perKmRate,
            perHour = perHourRate,
            totalDistanceKm = metrics.totalDistanceKm,
            totalDurationMin = metrics.totalDurationMin,
            passengerRating = offer.passengerRating,
            ratingBelowMinimum = ratingBelowMin,
            stopVerdict = offer.stopVerdict,
            recommendation = legacyRec,
            recommendationReason = recResult.justification,
            financialMetrics = metrics,
            recommendationResult = recResult,
            pending = pending,
            issueMessages = offer.issues.sortedByDescending { it.blocking }.map { it.message }
        )
    }

    fun evaluate(
        fare: Double?,
        distanceToPickupKm: Double?,
        tripDistanceKm: Double?,
        durationMin: Double?,
        passengerRating: Double?,
        stopVerdict: StopVerdict,
        perKmMin: Double, perKmMax: Double,
        perHourMin: Double, perHourMax: Double,
        includePickupInKm: Boolean,
        minRating: Double,
        stopAlertEnabled: Boolean = true
    ): OfferEvaluation {

        val tempOffer = RideOffer(
            fare = fare,
            distanceToPickupKm = distanceToPickupKm,
            tripDistanceKm = tripDistanceKm,
            durationMin = durationMin,
            passengerRating = passengerRating,
            stopVerdict = stopVerdict
        )

        val tempSettings = DriverSettings(
            perKmMin = perKmMin.toFloat(),
            perKmMax = perKmMax.toFloat(),
            perHourMin = perHourMin.toFloat(),
            perHourMax = perHourMax.toFloat(),
            includePickupInKm = includePickupInKm,
            minRating = minRating.toFloat(),
            stopAlertEnabled = stopAlertEnabled
        )

        return evaluateOffer(tempOffer, tempSettings)
    }

    fun classify(value: Double?, min: Double, max: Double): TrafficLight = when {
        value == null -> TrafficLight.NONE
        value < min -> TrafficLight.RED
        value < max -> TrafficLight.YELLOW
        else -> TrafficLight.GREEN
    }
}
