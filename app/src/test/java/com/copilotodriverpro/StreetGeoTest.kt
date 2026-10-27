package com.copilotodriverpro

import com.copilotodriverpro.regions.StreetGeo
import com.copilotodriverpro.regions.StreetLine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Ruas no mapa de regiões: classes, simplificação, arquivo binário e quadrantes de download. */
class StreetGeoTest {

    @Test
    fun classes_das_vias() {
        assertEquals(0, StreetGeo.classOf("motorway"))
        assertEquals(0, StreetGeo.classOf("trunk_link"))
        assertEquals(1, StreetGeo.classOf("primary"))
        assertEquals(2, StreetGeo.classOf("secondary_link"))
        assertEquals(3, StreetGeo.classOf("tertiary"))
        assertEquals(4, StreetGeo.classOf("residential"))
        assertEquals(4, StreetGeo.classOf("unclassified"))
        assertNull(StreetGeo.classOf("footway"))
        assertNull(StreetGeo.classOf("track"))
        assertNull(StreetGeo.classOf(""))
    }

    @Test
    fun arquivo_binario_ida_e_volta() {
        val lines = listOf(
            StreetLine(1L, 0, floatArrayOf(-16.1f, -49.1f, -16.2f, -49.2f)),
            StreetLine(9_876_543_210L, 4, floatArrayOf(-16.3f, -49.3f, -16.31f, -49.31f, -16.32f, -49.3f))
        )
        val out = ByteArrayOutputStream()
        StreetGeo.write(out, lines)
        val back = StreetGeo.read(ByteArrayInputStream(out.toByteArray()))
        assertNotNull(back)
        assertEquals(2, back!!.size)
        assertEquals(9_876_543_210L, back[1].id)
        assertEquals(4, back[1].cls)
        assertArrayEquals(lines[1].pts, back[1].pts, 0f)
    }

    @Test
    fun arquivo_corrompido_ou_cortado_devolve_null() {
        assertNull(StreetGeo.read(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))))
        val out = ByteArrayOutputStream()
        StreetGeo.write(out, listOf(StreetLine(1L, 2, floatArrayOf(0f, 0f, 1f, 1f))))
        val cortado = out.toByteArray().copyOf(out.size() - 5)
        assertNull(StreetGeo.read(ByteArrayInputStream(cortado)))
    }

    @Test
    fun simplificar_tira_pontos_alinhados_e_guarda_a_esquina() {
        // reta com pontos no meio + uma esquina em (0, 0.002)
        val pts = floatArrayOf(
            0f, 0f, 0f, 0.001f, 0f, 0.002f,
            0.001f, 0.002f, 0.002f, 0.002f
        )
        val s = StreetGeo.simplifyLine(pts, 4.0)
        assertEquals(3, s.size / 2) // começo, esquina, fim
        assertEquals(0.002f, s[3], 0f)
        assertEquals(0f, s[2], 0f)
    }

    @Test
    fun linhas_curtas_nao_mudam() {
        val pts = floatArrayOf(0f, 0f, 1f, 1f)
        assertArrayEquals(pts, StreetGeo.simplifyLine(pts, 4.0), 0f)
    }

    @Test
    fun quadrantes_cobrem_a_caixa_inteira() {
        val t = StreetGeo.tiles(0.0, 0.0, 0.25, 0.10, 0.12)
        assertEquals(3, t.size) // 3 linhas x 1 coluna
        assertEquals(0.0, t.minOf { it[0] }, 1e-12)
        assertEquals(0.25, t.maxOf { it[2] }, 1e-12)
        assertEquals(0.0, t.minOf { it[1] }, 1e-12)
        assertEquals(0.10, t.maxOf { it[3] }, 1e-12)
        t.forEach { assertTrue(it[2] - it[0] <= 0.12 + 1e-12 && it[3] - it[1] <= 0.12 + 1e-12) }
        // sem buraco: o norte de um é o sul do seguinte
        assertEquals(t[0][2], t[1][0], 1e-12)
        assertEquals(t[1][2], t[2][0], 1e-12)
    }

    @Test
    fun caixa_minuscula_ainda_gera_um_quadrante() {
        assertEquals(1, StreetGeo.tiles(1.0, 1.0, 1.0, 1.0, 0.12).size)
    }
}
