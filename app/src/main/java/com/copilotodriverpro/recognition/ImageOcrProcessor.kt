package com.copilotodriverpro.recognition

import android.content.Context
import kotlinx.coroutines.sync.withLock
import android.net.Uri
import com.copilotodriverpro.models.LatLang
import com.copilotodriverpro.models.RideOffer
import com.copilotodriverpro.models.RideSource
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OCR local com ML Kit (reconhecimento latino cobre "R$", "km", acentos).
 * Usado no modo manual: o usuário escolhe uma imagem/captura e o texto
 * é reconhecido no próprio aparelho.
 */
object ImageOcrProcessor {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Linhas do OCR em ORDEM VISUAL (de cima para baixo; na mesma linha, da esquerda para a direita).
     * A ordem dos blocos do ML Kit varia com o layout do card, e a leitura depende da ordem
     * embarque -> viagem.
     */
    internal fun orderedText(result: Text): String {
        val lines = result.textBlocks.flatMap { it.lines }
        if (lines.isEmpty()) return result.text
        val sorted = lines.sortedBy { it.boundingBox?.top ?: 0 }
        val rows = ArrayList<MutableList<Text.Line>>()
        for (line in sorted) {
            val box = line.boundingBox
            val row = rows.lastOrNull()
            val first = row?.firstOrNull()?.boundingBox
            val sameRow = row != null && box != null && first != null &&
                kotlin.math.abs(box.centerY() - first.centerY()) < first.height() / 2
            if (sameRow) row!!.add(line) else rows.add(mutableListOf(line))
        }
        return rows.joinToString("\n") { r ->
            r.sortedBy { it.boundingBox?.left ?: 0 }.joinToString("\n") { it.text }
        }
    }

    suspend fun recognizeText(context: Context, uri: Uri): String {
        val image = InputImage.fromFilePath(context, uri)
        return suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { result -> cont.resume(orderedText(result)) }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }
    }

    suspend fun recognizeOffer(context: Context, uri: Uri): RideOffer? {
        val text = recognizeText(context, uri)
        return RideTextParser.parse(text, RideSource.OCR_IMAGE)
    }

    /** OCR direto de um bitmap (usado pelo Print de corrida na leitura alternativa). */
    suspend fun recognizeTextFromBitmap(bitmap: android.graphics.Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        return suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { result -> cont.resume(orderedText(result)) }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }
    }
}

/**
 * Geocodificação de textos de embarque/destino. Três camadas, da mais precisa para a mais genérica:
 *  1. Geocoder do sistema (rápido, mas some em aparelhos sem serviços do Google);
 *  2. OpenStreetMap (Nominatim, pela internet) com várias formas do endereço;
 *  3. bairro do endereço nos bairros já baixados (funciona sem internet).
 * Nunca inventa: se nada achar, devolve null (o card mostra "não identificado").
 */
object GeoCoderHelper {
    private class Cached(val at: Long, val value: LatLang?)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()
    private const val MISS_TTL_MS = 90_000L
    private const val WIDE_MARGIN_M = 150_000.0
    private val osmLock = kotlinx.coroutines.sync.Mutex()
    private var lastOsmAt = 0L

    suspend fun geocode(
        context: Context,
        query: String,
        /** Regiões do motorista: a busca prefere dentro delas (e uma margem em volta). */
        regions: List<com.copilotodriverpro.models.Region> = emptyList()
    ): LatLang? {
        val key = query.trim().lowercase()
        if (key.isEmpty()) return null
        val now = System.currentTimeMillis()
        cache[key]?.let { if (it.value != null || now - it.at < MISS_TTL_MS) return it.value }
        val found = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { resolve(context, query, regions) }
        }.getOrNull()
        cache[key] = Cached(System.currentTimeMillis(), found)
        return found
    }

    private suspend fun resolve(
        context: Context,
        query: String,
        regions: List<com.copilotodriverpro.models.Region>
    ): LatLang? {
        val near = com.copilotodriverpro.regions.GeoUtils.boundsOf(regions)
        val wide = com.copilotodriverpro.regions.GeoUtils.boundsOf(regions, WIDE_MARGIN_M)
        val variants = com.copilotodriverpro.regions.AddressParser.variants(query)

        // 1) Geocoder do sistema
        if (android.location.Geocoder.isPresent()) {
            for (v in variants.take(4)) systemLookup(context, v, near, wide)?.let { return it }
        }
        // 2) OpenStreetMap
        var online = true
        for (v in variants.take(3)) {
            if (!online) break
            try { osmLookup(v, near, bounded = true)?.let { return it } } catch (e: java.io.IOException) { online = false }
        }
        for (v in variants.take(2)) {
            if (!online) break
            try {
                osmLookup(v, wide, bounded = false)
                    ?.takeIf { com.copilotodriverpro.regions.AddressParser.inside(it, wide) }?.let { return it }
            } catch (e: java.io.IOException) { online = false }
        }
        // 3) bairro conhecido (sem internet)
        val app = context.applicationContext as? com.copilotodriverpro.CopilotApp
        val cities = app?.neighborhoodRepository?.cachedCities().orEmpty()
        return com.copilotodriverpro.regions.AddressParser.fromNeighborhood(query, cities)
    }

    @Suppress("DEPRECATION")
    private fun systemLookup(
        context: Context, q: String,
        near: com.copilotodriverpro.regions.GeoUtils.Bounds?,
        wide: com.copilotodriverpro.regions.GeoUtils.Bounds?
    ): LatLang? {
        val geocoder = android.location.Geocoder(context, java.util.Locale("pt", "BR"))
        val attempts = listOf<() -> List<android.location.Address>?>(
            { if (near != null) geocoder.getFromLocationName(q, 1, near.south, near.west, near.north, near.east) else null },
            { geocoder.getFromLocationName(q, 1) }
        )
        for (a in attempts) {
            val r = runCatching { a() }.getOrNull()?.firstOrNull() ?: continue
            val p = LatLang(r.latitude, r.longitude)
            if (com.copilotodriverpro.regions.AddressParser.inside(p, wide)) return p
        }
        return null
    }

    /** Lança IOException se não houver rede; devolve null se a rede respondeu mas não achou. */
    private suspend fun osmLookup(
        q: String,
        box: com.copilotodriverpro.regions.GeoUtils.Bounds?,
        bounded: Boolean
    ): LatLang? = osmLock.withLock {
        val wait = lastOsmAt + 1_100L - System.currentTimeMillis()
        if (wait > 0) kotlinx.coroutines.delay(wait)
        try {
            val vb = box?.let { "&viewbox=${it.west},${it.north},${it.east},${it.south}" + if (bounded) "&bounded=1" else "" }.orEmpty()
            val url = "https://nominatim.openstreetmap.org/search?q=" + java.net.URLEncoder.encode(q, "UTF-8") +
                "&format=jsonv2&limit=1&countrycodes=br&accept-language=pt-BR" + vb
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            try {
                c.connectTimeout = 4_000
                c.readTimeout = 7_000
                c.setRequestProperty("User-Agent", "CopilotoDriverPro/2.2 (app Android; conferir regiao da corrida)")
                if (c.responseCode !in 200..299) throw java.io.IOException("Nominatim ${c.responseCode}")
                com.copilotodriverpro.regions.AddressParser.parseNominatim(c.inputStream.bufferedReader().use { it.readText() })
            } finally {
                c.disconnect()
            }
        } finally {
            lastOsmAt = System.currentTimeMillis()
        }
    }
}
