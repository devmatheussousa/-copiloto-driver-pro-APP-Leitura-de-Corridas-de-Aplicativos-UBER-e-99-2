package com.copilotodriverpro

import com.copilotodriverpro.analyzer.ProfitabilityCalculator
import com.copilotodriverpro.models.RideOffer
import com.copilotodriverpro.models.RideSource
import com.copilotodriverpro.models.SupportedPlatform
import com.copilotodriverpro.recognition.RideTextParser
import com.copilotodriverpro.settings.DriverSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regressão dos erros de R$/km e R$/hora:
 *  - o valor da corrida e o bônus nunca eram lidos pelo extrator (cifrão sem escape na regex);
 *  - durações em minutos ("5 min", "13 minutos") nunca casavam;
 *  - com UM só km na oferta, o mesmo valor virava embarque E viagem (distância dobrada);
 *  - sem a viagem, o total caía para só o embarque / só o tempo de embarque (R$/km e R$/h absurdos).
 */
class CalculoKmHoraRegressionTest {

    private val settings = DriverSettings(includePickupInKm = true)

    @Test
    fun `oferta uber completa - km e hora batem com o que o app mostra`() {
        val text = "UberX\nR$ 13,01\nR$1,13/km aprox.\n4,89 (19)\n5 min (2.1 km)\nRua A\n13 minutos (9.4 km)\nRua B\nAceitar"
        val o = RideTextParser.parse(text, RideSource.ACCESSIBILITY, SupportedPlatform.UBER)!!
        val m = ProfitabilityCalculator.calculate(o, settings)
        assertEquals(11.5, m.totalDistanceKm!!, 0.001)
        assertEquals(18.0, m.totalDurationMin!!, 0.001)
        assertEquals(13.01 / 11.5, m.grossPerKm!!, 0.001)          // ~R$ 1,13/km (igual ao do Uber)
        assertEquals(13.01 / 18.0 * 60.0, m.grossPerHour!!, 0.001) // ~R$ 43,37/h
    }

    @Test
    fun `um unico km na oferta nao e contado duas vezes`() {
        val o = RideTextParser.parse("R$ 22,00\n7,3 km\n15 min", RideSource.MANUAL)!!
        assertNull(o.distanceToPickupKm)
        assertEquals(7.3, o.tripDistanceKm!!, 0.001)
        val m = ProfitabilityCalculator.calculate(o, settings)
        assertEquals(7.3, m.totalDistanceKm!!, 0.001)
        assertEquals(22.0 / 7.3, m.grossPerKm!!, 0.001)
    }

    @Test
    fun `valor da corrida ignora bonus e valor por km mesmo quando vem antes`() {
        val text = "+R$ 3,00\nR$ 2,38 por km\nR$ 14,52\n6 min (2,3 km)\nViagem de 14 min (6,1 km)\nAceitar"
        val o = RideTextParser.parse(text, RideSource.ACCESSIBILITY, SupportedPlatform.UBER)!!
        assertEquals(14.52, o.fare!!, 0.001)
        assertEquals(3.0, o.bonusConfirmed!!, 0.001)
    }

    @Test
    fun `viagem listada antes do embarque nao troca os valores`() {
        val text = "R$ 18,00\nViagem de 15 min (7,0 km)\n4 min (1,5 km) de distância\nAceitar"
        val o = RideTextParser.parse(text, RideSource.ACCESSIBILITY, SupportedPlatform.UBER)!!
        assertEquals(1.5, o.distanceToPickupKm!!, 0.001)
        assertEquals(4.0, o.timeToPickupMin!!, 0.001)
        assertEquals(7.0, o.tripDistanceKm!!, 0.001)
        assertEquals(15.0, o.durationMin!!, 0.001)
    }

    @Test
    fun `hora e minutos somam corretamente`() {
        val o = RideTextParser.parse("R$ 90,00\n10 min (3 km)\n1 h 05 min (40 km)", RideSource.MANUAL)!!
        assertEquals(10.0, o.timeToPickupMin!!, 0.001)
        assertEquals(65.0, o.durationMin!!, 0.001)
        assertEquals(75.0, o.totalDurationMin!!, 0.001)
    }

    @Test
    fun `sem a viagem nao usa so o embarque como total`() {
        val offer = RideOffer(fare = 20.0, distanceToPickupKm = 1.2, timeToPickupMin = 3.0)
        assertNull(offer.totalDistanceKm)
        assertNull(offer.totalDurationMin)
        val m = ProfitabilityCalculator.calculate(offer, settings)
        assertNull(m.grossPerKm)
        assertNull(m.grossPerHour)
    }

    @Test
    fun `viagem sem tempo nao gera R por hora so com o tempo de embarque`() {
        val offer = RideOffer(fare = 10.0, distanceToPickupKm = 1.0, tripDistanceKm = 5.0, timeToPickupMin = 3.0)
        val m = ProfitabilityCalculator.calculate(offer, settings)
        assertNotNull(m.grossPerKm)
        assertNull(m.grossPerHour)
    }

    @Test
    fun `R por km e R por hora sempre incluem o embarque`() {
        val offer = RideOffer(fare = 30.0, distanceToPickupKm = 5.0, tripDistanceKm = 10.0, timeToPickupMin = 10.0, durationMin = 20.0)
        // A configuração antiga "embarque entra no km" não muda mais o resultado.
        for (flag in listOf(true, false)) {
            val m = ProfitabilityCalculator.calculate(offer, DriverSettings(includePickupInKm = flag))
            assertEquals(15.0, m.totalDistanceKm!!, 0.001)            // km: 5 + 10
            assertEquals(30.0 / 15.0, m.grossPerKm!!, 0.001)
            assertEquals(30.0 / 30.0 * 60.0, m.grossPerHour!!, 0.001) // hora: 10 + 20 min
            assertEquals(30.0 / 30.0, m.grossPerMinute!!, 0.001)
        }
    }

    @Test
    fun `print real uber - 24,60 por 22,2 km e 33 min`() {
        // 6 min (1,2 km) até o passageiro + 27 min (21 km) de viagem
        val offer = RideOffer(fare = 24.60, distanceToPickupKm = 1.2, tripDistanceKm = 21.0, timeToPickupMin = 6.0, durationMin = 27.0)
        val m = ProfitabilityCalculator.calculate(offer, DriverSettings())
        assertEquals(22.2, m.totalDistanceKm!!, 0.001)
        assertEquals(24.60 / 22.2, m.grossPerKm!!, 0.001)             // R$ 1,11/km (antes mostrava 1,17)
        assertEquals(24.60 / 33.0 * 60.0, m.grossPerHour!!, 0.001)    // R$ 44,73/h
        assertEquals(24.60 / 33.0, m.grossPerMinute!!, 0.001)         // R$ 0,75/min
    }
}
