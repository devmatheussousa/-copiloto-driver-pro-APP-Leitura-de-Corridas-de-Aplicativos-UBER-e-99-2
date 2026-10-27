package com.copilotodriverpro.regions

import com.copilotodriverpro.models.LatLang
import com.copilotodriverpro.models.Region
import com.copilotodriverpro.models.RegionKind
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Um bairro/setor/distrito com limite geográfico REAL (vem do OpenStreetMap).
 * [id] é o id da relação OSM; [rings] são os contornos externos (um bairro pode ter mais de um).
 */
data class Neighborhood(
    val id: String,
    val name: String,
    val rings: List<List<LatLang>>,
    val center: LatLang,
    val bounds: GeoUtils.Bounds
) {
    /**
     * true = o OpenStreetMap só tem o PONTO deste bairro (sem contorno). O limite desenhado é uma
     * célula aproximada ao redor do ponto (ids "n<id>"). Contornos reais: relação ("123") ou via ("w123").
     */
    val approx: Boolean get() = id.startsWith("n")

    /** true = "Demais áreas de <cidade>": o contorno do município, que cobre os furos entre os bairros. */
    val remainder: Boolean get() = id.startsWith("c")

    /** true = quadro de ~700 m que cobre um trecho SEM bairro mapeado (comércio, terminal, escola, empresa…). */
    val gap: Boolean get() = id.startsWith("g")
}

/** Cidade escolhida pelo motorista (relação administrativa do OSM) com o contorno do município. */
data class CityRef(
    val osmId: Long,
    val name: String,
    val boundary: List<List<LatLang>> = emptyList()
)

data class CityData(val city: CityRef, val items: List<Neighborhood>)

/** Geometria e leitura dos dados de bairros. Sem Android — testável em JVM. */
object NeighborhoodGeo {

    private const val M_PER_DEG = 111_320.0

    /* ------------------------------ anéis ------------------------------ */

    /**
     * Junta os trechos (ways) de uma relação em anéis fechados.
     * Trechos que não fecham (dado incompleto no OSM) são descartados — nunca "inventamos" borda.
     */
    fun assembleRings(ways: List<List<LatLang>>): List<List<LatLang>> {
        val remaining = ways.filter { it.size >= 2 }.map { it.toMutableList() }.toMutableList()
        val rings = mutableListOf<List<LatLang>>()
        while (remaining.isNotEmpty()) {
            val cur = remaining.removeAt(0)
            var progressed = true
            while (!isClosed(cur) && progressed) {
                progressed = false
                var i = 0
                while (i < remaining.size) {
                    val w = remaining[i]
                    var joined = true
                    if (w.first() == cur.last()) {
                        cur.addAll(w.drop(1))
                    } else if (w.last() == cur.last()) {
                        cur.addAll(w.reversed().drop(1))
                    } else if (w.last() == cur.first()) {
                        cur.addAll(0, w.dropLast(1))
                    } else if (w.first() == cur.first()) {
                        cur.addAll(0, w.reversed().dropLast(1))
                    } else {
                        joined = false
                    }
                    if (joined) {
                        remaining.removeAt(i)
                        progressed = true
                        break
                    }
                    i++
                }
            }
            if (isClosed(cur)) rings += cur.dropLast(1)
        }
        return rings
    }

    private fun isClosed(l: List<LatLang>) = l.size >= 4 && l.first() == l.last()

