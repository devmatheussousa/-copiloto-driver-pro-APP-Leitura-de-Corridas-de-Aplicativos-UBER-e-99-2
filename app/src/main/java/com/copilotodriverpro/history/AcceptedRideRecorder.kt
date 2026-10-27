package com.copilotodriverpro.history

import androidx.room.withTransaction
import com.copilotodriverpro.analyzer.OfferEvaluation
import com.copilotodriverpro.database.AppDatabase
import com.copilotodriverpro.database.ENTRY_SOURCE_AUTO
import com.copilotodriverpro.database.EntryEntity
import com.copilotodriverpro.database.EntryType
import com.copilotodriverpro.database.RideEntity
import com.copilotodriverpro.models.RideOffer
import com.copilotodriverpro.models.SupportedPlatform
import kotlinx.coroutines.flow.Flow

/**
 * Corridas ACEITAS (capturadas automaticamente): cada uma entra no Histórico e vira um
 * lançamento de ganho em Lançamentos. Despesas e demais lançamentos continuam manuais.
 */
class AcceptedRideRecorder(private val db: AppDatabase) {

    val rides: Flow<List<RideEntity>> = db.rideDao().observeAll()

    /** @return true se a corrida foi registrada agora (false = já existia ou sem valor). */
    suspend fun record(offer: RideOffer, evaluation: OfferEvaluation?): Boolean {
        val base = offer.fare ?: return false
        if (base <= 0.0) return false
        // Bônus confirmado que NÃO está dentro do valor da oferta é somado.
        val bonus = if (!offer.bonusIncludedInFare) offer.bonusConfirmed ?: 0.0 else 0.0
        val amount = base + bonus
        val platformName = platformLabel(offer.platform)

        return db.withTransaction {
            val shiftId = db.shiftDao().active()?.id
            val ride = RideEntity(
                id = offer.id,
                platform = offer.platform.name,
                fare = amount,
                tripKm = offer.tripDistanceKm,
                pickupKm = offer.distanceToPickupKm,
                durationMin = offer.durationMin,
                pickupMin = offer.timeToPickupMin,
                pickupText = offer.pickupText?.takeIf { it.isNotBlank() },
                destinationText = offer.destinationText?.takeIf { it.isNotBlank() },
                rideType = offer.rideType?.takeIf { it.isNotBlank() },
                passengerRating = offer.passengerRating,
                timestamp = System.currentTimeMillis(),
                shiftId = shiftId
            )
            if (db.rideDao().insertIfAbsent(ride) == -1L) return@withTransaction false
            db.entryDao().insert(
                EntryEntity(
                    type = EntryType.GANHO,
                    category = "Corrida $platformName",
                    amount = amount,
                    timestamp = ride.timestamp,
                    note = entryNote(ride),
                    shiftId = shiftId,
                    source = ENTRY_SOURCE_AUTO,
                    rideId = ride.id
                )
            )
            true
        }
    }

    /** Editar o valor no Histórico atualiza o lançamento ligado a ela. */
    suspend fun updateFare(rideId: String, fare: Double) = db.withTransaction {
        db.rideDao().updateFare(rideId, fare)
        db.entryDao().updateAmountByRideId(rideId, fare)
    }

    /** Excluir no Histórico remove também o lançamento automático. */
    suspend fun delete(rideId: String) = db.withTransaction {
        db.entryDao().deleteByRideId(rideId)
        db.rideDao().deleteById(rideId)
    }

    companion object {
        fun platformLabel(platform: String): String = when (platform) {
            SupportedPlatform.UBER.name -> "Uber"
            SupportedPlatform.NINE_NINE.name -> "99"
            else -> "App"
        }

        fun platformLabel(platform: SupportedPlatform): String = platformLabel(platform.name)

        fun entryNote(r: RideEntity): String = buildList {
            r.destinationText?.let { add(it) }
            r.totalKm?.let { add("%.1f km".format(java.util.Locale("pt", "BR"), it)) }
            r.totalMin?.let { add("${it.toInt()} min") }
        }.joinToString(" • ")
    }
}
