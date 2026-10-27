package com.copilotodriverpro

import com.copilotodriverpro.models.LatLang
import com.copilotodriverpro.models.Region
import com.copilotodriverpro.models.RegionKind
import com.copilotodriverpro.regions.ZoneGeometry
import com.copilotodriverpro.regions.ZoneStatus
import com.copilotodriverpro.regions.ZoneTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dentro/fora da região de atuação pela posição do motorista (GPS). */
class ZoneTrackerTest {

    private val c = LatLang(-16.3281, -48.9534)
    private val region = Region("1", "Centro", true, RegionKind.CIRCLE, center = c, radiusM = 1000.0)
    private val inside = LatLang(-16.3285, -48.9538)
    private val far = LatLang(-16.40, -49.05)
    private val edge = LatLang(-16.3281 + 1010.0 / 111_320.0, -48.9534)

    @Test
    fun `sem regioes ativas nao avisa nada`() {
        val t = ZoneTracker()
        assertNull(t.update(emptyList(), inside))
        assertEquals(ZoneStatus.NO_REGIONS, t.status)
        assertNull(t.update(listOf(region.copy(active = false)), far))
        assertEquals(ZoneStatus.NO_REGIONS, t.status)
    }

    @Test
    fun `primeira leitura valida vale na hora`() {
        val t = ZoneTracker()
        val change = t.update(listOf(region), inside)
        assertNotNull(change)
        assertEquals(ZoneStatus.INSIDE, change!!.to)
        assertTrue(change.initial)
        assertEquals("Centro", change.regionName)
    }

    @Test
    fun `sair da regiao exige duas leituras seguidas`() {
        val t = ZoneTracker()
        t.update(listOf(region), inside)
        assertNull(t.update(listOf(region), far))
        assertEquals(ZoneStatus.INSIDE, t.status)
        val change = t.update(listOf(region), far)
        assertNotNull(change)
        assertEquals(ZoneStatus.OUTSIDE, change!!.to)
    }

    @Test
    fun `perto da borda nao oscila`() {
        val t = ZoneTracker()
        t.update(listOf(region), inside)
        assertNull(t.update(listOf(region), edge))
        assertNull(t.update(listOf(region), edge))
        assertEquals(ZoneStatus.INSIDE, t.status)
    }

    @Test
    fun `sem sinal de GPS e desconhecido e nunca fora`() {
        val t = ZoneTracker()
        t.update(listOf(region), inside)
        t.noFix()
        assertEquals(ZoneStatus.UNKNOWN, t.status)
        assertNull(t.update(listOf(region), null))
        assertEquals(ZoneStatus.UNKNOWN, t.status)
    }

    @Test
    fun `distancia ate a borda do poligono`() {
        val poly = Region(
            "2", "Quadra", true, RegionKind.POLYGON,
            points = listOf(LatLang(0.0, 0.0), LatLang(0.0, 0.01), LatLang(0.01, 0.01), LatLang(0.01, 0.0))
        )
        assertEquals(0.0, ZoneGeometry.distanceOutsideM(poly, LatLang(0.005, 0.005)), 0.0)
        val d = ZoneGeometry.distanceOutsideM(poly, LatLang(0.005, 0.0101))
        assertTrue("distância $d", d in 8.0..14.0)
    }
}
