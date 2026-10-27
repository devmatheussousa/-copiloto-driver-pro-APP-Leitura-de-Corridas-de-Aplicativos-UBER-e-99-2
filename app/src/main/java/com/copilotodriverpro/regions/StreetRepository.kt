package com.copilotodriverpro.regions

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Ruas de uma cidade (OpenStreetMap / Overpass), para desenhar no mapa de regiões.
 *
 * Feito para ser leve:
 *  - baixa em PARTES: as vias grandes numa chamada e as ruas em quadrantes de ~13 km. Cada parte
 *    vira um arquivo; se uma falhar, as outras ficam salvas e na próxima vez só falta ela;
 *  - lê a resposta em fluxo (JsonReader), sem montar a árvore JSON inteira na memória;
 *  - simplifica cada linha (Douglas-Peucker) e grava em binário compacto;
 *  - vias que aparecem em dois quadrantes são juntadas pelo id (nada desenhado em dobro).
 */
class StreetRepository(context: Context) {

    private val dir = File(context.filesDir, "ruas").apply { mkdirs() }

    class Loaded(val data: StreetData, val missingParts: Int)

    private class Part(val key: String, val query: String)

    /**
     * Junta as partes de [city]. Com [download] = false só usa o que já está no aparelho
     * (devolve null se não houver nada). Com true, baixa o que faltar.
     * [onProgress] recebe (partes prontas, total).
     */
    suspend fun load(
        city: CityRef,
        download: Boolean,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Loaded? = withContext(Dispatchers.IO) {
        val parts = partsOf(city)
        val seen = HashSet<Long>()
        val lines = ArrayList<StreetLine>()
        var missing = 0
        var any = false
        parts.forEachIndexed { i, part ->
            ensureActive()
            val f = File(dir, "ruas_${city.osmId}_${part.key}.bin")
            var got: List<StreetLine>? = readFile(f)
            if (got == null && download) {
                got = try {
                    downloadPart(part).also { writeFile(f, it) }
                } catch (e: Exception) {
                    null
                }
            }
            if (got == null) missing++ else {
                any = true
                for (l in got) if (seen.add(l.id)) lines += l
            }
            onProgress(i + 1, parts.size)
        }
        if (!any && !download) null else Loaded(StreetData(lines), missing)
    }

    /* ------------------------------ partes ------------------------------ */

    private fun partsOf(city: CityRef): List<Part> {
        val head = "[out:json][timeout:150];area(${AREA_BASE + city.osmId})->.c;"
        val minor = "[\"highway\"~\"^(unclassified|residential|living_street)$\"]"
        val parts = ArrayList<Part>()
        parts += Part(
            "m",
            head + "way(area.c)[\"highway\"~\"^(motorway|trunk|primary|secondary|tertiary)(_link)?$\"];out geom qt;"
        )
        if (city.boundary.isEmpty()) {
            parts += Part("t", head + "way(area.c)$minor;out geom qt;")
        } else {
            val b = NeighborhoodGeo.boundsOfRings(city.boundary)
            StreetGeo.tiles(b.south, b.west, b.north, b.east, TILE_DEG).forEachIndexed { i, t ->
                val box = "(" + t.joinToString(",") { String.format(Locale.US, "%.5f", it) } + ")"
                parts += Part("t$i", head + "way(area.c)$minor$box;out geom qt;")
            }
        }
        return parts
    }

    private fun readFile(f: File): List<StreetLine>? {
        if (!f.exists()) return null
        val r = runCatching { f.inputStream().buffered().use { StreetGeo.read(it) } }.getOrNull()
        if (r == null) f.delete()
        return r
    }

    private fun writeFile(f: File, lines: List<StreetLine>) {
        val tmp = File(f.path + ".tmp")
        tmp.outputStream().buffered().use { StreetGeo.write(it, lines) }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /* ------------------------------- rede ------------------------------- */

    private fun downloadPart(part: Part): List<StreetLine> {
        var last: Exception? = null
        for (endpoint in OVERPASS) {
            try {
                return post(endpoint, "data=" + URLEncoder.encode(part.query, "UTF-8")) { parse(it) }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("Sem resposta do servidor de mapas.")
    }

    private fun <T> post(url: String, body: String, read: (InputStream) -> T): T {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 175_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("User-Agent", "CopilotoDriverPro/2.7 (app Android; ruas no mapa de regioes)")
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode !in 200..299) error("Servidor de mapas respondeu ${c.responseCode}.")
            return c.inputStream.use(read)
        } finally {
            c.disconnect()
        }
    }

    /** Lê `elements` em fluxo: só guarda id, tag highway e geometria de cada via. */
    private fun parse(input: InputStream): List<StreetLine> {
        val out = ArrayList<StreetLine>()
        JsonReader(InputStreamReader(input, Charsets.UTF_8).buffered()).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() == "elements") {
                    r.beginArray()
                    while (r.hasNext()) readElement(r, out)
                    r.endArray()
                } else r.skipValue()
            }
            r.endObject()
        }
        return out
    }

    private fun readElement(r: JsonReader, out: MutableList<StreetLine>) {
        var id = 0L
        var type = ""
        var highway = ""
        var pts: FloatArray? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "type" -> type = r.nextString()
                "id" -> id = r.nextLong()
                "tags" -> {
                    r.beginObject()
                    while (r.hasNext()) {
                        if (r.nextName() == "highway") highway = r.nextString() else r.skipValue()
                    }
                    r.endObject()
                }
                "geometry" -> pts = readGeometry(r)
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (type != "way" || pts == null || pts.size < 4) return
        val cls = StreetGeo.classOf(highway) ?: return
        out += StreetLine(id, cls, StreetGeo.simplifyLine(pts, SIMPLIFY_M))
    }

    private fun readGeometry(r: JsonReader): FloatArray {
        var buf = FloatArray(64)
        var n = 0
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() == JsonToken.NULL) { r.nextNull(); continue }
            var lat = Double.NaN
            var lon = Double.NaN
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "lat" -> lat = r.nextDouble()
                    "lon" -> lon = r.nextDouble()
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (!lat.isNaN() && !lon.isNaN()) {
                if (n + 2 > buf.size) buf = buf.copyOf(buf.size * 2)
                buf[n++] = lat.toFloat()
                buf[n++] = lon.toFloat()
            }
        }
        r.endArray()
        return buf.copyOf(n)
    }

    private companion object {
        val OVERPASS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://lz4.overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
        )
        const val AREA_BASE = 3_600_000_000L
        const val TILE_DEG = 0.12
        const val SIMPLIFY_M = 4.0
    }
}
