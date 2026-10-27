package com.copilotodriverpro

import com.copilotodriverpro.models.LatLang
import com.copilotodriverpro.models.RideSource
import com.copilotodriverpro.models.SupportedPlatform
import com.copilotodriverpro.recognition.OfferTextExtractor
import com.copilotodriverpro.recognition.RideTextParser
import com.copilotodriverpro.regions.AddressParser
import com.copilotodriverpro.regions.CityData
import com.copilotodriverpro.regions.CityRef
import com.copilotodriverpro.regions.NeighborhoodGeo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Endereços da oferta (embarque/destino) e a conversão deles em coordenadas. */
class AddressGeocodingTest {

    // Oferta real (Uber, Senador Canedo → Goiânia): o texto vem em pares "tempo (km)" + endereço
    private val offer = """
        Priority
        Exclusivo
        R${'$'} 47,05
        R${'$'}1,59/km aprox.
        5,00 (25)
        +R${'$'} 6,70 incluído para embarque
        2 min (0.3 km)
        Rua SF-10 & Rua SF - 02, Senador Canedo, Senador Canedo
        43 minutos (29.3 km)
        Avenida Dom Emanuel, 109, Cidade Jardim, Goiânia
        Viagem longa (mais de 30 min)
        Aceitar
    """.trimIndent()

    @Test
    fun oferta_trazEnderecoDeEmbarqueEDestino() {
        val o = RideTextParser.parse(offer, RideSource.ACCESSIBILITY, SupportedPlatform.UBER)!!
        assertEquals("Rua SF-10 & Rua SF - 02, Senador Canedo, Senador Canedo", o.pickupText)
        assertEquals("Avenida Dom Emanuel, 109, Cidade Jardim, Goiânia", o.destinationText)
        assertEquals(47.05, o.fare!!, 0.001)
    }

    @Test
    fun extrator_enderecoNaMesmaLinhaEOfertaSemEndereco() {
        val (p, d) = OfferTextExtractor.extractAddresses("5 min (2.1 km) Rua A, Goiânia\n13 minutos (9.4 km) Rua B, Goiânia")
        assertEquals("Rua A, Goiânia", p)
        assertEquals("Rua B, Goiânia", d)
        val none = OfferTextExtractor.extractAddresses("R\$ 10,00\n5 min (2 km)\n10 min (4 km)\nAceitar")
        assertNull(none.first); assertNull(none.second)
    }

    @Test
    fun extrator_comParadaUsaOUltimoComoDestino() {
        val (p, d) = OfferTextExtractor.extractAddresses(
            "2 min (0.5 km)\nRua A, X\n10 minutos (3 km)\nRua Parada, X\n20 minutos (8 km)\nRua Final, X"
        )
        assertEquals("Rua A, X", p)
        assertEquals("Rua Final, X", d)
    }

    @Test
    fun parser_separaRuaNumeroBairroCidade() {
        val p = AddressParser.parse("Avenida Dom Emanuel, 109, Cidade Jardim, Goiânia")!!
        assertEquals("Avenida Dom Emanuel", p.street)
        assertEquals("109", p.number)
        assertEquals("Cidade Jardim", p.neighborhood)
        assertEquals("Goiânia", p.city)
    }

    @Test
    fun parser_cruzamentoECidadeRepetida() {
        val p = AddressParser.parse("Rua SF-10 & Rua SF - 02, Senador Canedo, Senador Canedo")!!
        assertEquals("Rua SF-10", p.street)
        assertEquals("Rua SF-02", p.extraStreet)
        assertEquals("Senador Canedo", p.city)
        assertNull(p.neighborhood)
    }

    @Test
    fun parser_ignoraUfCepEBrasil() {
        val p = AddressParser.parse("Rua 5, 20, Setor Oeste, Goiânia - GO, 74000-000, Brasil")!!
        assertEquals("Setor Oeste", p.neighborhood)
        assertEquals("Goiânia", p.city)
    }

    @Test
    fun variantes_vaoDoEspecificoAoGenerico() {
        val v = AddressParser.variants("Avenida Dom Emanuel, 109, Cidade Jardim, Goiânia")
        assertEquals("Avenida Dom Emanuel, 109, Cidade Jardim, Goiânia, Brasil", v.first())
        assertTrue(v.contains("Avenida Dom Emanuel, Goiânia, Brasil"))
        assertTrue(v.contains("Cidade Jardim, Goiânia, Brasil"))
    }

    private fun city(name: String, vararg n: Pair<String, Double>): CityData {
        val items = n.mapIndexed { i, (nm, lat) ->
            val ring = listOf(LatLang(lat, 0.0), LatLang(lat, 0.01), LatLang(lat + 0.01, 0.01), LatLang(lat + 0.01, 0.0))
            NeighborhoodGeo.build("$i", nm, listOf(ring))!!
        }
        return CityData(CityRef(name.length.toLong(), name), items)
    }

    @Test
    fun bairroDoEndereco_acharNoDispositivo_semInternet() {
        val goiania = city("Goiânia", "Cidade Jardim" to -16.70, "Setor Bueno" to -16.60)
        val outra = city("Anápolis", "Cidade Jardim" to -16.30)
        val p = AddressParser.fromNeighborhood(
            "Avenida Dom Emanuel, 109, Cidade Jardim, Goiânia", listOf(outra, goiania)
        )
        assertNotNull(p)
        assertEquals(-16.695, p!!.lat, 0.01) // o de Goiânia, não o de Anápolis
        // "setor" opcional no nome
        assertNotNull(AddressParser.fromNeighborhood("Rua X, 1, Bueno, Goiânia", listOf(goiania)))
        // cidade do endereço não baixada: não chuta outra cidade
        assertNull(AddressParser.fromNeighborhood("Rua X, 1, Cidade Jardim, Inhumas", listOf(goiania)))
    }

    @Test
    fun nominatim_leLatLon() {
        val p = AddressParser.parseNominatim("""[{"lat":"-16.7","lon":"-49.25","display_name":"x"}]""")
        assertEquals(-16.7, p!!.lat, 0.0001)
        assertNull(AddressParser.parseNominatim("[]"))
    }
}
