package com.copilotodriverpro

import com.copilotodriverpro.models.LatLang
import com.copilotodriverpro.models.Region
import com.copilotodriverpro.models.RegionKind
import com.copilotodriverpro.regions.CityData
import com.copilotodriverpro.regions.CityRef
import com.copilotodriverpro.regions.GeoUtils
import com.copilotodriverpro.regions.NeighborhoodGeo
import com.copilotodriverpro.regions.NeighborhoodRegions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Testes da leitura dos bairros (OSM) e da ligação seleção → regiões salvas. */
class NeighborhoodGeoTest {

    private fun p(lat: Double, lng: Double) = LatLang(lat, lng)

    // quadrado de lado 0.01° com canto sudoeste em (lat, lng)
    private fun squareWays(lat: Double, lng: Double): List<List<LatLang>> {
        val a = p(lat, lng); val b = p(lat, lng + 0.01)
        val c = p(lat + 0.01, lng + 0.01); val d = p(lat + 0.01, lng)
        return listOf(listOf(a, b, c), listOf(a, d, c)) // 2º trecho vem "ao contrário"
    }

    private fun relationJson(id: Long, name: String, level: Int, lat: Double, lng: Double): String {
        val ways = squareWays(lat, lng).joinToString(",") { w ->
            val g = w.joinToString(",") { "{\"lat\":${it.lat},\"lon\":${it.lng}}" }
            "{\"type\":\"way\",\"role\":\"outer\",\"geometry\":[$g]}"
        }
        return "{\"type\":\"relation\",\"id\":$id,\"tags\":{\"name\":\"$name\",\"boundary\":\"administrative\",\"admin_level\":\"$level\"}," +
            "\"members\":[$ways]}"
    }

    @Test
    fun assembleRings_juntaTrechosEmUmAnelFechado() {
        val rings = NeighborhoodGeo.assembleRings(squareWays(0.0, 0.0))
        assertEquals(1, rings.size)
        assertEquals(4, rings[0].size)
    }

    @Test
    fun assembleRings_descartaContornoQueNaoFecha() {
        val open = listOf(listOf(p(0.0, 0.0), p(0.0, 1.0), p(1.0, 1.0)))
        assertTrue(NeighborhoodGeo.assembleRings(open).isEmpty())
    }

    @Test
    fun simplify_removePontosAlinhados() {
        val line = (0..10).map { p(0.0, it * 0.0001) } + listOf(p(0.001, 0.001), p(0.001, 0.0))
        val out = NeighborhoodGeo.simplify(line, 5.0)
        assertTrue(out.size < line.size)
    }

    @Test
    fun labelPoint_ficaDentroDeUmaFormaEmU() {
        // "U": o centro geométrico cai no vão vazio
        val u = listOf(
            p(0.0, 0.0), p(0.0, 3.0), p(3.0, 3.0), p(3.0, 2.0), p(1.0, 2.0), p(1.0, 1.0), p(3.0, 1.0), p(3.0, 0.0)
        )
        val label = NeighborhoodGeo.labelPoint(listOf(u))
        assertTrue(GeoUtils.isPointInPolygon(label, u))
    }

    @Test
    fun parseOverpass_usaNivel10_eIgnoraQuemEstaForaDoMunicipio() {
        val json = "{\"elements\":[" + listOf(
            relationJson(1, "Centro", 10, 0.0, 0.0),
            relationJson(2, "Jardim", 10, 0.0, 0.02),
            relationJson(3, "Vila Nova", 10, 0.0, 0.04),
            relationJson(4, "Vizinho", 10, 5.0, 5.0),       // fora do município
            relationJson(9, "Distrito", 9, 0.0, 0.0)
        ).joinToString(",") + "]}"
        val city = CityRef(10, "Teste", NeighborhoodGeo.assembleRings(listOf(
            listOf(p(-1.0, -1.0), p(-1.0, 1.0), p(1.0, 1.0), p(1.0, -1.0), p(-1.0, -1.0))
        )))
        val out = NeighborhoodGeo.parseOverpass(json, city)
        assertEquals(listOf("Centro", "Jardim", "Vila Nova"), out.map { it.name })
        assertTrue(GeoUtils.isPointInPolygon(out[0].center, out[0].rings[0]))
    }

