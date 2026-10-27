package com.copilotodriverpro.regions

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Busca os limites REAIS de bairros/setores de uma cidade.
 *
 * Fonte: OpenStreetMap — Nominatim (acha a cidade e o contorno do município) e Overpass API
 * (relações administrativas nível 10 = bairro, nível 9 = distrito/setor).
 * Depois de baixado, fica salvo no aparelho (uma vez por cidade): reabrir a tela não usa internet.
 *
 * Limitação: a cobertura depende do mapeamento do OSM. Se a cidade não tiver bairros mapeados,
 * devolvemos erro claro — nunca desenhamos divisões aproximadas.
 */
class NeighborhoodRepository(private val context: Context) {

    private val dir = File(context.filesDir, "bairros").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("copiloto_region_picker", Context.MODE_PRIVATE)

    fun lastCity(): Pair<Long, String>? {
        val id = prefs.getLong(KEY_ID, 0L)
        val name = prefs.getString(KEY_NAME, null)
        return if (id != 0L && name != null) id to name else null
    }

    private fun rememberCity(c: CityRef) {
        prefs.edit().putLong(KEY_ID, c.osmId).putString(KEY_NAME, c.name).apply()
    }

    /** Procura uma cidade pelo nome. */
    suspend fun findCity(query: String): Result<CityRef> = withContext(Dispatchers.IO) {
        runCatching {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            val body = httpGet(
                "$NOMINATIM/search?q=$q&format=jsonv2&limit=5&featuretype=city&polygon_geojson=1" +
                    "&polygon_threshold=0.0005&accept-language=pt-BR"
            )
            val arr = JSONArray(body)
            val hit = (0 until arr.length()).map { arr.getJSONObject(it) }
                .firstOrNull { it.optString("osm_type") == "relation" }
                ?: error("Não achei \"$query\" como cidade no OpenStreetMap.")
            cityFrom(hit)
        }
    }

    /** Cidade em que o ponto está (botão "Minha localização"). */
    suspend fun cityAt(lat: Double, lng: Double): Result<CityRef> = withContext(Dispatchers.IO) {
        runCatching {
            val body = httpGet(
                "$NOMINATIM/reverse?lat=$lat&lon=$lng&format=jsonv2&zoom=10&polygon_geojson=1" +
                    "&polygon_threshold=0.0005&accept-language=pt-BR"
            )
            val o = JSONObject(body)
            if (o.optString("osm_type") != "relation") error("Não consegui identificar a cidade desta posição.")
            cityFrom(o)
        }
    }

    private fun cityFrom(o: JSONObject): CityRef = CityRef(
        osmId = o.getLong("osm_id"),
        name = o.optString("name").ifBlank { o.optString("display_name").substringBefore(',') },
        boundary = NeighborhoodGeo.parseGeoJsonRings(o.optJSONObject("geojson"))
    )

    /** Do cache se existir; senão baixa. */
    suspend fun load(city: CityRef): Result<CityData> = withContext(Dispatchers.IO) {
        cached(city.osmId)?.let {
            rememberCity(it.city)
            return@withContext Result.success(it)
        }
        runCatching {
            val items = NeighborhoodGeo.parseOverpass(overpass(areaQuery(city.osmId)), city)
            if (items.isEmpty()) {
                error("O OpenStreetMap não tem bairros mapeados para ${city.name}.")
            }
            val data = NeighborhoodGeo.finish(CityData(city, items))
            fileOf(city.osmId).writeText(NeighborhoodGeo.encode(data))
            memo = null
            rememberCity(city)
            data
        }
    }

    @Volatile private var memo: List<CityData>? = null

    /** Igual a [allCached], mas guardado em memória (usado a cada oferta para achar o bairro). */
    suspend fun cachedCities(): List<CityData> = memo ?: allCached().also { memo = it }

    /** Todas as cidades já baixadas (cache do aparelho, sem internet). */
    suspend fun allCached(): List<CityData> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.name.startsWith("cidade_") && f.name.endsWith(".json") }
            .orEmpty()
            .mapNotNull { readFinished(it) }
            .sortedBy { it.city.name }
    }

    /** Reabre a última cidade usada (cache; se faltar, rebusca o contorno e baixa de novo). */
    suspend fun loadLast(): Result<CityData>? {
        val (id, name) = lastCity() ?: return null
        cached(id)?.let { return Result.success(it) }
        return withContext(Dispatchers.IO) {
            runCatching {
                val body = httpGet(
                    "$NOMINATIM/lookup?osm_ids=R$id&format=jsonv2&polygon_geojson=1" +
                        "&polygon_threshold=0.0005&accept-language=pt-BR"
                )
                val arr = JSONArray(body)
                val city = if (arr.length() > 0) cityFrom(arr.getJSONObject(0)) else CityRef(id, name)
                load(city).getOrThrow()
            }
        }
    }

    /**
     * Tudo que o OSM tem de bairro/setor na cidade: limites administrativos (9–11), áreas place=suburb/
     * neighbourhood/quarter, áreas residenciais com nome e os bairros que só existem como ponto.
     */
    private fun areaQuery(osmId: Long): String {
        val places = "[\"place\"~\"^(suburb|neighbourhood|quarter)$\"][\"name\"]"
        val admin = "[\"boundary\"=\"administrative\"][\"admin_level\"~\"^(9|10|11)$\"][\"name\"]"
        val resid = "[\"landuse\"=\"residential\"][\"name\"]"
        return "[out:json][timeout:180];area(${AREA_BASE + osmId})->.c;(" +
            "rel(area.c)$admin;way(area.c)$admin;" +
            "rel(area.c)$places;way(area.c)$places;node(area.c)$places;" +
            "rel(area.c)$resid;way(area.c)$resid;" +
            ");out geom;"
    }

    private fun fileOf(osmId: Long) = File(dir, "cidade_$osmId.json")

    /** Lê do disco; se ainda não estiver "arrumado" (cache antigo), arruma UMA vez e regrava. */
    private fun readFinished(f: File): CityData? {
        val text = f.readText()
        val data = NeighborhoodGeo.decode(text) ?: return null
        if (text.contains("\"fin\":${NeighborhoodGeo.FINISH_VERSION}")) return data
        val done = NeighborhoodGeo.finish(data)
        runCatching { f.writeText(NeighborhoodGeo.encode(done)) }
        return done
    }

    private fun cached(osmId: Long): CityData? =
        fileOf(osmId).takeIf { it.exists() }?.let { readFinished(it) }

    /* ------------------------------- rede ------------------------------- */

    private fun overpass(query: String): String {
        var last: Exception? = null
        for (endpoint in OVERPASS) {
            try {
                return http(endpoint, "data=" + URLEncoder.encode(query, "UTF-8"))
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("Sem resposta do servidor de mapas.")
    }

    private fun httpGet(url: String): String = http(url, null)

    private fun http(url: String, postBody: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 175_000
            c.setRequestProperty("User-Agent", "CopilotoDriverPro/2.1 (app Android; selecao de regioes)")
            if (postBody != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(postBody.toByteArray()) }
            }
            val code = c.responseCode
            if (code !in 200..299) error("Servidor de mapas respondeu $code.")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private companion object {
        const val NOMINATIM = "https://nominatim.openstreetmap.org"
        val OVERPASS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://lz4.overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
        )
        const val AREA_BASE = 3_600_000_000L
        const val KEY_ID = "last_city_id"
        const val KEY_NAME = "last_city_name"
    }
}
