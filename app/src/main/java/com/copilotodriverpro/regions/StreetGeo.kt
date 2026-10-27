package com.copilotodriverpro.regions

import com.copilotodriverpro.models.LatLang
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.ceil

/**
 * Uma via do OpenStreetMap. [pts] = [lat0, lng0, lat1, lng1, …] em Float (precisão ~20 cm, de sobra
 * para desenhar). [cls]: 0 = rodovia/via expressa, 1 = primária, 2 = secundária, 3 = terciária, 4 = ruas.
 */
class StreetLine(val id: Long, val cls: Int, val pts: FloatArray)

class StreetData(val lines: List<StreetLine>)

/** Lógica pura das ruas (sem Android — testável em JVM): classes, simplificação, arquivo binário, quadrantes. */
object StreetGeo {

    const val CLASSES = 5

    /** Classe da via a partir da tag `highway`; null = não desenhamos (trilha, calçada, etc.). */
    fun classOf(highway: String): Int? = when (highway) {
        "motorway", "trunk", "motorway_link", "trunk_link" -> 0
        "primary", "primary_link" -> 1
        "secondary", "secondary_link" -> 2
        "tertiary", "tertiary_link" -> 3
        "unclassified", "residential", "living_street" -> 4
        else -> null
    }

    /** Douglas-Peucker na linha (tolerância em metros): menos pontos, mesma forma. */
    fun simplifyLine(pts: FloatArray, toleranceM: Double): FloatArray {
        val n = pts.size / 2
        if (n <= 3) return pts
        val list = ArrayList<LatLang>(n)
        for (i in 0 until n) list += LatLang(pts[2 * i].toDouble(), pts[2 * i + 1].toDouble())
        val out = NeighborhoodGeo.simplify(list, toleranceM)
        val r = FloatArray(out.size * 2)
        for (i in out.indices) { r[2 * i] = out[i].lat.toFloat(); r[2 * i + 1] = out[i].lng.toFloat() }
        return r
    }

    /** Divide a caixa em quadrantes de no máximo [stepDeg] graus: [sul, oeste, norte, leste]. */
    fun tiles(south: Double, west: Double, north: Double, east: Double, stepDeg: Double): List<DoubleArray> {
        val rows = ceil((north - south) / stepDeg).toInt().coerceAtLeast(1)
        val cols = ceil((east - west) / stepDeg).toInt().coerceAtLeast(1)
        val dLat = (north - south) / rows
        val dLng = (east - west) / cols
        val out = ArrayList<DoubleArray>(rows * cols)
        for (r in 0 until rows) for (c in 0 until cols) {
            out += doubleArrayOf(south + r * dLat, west + c * dLng, south + (r + 1) * dLat, west + (c + 1) * dLng)
        }
        return out
    }

    /* ----------------------- arquivo binário (rápido de ler) ----------------------- */

    private const val MAGIC = 0x52554131 // "RUA1"

    fun write(out: OutputStream, lines: List<StreetLine>) {
        val d = DataOutputStream(out)
        d.writeInt(MAGIC)
        d.writeInt(lines.size)
        for (l in lines) {
            d.writeLong(l.id)
            d.writeByte(l.cls)
            d.writeInt(l.pts.size / 2)
            for (v in l.pts) d.writeFloat(v)
        }
        d.flush()
    }

    /** null = arquivo corrompido/de outra versão (quem chama baixa de novo). */
    fun read(input: InputStream): List<StreetLine>? = runCatching {
        val d = DataInputStream(input)
        if (d.readInt() != MAGIC) return null
        val count = d.readInt()
        if (count < 0 || count > 5_000_000) return null
        val out = ArrayList<StreetLine>(count)
        repeat(count) {
            val id = d.readLong()
            val cls = d.readUnsignedByte()
            val n = d.readInt()
            if (cls >= CLASSES || n < 2 || n > 200_000) return null
            val pts = FloatArray(n * 2)
            for (i in pts.indices) pts[i] = d.readFloat()
            out += StreetLine(id, cls, pts)
        }
        out
    }.getOrNull()
}
