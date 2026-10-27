package com.copilotodriverpro.regions

import com.copilotodriverpro.models.LatLang
import com.copilotodriverpro.models.Region
import com.copilotodriverpro.models.RegionKind
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Onde o MOTORISTA está agora em relação às regiões ativas dele. */
enum class ZoneStatus { INSIDE, OUTSIDE, UNKNOWN, NO_REGIONS }

/** Mudança confirmada de situação (já com anti-oscilação aplicado). */
data class ZoneChange(
    val from: ZoneStatus,
    val to: ZoneStatus,
    val regionName: String?,
    /** true quando é a primeira leitura válida (antes estava sem sinal / sem regiões). */
    val initial: Boolean
)

/**
 * Lógica pura (testável em JVM) de "dentro/fora da região de atuação".
 *
 * Anti-oscilação para o GPS não ficar piscando "dentro/fora" na borda:
 *  - histerese: estando DENTRO, só vira FORA depois de passar da borda por uma margem
 *    (buffer + metade da imprecisão do GPS);
 *  - confirmação: a troca só vale após [confirmations] leituras seguidas iguais
 *    (a primeira leitura válida vale na hora).
 */
class ZoneTracker(
    private val confirmations: Int = 2,
    private val bufferM: Double = 30.0
) {
    var status: ZoneStatus = ZoneStatus.UNKNOWN
        private set
    var regionName: String? = null
        private set

    private var pending: ZoneStatus? = null
    private var pendingCount = 0

    fun reset() {
        status = ZoneStatus.UNKNOWN
        regionName = null
        pending = null
        pendingCount = 0
    }

    /** Sem sinal de GPS: vira UNKNOWN na hora (nunca dá "fora" por falta de dado). */
    fun noFix() {
        status = ZoneStatus.UNKNOWN
        regionName = null
        pending = null
        pendingCount = 0
    }

    /**
     * Processa uma leitura de posição. Devolve [ZoneChange] só quando a troca
     * INSIDE <-> OUTSIDE (ou a primeira leitura válida) foi confirmada.
     */
    fun update(regions: List<Region>, point: LatLang?, accuracyM: Float? = null): ZoneChange? {
        val active = regions.filter { it.active }
        if (active.isEmpty()) {
            status = ZoneStatus.NO_REGIONS
            regionName = null
            pending = null
            pendingCount = 0
            return null
        }
        if (point == null) {
            noFix()
            return null
        }

        val containing = active.firstOrNull { GeoUtils.regionContains(it, point) }
        var raw: ZoneStatus
        var name: String?
        if (containing != null) {
            raw = ZoneStatus.INSIDE
            name = containing.name
        } else {
            raw = ZoneStatus.OUTSIDE
            name = null
            if (status == ZoneStatus.INSIDE) {
                // histerese: perto da borda continua DENTRO
                val margin = bufferM + min(accuracyM?.toDouble() ?: 0.0, 100.0) * 0.5
                val nearest = active.minByOrNull { ZoneGeometry.distanceOutsideM(it, point) }
                if (nearest != null && ZoneGeometry.distanceOutsideM(nearest, point) < margin) {
                    raw = ZoneStatus.INSIDE
                    name = nearest.name
                }
            }
        }

        val previous = status
        if (raw == previous) {
            regionName = name
            pending = null
            pendingCount = 0
            return null
        }

        val firstValid = previous == ZoneStatus.UNKNOWN || previous == ZoneStatus.NO_REGIONS
        if (pending == raw) pendingCount++ else {
            pending = raw
            pendingCount = 1
        }
        if (firstValid || pendingCount >= confirmations) {
            status = raw
            regionName = name
            pending = null
            pendingCount = 0
            return ZoneChange(previous, raw, name, initial = firstValid)
        }
        return null
    }
}

/** Distâncias até a borda das regiões (aproximação plana local — ok para centenas de metros). */
object ZoneGeometry {

    /** 0 se o ponto está dentro; senão, distância em metros até a borda da região. */
    fun distanceOutsideM(region: Region, p: LatLang): Double = when (region.kind) {
        RegionKind.CIRCLE -> {
            val c = region.center
            if (c == null) Double.MAX_VALUE else max(0.0, GeoUtils.distanceMeters(c, p) - region.radiusM)
        }
        RegionKind.POLYGON -> {
            if (region.points.size < 3) Double.MAX_VALUE
            else if (GeoUtils.isPointInPolygon(p, region.points)) 0.0
            else {
                var best = Double.MAX_VALUE
                var j = region.points.size - 1
                for (i in region.points.indices) {
                    best = min(best, distanceToSegmentM(p, region.points[j], region.points[i]))
                    j = i
                }
                best
            }
        }
    }

    private fun distanceToSegmentM(p: LatLang, a: LatLang, b: LatLang): Double {
        val mPerDegLat = 111_320.0
        val mPerDegLng = 111_320.0 * cos(Math.toRadians(p.lat))
        val ax = (a.lng - p.lng) * mPerDegLng
        val ay = (a.lat - p.lat) * mPerDegLat
        val bx = (b.lng - p.lng) * mPerDegLng
        val by = (b.lat - p.lat) * mPerDegLat
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(ax + t * dx, ay + t * dy)
    }

    /** Centro aproximado da região (para "Ver no mapa"). */
    fun centerOf(region: Region): LatLang? = when (region.kind) {
        RegionKind.CIRCLE -> region.center
        RegionKind.POLYGON ->
            if (region.points.isEmpty()) null
            else LatLang(region.points.map { it.lat }.average(), region.points.map { it.lng }.average())
    }
}
