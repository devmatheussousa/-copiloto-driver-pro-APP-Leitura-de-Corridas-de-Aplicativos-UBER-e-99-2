package com.copilotodriverpro

import com.copilotodriverpro.analyzer.RideAnalyzer
import com.copilotodriverpro.analyzer.TrafficLight
import com.copilotodriverpro.models.StopVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Testes unitários do semáforo financeiro (JVM, sem dispositivo).
 */
class RideAnalyzerTest {

    private fun eval(
        fare: Double?,
        tripKm: Double? = null,
        toPickup: Double? = null,
        min: Double = 1.50,
        max: Double = 2.00,
        hourMin: Double = 35.0,
        hourMax: Double = 40.0,
        durationMin: Double? = null,
        includePickup: Boolean = false
    ) = RideAnalyzer.evaluate(
        fare = fare,
        distanceToPickupKm = toPickup,
        tripDistanceKm = tripKm,
        durationMin = durationMin,
        passengerRating = null,
        stopVerdict = StopVerdict.NONE,
        perKmMin = min, perKmMax = max,
        perHourMin = hourMin, perHourMax = hourMax,
        includePickupInKm = includePickup,
        minRating = 4.8
    )

    @Test
    fun `R por km verde acima do maximo`() {
        val r = eval(fare = 30.0, tripKm = 10.0)   // 3,00 R$/km
        assertEquals(3.0, r.perKm.value!!, 0.001)
        assertEquals(TrafficLight.GREEN, r.perKm.light)
    }

    @Test
    fun `R por km amarelo entre min e max`() {
        val r = eval(fare = 17.5, tripKm = 10.0)   // 1,75
        assertEquals(TrafficLight.YELLOW, r.perKm.light)
    }

    @Test
    fun `R por km vermelho abaixo do minimo`() {
        val r = eval(fare = 10.0, tripKm = 10.0)   // 1,00
        assertEquals(TrafficLight.RED, r.perKm.light)
    }

    @Test
    fun `R por hora calculado pelo tempo em horas`() {
        val r = eval(fare = 40.0, tripKm = 10.0, durationMin = 60.0)  // 40/h -> verde
        assertEquals(40.0, r.perHour.value!!, 0.001)
        assertEquals(TrafficLight.GREEN, r.perHour.light)
    }

    @Test
    fun `R por hora amarelo`() {
        val r = eval(fare = 37.5, durationMin = 60.0)
        assertEquals(TrafficLight.YELLOW, r.perHour.light)
    }

    @Test
    fun `sem distancia mostra travessao e nada calculado`() {
        val r = eval(fare = 20.0, durationMin = null)
        assertNull(r.perKm.value)
        assertNull(r.perHour.value)
        assertEquals(TrafficLight.NONE, r.perKm.light)
        assertEquals(TrafficLight.NONE, r.perHour.light)
    }

    @Test
    fun `sem tempo mostra travessao`() {
        val r = eval(fare = 20.0, tripKm = 5.0)
        assertNull(r.perHour.value)
        assertEquals(4.0, r.perKm.value!!, 0.001)
    }

    @Test
    fun `pickup entra no km quando habilitado`() {
        val r = eval(fare = 30.0, tripKm = 10.0, toPickup = 5.0, includePickup = true)
        assertEquals(2.0, r.perKm.value!!, 0.001)
        assertEquals(TrafficLight.GREEN, r.perKm.light)
        assertEquals(15.0, r.totalDistanceKm!!, 0.001)
    }

    @Test
    fun `pickup sempre entra no km mesmo com a opcao antiga desligada`() {
        val r = eval(fare = 30.0, tripKm = 10.0, toPickup = 5.0, includePickup = false)
        assertEquals(2.0, r.perKm.value!!, 0.001)
        assertEquals(15.0, r.totalDistanceKm!!, 0.001)
    }

    @Test
    fun `classificacao com limites personalizados`() {
        assertEquals(TrafficLight.GREEN, RideAnalyzer.classify(5.0, 4.0, 4.5))
        assertEquals(TrafficLight.YELLOW, RideAnalyzer.classify(4.2, 4.0, 4.5))
        assertEquals(TrafficLight.RED, RideAnalyzer.classify(3.9, 4.0, 4.5))
    }
}