    @Test
    fun cache_idaEVolta() {
        val n = NeighborhoodGeo.build("7", "Bairro X", NeighborhoodGeo.assembleRings(squareWays(0.0, 0.0)))!!
        val data = CityData(CityRef(1, "Cidade"), listOf(n))
        val back = NeighborhoodGeo.decode(NeighborhoodGeo.encode(data))
        assertNotNull(back)
        assertEquals("Bairro X", back!!.items[0].name)
        assertEquals(n.rings[0].size, back.items[0].rings[0].size)
    }

    @Test
    fun selecao_salvaSemDuplicar_eMantemRegioesAntigas() {
        val a = NeighborhoodGeo.build("1", "A", NeighborhoodGeo.assembleRings(squareWays(0.0, 0.0)))!!
        val b = NeighborhoodGeo.build("2", "B", NeighborhoodGeo.assembleRings(squareWays(0.0, 0.02)))!!
        val legacy = Region("abc", "Círculo antigo", true, RegionKind.CIRCLE, p(0.0, 0.0), 500.0)

        val first = NeighborhoodRegions.applySelection(listOf(legacy), setOf("1", "2"), listOf(a, b))
        assertEquals(3, first.size)
        // salvar de novo a mesma seleção não duplica
        val again = NeighborhoodRegions.applySelection(first, setOf("1", "2"), listOf(a, b))
        assertEquals(3, again.size)
        // desmarcar B remove só B
        val onlyA = NeighborhoodRegions.applySelection(again, setOf("1"), listOf(a, b))
        assertEquals(listOf("abc", "bairro:1"), onlyA.map { it.id })
        assertEquals(setOf("1"), NeighborhoodRegions.selectedIds(onlyA))
    }

    @Test
    fun selecao_pontoDentroDoBairroSalvoContaComoDentro() {
        val a = NeighborhoodGeo.build("1", "A", NeighborhoodGeo.assembleRings(squareWays(0.0, 0.0)))!!
        val regions = NeighborhoodRegions.applySelection(emptyList(), setOf("1"), listOf(a))
        assertTrue(GeoUtils.findRegionAt(p(0.005, 0.005), regions) == "A")
        assertFalse(GeoUtils.findRegionAt(p(0.5, 0.5), regions) != null)
    }

    @Test
    fun fromSaved_reconstroiBairrosComVariosContornos() {
        val ring1 = listOf(p(0.0, 0.0), p(0.0, 1.0), p(1.0, 1.0), p(1.0, 0.0))
        val ring2 = listOf(p(5.0, 5.0), p(5.0, 6.0), p(6.0, 6.0), p(6.0, 5.0))
        val saved = listOf(
            Region("bairro:9", "Dois pedaços", true, RegionKind.POLYGON, points = ring1),
            Region("bairro:9#1", "Dois pedaços", true, RegionKind.POLYGON, points = ring2)
        )
        val out = NeighborhoodRegions.fromSaved(saved)
        assertEquals(1, out.size)
        assertEquals(2, out[0].rings.size)
    }

    /* ------------------------- todas as fontes do OSM ------------------------- */

    private fun wayJson(id: Long, name: String, tags: String, lat: Double, lng: Double, size: Double = 0.01): String {
        val pts = listOf(p(lat, lng), p(lat, lng + size), p(lat + size, lng + size), p(lat + size, lng), p(lat, lng))
        val g = pts.joinToString(",") { "{\"lat\":${it.lat},\"lon\":${it.lng}}" }
        return "{\"type\":\"way\",\"id\":$id,\"tags\":{\"name\":\"$name\",$tags},\"geometry\":[$g]}"
    }

    private fun nodeJson(id: Long, name: String, lat: Double, lng: Double) =
        "{\"type\":\"node\",\"id\":$id,\"lat\":$lat,\"lon\":$lng,\"tags\":{\"name\":\"$name\",\"place\":\"suburb\"}}"

