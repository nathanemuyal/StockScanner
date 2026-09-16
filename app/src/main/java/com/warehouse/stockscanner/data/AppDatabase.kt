package com.warehouse.stockscanner.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Local SQLite storage. This is what makes the app resilient to being
 * closed mid-scan: every confirmed product update is persisted here
 * immediately, so nothing is lost if the app is killed.
 *
 * That same promise is why a schema change ships with a real migration
 * where one is possible (see [MIGRATION_6_7]): updating the app in the
 * middle of a count must not be the one thing that throws away what's
 * already been scanned. [RoomDatabase.Builder.fallbackToDestructiveMigration]
 * stays on only as the last resort for an upgrade path no migration covers.
 */
@Database(entities = [ProductEntity::class, BarcodeAliasEntity::class], version = 7, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun barcodeAliasDao(): BarcodeAliasDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /**
         * v6 -> v7: [ProductEntity.looseUnits], the loose singles a "מעורב"
         * row counts alongside its packages. Every row that already exists
         * was counted in one of the two older modes, where nothing is loose
         * — so 0 is not just a filler default, it's the right value for
         * them.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE products ADD COLUMN looseUnits INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "stock_scanner.db"
                )
                    .addMigrations(MIGRATION_6_7)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }

        /**
         * Test-only: each Robolectric test method gets a fresh Application
         * (and storage), but this singleton would otherwise keep pointing at
         * a previous test's now-torn-down database. Call from @Before so the
         * next getInstance() builds a database tied to the current context.
         */
        fun resetForTests() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}
