package com.copilotodriverpro

import com.copilotodriverpro.models.GeoStatus
import com.copilotodriverpro.models.LatLang
import com.copilotodriverpro.models.Region
import com.copilotodriverpro.models.RegionCheck
import com.copilotodriverpro.models.RegionKind
import com.copilotodriverpro.regions.GeoUtils
import com.copilotodriverpro.regions.RideRegionAdvisor
import com.copilotodriverpro.regions.RideRegionLevel
import com.copilotodriverpro.regions.ZoneStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Esta corrida me joga para fora da região?" — mensagem do card e medidas até a borda.
 */
class RideRegionAdvisorTest {

    private val center = LatLang(-16.3281, -48.9534) // Anápolis
    private val region = Region(
        id = "1", name = "Anápolis", kind = RegionKind.CIRCLE, center = center, radiusM = 5_000.0
    )

    // ~1 grau de latitude = 111 km
    private fun north(km: Double) = LatLang(center.lat + km / 111.0, center.lng)

    private fun verdict(
        check: RegionCheck?,
        driver: ZoneStatus? = ZoneStatus.INSIDE,
        hasRegions: Boolean = true,
        pickup: Boolean = true,
        dest: Boolean = true
    ) = RideRegionAdvisor.verdict(check, hasRegions, driver, pickup, dest)

    @Test
    fun `destino fora com motorista dentro diz que a corrida te leva para fora`() {
        val v = verdict(RegionCheck(GeoStatus.INSIDE, GeoStatus.OUTSIDE, destinationOutsideM = 4_200.0))!!
        assertEquals(RideRegionLevel.OUT, v.level)
        assertTrue(v.title, v.title.contains("TE LEVA PARA FORA"))
        assertTrue(v.detail!!, v.detail!!.contains("4,2 km"))
        assertTrue(v.detail!!, v.detail!!.contains("embarque dentro"))
    }

    @Test
    fun `destino fora sem saber onde o motorista esta continua avisando`() {
        val v = verdict(RegionCheck(GeoStatus.UNKNOWN, GeoStatus.OUTSIDE, destinationOutsideM = 900.0), driver = null)!!
        assertEquals(RideRegionLevel.OUT, v.level)
        assertTrue(v.title, v.title.contains("DESTINO FORA"))
        assertTrue(v.detail!!, v.detail!!.contains("900 m"))
    }

    @Test
    fun `embarque fora e destino fora`() {
        val v = verdict(
            RegionCheck(GeoStatus.OUTSIDE, GeoStatus.OUTSIDE, pickupOutsideM = 1_000.0, destinationOutsideM = 3_000.0)
        )!!
        assertEquals(RideRegionLevel.OUT, v.level)
        assertTrue(v.title, v.title.contains("EMBARQUE E DESTINO FORA"))
    }

    @Test
    fun `so embarque fora`() {
        val v = verdict(RegionCheck(GeoStatus.OUTSIDE, GeoStatus.INSIDE, pickupOutsideM = 2_500.0))!!
        assertEquals(RideRegionLevel.OUT, v.level)
        assertTrue(v.title, v.title.contains("EMBARQUE FORA"))
        assertTrue(v.detail!!, v.detail!!.contains("destino dentro"))
    }

    @Test
    fun `embarque e destino dentro`() {
        val v = verdict(
            RegionCheck(GeoStatus.INSIDE, GeoStatus.INSIDE, "Anápolis", "Anápolis")
        )!!
        assertEquals(RideRegionLevel.IN, v.level)
        assertTrue(v.title, v.title.contains("DENTRO"))
        assertTrue(v.detail!!, v.detail!!.contains("Anápolis"))
    }

    @Test
    fun `motorista fora e destino dentro traz de volta`() {
        val v = verdict(
            RegionCheck(GeoStatus.INSIDE, GeoStatus.INSIDE, "Anápolis", "Anápolis"),
            driver = ZoneStatus.OUTSIDE
        )!!
        assertEquals(RideRegionLevel.BACK_IN, v.level)
        assertTrue(v.title, v.title.contains("TRAZ DE VOLTA"))
    }

