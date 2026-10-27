package com.copilotodriverpro

import com.copilotodriverpro.analyzer.ProfitabilityCalculator
import com.copilotodriverpro.analyzer.RecommendationResult
import com.copilotodriverpro.analyzer.RecommendationState
import com.copilotodriverpro.analyzer.RideRecommendationEngine
import com.copilotodriverpro.models.GeoStatus
import com.copilotodriverpro.models.RegionCheck
import com.copilotodriverpro.models.RideOffer
import com.copilotodriverpro.models.StopVerdict
import com.copilotodriverpro.settings.DriverSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class RideRecommendationEngineTest {

    private val settings = DriverSettings(
        minNetHourly = 30.00f,
        minNetPerKm = 1.00f,
        minRating = 4.80f,
        maxDistanceToPickupKm = 5.0f,
        stopAlertEnabled = true
    )

    @Test
    fun `estado DADOS_INSUFFICIENTES quando falta valor ou distancia`() {
        val offer = RideOffer(fare = null, tripDistanceKm = 10.0)
        val metrics = ProfitabilityCalculator.calculate(offer, settings)
        val rec = RideRecommendationEngine.evaluate(offer, metrics, settings)

        assertEquals(RecommendationState.DADOS_INSUFFICIENTES, rec.state)
    }

    @Test
    fun `estado ACEITAR quando atende todas as metas`() {
        val offer = RideOffer(
            fare = 35.00,
            distanceToPickupKm = 1.0,
            tripDistanceKm = 9.0,
            durationMin = 20.0,
            passengerRating = 4.90
        )
        val metrics = ProfitabilityCalculator.calculate(offer, settings)
        val rec = RideRecommendationEngine.evaluate(offer, metrics, settings)

        assertEquals(RecommendationState.ACEITAR, rec.state)
    }

    @Test
    fun `estado RECUSAR quando ganho liquido por hora esta abaixo da meta`() {
        val offer = RideOffer(
            fare = 15.00,
            distanceToPickupKm = 1.0,
            tripDistanceKm = 10.0,
            durationMin = 45.0, // 15 / 0.75h = 20.00/h < 30.00
            passengerRating = 4.90
        )
        val metrics = ProfitabilityCalculator.calculate(offer, settings)
        val rec = RideRecommendationEngine.evaluate(offer, metrics, settings)

        assertEquals(RecommendationState.RECUSAR, rec.state)
    }

    @Test
    fun `estado RECUSAR quando fora da regiao`() {
        val offer = RideOffer(fare = 40.00, tripDistanceKm = 10.0, durationMin = 20.0)
        val metrics = ProfitabilityCalculator.calculate(offer, settings)
        val check = RegionCheck(pickup = GeoStatus.OUTSIDE, destination = GeoStatus.INSIDE)
        val rec = RideRecommendationEngine.evaluate(offer, metrics, settings, check)
        assertEquals(RecommendationState.RECUSAR, rec.state)
    }

    @Test
    fun `estado AVALIAR quando corrida tem parada confirmada`() {
        val offer = RideOffer(
            fare = 40.00,
            tripDistanceKm = 10.0,
            durationMin = 20.0,
            stopVerdict = StopVerdict.CONFIRMED
        )
        val metrics = ProfitabilityCalculator.calculate(offer, settings)
        val rec = RideRecommendationEngine.evaluate(offer, metrics, settings)

        assertEquals(RecommendationState.AVALIAR, rec.state)
    }

    @Test
    fun `estado AVALIAR quando possui parada possivel ou baixa confianca`() {
        val offer = RideOffer(
            fare = 40.00,
            tripDistanceKm = 10.0,
            durationMin = 20.0,
            confidenceScore = 0.60
        )
        val metrics = ProfitabilityCalculator.calculate(offer, settings)
        val rec = RideRecommendationEngine.evaluate(offer, metrics, settings)

        assertEquals(RecommendationState.AVALIAR, rec.state)
    }
}
