package com.copilotodriverpro.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Turno de trabalho do motorista.
 * Ativo = endMillis nulo.
 */
@Entity(tableName = "shifts")
data class ShiftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMillis: Long,
    val endMillis: Long? = null,
    val distanceKm: Double? = null,     // quilometragem registrada no encerramento
    val note: String = ""
)

/** Tipo do lançamento financeiro. */
enum class EntryType { GANHO, DESPESA }

/**
 * Lançamento financeiro manual (ganhos, combustível, pedágio etc).
 */
@Entity(tableName = "entries")
data class EntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: EntryType,
    val category: String,
    val amount: Double,
    val timestamp: Long,
    val note: String = "",
    val shiftId: Long? = null,
    /** MANUAL = digitado pelo motorista; AUTO = corrida aceita capturada pelo app. */
    @ColumnInfo(defaultValue = "MANUAL") val source: String = ENTRY_SOURCE_MANUAL,
    /** Corrida (tabela rides) que originou este lançamento automático. */
    val rideId: String? = null
)

const val ENTRY_SOURCE_MANUAL = "MANUAL"
const val ENTRY_SOURCE_AUTO = "AUTO"

/**
 * Corrida ACEITA, capturada automaticamente do Uber/99. Alimenta o Histórico e gera
 * um lançamento de ganho em Lançamentos. A chave é o id da oferta: a mesma corrida
 * nunca entra duas vezes.
 */
@Entity(tableName = "rides")
data class RideEntity(
    @PrimaryKey val id: String,
    val platform: String,              // UBER | NINE_NINE
    val fare: Double,                  // valor lançado (já com bônus quando houver)
    val tripKm: Double? = null,
    val pickupKm: Double? = null,
    val durationMin: Double? = null,
    val pickupMin: Double? = null,
    val pickupText: String? = null,
    val destinationText: String? = null,
    val rideType: String? = null,
    val passengerRating: Double? = null,
    val timestamp: Long,
    val shiftId: Long? = null
) {
    val totalKm: Double? get() = tripKm?.let { it + (pickupKm ?: 0.0) }
    val totalMin: Double? get() = durationMin?.let { it + (pickupMin ?: 0.0) }
}
