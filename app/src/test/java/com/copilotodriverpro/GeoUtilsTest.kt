package com.copilotodriverpro

import com.copilotodriverpro.models.GeoStatus
import com.copilotodriverpro.models.LatLang
import com.copilotodriverpro.models.Region
import com.copilotodriverpro.models.RegionKind
import com.copilotodriverpro.regions.GeoUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes de geometria: círculos, polígonos e checagem da oferta.
 */
class GeoUtilsTest {

    private val center = LatLang(-23.5505, -46.6333)

    private fun circle(radiusM: Double, active: Boolean = true) = Region(
        id = "1", name = "Centro", active = active,
        kind = RegionKind.CIRCLE, center = center, radiusM = radiusM
    )

    private val square = Region(
        id = "2", name = "Quadrado", kind = RegionKind.POLYGON,
        points = listOf(
            LatLang(0.0, 0.0), LatLang(0.0, 1.0),
            LatLang(1.0, 1.0), LatLang(1.0, 0.0)
        )
    )

    @Test
    fun `distancia haversine conhecida`() {
        // Centro de SP -> Guarulhos (~14.8 km)
        val g = LatLang(-23.454, -46.533)
        val d = GeoUtils.distanceMeters(center, g)
        assertTrue("distância $d fora do esperado", d >= 14_000.0 && d <= 28_000.0)
    }

    @Test
    fun `ponto dentro do circulo`() {
        assertTrue(GeoUtils.regionContains(circle(2000.0), LatLang(-23.551, -46.634)))
    }

    @Test
    fun `ponto fora do circulo`() {
        val far = LatLang(-23.60, -46.70)
        assertFalse(GeoUtils.regionContains(circle(2000.0), far))
    }

    @Test
    fun `ponto dentro do poligono`() {
        assertTrue(GeoUtils.isPointInPolygon(LatLang(0.5, 0.5), square.points))
    }

    @Test
    fun `ponto fora do poligono`() {
        assertFalse(GeoUtils.isPointInPolygon(LatLang(1.5, 0.5), square.points))
    }

    @Test
    fun `regiao inativa e ignorada`() {
        val check = GeoUtils.checkOffer(listOf(circle(2000.0, active = false)), center, null)
        assertEquals(GeoStatus.NO_REGIONS, check.pickup)
    }

    @Test
    fun `embarque dentro e destino fora`() {
        val dest = LatLang(-23.70, -46.80)
        val check = GeoUtils.checkOffer(listOf(circle(5000.0)), center, dest)
        assertEquals(GeoStatus.INSIDE, check.pickup)
        assertEquals(GeoStatus.OUTSIDE, check.destination)
        assertEquals("Centro", check.pickupMatchedRegion)
    }

    @Test
    fun `sem coordenadas e unknown, nunca fora`() {
        val check = GeoUtils.checkOffer(listOf(circle(5000.0)), null, null)
        assertEquals(GeoStatus.UNKNOWN, check.pickup)
        assertEquals(GeoStatus.UNKNOWN, check.destination)
    }

    @Test
    fun `sem regiões cadastradas`() {
        val check = GeoUtils.checkOffer(emptyList(), center, center)
        assertEquals(GeoStatus.NO_REGIONS, check.pickup)
        assertEquals(GeoStatus.NO_REGIONS, check.destination)
    }

    @Test
    fun `encontra regiao por nome`() {
        assertEquals("Centro", GeoUtils.findRegionAt(center, listOf(square, circle(3000.0))))
        assertNull(GeoUtils.findRegionAt(LatLang(-10.0, -10.0), listOf(circle(3000.0))))
        assertNotNull(GeoUtils.findRegionAt(center, listOf(circle(3000.0))))
    }
}
