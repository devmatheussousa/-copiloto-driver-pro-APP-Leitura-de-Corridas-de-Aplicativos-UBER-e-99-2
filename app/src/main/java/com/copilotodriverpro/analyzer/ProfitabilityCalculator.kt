package com.copilotodriverpro.analyzer

import com.copilotodriverpro.models.RideOffer
import com.copilotodriverpro.settings.DriverSettings

/**
 * Indicadores financeiros calculados para a corrida.
 */
data class FinancialMetrics(
    val totalDistanceKm: Double?,
    val totalDurationMin: Double?,
    val fuelCost: Double?,
    val operationalCost: Double?,
    val netEarnings: Double?,
    val grossPerKm: Double?,
    val grossPerHour: Double?,
    val grossPerMinute: Double?,
    val netPerKm: Double?,
    val netPerHour: Double?
)

/**
 * Calculadora Financeira Operacional.
 * Computa custos reais do veículo (combustível, manutenção, pneus, depreciação)
 * e ganhos líquidos reais por km e por hora.
 */
object ProfitabilityCalculator {

    fun calculate(
        offer: RideOffer,
        settings: DriverSettings,
        overrideOpCost: Double? = null
    ): FinancialMetrics {
        // R$/km e custos usam o km REAL rodado: embarque + viagem (é o mesmo que o Uber e a 99
        // mostram no "R$ x,xx/km" do card). Dividir só pela viagem deixava o R$/km inflado.
        // Sem a distância da VIAGEM não há base confiável (não cai para só o embarque).
        val trip = offer.tripDistanceKm
        val pickup = offer.distanceToPickupKm
        val totalKm = trip?.let { it + (pickup ?: 0.0) }
        val revenueDistanceKm = totalKm
        // R$/hora: tempo real gasto = embarque + viagem (o motorista perde o tempo do embarque
        // mesmo quando escolhe não contar esse trecho no km). Sem o tempo da viagem, não calcula.
        val totalMin = offer.totalDurationMin
        val fare = offer.fare
        // "+R$ 1,00 incluído" já está dentro do valor exibido: somar de novo contaria o bônus 2x.
        val bonus = if (offer.bonusIncludedInFare) 0.0 else (offer.bonusConfirmed ?: 0.0)

        // 1. Custo de Combustível / Energia
        val fuelCost = if (overrideOpCost != null) {
            null
        } else if (totalKm != null && totalKm > 0 && settings.avgConsumptionKmPerUnit > 0) {
            (totalKm / settings.avgConsumptionKmPerUnit.toDouble()) * settings.fuelPricePerUnit.toDouble()
        } else null

        // 2. Custo Operacional Total
        val operationalCost = if (overrideOpCost != null) {
            overrideOpCost
        } else if (totalKm != null && totalKm > 0) {
            val variableCostPerKm = (settings.maintenancePerKm + settings.tireCostPerKm + settings.depreciationPerKm).toDouble()
            (fuelCost ?: 0.0) + (variableCostPerKm * totalKm)
        } else null

        // 3. Ganho Líquido Estimado
        val netEarnings = if (fare != null && operationalCost != null) {
            fare + bonus - operationalCost
        } else null

        val grossEarnings = if (fare != null) fare + bonus else null

        // 4. Indicadores por km (embarque + viagem, igual ao padrão da plataforma)
        val grossPerKm = if (grossEarnings != null && revenueDistanceKm != null && revenueDistanceKm > 0) {
            grossEarnings / revenueDistanceKm
        } else null

        val netPerKm = if (netEarnings != null && revenueDistanceKm != null && revenueDistanceKm > 0) {
            netEarnings / revenueDistanceKm
        } else null

        // 5. Indicadores por hora
        val grossPerHour = if (grossEarnings != null && totalMin != null && totalMin > 0) {
            grossEarnings / (totalMin / 60.0)
        } else null

        // 5b. Ganho por minuto (mesma base do R$/hora)
        val grossPerMinute = if (grossEarnings != null && totalMin != null && totalMin > 0) {
            grossEarnings / totalMin
        } else null

        val netPerHour = if (netEarnings != null && totalMin != null && totalMin > 0) {
            netEarnings / (totalMin / 60.0)
        } else null

        return FinancialMetrics(
            totalDistanceKm = totalKm,
            totalDurationMin = totalMin,
            fuelCost = fuelCost,
            operationalCost = operationalCost,
            netEarnings = netEarnings,
            grossPerKm = grossPerKm,
            grossPerHour = grossPerHour,
            grossPerMinute = grossPerMinute,
            netPerKm = netPerKm,
            netPerHour = netPerHour
        )
    }
}