    private fun json(vararg els: String) = "{\"elements\":[" + els.joinToString(",") + "]}"

    private val bigCity = CityRef(10, "Teste", NeighborhoodGeo.assembleRings(listOf(
        listOf(p(-1.0, -1.0), p(-1.0, 1.0), p(1.0, 1.0), p(1.0, -1.0), p(-1.0, -1.0))
    )))

    @Test
    fun parseOverpass_juntaRelacaoViaEPontoDeBairro() {
        val out = NeighborhoodGeo.parseOverpass(json(
            relationJson(1, "Setor A", 10, 0.0, 0.0),
            wayJson(2, "Setor B", "\"place\":\"suburb\"", 0.0, 0.02),
            nodeJson(3, "Setor C", 0.0, 0.05)
        ), bigCity)
        assertEquals(listOf("Setor A", "Setor B", "Setor C"), out.map { it.name })
        assertEquals(listOf(false, false, true), out.map { it.approx })
        // o ponto virou uma célula que contém o próprio ponto
        assertTrue(GeoUtils.isPointInPolygon(p(0.0, 0.05), out[2].rings[0]))
    }

    @Test
    fun parseOverpass_mesmoBairroEmDuasFontesNaoDuplica() {
        val out = NeighborhoodGeo.parseOverpass(json(
            relationJson(1, "Setor Bueno", 10, 0.0, 0.0),
            wayJson(2, "setor bueno", "\"place\":\"suburb\"", 0.001, 0.001),
            nodeJson(3, "Setor Bueno", 0.005, 0.005)
        ), bigCity)
        assertEquals(1, out.size)
        assertEquals("1", out[0].id) // fica o limite administrativo
    }

    @Test
    fun parseOverpass_distritoQueSoSomaBairrosNaoEntra_eAreaResidencialSoPreencheBuraco() {
        val out = NeighborhoodGeo.parseOverpass(json(
            wayJson(9, "Distrito Grande", "\"boundary\":\"administrative\",\"admin_level\":\"9\"", 0.0, 0.0, 0.1),
            relationJson(1, "Setor A", 10, 0.0, 0.0),
            relationJson(2, "Setor B", 10, 0.0, 0.02),
            wayJson(5, "Condominio Dentro", "\"landuse\":\"residential\"", 0.002, 0.002, 0.002), // dentro do A
            wayJson(6, "Loteamento Livre", "\"landuse\":\"residential\"", 0.0, 0.06)              // buraco
        ), bigCity)
        assertEquals(listOf("Loteamento Livre", "Setor A", "Setor B"), out.map { it.name })
    }

    @Test
    fun parseOverpass_celulasDePontosNaoSeSobrepoem() {
        val out = NeighborhoodGeo.parseOverpass(json(
            nodeJson(1, "Ponto 1", 0.0, 0.0),
            nodeJson(2, "Ponto 2", 0.0, 0.006)   // ~670 m ao lado
        ), bigCity)
        assertEquals(2, out.size)
        // o ponto de um nunca cai na célula do outro
        assertFalse(GeoUtils.isPointInPolygon(p(0.0, 0.006), out[0].rings[0]))
        assertFalse(GeoUtils.isPointInPolygon(p(0.0, 0.0), out[1].rings[0]))
        // e o ponto médio entre os dois é a fronteira: um pouco de cada lado
        assertTrue(GeoUtils.isPointInPolygon(p(0.0, 0.0025), out[0].rings[0]))
        assertTrue(GeoUtils.isPointInPolygon(p(0.0, 0.0035), out[1].rings[0]))
    }

    @Test
    fun parseOverpass_pontoForaDoMunicipioEIgnorado() {
        val out = NeighborhoodGeo.parseOverpass(json(nodeJson(1, "Longe", 5.0, 5.0)), bigCity)
        assertTrue(out.isEmpty())
    }