    @Test
    fun `destino nao identificado nunca vira fora`() {
        val v = verdict(RegionCheck(GeoStatus.INSIDE, GeoStatus.UNKNOWN))!!
        assertEquals(RideRegionLevel.UNKNOWN, v.level)
        val none = verdict(RegionCheck(GeoStatus.UNKNOWN, GeoStatus.UNKNOWN))!!
        assertEquals(RideRegionLevel.UNKNOWN, none.level)
    }

    @Test
    fun `enquanto converte enderecos mostra verificando`() {
        val v = verdict(RegionCheck(resolving = true))!!
        assertEquals(RideRegionLevel.CHECKING, v.level)
    }

    @Test
    fun `sem regiao ativa ou sem check nao mostra nada`() {
        assertNull(verdict(RegionCheck(GeoStatus.OUTSIDE, GeoStatus.OUTSIDE), hasRegions = false))
        assertNull(verdict(null))
    }

    @Test
    fun `verificacao de destino desligada ignora destino fora`() {
        val check = RegionCheck(GeoStatus.INSIDE, GeoStatus.OUTSIDE, destinationOutsideM = 5_000.0)
        val v = verdict(check, dest = false)!!
        assertEquals(RideRegionLevel.IN, v.level)
        assertNull(verdict(check, pickup = false, dest = false))
    }

    @Test
    fun `formatacao de distancia`() {
        assertEquals("—", RideRegionAdvisor.km(null))
        assertEquals("850 m", RideRegionAdvisor.km(850.0))
        assertEquals("1,5 km", RideRegionAdvisor.km(1_500.0))
        assertEquals("12,0 km", RideRegionAdvisor.km(12_000.0))
    }

    // ---------- GeoUtils: distância até a borda ----------

    @Test
    fun `checkOffer informa quantos metros o destino esta alem da borda`() {
        // destino a ~9 km do centro; raio 5 km => ~4 km além da borda
        val check = GeoUtils.checkOffer(listOf(region), center, north(9.0))
        assertEquals(GeoStatus.INSIDE, check.pickup)
        assertEquals(GeoStatus.OUTSIDE, check.destination)
        assertNull(check.pickupOutsideM)
        val d = check.destinationOutsideM
        assertNotNull(d)
        assertTrue("distância $d", d!! in 3_800.0..4_200.0)
    }

    @Test
    fun `dentro da regiao nao tem distancia alem da borda`() {
        val check = GeoUtils.checkOffer(listOf(region), north(1.0), north(2.0))
        assertNull(check.pickupOutsideM)
        assertNull(check.destinationOutsideM)
    }

    @Test
    fun `fluxo completo corrida que sai da regiao`() {
        val check = GeoUtils.checkOffer(listOf(region), north(1.0), north(8.0))
        val v = RideRegionAdvisor.verdict(check, true, ZoneStatus.INSIDE)!!
        assertEquals(RideRegionLevel.OUT, v.level)
    }

    // ---------- GeoUtils: caixa de busca do Geocoder ----------

    @Test
    fun `caixa envolve a regiao com folga`() {
        val b = GeoUtils.boundsOf(listOf(region), marginM = 10_000.0)!!
        assertTrue(b.south < center.lat && b.north > center.lat)
        assertTrue(b.west < center.lng && b.east > center.lng)
        // raio 5 km + folga 10 km = ~15 km para cada lado (~0,135 grau de latitude)
        assertTrue("altura ${b.north - b.south}", (b.north - b.south) in 0.25..0.29)
    }

    @Test
    fun `caixa ignora regioes inativas e sem regioes devolve null`() {
        assertNull(GeoUtils.boundsOf(emptyList()))
        assertNull(GeoUtils.boundsOf(listOf(region.copy(active = false))))
    }

    @Test
    fun `caixa de poligono usa os vertices`() {
        val poly = Region(
            id = "p", name = "Poli", kind = RegionKind.POLYGON,
            points = listOf(LatLang(-16.0, -49.0), LatLang(-16.0, -48.9), LatLang(-16.1, -48.9), LatLang(-16.1, -49.0))
        )
        val b = GeoUtils.boundsOf(listOf(poly), marginM = 0.0)!!
        assertEquals(-16.1, b.south, 1e-6)
        assertEquals(-16.0, b.north, 1e-6)
        assertEquals(-49.0, b.west, 1e-6)
        assertEquals(-48.9, b.east, 1e-6)
    }
}
