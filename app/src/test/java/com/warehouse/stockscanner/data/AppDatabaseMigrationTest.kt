package com.warehouse.stockscanner.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val DB_NAME = "migration_test.db"

/** The name [AppDatabase.getInstance] actually opens — the file a real upgrade would land on. */
private const val PRODUCTION_DB_NAME = "stock_scanner.db"

/**
 * A worker who updates the app in the middle of a count must not lose the
 * rows they've already scanned — so the "מעורב" mode's new column ships as
 * a real migration ([AppDatabase.MIGRATION_6_7]), not as a destructive
 * rebuild. This drives that migration against a genuine v6 database file
 * built by hand (the schema Room generated before [ProductEntity.looseUnits]
 * existed), and deliberately opens the result WITHOUT
 * fallbackToDestructiveMigration, so a missing or wrong migration fails the
 * test loudly instead of quietly wiping the table the way production's
 * last-resort fallback would.
 */
@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppDatabase.resetForTests()
        context.deleteDatabase(DB_NAME)
        context.deleteDatabase(PRODUCTION_DB_NAME)
    }

    @After
    fun tearDown() {
        AppDatabase.resetForTests()
        context.deleteDatabase(DB_NAME)
        context.deleteDatabase(PRODUCTION_DB_NAME)
    }

    /** Writes a real v6 database file holding [rows] — pre-looseUnits schema, user_version 6. */
    private fun createV6DatabaseWith(vararg rows: String, name: String = DB_NAME) {
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `products` (" +
                "`sku` TEXT NOT NULL, `description` TEXT NOT NULL, `barcode` TEXT NOT NULL, " +
                "`location` TEXT NOT NULL, `rowOrder` INTEGER NOT NULL, `quantityType` TEXT NOT NULL, " +
                "`packageContent` INTEGER NOT NULL, `packageCount` INTEGER NOT NULL, " +
                "`quantity` INTEGER NOT NULL, `scanned` INTEGER NOT NULL, " +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_products_sku_location_barcode` " +
                "ON `products` (`sku`, `location`, `barcode`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `barcode_aliases` (" +
                "`barcode` TEXT NOT NULL, `sku` TEXT NOT NULL, " +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_barcode_aliases_barcode` " +
                "ON `barcode_aliases` (`barcode`)"
        )
        rows.forEach { db.execSQL(it) }
        db.version = 6
        db.close()
    }

    private fun openMigrated(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .addMigrations(AppDatabase.MIGRATION_6_7)
            .allowMainThreadQueries()
            .build()

    @Test
    fun `a v6 database opens on v7 with every scanned row intact`() = runBlocking {
        createV6DatabaseWith(
            "INSERT INTO products (sku, description, barcode, location, rowOrder, quantityType, " +
                "packageContent, packageCount, quantity, scanned) " +
                "VALUES ('ABC-123', 'בורג', '111', 'A-01', 0, 'אריזות', 12, 5, 60, 1)",
            "INSERT INTO products (sku, description, barcode, location, rowOrder, quantityType, " +
                "packageContent, packageCount, quantity, scanned) " +
                "VALUES ('XYZ-9', 'אום', '222', 'B-02', 1, 'יחידות', 0, 0, 7, 1)",
            "INSERT INTO barcode_aliases (barcode, sku) VALUES ('999', 'ABC-123')"
        )

        val db = openMigrated()
        try {
            val rows = db.productDao().getAllOrdered()
            assertEquals(2, rows.size)

            val packageRow = rows.first { it.sku == "ABC-123" }
            assertEquals(ProductEntity.TYPE_PACKAGE, packageRow.quantityType)
            assertEquals("A-01", packageRow.location)
            assertEquals(12, packageRow.packageContent)
            assertEquals(5, packageRow.packageCount)
            assertEquals(60, packageRow.quantity)
            assertEquals(true, packageRow.scanned)
            // Nothing was loose in either of the older modes, so 0 is the
            // correct value for an already-counted row, not just a filler.
            assertEquals(0, packageRow.looseUnits)

            assertEquals(0, rows.first { it.sku == "XYZ-9" }.looseUnits)
            assertEquals("ABC-123", db.barcodeAliasDao().findSkuByBarcode("999"))
        } finally {
            db.close()
        }
    }

    /**
     * The migration existing isn't the same as production using it. This
     * goes through [AppDatabase.getInstance] itself, on the database name it
     * really opens — the one path a worker's upgrade actually takes. Without
     * the `addMigrations` wiring the builder's `fallbackToDestructiveMigration`
     * would swallow the missing migration and hand back an empty table, so
     * the row count below is what keeps that wiring from being dropped.
     */
    @Test
    fun `getInstance migrates the real database instead of falling back to a wipe`() = runBlocking {
        createV6DatabaseWith(
            "INSERT INTO products (sku, description, barcode, location, rowOrder, quantityType, " +
                "packageContent, packageCount, quantity, scanned) " +
                "VALUES ('ABC-123', 'בורג', '111', 'A-01', 0, 'אריזות', 12, 5, 60, 1)",
            "INSERT INTO products (sku, description, barcode, location, rowOrder, quantityType, " +
                "packageContent, packageCount, quantity, scanned) " +
                "VALUES ('XYZ-9', 'אום', '222', 'B-02', 1, 'יחידות', 0, 0, 7, 1)",
            name = PRODUCTION_DB_NAME
        )

        val rows = AppDatabase.getInstance(context).productDao().getAllOrdered()

        assertEquals(2, rows.size)
        val packageRow = rows.first { it.sku == "ABC-123" }
        assertEquals(60, packageRow.quantity)
        assertEquals(0, packageRow.looseUnits)
        assertEquals(7, rows.first { it.sku == "XYZ-9" }.quantity)
    }

    /**
     * The next schema change has to bring its own migration. Pinning the
     * declared version to the migrations that actually reach it means a bump
     * to v8 with nothing covering 7 -> 8 fails here, instead of silently
     * falling through to the destructive rebuild in production.
     */
    @Test
    fun `every schema version up to the declared one is covered by a migration`() {
        // Read back from Room itself rather than from the @Database
        // annotation, which isn't retained at runtime.
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val declared = try {
            db.openHelper.readableDatabase.version
        } finally {
            db.close()
        }

        assertEquals(6, AppDatabase.MIGRATION_6_7.startVersion)
        assertEquals(declared, AppDatabase.MIGRATION_6_7.endVersion)
    }

    @Test
    fun `a row carried over from v6 can then be counted in mixed mode`() = runBlocking {
        createV6DatabaseWith(
            "INSERT INTO products (sku, description, barcode, location, rowOrder, quantityType, " +
                "packageContent, packageCount, quantity, scanned) " +
                "VALUES ('ABC-123', 'בורג', '111', 'A-01', 0, 'אריזות', 12, 5, 60, 1)"
        )

        val db = openMigrated()
        try {
            val existing = db.productDao().findBySkuLocationAndBarcode("ABC-123", "A-01", "111")!!
            db.productDao().update(
                existing.copy(
                    quantityType = ProductEntity.TYPE_MIXED,
                    looseUnits = 7,
                    quantity = ProductEntity.totalUnits(ProductEntity.TYPE_MIXED, 12, 5, 7, 0)
                )
            )

            val updated = db.productDao().findAllBySku("ABC-123").single()
            assertEquals(ProductEntity.TYPE_MIXED, updated.quantityType)
            assertEquals(7, updated.looseUnits)
            assertEquals(67, updated.quantity)
        } finally {
            db.close()
        }
    }
}
