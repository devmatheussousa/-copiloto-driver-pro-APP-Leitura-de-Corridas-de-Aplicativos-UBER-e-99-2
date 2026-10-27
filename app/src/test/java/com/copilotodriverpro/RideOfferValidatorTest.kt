package com.copilotodriverpro

import com.copilotodriverpro.models.RideOffer
import com.copilotodriverpro.models.SupportedPlatform
import com.copilotodriverpro.recognition.RideOfferValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RideOfferValidatorTest {

    @Before
    fun setup() {
        RideOfferValidator.reset()
    }

    @Test
    fun `detecta oferta duplicada`() {
        val a = RideOffer(platform = SupportedPlatform.UBER, fare = 25.0, tripDistanceKm = 8.0, durationMin = 15.0)
        val b = RideOffer(platform = SupportedPlatform.UBER, fare = 25.0, tripDistanceKm = 8.0, durationMin = 15.0)

        assertTrue(RideOfferValidator.isDuplicate(a, b))
    }

    @Test
    fun `diferencia ofertas com plataformas distintas`() {
        val a = RideOffer(platform = SupportedPlatform.UBER, fare = 25.0, tripDistanceKm = 8.0, durationMin = 15.0)
        val b = RideOffer(platform = SupportedPlatform.NINE_NINE, fare = 25.0, tripDistanceKm = 8.0, durationMin = 15.0)

        assertFalse(RideOfferValidator.isDuplicate(a, b))
    }

    @Test
    fun `rejeita oferta sem campos ou antiga`() {
        val emptyOffer = RideOffer()
        assertFalse(RideOfferValidator.isValidAndNew(emptyOffer))

        val oldOffer = RideOffer(fare = 20.0, timestamp = System.currentTimeMillis() - 120_000L)
        assertFalse(RideOfferValidator.isValidAndNew(oldOffer))
    }
}
