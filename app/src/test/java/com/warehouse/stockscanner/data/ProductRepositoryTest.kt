package com.warehouse.stockscanner.data

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.warehouse.stockscanner.excel.ExcelReader
import com.warehouse.stockscanner.excel.ExcelSaveException
import com.warehouse.stockscanner.excel.ExcelWriter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream

private const val LOCATIONS_QUANTITIES_SUFFIX = "original_locations_quantities.xlsx"
private const val MULTIPLE_BARCODES_SUFFIX = "original_multiple_barcodes.xlsx"

/**
 * Exercises the row-per-location contract from the spec directly against
 * Room (in-memory, via Robolectric — no emulator needed): confirming a scan
 * must never touch the sku, must fill in a still-blank location in place,
 * and must open a brand-new row — never overwrite an existing one — the
 * moment a product is confirmed at a genuinely new location.
 */
@RunWith(RobolectricTestRunner::class)
class ProductRepositoryTest {

    private lateinit var context: android.content.Context
    private lateinit var db: AppDatabase
    private lateinit var repository: ProductRepository

    private lateinit var prefs: SessionPrefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        prefs = SessionPrefs(context)
        repository = ProductRepository(context, db.productDao(), db.barcodeDao(), prefs)
    }

    /** Writes [products] (and optionally [aliases]) as a real .xlsx to a temp file, returning a Uri as if picked via SAF. */
    private fun writeSourceFile(products: List<ProductEntity>, aliases: List<BarcodeEntity> = emptyList()): Uri {
        val file = File.createTempFile("source", ".xlsx", context.cacheDir)
        FileOutputStream(file).use { ExcelWriter.writeProductsToStream(it, products, aliases) }
        return Uri.fromFile(file)
    }

    /** The physical locations/quantities working file the repository is currently writing to — real bytes on disk. */
    private fun locationsFile(): File = repository.locationsQuantitiesFile()
        ?: error("locations/quantities working file was never created")

    /** The physical multiple-barcodes working file the repository is currently writing to — real bytes on disk. */
    private fun barcodesFile(): File = repository.multipleBarcodesFile()
        ?: error("multiple-barcodes working file was never created")

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `updateProduct fills in a still-blank location without creating a new row`() = runBlocking {
        db.productDao().insertAll(
            listOf(ProductEntity("ABC-123", "פילטר שמן טויוטה", "7290012345678", "", 0))
        )

        repository.updateProduct("ABC-123", "פילטר שמן טויוטה קורולה", "7290012345678", "A-01-05")

        assertEquals(1, repository.count())
        val updated = repository.findBySku("ABC-123")!!
        assertEquals("ABC-123", updated.sku) // sku itself never changes
        assertEquals("פילטר שמן טויוטה קורולה", updated.description)
        assertEquals(listOf("A-01-05"), updated.existingLocations)
    }

    @Test
    fun `updateProduct overwrites barcode only when a different one was scanned`() = runBlocking {
        db.productDao().insertAll(
            listOf(ProductEntity("ABC-123", "מוצר קיים", "", "", 0))
        )

        // Simulates the "barcode not found -> found via search" flow: the scanned
        // barcode differs from what was on file, so it should be adopted.
        repository.updateProduct("ABC-123", "מוצר קיים מעודכן", "72900999", "A-01-05")

        val updated = repository.findBySku("ABC-123")!!
        assertEquals("72900999", updated.barcode)
        assertEquals(listOf("A-01-05"), updated.existingLocations)

        // Confirmed again at a second, different location: the barcode stays
        // the same and BOTH locations are kept, not just the newest.
        repository.updateProduct("ABC-123", "מוצר קיים מעודכן", "72900999", "A-01-06")
        val movedAgain = repository.findBySku("ABC-123")!!
        assertEquals("72900999", movedAgain.barcode)
        assertEquals(setOf("A-01-05", "A-01-06"), movedAgain.existingLocations.toSet())
        assertEquals(2, repository.count()) // a new row was opened, the old one kept
    }

    /**
     * A מקט can legitimately have two unplaced rows: one already carrying a
     * ברקוד and one with none. (Two with nothing in both columns cannot —
     * the unique index forbids it.) Requiring exactly one candidate made a
     * scan of that very ברקוד clone a third row, stranding both originals
     * and adding one no shelf and no scan ever accounted for.
     */
    @Test
    fun `a scan fills the unplaced row already carrying its barcode instead of cloning a third`() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity("ABC-123", "מוצר", "111", "", 0),
                ProductEntity("ABC-123", "מוצר", "", "", 1)
            )
        )

        repository.updateProduct("ABC-123", "מוצר", "111", "A-01-05")

        val rows = db.productDao().findAllBySku("ABC-123")
        assertEquals(2, rows.size)
        val placed = rows.single { it.location.isNotBlank() }
        assertEquals("A-01-05", placed.location)
        // The row that already had this code is the one that got the shelf...
        assertEquals("111", placed.barcode)
        // ...and the barcode-less row stays unplaced and available.
        assertEquals(1, rows.count { it.location.isBlank() && it.barcode.isBlank() })
    }

    /** The leftover blank row is still there for the next shelf, rather than a clone being made. */
    @Test
    fun `a second shelf uses the remaining unplaced row`() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity("ABC-123", "מוצר", "111", "", 0),
                ProductEntity("ABC-123", "מוצר", "", "", 1)
            )
        )

        repository.updateProduct("ABC-123", "מוצר", "111", "A-01-05")
        repository.updateProduct("ABC-123", "מוצר", "222", "B-02-01")

        val rows = db.productDao().findAllBySku("ABC-123")
        assertEquals(2, rows.size)
        assertEquals(setOf("A-01-05", "B-02-01"), rows.map { it.location }.toSet())
        assertEquals(setOf("111", "222"), rows.map { it.barcode }.toSet())
    }

    @Test
    fun `updateProduct on an unknown sku is a no-op, never creates a row`() = runBlocking {
        repository.updateProduct("DOES-NOT-EXIST", "x", "1", "A-01-01")
        assertEquals(0, repository.count())
        assertNull(repository.findBySku("DOES-NOT-EXIST"))
    }

    @Test
    fun `confirming a product at a location it already has updates in place, no duplicate row`() = runBlocking {
        db.productDao().insertAll(
            listOf(ProductEntity("ABC-123", "ישן", "111", "A-01-05", 0))
        )

        repository.updateProduct("ABC-123", "חדש", "111", "A-01-05")

        assertEquals(1, repository.count())
        val updated = repository.findBySku("ABC-123")!!
        assertEquals("חדש", updated.description)
        assertEquals(listOf("A-01-05"), updated.existingLocations)
    }

    @Test
    fun `updateProduct syncs description across every row but a genuinely new location opens its own row with its own barcode`() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity("ABC-123", "ישן", "111", "A-01-05", 0),
                ProductEntity("ABC-123", "ישן", "111", "B-02-01", 1)
            )
        )

        // A different barcode than the sku's existing rows — e.g. the product
        // was found via description search after an unrecognized scan.
        repository.updateProduct("ABC-123", "חדש", "222", "C-03-01")

        assertEquals(3, repository.count())
        val rows = db.productDao().findAllBySku("ABC-123")
        assertTrue("description syncs everywhere, regardless of barcode", rows.all { it.description == "חדש" })
        val newRow = rows.single { it.location == "C-03-01" }
        assertEquals("222", newRow.barcode)
        // The two original rows' own barcodes are untouched — barcode lives on the row, not the sku.
        assertTrue(rows.filter { it.location != "C-03-01" }.all { it.barcode == "111" })
        assertEquals(setOf("A-01-05", "B-02-01", "C-03-01"), rows.map { it.location }.toSet())
    }

    @Test
    fun `a different barcode scanned at a location the sku already has opens its own row, not an alias`() = runBlocking {
        db.productDao().insertAll(
            listOf(ProductEntity("ABC-123", "פילטר שמן", "111", "A-01-05", 0))
        )
        // Inserting straight into the dao skips the registration an import or
        // a scan would have done, and resolution runs off that table alone.
        repository.registerBarcode("111", "ABC-123")

        repository.updateProduct("ABC-123", "פילטר שמן", "222", "A-01-05")

        // Both barcodes now resolve to the product, each via its own row.
        assertEquals("ABC-123", repository.findByBarcode("111")!!.sku)
        assertEquals("ABC-123", repository.findByBarcode("222")!!.sku)

        val rows = db.productDao().findAllBySku("ABC-123")
        assertEquals(2, rows.size)
        assertEquals(setOf("111", "222"), rows.map { it.barcode }.toSet())
        assertTrue("both rows sit at the same location", rows.all { it.location == "A-01-05" })
    }

    @Test
    fun `confirming the same second barcode again does not duplicate the row or error`() = runBlocking {
        db.productDao().insertAll(
            listOf(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0))
        )

        repository.updateProduct("ABC-123", "מוצר", "222", "A-01-05")
        repository.updateProduct("ABC-123", "מוצר", "222", "A-01-05")

        assertEquals("ABC-123", repository.findByBarcode("222")!!.sku)
        assertEquals(2, repository.count()) // still just the original row plus the one new one
    }

    @Test
    fun `a barcode already on one sku's row can still be attached to a different sku directly via updateProduct`() = runBlocking {
        // Not a real app journey (MainActivity always resolves an existing
        // barcode's owner via findByBarcode before ever reaching a confirm
        // for a different sku — see "שנה מקט"/reassignBarcode for that fix
        // flow instead) — this just documents that updateProduct itself
        // applies no cross-sku uniqueness of its own.
        db.productDao().insertAll(
            listOf(
                ProductEntity("ABC-123", "מוצר א", "111", "A-01-05", 0),
                ProductEntity("XYZ-999", "מוצר ב", "333", "B-02-01", 1)
            )
        )
        repository.updateProduct("ABC-123", "מוצר א", "222", "A-01-05")

        repository.updateProduct("XYZ-999", "מוצר ב", "222", "B-02-01")

        // XYZ-999 now has two rows at B-02-01 — its original one (barcode
        // 333) plus the new one for the barcode borrowed from ABC-123.
        val xyzRows = db.productDao().findAllBySku("XYZ-999")
        assertEquals(setOf("333", "222"), xyzRows.map { it.barcode }.toSet())
    }

    @Test
    fun `a fresh barcode only lands on the row it was actually scanned for, other blank-barcode rows stay untouched`() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity("ABC-123", "מוצר", "", "A-01-05", 0),
                ProductEntity("ABC-123", "מוצר", "", "B-02-01", 1)
            )
        )

        repository.updateProduct("ABC-123", "מוצר", "111", "C-03-01")

        val rows = db.productDao().findAllBySku("ABC-123")
        assertEquals("111", rows.single { it.location == "C-03-01" }.barcode)
        assertTrue(rows.filter { it.location != "C-03-01" }.all { it.barcode.isBlank() })
    }

    @Test
    fun `shelving a product whose barcode was already known fills in its blank-location row instead of duplicating it`() = runBlocking {
        db.productDao().insertAll(
            listOf(ProductEntity("ABC-123", "פילטר שמן טויוטה", "7290012345678", "", 0))
        )

        repository.updateProduct("ABC-123", "פילטר שמן טויוטה", "7290012345678", "A-01-05")

        assertEquals(1, repository.count())
        val row = db.productDao().findAllBySku("ABC-123").single()
        assertEquals("A-01-05", row.location)
        assertEquals("7290012345678", row.barcode)
    }

    @Test
    fun `findByBarcode ignores blank barcodes so it never falsely matches unset rows`() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity("A", "first", "", "", 0),
                ProductEntity("B", "second", "", "", 1)
            )
        )
        assertNull(repository.findByBarcode(""))
        assertNull(repository.findByBarcode("   "))
    }

    @Test
    fun `searchByDescription returns one result per product, not one per location row`() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity("A", "פילטר שמן טויוטה", "", "A-01-05", 0),
                ProductEntity("A", "פילטר שמן טויוטה", "", "B-02-01", 1),
                ProductEntity("B", "מצבר 12V", "", "", 2)
            )
        )
        val results = repository.searchByDescription("שמן")
        assertEquals(1, results.size)
        assertEquals("A", results.first().sku)
    }

    @Test
    fun `a product confirmed at a second location keeps both as separate rows`() = runBlocking {
        db.productDao().insertAll(
            listOf(ProductEntity("ABC-123", "פילטר שמן טויוטה", "7290012345678", "A-01-05", 0))
        )

        val existing = repository.findBySku("ABC-123")!!
        repository.updateProduct("ABC-123", existing.description, existing.barcode, "B-02-01")

        val updated = repository.findBySku("ABC-123")!!
        assertEquals(setOf("A-01-05", "B-02-01"), updated.existingLocations.toSet())
        assertEquals(2, repository.count())
    }

    @Test
    fun `findByLocation matches products at exactly that location, among several`() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity("A", "in one place", "", "A-01-05", 0),
                ProductEntity("B", "in two places", "", "A-01-05", 1),
                ProductEntity("B", "in two places", "", "B-02-01", 2),
                ProductEntity("C", "elsewhere only", "", "A-01-10", 3),
                ProductEntity("D", "no location yet", "", "", 4)
            )
        )

        val atA0105 = repository.findByLocation("A-01-05")
        assertEquals(setOf("A", "B"), atA0105.map { it.sku }.toSet())

        val atB0201 = repository.findByLocation("B-02-01")
        assertEquals(listOf("B"), atB0201.map { it.sku })

        // "A-01-05" must not falsely match "A-01-10" via a substring/prefix check.
        val atA011 = repository.findByLocation("A-01-1")
        assertEquals(emptyList<String>(), atA011.map { it.sku })
    }

    @Test
    fun `loadFromExcel creates both working files immediately, separate from the source file`() = runBlocking {
        val sourceUri = writeSourceFile(
            listOf(ProductEntity("ABC-123", "פילטר שמן טויוטה", "111", "A-01-05", 0))
        )

        assertNull("no working file should be remembered before any file is loaded", repository.locationsQuantitiesFile())
        assertNull("no working file should be remembered before any file is loaded", repository.multipleBarcodesFile())

        repository.loadFromExcel(sourceUri, "מלאי מרץ.xlsx")

        assertTrue("loading a file must create the locations/quantities working file right away", locationsFile().exists())
        assertTrue("loading a file must create the multiple-barcodes working file right away", barcodesFile().exists())
        assertTrue(locationsFile().name.endsWith(LOCATIONS_QUANTITIES_SUFFIX))
        assertTrue(barcodesFile().name.endsWith(MULTIPLE_BARCODES_SUFFIX))
        assertTrue("the original source file's name must be embedded in the working file names", locationsFile().name.contains("מלאי_מרץ"))
    }

    /**
     * The core "log, not a copy" contract: the source file the user picks
     * can already list a מיקום for a product (e.g. from a previous count) —
     * that must NOT make it show up in the locations/quantities working file
     * on its own. Only an actual in-app scan (updateProduct) does that, even
     * for the very same (sku, location) the source file already had.
     */
    @Test
    fun `loading a source file whose rows already list a מיקום does not copy them into the working file`() = runBlocking {
        val sourceUri = writeSourceFile(
            listOf(ProductEntity("ABC-123", "פילטר שמן טויוטה", "111", "A-01-05", 0))
        )

        repository.loadFromExcel(sourceUri)

        // The product itself is known (so scanning it still works)...
        assertEquals(1, repository.count())
        assertEquals(listOf("A-01-05"), repository.findBySku("ABC-123")!!.existingLocations)
        // ...but nothing has actually been scanned yet, so the working file
        // that's meant to log real scans starts empty.
        val workingCopyProducts = locationsFile().inputStream().use { ExcelReader.readProductsFromStream(it) }.products
        assertTrue("nothing was scanned yet, so the working file must have no product rows", workingCopyProducts.isEmpty())

        // Actually scanning it — even at the exact location the source file
        // already listed — is what makes it appear.
        repository.updateProduct("ABC-123", "פילטר שמן טויוטה", "111", "A-01-05")
        repository.saveWorkingCopies()
        val afterScan = locationsFile().inputStream().use { ExcelReader.readProductsFromStream(it) }.products
        assertEquals(1, afterScan.size)
        assertEquals("A-01-05", afterScan.first().location)
    }

    @Test
    fun `saveWorkingCopies is the only thing that writes to disk — updateProduct alone never does`() = runBlocking {
        val sourceUri = writeSourceFile(
            listOf(ProductEntity("ABC-123", "פילטר שמן טויוטה", "111", "A-01-05", 0))
        )
        repository.loadFromExcel(sourceUri)
        val snapshotAfterLoad = locationsFile().readBytes()

        // A confirmed scan updates the database immediately, but must NOT
        // touch the working files on disk by itself — only an explicit save
        // (see MainActivity/ExcelActionsActivity) does that.
        repository.updateProduct("ABC-123", "פילטר שמן טויוטה", "111", "B-02-01")
        assertArrayEquals(snapshotAfterLoad, locationsFile().readBytes())
        val stillNothingOnDisk =
            locationsFile().inputStream().use { ExcelReader.readProductsFromStream(it) }.products
        assertTrue(stillNothingOnDisk.isEmpty())

        repository.saveWorkingCopies()

        // Only the row that was actually scanned (B-02-01) is written — the
        // pre-existing, never-rescanned A-01-05 row from the source file
        // stays out of the log.
        val afterSave = locationsFile().inputStream().use { ExcelReader.readProductsFromStream(it) }.products
        assertEquals(1, afterSave.size)
        assertEquals(setOf("B-02-01"), afterSave.map { it.location }.toSet())
    }

    @Test
    fun `updateQuantity in units mode stores the quantity directly on that row only`() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0),
                ProductEntity("ABC-123", "מוצר", "111", "B-02-01", 1)
            )
        )

        repository.updateQuantity("ABC-123", "A-01-05", "111", ProductEntity.TYPE_UNITS, 0, 0, 0, 15)

        val rows = db.productDao().findAllBySku("ABC-123")
        val updatedRow = rows.first { it.location == "A-01-05" }
        val untouchedRow = rows.first { it.location == "B-02-01" }
        assertEquals(ProductEntity.TYPE_UNITS, updatedRow.quantityType)
        assertEquals(15, updatedRow.quantity)
        assertEquals(0, untouchedRow.quantity) // quantity is per row, other locations are unaffected
    }

    @Test
    fun `updateQuantity in package mode stores the breakdown alongside the computed total`() = runBlocking {
        db.productDao().insertAll(
            listOf(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0))
        )

        repository.updateQuantity("ABC-123", "A-01-05", "111", ProductEntity.TYPE_PACKAGE, 12, 5, 0, 60)

        val row = db.productDao().findAllBySku("ABC-123").single()
        assertEquals(ProductEntity.TYPE_PACKAGE, row.quantityType)
        assertEquals(12, row.packageContent)
        assertEquals(5, row.packageCount)
        assertEquals(60, row.quantity)
    }

    /**
     * A row opened for a genuinely new (location, barcode) is cloned for the
     * product's identity, never for its count: the quantity it was cloned
     * from was counted at *that* shelf. The clone is marked scanned, so it
     * reaches the locations/quantities file straight away — inheriting the
     * count would report units nobody ever counted at the new shelf, and
     * would prefill the inventory screen as if they had been confirmed.
     */
    @Test
    fun `a new location's row starts empty instead of inheriting the count from another shelf`() = runBlocking {
        db.productDao().insertAll(
            listOf(ProductEntity("ABC-123", "מוצר", "111", "A-01", 0))
        )
        repository.updateProduct("ABC-123", "מוצר", "111", "A-01")
        repository.updateQuantity("ABC-123", "A-01", "111", ProductEntity.TYPE_MIXED, 12, 5, 7, 67)

        repository.updateProduct("ABC-123", "מוצר", "111", "B-02")

        val rows = db.productDao().findAllBySku("ABC-123").associateBy { it.location }
        assertEquals(2, rows.size)
        // The shelf that was actually counted keeps every bit of its count...
        val counted = rows.getValue("A-01")
        assertEquals(ProductEntity.TYPE_MIXED, counted.quantityType)
        assertEquals(7, counted.looseUnits)
        assertEquals(67, counted.quantity)
        // ...and the new one starts from nothing, in the default mode.
        val fresh = rows.getValue("B-02")
        assertEquals(ProductEntity.TYPE_UNITS, fresh.quantityType)
        assertEquals(0, fresh.packageContent)
        assertEquals(0, fresh.packageCount)
        assertEquals(0, fresh.looseUnits)
        assertEquals(0, fresh.quantity)
    }

    /**
     * The same rule as the cloned row, on the other path that places one:
     * a quantity sitting on a not-yet-shelved row came in on the source
     * file, not from anyone counting it at the shelf it is only now being
     * put on. Since this marks the row scanned, keeping that number would
     * send units nobody counted straight into the locations/quantities file.
     */
    @Test
    fun `shelving a blank row starts its count fresh instead of keeping the source file's`() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity(
                    "ABC-123", "מוצר", "", "", 0,
                    ProductEntity.TYPE_PACKAGE, 12, 5, quantity = 60
                )
            )
        )

        repository.updateProduct("ABC-123", "מוצר", "111", "A-01")

        val row = db.productDao().findAllBySku("ABC-123").single()
        // Still the same row, now placed and confirmed...
        assertEquals("A-01", row.location)
        assertEquals("111", row.barcode)
        assertEquals(true, row.scanned)
        // ...but counted from scratch, by the inventory screen, not by the file.
        assertEquals(ProductEntity.TYPE_UNITS, row.quantityType)
        assertEquals(0, row.packageContent)
        assertEquals(0, row.packageCount)
        assertEquals(0, row.looseUnits)
        assertEquals(0, row.quantity)
    }

    /**
     * The third path that places a row, and the one that used to leak. A row
     * arriving from the source file with a מיקום, a ברקוד *and* a quantity is
     * an exact match for the scan that first confirms it, so it takes neither
     * the blank-row nor the cloned-row path above and used to keep its count.
     * That count belongs to the file, not to anyone who counted this shelf —
     * and since the inventory screen prefills straight off this row, keeping
     * it put the figure the file expects in front of the worker before they
     * had counted a thing.
     */
    @Test
    fun `first confirming a source-file row that already has a location empties its count`() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity(
                    "ABC-123", "מוצר", "111", "A-01", 0,
                    ProductEntity.TYPE_PACKAGE, 12, 5, quantity = 60
                )
            )
        )

        repository.updateProduct("ABC-123", "מוצר", "111", "A-01")

        val row = db.productDao().findAllBySku("ABC-123").single()
        // Same row, same spot, now confirmed by a real scan...
        assertEquals("A-01", row.location)
        assertEquals("111", row.barcode)
        assertEquals(true, row.scanned)
        // ...with nothing left on it for the worker to count against.
        assertEquals(ProductEntity.TYPE_UNITS, row.quantityType)
        assertEquals(0, row.packageContent)
        assertEquals(0, row.packageCount)
        assertEquals(0, row.looseUnits)
        assertEquals(0, row.quantity)
    }

    /** Emptying happens on that first confirm only — a real count made afterwards stands. */
    @Test
    fun `a source-file row emptied on first confirm keeps the count made afterwards`() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity(
                    "ABC-123", "מוצר", "111", "A-01", 0,
                    ProductEntity.TYPE_PACKAGE, 12, 5, quantity = 60
                )
            )
        )
        repository.updateProduct("ABC-123", "מוצר", "111", "A-01")
        repository.updateQuantity("ABC-123", "A-01", "111", ProductEntity.TYPE_UNITS, 0, 0, 0, 43)

        repository.updateProduct("ABC-123", "מוצר", "111", "A-01")

        val row = db.productDao().findAllBySku("ABC-123").single()
        assertEquals(43, row.quantity)
        assertEquals(ProductEntity.TYPE_UNITS, row.quantityType)
    }

    /** Re-confirming a row already at this exact spot leaves the count that was made there alone. */
    @Test
    fun `re-scanning a row at its own location keeps the quantity already counted there`() = runBlocking {
        db.productDao().insertAll(listOf(ProductEntity("ABC-123", "מוצר", "111", "A-01", 0)))
        repository.updateProduct("ABC-123", "מוצר", "111", "A-01")
        repository.updateQuantity("ABC-123", "A-01", "111", ProductEntity.TYPE_MIXED, 12, 5, 7, 67)

        repository.updateProduct("ABC-123", "מוצר", "111", "A-01")

        val row = db.productDao().findAllBySku("ABC-123").single()
        assertEquals(ProductEntity.TYPE_MIXED, row.quantityType)
        assertEquals(7, row.looseUnits)
        assertEquals(67, row.quantity)
    }

    @Test
    fun `updateQuantity in mixed mode keeps the packages and the loose units on one row`() = runBlocking {
        db.productDao().insertAll(
            listOf(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0))
        )

        repository.updateQuantity("ABC-123", "A-01-05", "111", ProductEntity.TYPE_MIXED, 12, 5, 7, 67)

        val row = db.productDao().findAllBySku("ABC-123").single()
        assertEquals(ProductEntity.TYPE_MIXED, row.quantityType)
        assertEquals(12, row.packageContent)
        assertEquals(5, row.packageCount)
        assertEquals(7, row.looseUnits)
        // Both halves of the shelf, in one total, on the one row they share.
        assertEquals(67, row.quantity)
    }

    /**
     * The whole point of מעורב: a shelf holding sealed packages AND loose
     * singles of the same מקט under a single ברקוד is one row — both halves
     * have to reach the physical file together. Counting them as two scans
     * of that same barcode can't work: (sku, location, barcode) is one row,
     * so the second quantity would simply overwrite the first.
     */
    @Test
    fun `packages and loose units on the same shelf survive together in the saved file`() = runBlocking {
        val sourceUri = writeSourceFile(listOf(ProductEntity("ABC-123", "בורג", "111", "", 0)))
        repository.loadFromExcel(sourceUri, "products.xlsx")

        repository.updateProduct("ABC-123", "בורג", "111", "A-01")
        repository.updateQuantity("ABC-123", "A-01", "111", ProductEntity.TYPE_MIXED, 12, 5, 7, 67)
        repository.saveWorkingCopies()

        assertEquals(1, repository.count())
        val saved = locationsFile().inputStream().use { ExcelReader.readProductsFromStream(it) }.products.single()
        assertEquals("A-01", saved.location)
        assertEquals(ProductEntity.TYPE_MIXED, saved.quantityType)
        assertEquals(12, saved.packageContent)
        assertEquals(5, saved.packageCount)
        assertEquals(7, saved.looseUnits)
        assertEquals(67, saved.quantity)
    }

    @Test
    fun `switching a row from mixed back to plain units clears the loose count`() = runBlocking {
        db.productDao().insertAll(
            listOf(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0))
        )
        repository.updateQuantity("ABC-123", "A-01-05", "111", ProductEntity.TYPE_MIXED, 12, 5, 7, 67)

        repository.updateQuantity("ABC-123", "A-01-05", "111", ProductEntity.TYPE_UNITS, 0, 0, 0, 9)

        val row = db.productDao().findAllBySku("ABC-123").single()
        assertEquals(ProductEntity.TYPE_UNITS, row.quantityType)
        assertEquals(0, row.packageContent)
        assertEquals(0, row.packageCount)
        assertEquals(0, row.looseUnits)
        assertEquals(9, row.quantity)
    }

    /**
     * A count that gets questioned later has to be answerable, and neither
     * the scanned flag nor the quantity can say when a number was put there.
     * updateQuantity is the only thing that ever records a count, so it is
     * the only thing that can date one.
     */
    @Test
    fun `updateQuantity stamps the row with when it was counted`() = runBlocking {
        val pinned = 1_726_000_000_000L
        val stamped = ProductRepository(context, db.productDao(), db.barcodeDao(), prefs) { pinned }
        db.productDao().insertAll(listOf(ProductEntity("ABC-123", "מוצר", "111", "A-01", 0)))

        stamped.updateQuantity("ABC-123", "A-01", "111", ProductEntity.TYPE_UNITS, 0, 0, 0, 43)

        assertEquals(pinned, db.productDao().findAllBySku("ABC-123").single().countedAt)
    }

    /** Placing a row is not counting it — the stamp waits for a real count. */
    @Test
    fun `a row confirmed but not yet counted carries no stamp`() = runBlocking {
        db.productDao().insertAll(listOf(ProductEntity("ABC-123", "מוצר", "", "", 0)))

        repository.updateProduct("ABC-123", "מוצר", "111", "A-01")

        assertEquals(0L, db.productDao().findAllBySku("ABC-123").single().countedAt)
    }

    @Test
    fun `updateQuantity for a location with no row is a no-op`() = runBlocking {
        db.productDao().insertAll(
            listOf(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0))
        )

        repository.updateQuantity("ABC-123", "DOES-NOT-EXIST", "111", ProductEntity.TYPE_UNITS, 0, 0, 0, 15)

        val row = db.productDao().findAllBySku("ABC-123").single()
        assertEquals(0, row.quantity)
    }

    @Test
    fun `findRow returns the exact sku+location row, prefill-ready for the inventory screen`() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0, ProductEntity.TYPE_PACKAGE, 12, 5, quantity = 60),
                ProductEntity("ABC-123", "מוצר", "111", "B-02-01", 1)
            )
        )

        val row = repository.findRow("ABC-123", "A-01-05", "111")!!
        assertEquals(60, row.quantity)
        assertEquals(ProductEntity.TYPE_PACKAGE, row.quantityType)
        assertNull(repository.findRow("ABC-123", "NOWHERE", "111"))
    }

    /**
     * The same code claimed by one מקט on a product row and by another on
     * the barcodes sheet. Only one claim can survive, so the other product's
     * units would be counted onto the wrong מקט — the loader has to say so
     * rather than pick quietly.
     */
    @Test
    fun `a barcode two skus both claim is reported, and the sheet's owner wins`() = runBlocking {
        val sourceUri = writeSourceFile(
            listOf(
                ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0),
                ProductEntity("XYZ-9", "אחר", "222", "B-02-01", 1)
            ),
            // The sheet says 222 belongs to ABC-123; the product row says XYZ-9.
            listOf(BarcodeEntity(barcode = "222", sku = "ABC-123"))
        )

        val result = repository.loadFromExcel(sourceUri)

        assertEquals(1, result.duplicateBarcodeRows)
        // The sheet states ownership outright, so it is the claim that stands.
        assertEquals("ABC-123", repository.findByBarcode("222")!!.sku)
    }

    /** A code the sheet repeats for the same מקט is agreement, not a conflict. */
    @Test
    fun `a barcode both sheets agree on is not reported as a conflict`() = runBlocking {
        val sourceUri = writeSourceFile(
            listOf(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0)),
            listOf(BarcodeEntity(barcode = "111", sku = "ABC-123"))
        )

        val result = repository.loadFromExcel(sourceUri)

        assertEquals(0, result.duplicateBarcodeRows)
        assertEquals("ABC-123", repository.findByBarcode("111")!!.sku)
    }

    /**
     * The whole point of keeping a role on a code: the screens that count a
     * shelf can ask what this scan means instead of making the worker say it
     * again at every single scan.
     */
    @Test
    fun `barcodeInfo reports what a scan of the code means`() = runBlocking {
        val sourceUri = writeSourceFile(
            listOf(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0)),
            listOf(
                BarcodeEntity(barcode = "222", sku = "ABC-123", role = BarcodeEntity.ROLE_PACKAGE, packageContent = 12)
            )
        )
        repository.loadFromExcel(sourceUri)

        val pack = repository.barcodeInfo("222")!!
        assertEquals(BarcodeEntity.ROLE_PACKAGE, pack.role)
        assertEquals(12, pack.packageContent)

        // The product's own code was seeded too, as a plain unit — nothing is
        // guessed about packaging the file never stated.
        val primary = repository.barcodeInfo("111")!!
        assertEquals(BarcodeEntity.ROLE_UNIT, primary.role)
        assertEquals(0, primary.packageContent)

        assertNull(repository.barcodeInfo("nope"))
    }

    /**
     * A code first seen at a shelf has to resolve on the next scan of it, and
     * has to reach the barcodes working file — a second sticker found in the
     * field is the most valuable thing a count turns up, and it used to be
     * recorded nowhere but the product row it created.
     */
    @Test
    fun `a barcode discovered mid-count is registered under its sku`() = runBlocking {
        val sourceUri = writeSourceFile(listOf(ProductEntity("ABC-123", "מוצר", "111", "", 0)))
        repository.loadFromExcel(sourceUri)

        repository.updateProduct("ABC-123", "מוצר", "999", "A-01-05")

        assertEquals("ABC-123", repository.findByBarcode("999")!!.sku)
        // Defaults to a plain unit until someone actually says otherwise.
        assertEquals(BarcodeEntity.ROLE_UNIT, repository.barcodeInfo("999")!!.role)
    }

    /**
     * A role nothing recognises falls back to a plain unit, and the package
     * content has to fall with it: a row saying בודד while carrying a carton
     * size of 12 contradicts the rule the reader enforces on the very same
     * data, and would quietly feed a package screen a size for a code that
     * is not on packages.
     */
    @Test
    fun `an unrecognised role drops its package content too`() = runBlocking {
        db.productDao().insertAll(listOf(ProductEntity("ABC-123", "מוצר", "", "", 0)))

        repository.registerBarcode("999", "ABC-123", "קרטון", 12)

        val stored = repository.barcodeInfo("999")!!
        assertEquals(BarcodeEntity.ROLE_UNIT, stored.role)
        assertEquals(0, stored.packageContent)
    }

    /** A code already owned by another מקט is never quietly stolen by a scan. */
    @Test
    fun `registering a barcode never moves it off the sku that already owns it`() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0),
                ProductEntity("XYZ-9", "אחר", "", "", 1)
            )
        )
        repository.registerBarcode("111", "ABC-123")

        repository.registerBarcode("111", "XYZ-9")

        assertEquals("ABC-123", repository.findByBarcode("111")!!.sku)
    }

    @Test
    fun `loadFromExcel also loads barcode aliases from the ברקודים כפולים sheet`() = runBlocking {
        val sourceUri = writeSourceFile(
            listOf(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0)),
            listOf(BarcodeEntity(barcode = "222", sku = "ABC-123"))
        )

        repository.loadFromExcel(sourceUri)

        val viaAlias = repository.findByBarcode("222")!!
        assertEquals("ABC-123", viaAlias.sku)
    }

    /**
     * A different barcode scanned at a location the sku already has (on file,
     * unscanned) opens its own row in the locations/quantities file — the
     * count of that shelf is per (מקט, מיקום, ברקוד), so the second code is
     * its own line, not a footnote on the first. See [ProductEntity].
     *
     * The code itself is also registered, which is the whole point of
     * discovering one mid-count: it has to resolve on the next scan, and it
     * has to reach the multiple-barcodes file, where a second sticker found
     * in the field is the most valuable thing a count turns up.
     */
    @Test
    fun `a different barcode scanned at an already-known location opens its own row and registers the code`() = runBlocking {
        val sourceUri = writeSourceFile(listOf(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0)))
        repository.loadFromExcel(sourceUri)

        repository.updateProduct("ABC-123", "מוצר", "222", "A-01-05")
        repository.saveWorkingCopies()

        val barcodes = barcodesFile().inputStream().use { ExcelReader.readMultipleBarcodesFromStream(it) }
        assertEquals(setOf("111", "222"), barcodes.map { it.barcode }.toSet())
        assertEquals("ABC-123", repository.findByBarcode("222")!!.sku)

        // Only the newly-scanned row shows up — the source file's own 111
        // row was never itself rescanned, so it stays out of the log.
        val products = locationsFile().inputStream().use { ExcelReader.readProductsFromStream(it) }.products
        val row = products.single()
        assertEquals("ABC-123", row.sku)
        assertEquals("A-01-05", row.location)
        assertEquals("222", row.barcode)
    }

    @Test
    fun `reassignBarcode moves the wrongly-scanned row to the right sku, at the same location`() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity("WRONG-1", "מוצר שגוי", "111", "A-01-05", 0),
                ProductEntity("RIGHT-1", "מוצר נכון", "", "B-02-01", 1)
            )
        )

        repository.reassignBarcode("111", "RIGHT-1")

        assertEquals("RIGHT-1", repository.findByBarcode("111")!!.sku)
        // The right sku gets a proper new row at the location the barcode was
        // actually scanned at, alongside its existing one.
        val rightRows = db.productDao().findAllBySku("RIGHT-1")
        assertEquals(setOf("A-01-05", "B-02-01"), rightRows.map { it.location }.toSet())
        assertEquals("111", rightRows.single { it.location == "A-01-05" }.barcode)
        // WRONG-1 is not lost (its row is cleared, not deleted, since it was
        // its only one) and no longer carries the misassigned barcode.
        val wrongRow = db.productDao().findAllBySku("WRONG-1").single()
        assertEquals("", wrongRow.barcode)
        assertEquals("", wrongRow.location)
    }

    @Test
    fun `reassignBarcode when the wrong sku has other rows too just deletes the misassigned one`() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity("WRONG-1", "מוצר שגוי", "111", "A-01-05", 0),
                ProductEntity("WRONG-1", "מוצר שגוי", "222", "C-03-01", 1),
                ProductEntity("RIGHT-1", "מוצר נכון", "999", "B-02-01", 2)
            )
        )

        repository.reassignBarcode("111", "RIGHT-1")

        assertEquals("RIGHT-1", repository.findByBarcode("111")!!.sku)
        // RIGHT-1 keeps its existing row and gains a new one for the moved barcode.
        val rightRows = db.productDao().findAllBySku("RIGHT-1")
        assertEquals(setOf("A-01-05", "B-02-01"), rightRows.map { it.location }.toSet())
        // WRONG-1's other, unrelated row survives untouched.
        assertEquals(listOf("C-03-01"), db.productDao().findAllBySku("WRONG-1").map { it.location })
    }

    /**
     * Moving a code to the right מקט must not throw away what it says about
     * packaging. That answer can be a worker's own, given once when the code
     * was first scanned, and it is never asked for again — the code counts
     * as known from then on. Losing it would leave every later scan of it
     * dividing a carton by its contents, silently.
     */
    @Test
    fun `reassignBarcode keeps what the code says about packaging`() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity("WRONG-1", "מוצר שגוי", "111", "A-01-05", 0),
                ProductEntity("RIGHT-1", "מוצר נכון", "", "", 1)
            )
        )
        repository.registerBarcode("111", "WRONG-1", BarcodeEntity.ROLE_PACKAGE, 12)

        repository.reassignBarcode("111", "RIGHT-1")

        val moved = repository.barcodeInfo("111")!!
        // The product changed...
        assertEquals("RIGHT-1", moved.sku)
        // ...the carton it is stuck on did not.
        assertEquals(BarcodeEntity.ROLE_PACKAGE, moved.role)
        assertEquals(12, moved.packageContent)
    }

    @Test
    fun `reassignBarcode to an unknown sku is a no-op, keeping the existing link intact`() = runBlocking {
        db.productDao().insertAll(
            listOf(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0))
        )
        repository.registerBarcode("111", "ABC-123")

        repository.reassignBarcode("111", "DOES-NOT-EXIST")

        assertEquals("ABC-123", repository.findByBarcode("111")!!.sku)
    }

    @Test
    fun `reassignBarcode for a barcode nothing has scanned yet is a no-op — there is no mistaken row to move`() = runBlocking {
        db.productDao().insertAll(
            listOf(ProductEntity("ABC-123", "מוצר", "", "A-01-05", 0))
        )

        repository.reassignBarcode("555", "ABC-123")

        assertNull(repository.findByBarcode("555"))
        assertEquals(1, repository.count())
    }

    @Test
    fun `reassignBarcode is a no-op when the barcode already belongs to newSku`() = runBlocking {
        db.productDao().insertAll(
            listOf(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0))
        )

        repository.reassignBarcode("111", "ABC-123")

        val row = db.productDao().findAllBySku("ABC-123").single()
        assertEquals("A-01-05", row.location)
        assertEquals("111", row.barcode)
    }

    @Test
    fun `removeFromLocation deletes just that row when the sku has other locations too`() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0),
                ProductEntity("ABC-123", "מוצר", "111", "B-02-01", 1)
            )
        )

        val row = db.productDao().findBySkuLocationAndBarcode("ABC-123", "A-01-05", "111")!!
        repository.removeFromLocation(row)

        assertEquals(1, repository.count())
        val remaining = repository.findBySku("ABC-123")!!
        assertEquals(listOf("B-02-01"), remaining.existingLocations)
    }

    @Test
    fun `removeFromLocation on a sku's only location clears the row instead of deleting it`() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity(
                    "ABC-123", "מוצר", "111", "A-01-05", 0,
                    ProductEntity.TYPE_PACKAGE, 12, 5, quantity = 60
                )
            )
        )

        val existing = db.productDao().findBySkuLocationAndBarcode("ABC-123", "A-01-05", "111")!!
        repository.removeFromLocation(existing)

        // The row survives (sku/description aren't lost, only its barcode)...
        assertEquals(1, repository.count())
        val row = db.productDao().findAllBySku("ABC-123").single()
        assertEquals("ABC-123", row.sku)
        assertEquals("", row.barcode)
        // ...but no longer sits at any location, and its quantity was reset.
        assertEquals("", row.location)
        assertEquals(ProductEntity.TYPE_UNITS, row.quantityType)
        assertEquals(0, row.quantity)
        assertTrue(repository.findBySku("ABC-123")!!.existingLocations.isEmpty())
    }

    @Test
    fun `removeFromLocation for a row that isn't actually in the database is a no-op`() = runBlocking {
        db.productDao().insertAll(
            listOf(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0))
        )

        // A row the UI would never actually pass in (bogus id, doesn't match
        // any real row) — must not disturb the real one.
        repository.removeFromLocation(ProductEntity("ABC-123", "מוצר", "111", "NOWHERE", 99, id = 999))

        assertEquals(1, repository.count())
        assertEquals(listOf("A-01-05"), repository.findBySku("ABC-123")!!.existingLocations)
    }

    @Test
    fun `removeFromLocation deletes the right row without disturbing an existing blank row for the same sku`() = runBlocking {
        // Can happen after an Excel import that already had a not-yet-placed
        // row alongside a real one for the same מקט.
        db.productDao().insertAll(
            listOf(
                ProductEntity("ABC-123", "מוצר", "111", "", 0),
                ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 1)
            )
        )

        val placedRow = db.productDao().findBySkuLocationAndBarcode("ABC-123", "A-01-05", "111")!!
        repository.removeFromLocation(placedRow)

        val remaining = db.productDao().findAllBySku("ABC-123").single()
        assertEquals("", remaining.location)
        assertEquals(1, repository.count())
    }

    @Test
    fun `removeFromLocation, like updateProduct, never writes to disk by itself`() = runBlocking {
        val sourceUri = writeSourceFile(
            listOf(ProductEntity("ABC-123", "פילטר שמן טויוטה", "111", "A-01-05", 0))
        )
        repository.loadFromExcel(sourceUri)
        val snapshotAfterLoad = locationsFile().readBytes()

        val row = db.productDao().findBySkuLocationAndBarcode("ABC-123", "A-01-05", "111")!!
        repository.removeFromLocation(row)

        assertArrayEquals(snapshotAfterLoad, locationsFile().readBytes())
        // The in-memory change did take effect — only the disk write is deferred.
        assertEquals("", db.productDao().findAllBySku("ABC-123").single().location)
    }

    @Test
    fun `loading a different source file replaces the working copy, not merges with it`() = runBlocking {
        repository.loadFromExcel(writeSourceFile(listOf(ProductEntity("OLD", "old product", "", "A-01-01", 0))))
        repository.loadFromExcel(writeSourceFile(listOf(ProductEntity("NEW", "new product", "", "A-01-02", 0))))

        assertEquals(1, repository.count())
        assertNull(repository.findBySku("OLD"))
        assertEquals("new product", repository.findBySku("NEW")!!.description)
    }

    // --- Two separate working files (task spec sections 2-5) ---------------

    @Test
    fun `loading a source file creates two distinctly-named files, both valid and non-empty`() = runBlocking {
        repository.loadFromExcel(writeSourceFile(listOf(ProductEntity("A", "x", "", "A-01-01", 0))), "מלאי.xlsx")

        assertTrue(locationsFile().exists())
        assertTrue(barcodesFile().exists())
        assertTrue("the two working files must never share a name", locationsFile().name != barcodesFile().name)
        assertTrue(locationsFile().length() > 0)
        assertTrue(barcodesFile().length() > 0)
    }

    @Test
    fun `saveLocationsQuantitiesFile updates only that file, leaving the barcodes file untouched`() = runBlocking {
        repository.loadFromExcel(writeSourceFile(listOf(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0))))
        val barcodesSnapshot = barcodesFile().readBytes()

        repository.updateProduct("ABC-123", "מוצר", "111", "B-02-01")
        repository.saveLocationsQuantitiesFile()

        // Only the newly-scanned B-02-01 row is written — the source file's
        // own A-01-05 row was never actually (re)scanned in this app.
        val updated = locationsFile().inputStream().use { ExcelReader.readProductsFromStream(it) }.products
        assertEquals(1, updated.size)
        assertEquals("B-02-01", updated.single().location)
        assertArrayEquals("saving just the locations file must not rewrite the barcodes file", barcodesSnapshot, barcodesFile().readBytes())
    }

    @Test
    fun `saveMultipleBarcodesFile updates only that file, leaving the locations file untouched`() = runBlocking {
        // The barcodes file holds every code the count knows — the product's
        // own 111 as well as the 222 the source file's "ברקודים כפולים"
        // sheet adds — so both are expected below.
        repository.loadFromExcel(
            writeSourceFile(
                listOf(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0)),
                listOf(BarcodeEntity(barcode = "222", sku = "ABC-123"))
            )
        )
        repository.updateProduct("ABC-123", "מוצר", "111", "B-02-01") // a new location, in memory only so far
        val locationsSnapshot = locationsFile().readBytes()

        repository.saveMultipleBarcodesFile()

        val barcodes = barcodesFile().inputStream().use { ExcelReader.readMultipleBarcodesFromStream(it) }
        assertEquals(setOf("111", "222"), barcodes.map { it.barcode }.toSet())
        assertArrayEquals("saving just the barcodes file must not rewrite the locations file", locationsSnapshot, locationsFile().readBytes())
    }

    @Test
    fun `a save that cannot actually write to disk throws instead of silently succeeding`() = runBlocking {
        repository.loadFromExcel(writeSourceFile(listOf(ProductEntity("A", "x", "", "A-01-01", 0))))
        val target = locationsFile()
        val snapshotBeforeFailure = target.readBytes()

        // Replace the working file's path with a directory of the same name,
        // so opening it for writing fails with a real IOException — simulates
        // "the file is unavailable" from the task spec's error-handling requirement.
        target.delete()
        target.mkdirs()
        try {
            try {
                repository.saveWorkingCopies()
                fail("expected ExcelSaveException when the file cannot be written")
            } catch (e: ExcelSaveException) {
                // expected — the caller must not report success on this path.
            }
        } finally {
            target.delete() // remove the directory so later assertions/teardown see a normal file path again
            target.writeBytes(snapshotBeforeFailure) // restore, in case something else in this test method still reads it
        }
    }

    // --- Surviving the app being closed and reopened (task spec section 3) ---

    @Test
    fun `a freshly-constructed repository (simulating an app restart) still knows which working files to keep using`() = runBlocking {
        AppDatabase.resetForTests()
        val realDb = AppDatabase.getInstance(context) // file-backed, unlike this test class's in-memory db
        val realPrefs = SessionPrefs(context)
        val firstRunRepository = ProductRepository(context, realDb.productDao(), realDb.barcodeDao(), realPrefs)

        firstRunRepository.loadFromExcel(writeSourceFile(listOf(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0))), "מלאי.xlsx")
        firstRunRepository.updateProduct("ABC-123", "מוצר", "111", "B-02-01")
        firstRunRepository.saveWorkingCopies()

        val locationsNameBefore = realPrefs.locationsQuantitiesFileName
        val barcodesNameBefore = realPrefs.multipleBarcodesFileName

        // "Restart the app": drop the Room singleton and rebuild every
        // wrapper object fresh — only what's actually persisted (the
        // SharedPreferences file and the sqlite file) survives this.
        AppDatabase.resetForTests()
        val reopenedDb = AppDatabase.getInstance(context)
        val reopenedPrefs = SessionPrefs(context)
        val reopenedRepository = ProductRepository(context, reopenedDb.productDao(), reopenedDb.barcodeDao(), reopenedPrefs)

        assertEquals(locationsNameBefore, reopenedPrefs.locationsQuantitiesFileName)
        assertEquals(barcodesNameBefore, reopenedPrefs.multipleBarcodesFileName)
        assertEquals(2, reopenedRepository.count()) // both location rows survived, from the real sqlite file

        // Scanning can continue right away, still writing to the very same files.
        reopenedRepository.updateProduct("ABC-123", "מוצר", "111", "C-03-01")
        reopenedRepository.saveWorkingCopies()

        // Only the two rows actually scanned across both "runs" (B-02-01 then
        // C-03-01) are in the log — the source file's own A-01-05 row was
        // never itself rescanned.
        val finalProducts = reopenedRepository.locationsQuantitiesFile()!!
            .inputStream().use { ExcelReader.readProductsFromStream(it) }.products
        assertEquals(2, finalProducts.size)
        assertEquals(setOf("B-02-01", "C-03-01"), finalProducts.map { it.location }.toSet())
        assertEquals(locationsNameBefore, reopenedRepository.locationsQuantitiesFile()!!.name)

        AppDatabase.resetForTests()
    }
}
