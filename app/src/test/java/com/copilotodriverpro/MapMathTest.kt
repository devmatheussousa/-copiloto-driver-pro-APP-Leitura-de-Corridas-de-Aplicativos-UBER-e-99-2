package com.copilotodriverpro

import com.copilotodriverpro.maps.MapMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** Matemática do mapa próprio da tela de regiões (projeção, enquadramento, toque). */
class MapMathTest {

    @Test
    fun projecao_ida_e_volta() {
        // Goiânia
        val lat = -16.6869
        val lng = -49.2648
        assertEquals(lat, MapMath.latOf(MapMath.worldY(lat)), 1e-9)
        assertEquals(lng, MapMath.lngOf(MapMath.worldX(lng)), 1e-9)
    }

    @Test
    fun pontos_conhecidos_da_projecao() {
        assertEquals(0.5, MapMath.worldX(0.0), 1e-12)
        assertEquals(0.5, MapMath.worldY(0.0), 1e-12)
        assertEquals(0.0, MapMath.worldX(-180.0), 1e-12)
        assertEquals(1.0, MapMath.worldX(180.0), 1e-12)
        // norte fica em cima: y menor
        assertTrue(MapMath.worldY(10.0) < MapMath.worldY(-10.0))
    }

    @Test
    fun enquadramento_cabe_na_tela_e_centraliza() {
        val density = 3f
        val w = 1080
        val h = 2000
        val pad = 48 * density
        val t = MapMath.fit(-16.8, -49.4, -16.5, -49.1, w, h, density, pad)

        val spanX = MapMath.worldX(-49.1) - MapMath.worldX(-49.4)
        val spanY = MapMath.worldY(-16.8) - MapMath.worldY(-16.5) // y cresce para o sul
        val px = MapMath.worldPx(t.zoom, density)
        // cabe nas duas direções (com a folga) e encosta em pelo menos uma
        assertTrue(spanX * px <= w - 2 * pad + 1.0)
        assertTrue(spanY * px <= h - 2 * pad + 1.0)
        assertTrue(spanX * px >= w - 2 * pad - 1.0 || spanY * px >= h - 2 * pad - 1.0)
        // centro = meio da caixa
        assertEquals((MapMath.worldX(-49.4) + MapMath.worldX(-49.1)) / 2.0, t.cx, 1e-12)
        assertEquals((MapMath.worldY(-16.8) + MapMath.worldY(-16.5)) / 2.0, t.cy, 1e-12)
    }

    @Test
    fun enquadramento_respeita_limites_de_zoom() {
        val longe = MapMath.fit(-80.0, -170.0, 80.0, 170.0, 1080, 2000, 3f, 100f)
        assertEquals(MapMath.MIN_ZOOM, longe.zoom, 0f)
        val ponto = MapMath.fit(-16.7, -49.2, -16.7, -49.2, 1080, 2000, 3f, 100f)
        assertEquals(MapMath.MAX_ZOOM, ponto.zoom, 0f)
    }

    @Test
    fun escala_da_unidade_coincide_com_o_mundo() {
        // No zoom 20 e densidade 1, 1 unidade = 1 px: o mundo inteiro tem UNIT_SCALE unidades.
        assertEquals(MapMath.UNIT_SCALE, MapMath.worldPx(20f, 1f), 1e-6)
        assertEquals(1f, MapMath.unitToPx(20f, 1f), 0f)
        assertEquals(0.5f, MapMath.unitToPx(19f, 1f), 0f)
        assertEquals(3f * 2.0.pow(-6).toFloat(), MapMath.unitToPx(14f, 3f), 1e-9f)
    }

    @Test
    fun ponto_em_poligono_com_float() {
        // quadrado 0..10, recorte em "L" para testar reentrância
        val quadrado = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        assertTrue(MapMath.ringContains(quadrado, 5f, 5f))
        assertFalse(MapMath.ringContains(quadrado, 11f, 5f))
        assertFalse(MapMath.ringContains(quadrado, -1f, 5f))

        val ele = floatArrayOf(0f, 0f, 10f, 0f, 10f, 4f, 4f, 4f, 4f, 10f, 0f, 10f)
        assertTrue(MapMath.ringContains(ele, 2f, 8f))
        assertTrue(MapMath.ringContains(ele, 8f, 2f))
        assertFalse(MapMath.ringContains(ele, 8f, 8f)) // o "buraco" do L
    }

    @Test
    fun area_do_aneis_e_degenerados() {
        assertEquals(100.0, MapMath.ringArea(floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)), 1e-6)
        assertEquals(0.0, MapMath.ringArea(floatArrayOf(0f, 0f, 1f, 1f)), 0.0)
        assertFalse(MapMath.ringContains(floatArrayOf(0f, 0f, 1f, 1f), 0.5f, 0.5f))
    }

    @Test
    fun raio_em_metros_vira_unidades_coerentes() {
        // 1 grau de longitude no equador ≈ 111,32 km = UNIT_SCALE/360 unidades
        val umGrauM = MapMath.EARTH_CIRCUMFERENCE_M / 360.0
        assertEquals(MapMath.UNIT_SCALE / 360.0, MapMath.metersToUnits(umGrauM, 0.0), 1e-3)
    }
}