    @Test
    fun cache_preservaBairroAproximado() {
        val out = NeighborhoodGeo.parseOverpass(json(nodeJson(3, "Setor C", 0.0, 0.0)), bigCity)
        val back = NeighborhoodGeo.decode(NeighborhoodGeo.encode(CityData(bigCity, out)))!!
        assertTrue(back.items[0].approx)
    }

    @Test
    fun cacheAntigoV1EDescartado_paraBaixarDeNovoComACoberturaCompleta() {
        assertTrue(NeighborhoodGeo.decode("{\"v\":1,\"osmId\":1,\"name\":\"X\",\"items\":[]}") == null)
    }

    /* ------------------------- mapa leve e sem furos ------------------------- */

    private fun sq(id: String, name: String, lat: Double, lng: Double, size: Double) =
        NeighborhoodGeo.build(id, name, listOf(listOf(
            p(lat, lng), p(lat, lng + size), p(lat + size, lng + size), p(lat + size, lng)
        )))!!

    @Test
    fun finish_poeDemaisAreasQueCobreOsFuros_eNaoDuplicaAoRepetir() {
        val a = sq("1", "A", 0.0, 0.0, 0.01)
        val data = CityData(bigCity, listOf(a))
        val once = NeighborhoodGeo.finish(data)
        val rem = once.items.single { it.remainder }
        assertEquals("Demais áreas de Teste", rem.name)
        // ponto fora de qualquer bairro (um "furo") cai dentro de "Demais áreas"
        assertTrue(NeighborhoodGeo.ringsContain(rem.rings, p(0.5, 0.5)))
        val twice = NeighborhoodGeo.finish(once)
        assertEquals(once.items.size, twice.items.size)
    }

    @Test
    fun slim_somePedacoMinusculoESubdivisaoUnica() {
        val bairro = sq("10", "Bairro Grande", 0.0, 0.0, 0.05)          // ~30 km²
        val quadra = sq("w1", "Quadra Minuscula", 0.01, 0.01, 0.0005)   // ~3 mil m²
        val sub = sq("w2", "Condominio", 0.02, 0.02, 0.01)               // sub-divisão única
        val outro = sq("11", "Outro Bairro", 0.0, 0.2, 0.02)
        val out = NeighborhoodGeo.slim(listOf(bairro, quadra, sub, outro)).map { it.name }.sorted()
        assertEquals(listOf("Bairro Grande", "Outro Bairro"), out)
    }

    @Test
    fun slim_somaDeBairrosMenoresSubstituiODistrito() {
        val distrito = sq("20", "Regiao Norte", 0.0, 0.0, 0.1)
        val a = sq("21", "Setor A", 0.0, 0.0, 0.06)
        val b = sq("22", "Setor B", 0.06, 0.06, 0.04)
        val out = NeighborhoodGeo.slim(listOf(distrito, a, b)).map { it.name }.sorted()
        assertEquals(listOf("Setor A", "Setor B"), out)
    }

    @Test
    fun finish_cobreTrechosSemBairroComQuadrosSelecionaveis() {
        val a = sq("1", "Setor A", 0.0, 0.0, 0.01)
        val data = NeighborhoodGeo.finish(CityData(bigCity, listOf(a)))
        val gaps = data.items.filter { it.gap }
        assertTrue(gaps.isNotEmpty())
        // ponto ~1 km ao lado do bairro (ex.: um terminal): cai em algum quadro
        val terminal = p(0.005, 0.017)
        assertTrue(gaps.any { NeighborhoodGeo.ringsContain(it.rings, terminal) })
        assertTrue(gaps.all { it.name == "Área perto de Setor A" })
        // longe da mancha urbana não cria quadro (fica só em "Demais áreas")
        assertFalse(gaps.any { NeighborhoodGeo.ringsContain(it.rings, p(0.5, 0.5)) })
        // dentro do próprio bairro não cria quadro
        assertFalse(gaps.any { NeighborhoodGeo.ringsContain(it.rings, p(0.005, 0.005)) })
        // repetir não duplica
        assertEquals(data.items.size, NeighborhoodGeo.finish(data).items.size)
    }
}
