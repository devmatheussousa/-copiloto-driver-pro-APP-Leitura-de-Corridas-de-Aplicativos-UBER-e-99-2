package com.copilotodriverpro.maps

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

/** Para onde a câmera deve ir: centro (coordenada de mundo 0..1) e zoom. */
data class CameraTarget(val cx: Double, val cy: Double, val zoom: Float)

/**
 * Matemática do mapa próprio da tela de regiões. Sem Android/Compose — testável em JVM.
 *
 * Usa a mesma projeção do Google/OSM (Web Mercator) e a mesma escala de zoom (tile de 256 dp),
 * então os níveis de zoom continuam significando o que significavam antes.
 *
 * "Coordenada de mundo": x e y entre 0 e 1 (x cresce para leste, y cresce para o sul).
 * "Unidade": os desenhos são guardados em unidades = 1 px no zoom 20 (densidade 1), relativas a
 * uma origem perto dos dados. Assim cabem em Float sem perder precisão (milímetros) e a câmera
 * só precisa de uma transformação (translate + scale) para desenhar — nada é recalculado no gesto.
 */
object MapMath {
    const val TILE = 256.0
    const val MIN_ZOOM = 3f
    const val MAX_ZOOM = 18f
    const val EARTH_CIRCUMFERENCE_M = 40_075_016.686

    /** Multiplica (mundo − origem) para virar "unidade". */
    val UNIT_SCALE: Double = TILE * 2.0.pow(20)

    fun worldX(lng: Double): Double = (lng + 180.0) / 360.0

    fun worldY(lat: Double): Double {
        val s = sin(Math.toRadians(lat.coerceIn(-85.0511, 85.0511)))
        return 0.5 - ln((1.0 + s) / (1.0 - s)) / (4.0 * PI)
    }

    fun lngOf(x: Double): Double = x * 360.0 - 180.0

    fun latOf(y: Double): Double = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y))))

    /** Pixels que o mundo inteiro ocupa neste zoom. */
    fun worldPx(zoom: Float, density: Float): Double = TILE * density * 2.0.pow(zoom.toDouble())

    /** Escala para desenhar caminhos em "unidade" na tela: px de tela por unidade. */
    fun unitToPx(zoom: Float, density: Float): Float =
        (density * 2.0.pow(zoom.toDouble() - 20.0)).toFloat()

    /** Raio em metros → unidades (à latitude dada). */
    fun metersToUnits(meters: Double, lat: Double): Double =
        meters / (EARTH_CIRCUMFERENCE_M * cos(Math.toRadians(lat)).coerceAtLeast(0.01)) * UNIT_SCALE

    /** Câmera que enquadra a caixa (graus) dentro de [widthPx]×[heightPx] com [padPx] de folga. */
    fun fit(
        south: Double, west: Double, north: Double, east: Double,
        widthPx: Int, heightPx: Int, density: Float, padPx: Float
    ): CameraTarget {
        val x0 = worldX(west); val x1 = worldX(east)
        val y0 = worldY(north); val y1 = worldY(south)
        val spanX = x1 - x0
        val spanY = y1 - y0
        val availW = (widthPx - 2 * padPx).coerceAtLeast(1f)
        val availH = (heightPx - 2 * padPx).coerceAtLeast(1f)
        val zx = if (spanX > 0) log2(availW / (TILE * density * spanX)) else MAX_ZOOM.toDouble()
        val zy = if (spanY > 0) log2(availH / (TILE * density * spanY)) else MAX_ZOOM.toDouble()
        val zoom = min(zx, zy).toFloat().coerceIn(MIN_ZOOM, MAX_ZOOM)
        return CameraTarget((x0 + x1) / 2.0, (y0 + y1) / 2.0, zoom)
    }

    /** Ray casting sobre um anel [x0,y0,x1,y1,…] (fechado implicitamente). */
    fun ringContains(ring: FloatArray, x: Float, y: Float): Boolean {
        val n = ring.size / 2
        if (n < 3) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = ring[2 * i]; val yi = ring[2 * i + 1]
            val xj = ring[2 * j]; val yj = ring[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Área (unidade²) do anel — só para escolher o menor quando dois bairros se sobrepõem. */
    fun ringArea(ring: FloatArray): Double {
        val n = ring.size / 2
        if (n < 3) return 0.0
        var sum = 0.0
        var j = n - 1
        for (i in 0 until n) {
            sum += (ring[2 * j].toDouble() + ring[2 * i]) * (ring[2 * j + 1].toDouble() - ring[2 * i + 1])
            j = i
        }
        return kotlin.math.abs(sum / 2.0)
    }
}
