package com.copilotodriverpro.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Banco local do Piloto Ninja GO: turnos, lançamentos e corridas aceitas.
 */
@Database(
    entities = [ShiftEntity::class, EntryEntity::class, RideEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun shiftDao(): ShiftDao
    abstract fun entryDao(): EntryDao
    abstract fun rideDao(): RideDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * v1 -> v2: corridas aceitas automáticas. NÃO apaga nada: os lançamentos e turnos
         * que o motorista já tem continuam no aparelho.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN source TEXT NOT NULL DEFAULT 'MANUAL'")
                db.execSQL("ALTER TABLE entries ADD COLUMN rideId TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS rides (" +
                        "id TEXT NOT NULL, " +
                        "platform TEXT NOT NULL, " +
                        "fare REAL NOT NULL, " +
                        "tripKm REAL, " +
                        "pickupKm REAL, " +
                        "durationMin REAL, " +
                        "pickupMin REAL, " +
                        "pickupText TEXT, " +
                        "destinationText TEXT, " +
                        "rideType TEXT, " +
                        "passengerRating REAL, " +
                        "timestamp INTEGER NOT NULL, " +
                        "shiftId INTEGER, " +
                        "PRIMARY KEY(id))"
                )
            }
        }

        fun build(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "piloto_ninja_go.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
