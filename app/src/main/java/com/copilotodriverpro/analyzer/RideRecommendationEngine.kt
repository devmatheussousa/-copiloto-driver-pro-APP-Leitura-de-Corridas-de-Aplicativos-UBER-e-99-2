package com.copilotodriverpro.analyzer

import com.copilotodriverpro.models.GeoStatus
import com.copilotodriverpro.models.RegionCheck
import com.copilotodriverpro.models.RideOffer
import com.copilotodriverpro.models.StopVerdict
import com.copilotodriverpro.settings.DriverSettings

/**
 * 4 Estados de Recomendação oficiais requeridos pelo sistema.
 */
enum class RecommendationState {
    ACEITAR,                // Atende metas e limites com dados confiáveis
    AVALIAR,                // Possui potencial mas tem fatores incertos/limítrofes
    RECUSAR,                // Viola metas ou limites do motorista
    DADOS_INSUFFICIENTES    // Faltam informações essenciais para avaliação
}

data class RecommendationResult(
    val state: RecommendationState,
    val justification: String
)

/**
 * Motor de Recomendação Rápida para o Motorista.
 */
object RideRecommendationEngine {

    fun evaluate(
        offer: RideOffer,
        metrics: FinancialMetrics,
        settings: DriverSettings,
        regionCheck: RegionCheck? = null
    ): RecommendationResult {
        // 0. PENDENTE DE VALIDAÇÃO: leitura incompleta ou conflitante (ex.: R$/km calculado não bate
        // com o do card). Nunca classifica como aprovada/reprovada com números duvidosos.
        if (offer.needsValidation) {
            val reasons = offer.issues.filter { it.blocking }.joinToString(" ") { it.message }
            return RecommendationResult(
                state = RecommendationState.DADOS_INSUFFICIENTES,
                justification = "PENDENTE DE VALIDAÇÃO — $reasons"
            )
        }

        // 1. DADOS INSUFFICIENTES: Se não tiver o valor da corrida ou a distância
        if (offer.fare == null || metrics.totalDistanceKm == null || metrics.totalDistanceKm <= 0) {
            return RecommendationResult(
                state = RecommendationState.DADOS_INSUFFICIENTES,
                justification = "Faltam informações essenciais da corrida (valor ou distância) para uma análise responsável."
            )
        }

        // 2. RECUSAR: Violação de Região de Atuação
        if (settings.checkPickupRegion && settings.alertPickupOutside && regionCheck?.pickup == GeoStatus.OUTSIDE) {
            return RecommendationResult(
                state = RecommendationState.RECUSAR,
                justification = "RECUSAR — Embarque fora da sua região de atuação configurada."
            )
        }
        if (settings.checkDestinationRegion && settings.alertDestinationOutside && regionCheck?.destination == GeoStatus.OUTSIDE) {
            return RecommendationResult(
                state = RecommendationState.RECUSAR,
                justification = "RECUSAR — Destino fora da sua região de atuação configurada."
            )
        }

        // 3. RECUSAR: Distância ou Tempo até o passageiro acima do limite
        if (offer.distanceToPickupKm != null && offer.distanceToPickupKm > settings.maxDistanceToPickupKm) {
            return RecommendationResult(
                state = RecommendationState.RECUSAR,
                justification = "RECUSAR — Distância até o passageiro (%.1f km) excede seu limite de %.1f km.".format(offer.distanceToPickupKm, settings.maxDistanceToPickupKm.toDouble())
            )
        }
        if (offer.timeToPickupMin != null && offer.timeToPickupMin > settings.maxTimeToPickupMin) {
            return RecommendationResult(
                state = RecommendationState.RECUSAR,
                justification = "RECUSAR — Tempo até o passageiro (%.0f min) excede seu limite de %.0f min.".format(offer.timeToPickupMin, settings.maxTimeToPickupMin.toDouble())
            )
        }

        // 4. RECUSAR: Nota do passageiro abaixo do mínimo
        if (settings.showPassengerRating && offer.passengerRating != null && offer.passengerRating < settings.minRating) {
            return RecommendationResult(
                state = RecommendationState.RECUSAR,
                justification = "RECUSAR — Nota do passageiro (★ %.2f) abaixo da sua nota mínima de ★ %.2f.".format(offer.passengerRating, settings.minRating.toDouble())
            )
        }

        // 5. AVALIAR: Corrida com parada.
        // O documento é explícito: a parada exige atenção, mas NÃO deve, sozinha,
        // determinar aceitação ou recusa. Por isso só rebaixa para AVALIAR.
        if (settings.stopAlertEnabled && offer.stopVerdict == StopVerdict.CONFIRMED) {
            return RecommendationResult(
                state = RecommendationState.AVALIAR,
                justification = "AVALIAR — Corrida com parada. Confirme se o ganho compensa a parada antes de decidir."
            )
        }

        // 6. RECUSAR: Ganhos líquidos abaixo das metas do motorista
        val netPerKm = metrics.netPerKm
        val netPerHour = metrics.netPerHour
        val netEarnings = metrics.netEarnings

        if (netPerKm != null && netPerKm < settings.minNetPerKm) {
            return RecommendationResult(
                state = RecommendationState.RECUSAR,
                justification = "RECUSAR — Ganho líquido por km (R$ %.2f) abaixo da sua meta de R$ %.2f.".format(netPerKm, settings.minNetPerKm.toDouble())
            )
        }

        if (netPerHour != null && netPerHour < settings.minNetHourly) {
            return RecommendationResult(
                state = RecommendationState.RECUSAR,
                justification = "RECUSAR — Ganho líquido por hora (R$ %.2f) abaixo da sua meta de R$ %.2f.".format(netPerHour, settings.minNetHourly.toDouble())
            )
        }

        if (settings.minNetPerRide > 0 && netEarnings != null && netEarnings < settings.minNetPerRide) {
            return RecommendationResult(
                state = RecommendationState.RECUSAR,
                justification = "RECUSAR — Ganho líquido por corrida (R$ %.2f) abaixo da sua meta de R$ %.2f.".format(netEarnings, settings.minNetPerRide.toDouble())
            )
        }

        // 7. AVALIAR: Se a confiança for baixa ou houver parada possível
        if (offer.confidenceScore < 0.75 || offer.stopVerdict == StopVerdict.POSSIBLE) {
            return RecommendationResult(
                state = RecommendationState.AVALIAR,
                justification = "AVALIAR — A corrida atende aos ganhos, mas possui informações incertas ou possível parada para confirmar."
            )
        }

        // 8. ACEITAR: Atende a todas as metas e critérios com confiança
        val kmText = netPerKm?.let { "R$ %.2f/km".format(it) } ?: ""
        val hrText = netPerHour?.let { "R$ %.2f/h".format(it) } ?: ""
        val details = listOf(kmText, hrText).filter { it.isNotBlank() }.joinToString(" e ")

        return RecommendationResult(
            state = RecommendationState.ACEITAR,
            justification = "ACEITAR — A estimativa de ganho líquido ($details) supera suas metas configuradas."
        )
    }
}
