package com.copilotodriverpro

import com.copilotodriverpro.analyzer.ProfitabilityCalculator
import com.copilotodriverpro.models.RideOffer
import com.copilotodriverpro.settings.DriverSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Teste Financeiro Obrigatório exigido pelo projeto (Seção 10).
 *
 * Dados de Entrada:
 * - Valor da corrida: R$ 24,90
 * - Distância total: 12,5 km
 * - Duração total: 28 minutos
 * - Custo operacional estimado: R$ 6,50
 * - Bônus adicional: R$ 0,00
 *
 * Resultados esperados:
 * - Ganho líquido estimado: R$ 18,40
 * - Ganho bruto por quilômetro: aproximadamente R$ 1,99 (1.992)
 * - Ganho bruto por hora: aproximadamente R$ 53,36 (53.3571)
 * - Ganho líquido por quilômetro: aproximadamente R$ 1,47 (1.472)
 * - Ganho líquido por hora: aproximadamente R$ 39,43 (39.4285)
 */
class ProfitabilityCalculatorTest {

    @Test
    fun `teste financeiro obrigatorio do projeto`() {
        val offer = RideOffer(
            fare = 24.90,
            tripDistanceKm = 12.5,
            durationMin = 28.0,
            bonusConfirmed = 0.0
        )
        val settings = DriverSettings()

        // Executa o cálculo informando o custo operacional de R$ 6,50
        val metrics = ProfitabilityCalculator.calculate(
            offer = offer,
            settings = settings,
            overrideOpCost = 6.50
        )

        assertNotNull(metrics)
        assertEquals(18.40, metrics.netEarnings!!, 0.001)
        assertEquals(1.992, metrics.grossPerKm!!, 0.001)
        assertEquals(53.3571, metrics.grossPerHour!!, 0.001)
        assertEquals(1.472, metrics.netPerKm!!, 0.001)
        assertEquals(39.4285, metrics.netPerHour!!, 0.001)
    }

    @Test
    fun `calculo de custo de combustivel e custos operacionais por configuracao`() {
        val offer = RideOffer(
            fare = 50.00,
            distanceToPickupKm = 2.0,
            tripDistanceKm = 10.0,
            durationMin = 30.0
        )
        val settings = DriverSettings(
            avgConsumptionKmPerUnit = 10.0f, // 10 km/L
            fuelPricePerUnit = 5.00f,         // R$ 5,00/L -> Combustível = (12/10)*5 = 6.00
            maintenancePerKm = 0.10f,
            tireCostPerKm = 0.05f,
            depreciationPerKm = 0.15f        // Variável = (0.10+0.05+0.15)*12 = 3.60
            // Custo Total = 6.00 + 3.60 = 9.60
            // Ganho Líquido = 50.00 - 9.60 = 40.40
        )

        val metrics = ProfitabilityCalculator.calculate(offer, settings)

        assertEquals(12.0, metrics.totalDistanceKm!!, 0.001)
        assertEquals(6.00, metrics.fuelCost!!, 0.001)
        assertEquals(9.60, metrics.operationalCost!!, 0.001)
        assertEquals(40.40, metrics.netEarnings!!, 0.001)
        assertEquals(40.40 / 12.0, metrics.netPerKm!!, 0.001)
        assertEquals(40.40 / 0.5, metrics.netPerHour!!, 0.001)
    }

    @Test
    fun `ganho por minuto usa a mesma base do valor por hora`() {
        val offer = RideOffer(fare = 24.90, tripDistanceKm = 12.5, durationMin = 28.0)
        val metrics = ProfitabilityCalculator.calculate(offer, DriverSettings(), overrideOpCost = 6.50)

        assertEquals(24.90 / 28.0, metrics.grossPerMinute!!, 0.001)
        // Consistência: R$/min * 60 tem de bater com o R$/hora
        assertEquals(metrics.grossPerHour!!, metrics.grossPerMinute!! * 60.0, 0.001)
    }

    @Test
    fun `sem distância ou tempo os indicadores ficam nulos`() {
        val offer = RideOffer(fare = 20.0)
        val metrics = ProfitabilityCalculator.calculate(offer, DriverSettings())

        assertNull(metrics.grossPerKm)
        assertNull(metrics.grossPerHour)
        assertNull(metrics.grossPerMinute)
        assertNull(metrics.operationalCost)
        assertNull(metrics.netEarnings)
    }

    @Test
    fun `pickup sempre entra no total de km`() {
        val offer = RideOffer(fare = 30.0, distanceToPickupKm = 5.0, tripDistanceKm = 10.0, durationMin = 40.0)

        for (flag in listOf(true, false)) {
            val m = ProfitabilityCalculator.calculate(offer, DriverSettings(includePickupInKm = flag))
            assertEquals(15.0, m.totalDistanceKm!!, 0.001)
            assertEquals(30.0 / 15.0, m.grossPerKm!!, 0.001)
        }
    }
}
