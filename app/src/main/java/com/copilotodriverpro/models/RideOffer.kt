package com.copilotodriverpro.models

import java.util.UUID

/** Origem da plataforma. */
enum class SupportedPlatform { UBER, NINE_NINE, UNKNOWN }

/** Origem dos dados da oferta. */
enum class RideSource { ACCESSIBILITY, OCR_IMAGE, MANUAL }

/** Veredito sobre parada. */
enum class StopVerdict { NONE, POSSIBLE, CONFIRMED }

/** Procedência do campo (Rastreabilidade dos dados capturados). */
enum class DataProvenance {
    CAPTURED,               // Lido diretamente do app
    CONFIRMED_BY_DRIVER,   // Confirmado/inserido pelo motorista
    CALCULATED,            // Obtido via fórmula
    ESTIMATED,             // Estimado via heurística
    UNAVAILABLE,           // Não foi possível obter
    INVALID                // Dado não passou na validação
}

/**
 * Problema detectado na leitura ou na consistência de uma oferta.
 * [blocking] = os números derivados não são confiáveis: a oferta fica "pendente de validação".
 */
data class OfferIssue(
    val code: String,
    val message: String,
    val blocking: Boolean
)

/**
 * Representação completa de uma Oferta de Corrida Estruturada.
 *
 * Esta é a ÚNICA estrutura de oferta normalizada: painel, card, histórico e tela inicial
 * consomem o mesmo objeto (e o mesmo [com.copilotodriverpro.analyzer.OfferEvaluation]).
 * Mapeamento para os nomes sugeridos: fare_amount=fare, pickup_distance_km=distanceToPickupKm,
 * pickup_time_min=timeToPickupMin, trip_distance_km=tripDistanceKm, trip_time_min=durationMin,
 * total_time_min=totalDurationMin, bonus_amount=bonusConfirmed, ride_type=rideType,
 * rating=passengerRating, rating_count=ratingCount, data_confidence=confidenceScore.
 */
data class RideOffer(
    val id: String = UUID.randomUUID().toString(),
    val platform: SupportedPlatform = SupportedPlatform.UNKNOWN,
    val fare: Double? = null,                         // R$ oferecido pela corrida
    val bonusConfirmed: Double? = null,              // Bônus/dinâmica confirmado
    val tollIncluded: Boolean = false,               // Pedágio incluso
    val distanceToPickupKm: Double? = null,           // Distância até o passageiro
    val timeToPickupMin: Double? = null,               // Tempo até o passageiro
    val tripDistanceKm: Double? = null,               // Distância da viagem
    val durationMin: Double? = null,                  // Duração estimada da viagem
    val passengerRating: Double? = null,              // Nota do passageiro
    val stopVerdict: StopVerdict = StopVerdict.NONE,
    val pickupText: String? = null,                   // Origem (texto)
    val destinationText: String? = null,              // Destino (texto)
    val source: RideSource = RideSource.MANUAL,
    val timestamp: Long = System.currentTimeMillis(),
    val rawText: String = "",
    val confidenceScore: Double = 1.0,                // 0.0 a 1.0
    val fareProvenance: DataProvenance = if (fare != null) DataProvenance.CAPTURED else DataProvenance.UNAVAILABLE,
    val distanceProvenance: DataProvenance = if (tripDistanceKm != null) DataProvenance.CAPTURED else DataProvenance.UNAVAILABLE,
    val durationProvenance: DataProvenance = if (durationMin != null) DataProvenance.CAPTURED else DataProvenance.UNAVAILABLE,
    val rideType: String? = null,                     // UberX, Comfort, Black...
    val ratingCount: Int? = null,                     // nº de avaliações do passageiro (ex.: 326)
    val bonusIncludedInFare: Boolean = false,         // "+R$ 1,00 incluído": já está dentro de [fare]
    val platformPerKmHint: Double? = null,            // "R$1,16/km aprox." mostrado pela plataforma
    val issues: List<OfferIssue> = emptyList()        // problemas de leitura/consistência
) {
    /** Há problema que impede apresentar os indicadores como definitivos. */
    val needsValidation: Boolean
        get() = issues.any { it.blocking }

    val isCompleteRead: Boolean
        get() = !needsValidation

    fun hasAnyField(): Boolean =
        fare != null || distanceToPickupKm != null || tripDistanceKm != null ||
            durationMin != null || passengerRating != null

    /**
     * Distância total = embarque + viagem. Só existe se a VIAGEM é conhecida: usar apenas o
     * embarque como "total" gerava R$/km absurdo (ex.: R$ 20 / 1,2 km).
     */
    val totalDistanceKm: Double?
        get() = tripDistanceKm?.let { it + (distanceToPickupKm ?: 0.0) }

    /**
     * Tempo total = embarque + viagem. Só existe se o tempo da VIAGEM é conhecido: usar só o
     * tempo até o passageiro inflava o R$/hora (ex.: R$ 10 em "3 min" = R$ 200/h).
     */
    val totalDurationMin: Double?
        get() = durationMin?.let { it + (timeToPickupMin ?: 0.0) }
}

/** Coordenada geográfica simples. */
data class LatLang(val lat: Double, val lng: Double)
