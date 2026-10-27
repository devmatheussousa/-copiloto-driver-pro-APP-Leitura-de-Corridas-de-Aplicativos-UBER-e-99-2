package com.copilotodriverpro.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ShiftDao {

    @Query("SELECT * FROM shifts ORDER BY startMillis DESC")
    fun observeAll(): Flow<List<ShiftEntity>>

    @Query("SELECT * FROM shifts WHERE endMillis IS NULL ORDER BY startMillis DESC LIMIT 1")
    fun observeActive(): Flow<ShiftEntity?>

    @Query("SELECT * FROM shifts WHERE endMillis IS NULL ORDER BY startMillis DESC LIMIT 1")
    suspend fun active(): ShiftEntity?

    @Query("SELECT * FROM shifts ORDER BY startMillis DESC")
    suspend fun all(): List<ShiftEntity>

    @Query("SELECT * FROM shifts WHERE id = :id")
    suspend fun byId(id: Long): ShiftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(shift: ShiftEntity): Long

    @Update
    suspend fun update(shift: ShiftEntity)

    @Delete
    suspend fun delete(shift: ShiftEntity)
}

@Dao
interface EntryDao {

    @Query("SELECT * FROM entries ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE shiftId = :shiftId ORDER BY timestamp DESC")
    fun observeByShift(shiftId: Long): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE shiftId = :shiftId ORDER BY timestamp DESC")
    suspend fun byShift(shiftId: Long): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp DESC")
    suspend fun between(from: Long, to: Long): List<EntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: EntryEntity): Long

    @Update
    suspend fun update(entry: EntryEntity)

    @Delete
    suspend fun delete(entry: EntryEntity)

    @Query("DELETE FROM entries WHERE rideId = :rideId")
    suspend fun deleteByRideId(rideId: String)

    @Query("UPDATE entries SET amount = :amount WHERE rideId = :rideId")
    suspend fun updateAmountByRideId(rideId: String, amount: Double)
}

@Dao
interface RideDao {

    @Query("SELECT * FROM rides ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<RideEntity>>

    /** IGNORE: se a corrida já existe devolve -1 e nada é duplicado. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(ride: RideEntity): Long

    @Query("UPDATE rides SET fare = :fare WHERE id = :id")
    suspend fun updateFare(id: String, fare: Double)

    @Query("DELETE FROM rides WHERE id = :id")
    suspend fun deleteById(id: String)
}
