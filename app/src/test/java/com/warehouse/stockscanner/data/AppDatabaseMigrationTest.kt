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

    /** Writes a real v7 database file holding [rows] — looseUnits present, barcodes still the old alias table. */
    private fun createV7DatabaseWith(vararg rows: String, name: String = DB_NAME) {
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `products` (" +
                "`sku` TEXT NOT NULL, `description` TEXT NOT NULL, `barcode` TEXT NOT NULL, " +
                "`location` TEXT NOT NULL, `rowOrder` INTEGER NOT NULL, `quantityType` TEXT NOT NULL, " +
                "`packageContent` INTEGER NOT NULL, `packageCount` INTEGER NOT NULL, " +
                "`looseUnits` INTEGER NOT NULL, `quantity` INTEGER NOT NULL, `scanned` INTEGER NOT NULL, " +
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
        db.version = 7
        db.close()
    }

    private fun openMigrated(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .addMigrations(AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8)
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
            assertEquals("ABC-123", db.barcodeDao().findSkuByBarcode("999"))
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

        val chain = listOf(AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8)
            .sortedBy { it.startVersion }
        assertEquals(6, chain.first().startVersion)
        assertEquals(declared, chain.last().endVersion)
        // No gap in the middle either — a chain that skips a version would
        // hand the upgrade straight back to the destructive fallback.
        chain.zipWithNext().forEach { (earlier, later) ->
            assertEquals(earlier.endVersion, later.startVersion)
        }
    }

    /**
     * v8 moves every ברקוד into one table. A worker upgrading mid-count must
     * come out the other side able to scan exactly what they could scan
     * before — both the extra codes the old alias table held and the primary
     * ones that only ever existed on a product row.
     */
    @Test
    fun `a v7 database opens on v8 with every barcode resolvable and every count intact`() = runBlocking {
        createV7DatabaseWith(
            "INSERT INTO products (sku, description, barcode, location, rowOrder, quantityType, " +
                "packageContent, packageCount, looseUnits, quantity, scanned) " +
                "VALUES ('ABC-123', 'בורג', '111', 'A-01', 0, 'מעורב', 12, 5, 7, 67, 1)",
            "INSERT INTO barcode_aliases (barcode, sku) VALUES ('999', 'ABC-123')"
        )

        val db = openMigrated()
        try {
            val row = db.productDao().findAllBySku("ABC-123").single()
            assertEquals(ProductEntity.TYPE_MIXED, row.quantityType)
            assertEquals(7, row.looseUnits)
            assertEquals(67, row.quantity)
            // Never counted through updateQuantity, so it carries no stamp yet.
            assertEquals(0L, row.countedAt)

            // The alias carried over...
            assertEquals("ABC-123", db.barcodeDao().findSkuByBarcode("999"))
            // ...and so did the primary code, which had no row of its own before.
            assertEquals("ABC-123", db.barcodeDao().findSkuByBarcode("111"))

            // Nothing is guessed about packaging: both are plain single units
            // until a source file or a worker says otherwise.
            val primary = db.barcodeDao().findByBarcode("111")!!
            assertEquals(BarcodeEntity.ROLE_UNIT, primary.role)
            assertEquals(0, primary.packageContent)
        } finally {
            db.close()
        }
    }

    /**
     * The unique index is on (sku, location, barcode), not on barcode alone,
     * so two different מקטים really can carry the same code on their product
     * rows. Only one claim survives into v8, and which one must not depend on
     * the order SQLite happens to scan in — an upgrade has no screen to
     * report an ambiguity on, so the least it can do is be repeatable.
     */
    @Test
    fun `a code two product rows claim resolves to the one the file listed first`() = runBlocking {
        createV7DatabaseWith(
            // Deliberately inserted in the opposite order to rowOrder, so a
            // pass that just took what it found first would pick SECOND-1.
            "INSERT INTO products (sku, description, barcode, location, rowOrder, quantityType, " +
                "packageContent, packageCount, looseUnits, quantity, scanned) " +
                "VALUES ('SECOND-1', 'שני', '777', 'B-02', 5, 'יחידות', 0, 0, 0, 0, 0)",
            "INSERT INTO products (sku, description, barcode, location, rowOrder, quantityType, " +
                "packageContent, packageCount, looseUnits, quantity, scanned) " +
                "VALUES ('FIRST-1', 'ראשון', '777', 'A-01', 1, 'יחידות', 0, 0, 0, 0, 0)"
        )

        val db = openMigrated()
        try {
            assertEquals("FIRST-1", db.barcodeDao().findSkuByBarcode("777"))
            assertEquals(1, db.barcodeDao().getAll().count { it.barcode == "777" })
        } finally {
            db.close()
        }
    }

    /**
     * The same code can sit on a product row AND in the alias table. The
     * alias is the deliberate statement of which מקט owns it, so it must be
     * the one that survives — matching the IGNORE the alias table was always
     * written with.
     */
    @Test
    fun `an alias wins over a product row claiming the same barcode`() = runBlocking {
        createV7DatabaseWith(
            "INSERT INTO products (sku, description, barcode, location, rowOrder, quantityType, " +
                "packageContent, packageCount, looseUnits, quantity, scanned) " +
                "VALUES ('XYZ-9', 'אום', '555', 'B-02', 0, 'יחידות', 0, 0, 0, 7, 1)",
            "INSERT INTO barcode_aliases (barcode, sku) VALUES ('555', 'ABC-123')"
        )

        val db = openMigrated()
        try {
            assertEquals("ABC-123", db.barcodeDao().findSkuByBarcode("555"))
            assertEquals(1, db.barcodeDao().getAll().count { it.barcode == "555" })
        } finally {
            db.close()
        }
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