    /** Douglas-Peucker (iterativo). Reduz pontos mantendo a forma; [toleranceM] em metros. */
    fun simplify(points: List<LatLang>, toleranceM: Double): List<LatLang> {
        if (points.size <= 4 || toleranceM <= 0.0) return points
        val lat0 = points.first().lat
        val kx = M_PER_DEG * cos(Math.toRadians(lat0))
        val xs = DoubleArray(points.size) { points[it].lng * kx }
        val ys = DoubleArray(points.size) { points[it].lat * M_PER_DEG }
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, points.size - 1))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var maxD = 0.0
            var idx = -1
            for (i in a + 1 until b) {
                val d = distToSeg(xs[i], ys[i], xs[a], ys[a], xs[b], ys[b])
                if (d > maxD) {
                    maxD = d
                    idx = i
                }
            }
            if (idx >= 0 && maxD > toleranceM) {
                keep[idx] = true
                stack.addLast(intArrayOf(a, idx))
                stack.addLast(intArrayOf(idx, b))
            }
        }
        val out = points.filterIndexed { i, _ -> keep[i] }
        return if (out.size >= 3) out else points
    }

    private fun distToSeg(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /* ------------------------- pontos e limites ------------------------- */

    fun boundsOfRings(rings: List<List<LatLang>>): GeoUtils.Bounds {
        var s = Double.MAX_VALUE; var n = -Double.MAX_VALUE
        var w = Double.MAX_VALUE; var e = -Double.MAX_VALUE
        for (r in rings) for (p in r) {
            s = min(s, p.lat); n = max(n, p.lat)
            w = min(w, p.lng); e = max(e, p.lng)
        }
        return GeoUtils.Bounds(s, w, n, e)
    }

    fun ringsContain(rings: List<List<LatLang>>, p: LatLang): Boolean =
        rings.any { GeoUtils.isPointInPolygon(p, it) }

    /** Área (m², plana local) de um anel — só para achar o maior. */
    private fun areaM2(ring: List<LatLang>): Double {
        if (ring.size < 3) return 0.0
        val kx = M_PER_DEG * cos(Math.toRadians(ring[0].lat))
        var sum = 0.0
        var j = ring.size - 1
        for (i in ring.indices) {
            val xi = ring[i].lng * kx; val yi = ring[i].lat * M_PER_DEG
            val xj = ring[j].lng * kx; val yj = ring[j].lat * M_PER_DEG
            sum += (xj + xi) * (yj - yi)
            j = i
        }
        return kotlin.math.abs(sum / 2.0)
    }

    /**
     * Ponto para o nome do bairro: garantidamente DENTRO do maior anel.
     * Usa a linha horizontal que passa pelo centro e pega o meio do trecho mais largo.
     */
    fun labelPoint(rings: List<List<LatLang>>): LatLang {
        val ring = rings.maxByOrNull { areaM2(it) } ?: return LatLang(0.0, 0.0)
        val b = boundsOfRings(listOf(ring))
        val midLat = (b.south + b.north) / 2.0
        var best: LatLang? = null
        var bestW = -1.0
        // algumas linhas perto do meio, para não cair numa reentrância
        for (f in listOf(0.5, 0.4, 0.6, 0.3, 0.7)) {
            val lat = b.south + (b.north - b.south) * f
            val xs = mutableListOf<Double>()
            var j = ring.size - 1
            for (i in ring.indices) {
                val pi = ring[i]; val pj = ring[j]
                if ((pi.lat > lat) != (pj.lat > lat)) {
                    xs += (pj.lng - pi.lng) * (lat - pi.lat) / (pj.lat - pi.lat) + pi.lng
                }
                j = i
            }
            xs.sort()
            var k = 0
            while (k + 1 < xs.size) {
                val w = xs[k + 1] - xs[k]
                if (w > bestW) {
                    bestW = w
                    best = LatLang(lat, (xs[k] + xs[k + 1]) / 2.0)
                }
                k += 2
            }
            if (best != null && f == 0.5) break
        }
        return best ?: LatLang(midLat, (b.west + b.east) / 2.0)
    }

    fun build(id: String, name: String, rings: List<List<LatLang>>): Neighborhood? {
        val valid = rings.filter { it.size >= 3 }
        if (valid.isEmpty()) return null
        return Neighborhood(id, name, valid, labelPoint(valid), boundsOfRings(valid))
    }

    /* ------------------------------ leitura ------------------------------ */

    private val PLACES = setOf("suburb", "neighbourhood", "quarter")
    private const val CELL_RADIUS_M = 700.0
    private const val DUP_DISTANCE_M = 600.0

    private enum class Kind { NEIGHBORHOOD, DISTRICT, LAND }
    private class Cand(val n: Neighborhood, val kind: Kind, val rank: Int, val key: String)
    private class NodePt(val id: String, val name: String, val p: LatLang, val key: String)

    /** "Setor Bueno" / "setor  bueno" → mesma chave (sem acento, caixa ou pontuação). */
    fun normName(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), "")

    private fun covers(n: Neighborhood, p: LatLang): Boolean {
        val b = n.bounds
        return p.lat >= b.south && p.lat <= b.north && p.lng >= b.west && p.lng <= b.east &&
            ringsContain(n.rings, p)
    }

    private fun distM(a: LatLang, b: LatLang): Double {
        val kx = M_PER_DEG * cos(Math.toRadians((a.lat + b.lat) / 2.0))
        return hypot((a.lng - b.lng) * kx, (a.lat - b.lat) * M_PER_DEG)
    }

    /** Mesmo nome e (muito perto ou um contém o centro do outro) = o mesmo bairro mapeado duas vezes. */
    private fun isDuplicate(c: Cand, kept: List<Cand>): Boolean =
        kept.any { k ->
            k.key == c.key &&
                (distM(k.n.center, c.n.center) < DUP_DISTANCE_M || covers(k.n, c.n.center) || covers(c.n, k.n.center))
        }

    private fun readGeometry(g: JSONArray): List<LatLang> {
        val pts = ArrayList<LatLang>(g.length())
        for (k in 0 until g.length()) {
            val p = g.optJSONObject(k) ?: continue
            pts += LatLang(p.getDouble("lat"), p.getDouble("lon"))
        }
        return pts
    }

    private fun relationRings(el: JSONObject): List<List<LatLang>> {
        val members = el.optJSONArray("members") ?: return emptyList()
        val ways = mutableListOf<List<LatLang>>()
        for (m in 0 until members.length()) {
            val mo = members.optJSONObject(m) ?: continue
            if (mo.optString("type") != "way") continue
            val role = mo.optString("role")
            if (role != "outer" && role != "") continue
            val g = mo.optJSONArray("geometry") ?: continue
            ways += readGeometry(g)
        }
        return assembleRings(ways)
    }

    private fun wayRings(el: JSONObject): List<List<LatLang>> {
        val g = el.optJSONArray("geometry") ?: return emptyList()
        val pts = readGeometry(g)
        return if (pts.size >= 4 && pts.first() == pts.last()) listOf(pts.dropLast(1)) else emptyList()
    }

    /**
     * Resposta do Overpass (`out geom`) → bairros/setores, juntando TODAS as fontes do OpenStreetMap:
     *  1. limites administrativos nível 10/11 e áreas place=suburb/neighbourhood/quarter (relação ou via fechada);
     *  2. distritos (nível 9) só se não forem apenas a soma de bairros já achados;
     *  3. áreas residenciais com nome (loteamentos/condomínios) só onde ainda não há bairro;
     *  4. bairros que no OSM são só um PONTO: viram uma célula aproximada (id "n…", [Neighborhood.approx]).
     * Se [city] tiver contorno, descarta quem está fora do município.
     */
    fun parseOverpass(json: String, city: CityRef?, tolM: Double = 8.0): List<Neighborhood> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val boundary = city?.boundary.orEmpty()
        val strong = mutableListOf<Cand>()
        val districts = mutableListOf<Cand>()
        val land = mutableListOf<Cand>()
        val points = mutableListOf<NodePt>()
        val seen = HashSet<String>()

        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            val type = el.optString("type")
            val tags = el.optJSONObject("tags") ?: continue
            val name = tags.optString("name").trim()
            if (name.isEmpty()) continue
            val rawId = el.optLong("id")
            val uid = type.take(1) + rawId
            if (!seen.add(uid)) continue

            val level = tags.optString("admin_level")
            val isAdmin = tags.optString("boundary") == "administrative" && level in setOf("9", "10", "11")
            val isPlace = tags.optString("place") in PLACES
            val isLand = tags.optString("landuse") == "residential"
            if (!isAdmin && !isPlace && !isLand) continue

            if (type == "node") {
                if (!isPlace) continue
                val lat = el.optDouble("lat", Double.NaN)
                val lon = el.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
                val p = LatLang(lat, lon)
                if (boundary.isNotEmpty() && !ringsContain(boundary, p)) continue
                points += NodePt("n$rawId", name, p, normName(name))
                continue
            }

            val id = if (type == "relation") rawId.toString() else "w$rawId"
            val rings = when (type) {
                "relation" -> relationRings(el)
                "way" -> wayRings(el)
                else -> emptyList()
            }.map { simplify(it, tolM) }
            val n = build(id, name, rings) ?: continue
            if (boundary.isNotEmpty() && !ringsContain(boundary, n.center)) continue

            val key = normName(name)
            when {
                isAdmin && level == "9" && !isPlace -> districts += Cand(n, Kind.DISTRICT, 2, key)
                isAdmin -> strong += Cand(n, Kind.NEIGHBORHOOD, 0, key)
                isPlace -> strong += Cand(n, Kind.NEIGHBORHOOD, 1, key)
                else -> land += Cand(n, Kind.LAND, 3, key)
            }
        }

        // 1) bairros "de verdade" (limite administrativo primeiro; o mesmo bairro repetido some)
        val kept = mutableListOf<Cand>()
        for (c in strong.sortedBy { it.rank }) if (!isDuplicate(c, kept)) kept += c
        val strongCenters = kept.map { it.n.center }

        // 2) distritos e 3) áreas residenciais: só preenchem buracos (não cobrem 2+ bairros nem repetem)
        fun fillGap(c: Cand) {
            if (strongCenters.count { covers(c.n, it) } >= 2) return
            if (kept.any { covers(it.n, c.n.center) }) return
            if (isDuplicate(c, kept)) return
            kept += c
        }
        districts.forEach { fillGap(it) }
        land.sortedByDescending { c -> c.n.rings.sumOf { areaM2(it) } }.forEach { fillGap(it) }

        // 4) bairros que só existem como ponto: célula aproximada onde ainda não há nada
        val lonePoints = points.filter { pt ->
            kept.none { covers(it.n, pt.p) } &&
                kept.none { it.key == pt.key && distM(it.n.center, pt.p) < DUP_DISTANCE_M }
        }.distinctBy { Triple(it.key, (it.p.lat * 1000).toInt(), (it.p.lng * 1000).toInt()) }
        val cells = approxCells(lonePoints)

        return (kept.map { it.n } + cells).sortedBy { it.name }
    }

    /**
     * Para cada ponto, um polígono: círculo de [CELL_RADIUS_M] recortado pela mediatriz de cada vizinho
     * (as células não se sobrepõem entre si). É APROXIMADO — só existe porque o OSM não tem o contorno.
     */
    private fun approxCells(pts: List<NodePt>): List<Neighborhood> {
        val out = ArrayList<Neighborhood>(pts.size)
        val r = CELL_RADIUS_M
        for (a in pts) {
            val kx = M_PER_DEG * cos(Math.toRadians(a.p.lat))
            var poly: List<DoubleArray> = (0 until 24).map { k ->
                val ang = 2.0 * Math.PI * k / 24.0
                doubleArrayOf(r * cos(ang), r * kotlin.math.sin(ang))
            }
            for (b in pts) {
                if (b === a) continue
                val dx = (b.p.lng - a.p.lng) * kx
                val dy = (b.p.lat - a.p.lat) * M_PER_DEG
                val d2 = dx * dx + dy * dy
                if (d2 > 4 * r * r || d2 < 1.0) continue
                poly = clipHalfPlane(poly, dx, dy, d2 / 2.0)
                if (poly.size < 3) break
            }
            if (poly.size < 3) continue
            val ring = poly.map { LatLang(a.p.lat + it[1] / M_PER_DEG, a.p.lng + it[0] / kx) }
            out += Neighborhood(a.id, a.name, listOf(ring), a.p, boundsOfRings(listOf(ring)))
        }
        return out
    }

    /** Sutherland–Hodgman contra o semiplano x·nx + y·ny <= c. */
    private fun clipHalfPlane(poly: List<DoubleArray>, nx: Double, ny: Double, c: Double): List<DoubleArray> {
        val res = ArrayList<DoubleArray>(poly.size + 1)
        for (i in poly.indices) {
            val cur = poly[i]
            val prev = poly[(i + poly.size - 1) % poly.size]
            val dc = cur[0] * nx + cur[1] * ny - c
            val dp = prev[0] * nx + prev[1] * ny - c
            if (dc <= 0.0) {
                if (dp > 0.0) res += cross(prev, cur, dp, dc)
                res += cur
            } else if (dp <= 0.0) {
                res += cross(prev, cur, dp, dc)
            }
        }
        return res
    }

    private fun cross(a: DoubleArray, b: DoubleArray, da: Double, db: Double): DoubleArray {
        val t = da / (da - db)
        return doubleArrayOf(a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]))
    }

    /** `geojson` do Nominatim (Polygon/MultiPolygon, [lng,lat]) → anéis externos do município. */
    fun parseGeoJsonRings(geo: JSONObject?): List<List<LatLang>> {
        if (geo == null) return emptyList()
        fun ringOf(a: JSONArray): List<LatLang> =
            (0 until a.length()).mapNotNull { i ->
                val c = a.optJSONArray(i) ?: return@mapNotNull null
                LatLang(c.getDouble(1), c.getDouble(0))
            }
        return runCatching {
            when (geo.optString("type")) {
                "Polygon" -> listOf(ringOf(geo.getJSONArray("coordinates").getJSONArray(0)))
                "MultiPolygon" -> {
                    val polys = geo.getJSONArray("coordinates")
                    (0 until polys.length()).map { ringOf(polys.getJSONArray(it).getJSONArray(0)) }
                }
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    /* ----------------------- mapa leve e sem furos ----------------------- */

    private const val MIN_PIECE_M2 = 20_000.0
    private const val LIGHT_TOL_M = 15.0

    fun areaOf(n: Neighborhood): Double = n.rings.sumOf { areaM2(it) }

    /** Contorno do município como uma região só: quem toca num furo entre bairros seleciona isto. */
    fun cityRemainder(city: CityRef): Neighborhood? {
        if (city.boundary.isEmpty()) return null
        return build("c${city.osmId}", "Demais áreas de ${city.name}", city.boundary.map { simplify(it, 30.0) })
    }

    /**
     * Deixa o mapa leve e o bairro "inteiro":
     *  - some pedaços minúsculos (< 20 mil m², tipicamente quadras/condomínios mapeados como via);
     *  - uma subdivisão única dentro de um bairro maior some (fica o bairro todo);
     *  - um bairro maior que é só a soma de vários menores some (ficam os menores);
     *  - vários pedaços pequenos dentro de um bairro grande somem (fica o bairro todo).
     */
    fun slim(items: List<Neighborhood>): List<Neighborhood> {
        val sized = items.map { it to areaOf(it) }
            .filter { (n, a) -> n.approx || !n.id.startsWith("w") || a >= MIN_PIECE_M2 }
            .sortedByDescending { it.second }
        val drop = HashSet<String>()
        for ((b, ba) in sized) {
            if (b.id in drop || b.approx) continue
            val kids = sized.filter { (c, ca) ->
                c.id != b.id && c.id !in drop && !c.approx && ca < ba && covers(b, c.center)
            }
            if (kids.isEmpty()) continue
            val kidsArea = kids.sumOf { it.second }
            if (kids.size >= 2 && kidsArea >= 0.5 * ba) drop += b.id
            else kids.forEach { drop += it.first.id }
        }
        return sized.map { it.first }.filter { it.id !in drop }
    }

    const val FINISH_VERSION = 2
    private const val GRID_M = 700.0
    private const val URBAN_REACH_M = 1_500.0

    /**
     * Cobre os trechos da cidade que NENHUM bairro mapeado alcança (centros comerciais, terminais, escolas,
     * empresas, áreas verdes…) com quadros de ~700 m, cada um selecionável e com o nome do bairro mais
     * próximo ("Área perto de …"). Só perto da mancha urbana (até 1,5 km de algum bairro): zona rural
     * continua só em "Demais áreas". Ids estáveis ("g<cidade>_<i>_<j>"): salvar de novo não duplica.
     */
    fun gapCells(city: CityRef, kept: List<Neighborhood>): List<Neighborhood> {
        if (city.boundary.isEmpty() || kept.isEmpty()) return emptyList()
        val b = boundsOfRings(city.boundary)
        val lat0 = (b.south + b.north) / 2.0
        val dLat = GRID_M / M_PER_DEG
        val kx = M_PER_DEG * cos(Math.toRadians(lat0))
        val dLng = GRID_M / kx
        val reach2 = URBAN_REACH_M * URBAN_REACH_M
        val near = kept.filter { !it.remainder && !it.gap }
        val out = ArrayList<Neighborhood>()
        val i0 = kotlin.math.floor(b.south / dLat).toLong()
        val i1 = kotlin.math.floor(b.north / dLat).toLong()
        val j0 = kotlin.math.floor(b.west / dLng).toLong()
        val j1 = kotlin.math.floor(b.east / dLng).toLong()
        for (i in i0..i1) for (j in j0..j1) {
            val s = i * dLat
            val w = j * dLng
            val c = LatLang(s + dLat / 2.0, w + dLng / 2.0)
            var best: Neighborhood? = null
            var bestD2 = Double.MAX_VALUE
            for (n in near) {
                val dx = (c.lng - n.center.lng) * kx
                val dy = (c.lat - n.center.lat) * M_PER_DEG
                val d2 = dx * dx + dy * dy
                if (d2 < bestD2) { bestD2 = d2; best = n }
            }
            if (best == null || bestD2 > reach2) continue
            if (!ringsContain(city.boundary, c)) continue
            if (near.any { covers(it, c) }) continue
            val ring = listOf(LatLang(s, w), LatLang(s, w + dLng), LatLang(s + dLat, w + dLng), LatLang(s + dLat, w))
            build("g${city.osmId}_${i}_$j", "Área perto de ${best.name}", listOf(ring))?.let { out += it }
        }
        return out
    }

    /** Arruma os dados de uma cidade (novos ou do cache): simplifica, limpa pedaços e põe "Demais áreas". */
    fun finish(data: CityData): CityData {
        val base = data.items.filter { !it.remainder && !it.gap }.map { n ->
            if (n.approx) n else build(n.id, n.name, n.rings.map { simplify(it, LIGHT_TOL_M) }) ?: n
        }
        val kept = slim(base)
        val items = kept + gapCells(data.city, kept) + listOfNotNull(cityRemainder(data.city))
        return CityData(data.city, items.sortedBy { it.name })
    }

    /* ----------------------------- cache em disco ----------------------------- */

    fun encode(data: CityData): String {
        fun ringJson(r: List<LatLang>): JSONArray {
            val a = JSONArray()
            r.forEach { a.put(JSONArray().put(it.lat).put(it.lng)) }
            return a
        }
        val root = JSONObject()
        root.put("v", 2)
        root.put("fin", FINISH_VERSION) // dados já arrumados por finish(): não refazer a cada abertura
        root.put("osmId", data.city.osmId)
        root.put("name", data.city.name)
        root.put("boundary", JSONArray().also { arr -> data.city.boundary.forEach { arr.put(ringJson(it)) } })
        val items = JSONArray()
        data.items.forEach { n ->
            items.put(
                JSONObject()
                    .put("id", n.id)
                    .put("name", n.name)
                    .put("rings", JSONArray().also { arr -> n.rings.forEach { arr.put(ringJson(it)) } })
            )
        }
        root.put("items", items)
        return root.toString()
    }

    fun decode(json: String): CityData? = runCatching {
        fun ringOf(a: JSONArray): List<LatLang> =
            (0 until a.length()).map { i -> a.getJSONArray(i).let { LatLang(it.getDouble(0), it.getDouble(1)) } }
        fun ringsOf(a: JSONArray?): List<List<LatLang>> =
            if (a == null) emptyList() else (0 until a.length()).map { ringOf(a.getJSONArray(it)) }
        val o = JSONObject(json)
        if (o.optInt("v") != 2) return null
        val city = CityRef(o.getLong("osmId"), o.getString("name"), ringsOf(o.optJSONArray("boundary")))
        val arr = o.getJSONArray("items")
        val items = (0 until arr.length()).mapNotNull { i ->
            val o2 = arr.getJSONObject(i)
            build(o2.getString("id"), o2.getString("name"), ringsOf(o2.getJSONArray("rings")))
        }
        CityData(city, items)
    }.getOrNull()
}

/** Liga a seleção de bairros às [Region] que o resto do app já usa (aviso dentro/fora, corridas). */
object NeighborhoodRegions {

    const val PREFIX = "bairro:"

    fun regionId(neighborhoodId: String, ringIndex: Int): String =
        if (ringIndex == 0) "$PREFIX$neighborhoodId" else "$PREFIX$neighborhoodId#$ringIndex"

    /** Id do bairro a que a região pertence; null se for uma região antiga (círculo/polígono livre). */
    fun neighborhoodIdOf(regionId: String): String? =
        if (regionId.startsWith(PREFIX)) regionId.removePrefix(PREFIX).substringBefore('#') else null

    fun selectedIds(regions: List<Region>): Set<String> =
        regions.filter { it.active }.mapNotNull { neighborhoodIdOf(it.id) }.toSet()

    fun toRegions(n: Neighborhood): List<Region> =
        n.rings.mapIndexed { i, ring ->
            Region(
                id = regionId(n.id, i),
                name = n.name,
                active = true,
                kind = RegionKind.POLYGON,
                points = ring
            )
        }

    /** Reconstrói bairros a partir do que já foi salvo (para mostrar a seleção de outra cidade). */
    fun fromSaved(regions: List<Region>): List<Neighborhood> =
        regions.filter { it.kind == RegionKind.POLYGON && neighborhoodIdOf(it.id) != null && it.points.size >= 3 }
            .groupBy { neighborhoodIdOf(it.id)!! }
            .mapNotNull { (id, parts) ->
                NeighborhoodGeo.build(id, parts.first().name, parts.sortedBy { it.id }.map { it.points })
            }

    /**
     * Lista final a gravar: regiões antigas (intactas) + um conjunto de regiões por bairro selecionado.
     * Ids determinísticos ("bairro:<id>") → salvar de novo nunca duplica.
     */
    fun applySelection(existing: List<Region>, selected: Set<String>, available: List<Neighborhood>): List<Region> {
        val legacy = existing.filter { neighborhoodIdOf(it.id) == null }
        val byId = available.associateBy { it.id }
        val fresh = selected.mapNotNull { byId[it] }.sortedBy { it.name }.flatMap { toRegions(it) }
        return (legacy + fresh).distinctBy { it.id }
    }
}
