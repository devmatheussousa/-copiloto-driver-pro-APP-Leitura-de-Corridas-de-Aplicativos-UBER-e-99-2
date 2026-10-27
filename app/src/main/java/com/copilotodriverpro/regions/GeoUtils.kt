package com.copilotodriverpro.regions

import com.copilotodriverpro.models.GeoStatus
import com.copilotodriverpro.models.LatLang
import com.copilotodriverpro.models.Region
import com.copilotodriverpro.models.RegionCheck
import com.copilotodriverpro.models.RegionKind
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometria pura (testável em JVM): distância haversine e
 * ponto-em-polígono por ray casting.
 */
object GeoUtils {

    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceMeters(a: LatLang, b: LatLang): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val sLat = sin(dLat / 2)
        val sLng = sin(dLng / 2)
        val h = sLat * sLat + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sLng * sLng
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Ray casting. Polígono fechado implicitamente (último -> primeiro). */
    fun isPointInPolygon(point: LatLang, polygon: List<LatLang>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]
            val intersects = ((pi.lat > point.lat) != (pj.lat > point.lat)) &&
                (point.lng < (pj.lng - pi.lng) * (point.lat - pi.lat) / (pj.lat - pi.lat) + pi.lng)
            if (intersects) inside = !inside
            j = i
        }
        return inside
    }

    /** Contém o ponto, considerando o tipo da região. */
    fun regionContains(region: Region, point: LatLang): Boolean = when (region.kind) {
        RegionKind.CIRCLE -> {
            val c = region.center
            c != null && distanceMeters(c, point) <= region.radiusM
        }
        RegionKind.POLYGON -> isPointInPolygon(point, region.points)
    }

    /** Devolve o nome da primeira região ativa que contém o ponto, ou null. */
    fun findRegionAt(point: LatLang, regions: List<Region>): String? =
        regions.firstOrNull { it.active && regionContains(it, point) }?.name

    /**
     * Checagem da oferta. Um ponto sem coordenadas é UNKNOWN — nunca OUTSIDE.
     * Se não há nenhuma região ativa, NO_REGIONS (filtro não configurado).
     */
    fun checkOffer(
        regions: List<Region>,
        pickup: LatLang?,
        destination: LatLang?
    ): RegionCheck {
        val active = regions.filter { it.active }
        if (active.isEmpty()) {
            return RegionCheck(
                pickup = if (pickup == null) GeoStatus.UNKNOWN else GeoStatus.NO_REGIONS,
                destination = if (destination == null) GeoStatus.UNKNOWN else GeoStatus.NO_REGIONS
            )
        }
        val pickupStatus = when (pickup) {
            null -> GeoStatus.UNKNOWN
            else -> if (findRegionAt(pickup, active) != null) GeoStatus.INSIDE else GeoStatus.OUTSIDE
        }
        val destStatus = when (destination) {
            null -> GeoStatus.UNKNOWN
            else -> if (findRegionAt(destination, active) != null) GeoStatus.INSIDE else GeoStatus.OUTSIDE
        }
        return RegionCheck(
            pickup = pickupStatus,
            destination = destStatus,
            pickupMatchedRegion = pickup?.let { findRegionAt(it, active) },
            destinationMatchedRegion = destination?.let { findRegionAt(it, active) },
            pickupOutsideM = if (pickupStatus == GeoStatus.OUTSIDE && pickup != null) distanceToNearestM(pickup, active) else null,
            destinationOutsideM = if (destStatus == GeoStatus.OUTSIDE && destination != null) distanceToNearestM(destination, active) else null
        )
    }

    /** Distância (m) do ponto até a borda da região ativa MAIS PRÓXIMA; null se não der para medir. */
    fun distanceToNearestM(point: LatLang, regions: List<Region>): Double? =
        regions.filter { it.active }
            .map { ZoneGeometry.distanceOutsideM(it, point) }
            .filter { it < Double.MAX_VALUE }
            .minOrNull()

    /** Caixa (sul, oeste, norte, leste) que envolve as regiões ativas, com folga. */
    data class Bounds(val south: Double, val west: Double, val north: Double, val east: Double)

    /**
     * Caixa em volta de todas as regiões ativas + [marginM]. Serve para "puxar" o Geocoder para a
     * sua área: "Rua das Flores" existe em várias cidades, e sem isso ele podia escolher a errada.
     */
    fun boundsOf(regions: List<Region>, marginM: Double = 15_000.0): Bounds? {
        var south = Double.MAX_VALUE; var north = -Double.MAX_VALUE
        var west = Double.MAX_VALUE; var east = -Double.MAX_VALUE
        var any = false
        fun add(lat: Double, lng: Double) {
            any = true
            south = minOf(south, lat); north = maxOf(north, lat)
            west = minOf(west, lng); east = maxOf(east, lng)
        }
        for (r in regions.filter { it.active }) {
            when (r.kind) {
                RegionKind.CIRCLE -> r.center?.let { c ->
                    val dLat = Math.toDegrees(r.radiusM / EARTH_RADIUS_M)
                    val dLng = dLat / cos(Math.toRadians(c.lat)).coerceAtLeast(0.01)
                    add(c.lat - dLat, c.lng - dLng); add(c.lat + dLat, c.lng + dLng)
                }
                RegionKind.POLYGON -> r.points.forEach { add(it.lat, it.lng) }
            }
        }
        if (!any) return null
        val mLat = Math.toDegrees(marginM / EARTH_RADIUS_M)
        val mLng = mLat / cos(Math.toRadians((south + north) / 2)).coerceAtLeast(0.01)
        return Bounds(
            (south - mLat).coerceAtLeast(-90.0), (west - mLng).coerceAtLeast(-180.0),
            (north + mLat).coerceAtMost(90.0), (east + mLng).coerceAtMost(180.0)
        )
    }
}
