package com.copilotodriverpro.models

/**
 * Região de atuação cadastrada pelo motorista.
 * CIRCLE: centro + raio em metros. POLYGON: lista de vértices em ordem.
 */
enum class RegionKind { CIRCLE, POLYGON }

data class Region(
    val id: String,
    val name: String,
    val active: Boolean = true,
    val kind: RegionKind,
    // CIRCLE
    val center: LatLang? = null,
    val radiusM: Double = 0.0,
    // POLYGON
    val points: List<LatLang> = emptyList()
)

/** Situação de um ponto em relação às regiões ativas. */
enum class GeoStatus { INSIDE, OUTSIDE, UNKNOWN, NO_REGIONS }

/** Resultado da checagem geográfica de uma oferta. */
data class RegionCheck(
    val pickup: GeoStatus = GeoStatus.UNKNOWN,
    val destination: GeoStatus = GeoStatus.UNKNOWN,
    val pickupMatchedRegion: String? = null,
    val destinationMatchedRegion: String? = null,
    /** Quantos metros o embarque está além da borda da região ativa mais próxima (só se OUTSIDE). */
    val pickupOutsideM: Double? = null,
    /** Quantos metros o destino está além da borda da região ativa mais próxima (só se OUTSIDE). */
    val destinationOutsideM: Double? = null,
    /** true = os endereços da oferta ainda estão sendo convertidos em coordenadas. */
    val resolving: Boolean = false
)
