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
 * where one is possible (see [MIGRATION_7_8]): updating the app in the
 * middle of a count must not be the one thing that throws away what's
 * already been scanned. [RoomDatabase.Builder.fallbackToDestructiveMigration]
 * stays on only as the last resort for an upgrade path no migration covers.
 */
@Database(entities = [ProductEntity::class, BarcodeEntity::class], version = 8, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun barcodeDao(): BarcodeDao

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

        /**
         * v7 -> v8: barcode_aliases becomes [BarcodeEntity], the single
         * table holding every ברקוד with the packaging [BarcodeEntity.role]
         * one scan of it means, and [ProductEntity.countedAt] records when a
         * row was counted.
         *
         * Both halves of the old mapping are carried over, aliases first:
         * those rows are an explicit statement that a code belongs to a מקט,
         * while a products.barcode is only implied by a row that happens to
         * carry it. INSERT OR IGNORE then keeps the explicit one wherever
         * both name the same code, matching the IGNORE the alias table was
         * always written with.
         *
         * The products pass is ordered because the unique index is on
         * (sku, location, barcode), not on barcode alone — so two different
         * מקטים really can carry the same code, and only one claim can
         * survive here. Unordered, the winner would be whichever row SQLite
         * happened to scan first; ORDER BY rowOrder makes it the one the
         * source file listed first, which is both fixed and explicable. (No
         * DISTINCT needed alongside it: IGNORE already collapses repeats.)
         * A file in that state is ambiguous data, not a migration fault —
         * loading one reports the conflict, but an upgrade has no screen to
         * report anything on, so the least it can do is not be arbitrary.
         *
         * Everything lands as [BarcodeEntity.ROLE_UNIT] with no package
         * content: that is exactly what the app assumed before this table
         * existed — one scan, one unit — so an upgrade mid-count changes
         * nothing about how the codes already in use behave. Real roles
         * arrive from the source file or from the worker, never guessed here.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE products ADD COLUMN countedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS barcodes (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "barcode TEXT NOT NULL, " +
                        "sku TEXT NOT NULL, " +
                        "role TEXT NOT NULL, " +
                        "packageContent INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_barcodes_barcode ON barcodes (barcode)")
                db.execSQL(
                    "INSERT OR IGNORE INTO barcodes (barcode, sku, role, packageContent) " +
                        "SELECT barcode, sku, '${BarcodeEntity.ROLE_UNIT}', 0 FROM barcode_aliases WHERE barcode <> ''"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO barcodes (barcode, sku, role, packageContent) " +
                        "SELECT barcode, sku, '${BarcodeEntity.ROLE_UNIT}', 0 FROM products " +
                        "WHERE barcode <> '' ORDER BY rowOrder"
                )
                db.execSQL("DROP TABLE barcode_aliases")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "stock_scanner.db"
                )
                    .addMigrations(MIGRATION_6_7, MIGRATION_7_8)
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
