package com.warehouse.stockscanner.data

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.warehouse.stockscanner.excel.ExcelReader
import com.warehouse.stockscanner.excel.ExcelWriter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream

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

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ProductRepository(context, db.productDao(), db.barcodeAliasDao())
    }

    /** Writes [products] (and optionally [aliases]) as a real .xlsx to a temp file, returning a Uri as if picked via SAF. */
    private fun writeSourceFile(products: List<ProductEntity>, aliases: List<BarcodeAliasEntity> = emptyList()): Uri {
        val file = File.createTempFile("source", ".xlsx", context.cacheDir)
        FileOutputStream(file).use { ExcelWriter.writeProductsToStream(it, products, aliases) }
        return Uri.fromFile(file)
    }

    private fun workingCopyFile() = File(context.filesDir, "working_products.xlsx")

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
    fun `updateProduct syncs description across every location row but never overwrites an existing primary barcode`() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity("ABC-123", "ישן", "111", "A-01-05", 0),
                ProductEntity("ABC-123", "ישן", "111", "B-02-01", 1)
            )
        )

        // A different barcode than the sku's existing one — e.g. the product
        // was found via description search after an unrecognized scan.
        repository.updateProduct("ABC-123", "חדש", "222", "C-03-01")

        assertEquals(3, repository.count())
        val rows = db.productDao().findAllBySku("ABC-123")
        assertTrue(rows.all { it.description == "חדש" })
        assertTrue("the original primary barcode must survive untouched", rows.all { it.barcode == "111" })
        assertEquals(setOf("A-01-05", "B-02-01", "C-03-01"), rows.map { it.location }.toSet())
    }

    @Test
    fun `a barcode scanned for a sku that already has one is saved as an alias, not a replacement`() = runBlocking {
        db.productDao().insertAll(
            listOf(ProductEntity("ABC-123", "פילטר שמן", "111", "A-01-05", 0))
        )

        repository.updateProduct("ABC-123", "פילטר שמן", "222", "A-01-05")

        // Both the original and the new barcode now resolve to the same product.
        assertEquals("ABC-123", repository.findByBarcode("111")!!.sku)
        val viaAlias = repository.findByBarcode("222")!!
        assertEquals("ABC-123", viaAlias.sku)
        assertEquals("פילטר שמן", viaAlias.description)

        // The row itself still only carries the original primary barcode.
        assertEquals("111", db.productDao().findAllBySku("ABC-123").single().barcode)
    }

    @Test
    fun `confirming the same alias barcode again does not duplicate it or error`() = runBlocking {
        db.productDao().insertAll(
            listOf(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0))
        )

        repository.updateProduct("ABC-123", "מוצר", "222", "A-01-05")
        repository.updateProduct("ABC-123", "מוצר", "222", "A-01-05")

        assertEquals("ABC-123", repository.findByBarcode("222")!!.sku)
    }

    @Test
    fun `a barcode already aliased to one sku is never stolen by another`() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity("ABC-123", "מוצר א", "111", "A-01-05", 0),
                ProductEntity("XYZ-999", "מוצר ב", "333", "B-02-01", 1)
            )
        )
        repository.updateProduct("ABC-123", "מוצר א", "222", "A-01-05")

        // A later attempt to alias the same barcode to a different sku is a no-op.
        repository.updateProduct("XYZ-999", "מוצר ב", "222", "B-02-01")

        assertEquals("ABC-123", repository.findByBarcode("222")!!.sku)
    }

    @Test
    fun `first-time barcode for a sku that had none yet still becomes the primary, across every row`() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity("ABC-123", "מוצר", "", "A-01-05", 0),
                ProductEntity("ABC-123", "מוצר", "", "B-02-01", 1)
            )
        )

        repository.updateProduct("ABC-123", "מוצר", "111", "C-03-01")

        val rows = db.productDao().findAllBySku("ABC-123")
        assertTrue(rows.all { it.barcode == "111" })
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
    fun `loadFromExcel creates a private working copy immediately, separate from the source file`() = runBlocking {
        val sourceUri = writeSourceFile(
            listOf(ProductEntity("ABC-123", "פילטר שמן טויוטה", "111", "A-01-05", 0))
        )

        assertFalse("no working copy should exist before any file is loaded", workingCopyFile().exists())

        repository.loadFromExcel(sourceUri)

        assertTrue("loading a file must create the working copy right away", workingCopyFile().exists())
        val workingCopyProducts = workingCopyFile().inputStream().use { ExcelReader.readProductsFromStream(it) }.products
        assertEquals(1, workingCopyProducts.size)
        assertEquals("ABC-123", workingCopyProducts.first().sku)
        assertEquals("A-01-05", workingCopyProducts.first().location)
    }

    @Test
    fun `saveWorkingCopy is the only thing that writes to disk — updateProduct alone never does`() = runBlocking {
        val sourceUri = writeSourceFile(
            listOf(ProductEntity("ABC-123", "פילטר שמן טויוטה", "111", "A-01-05", 0))
        )
        repository.loadFromExcel(sourceUri)
        val snapshotAfterLoad = workingCopyFile().readBytes()

        // A confirmed scan updates the database immediately, but must NOT
        // touch the working copy on disk by itself — only an explicit save
        // (see MainActivity/ExcelActionsActivity) does that.
        repository.updateProduct("ABC-123", "פילטר שמן טויוטה", "111", "B-02-01")
        assertArrayEquals(snapshotAfterLoad, workingCopyFile().readBytes())
        val stillOnlyOneLocationOnDisk =
            workingCopyFile().inputStream().use { ExcelReader.readProductsFromStream(it) }.products
        assertEquals(1, stillOnlyOneLocationOnDisk.size)

        repository.saveWorkingCopy()

        val afterSave = workingCopyFile().inputStream().use { ExcelReader.readProductsFromStream(it) }.products
        assertEquals(2, afterSave.size)
        assertEquals(setOf("A-01-05", "B-02-01"), afterSave.map { it.location }.toSet())
    }

    @Test
    fun `updateQuantity in units mode stores the quantity directly on that row only`() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0),
                ProductEntity("ABC-123", "מוצר", "111", "B-02-01", 1)
            )
        )

        repository.updateQuantity("ABC-123", "A-01-05", ProductEntity.TYPE_UNITS, 0, 0, 15)

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

        repository.updateQuantity("ABC-123", "A-01-05", ProductEntity.TYPE_PACKAGE, 12, 5, 60)

        val row = db.productDao().findAllBySku("ABC-123").single()
        assertEquals(ProductEntity.TYPE_PACKAGE, row.quantityType)
        assertEquals(12, row.packageContent)
        assertEquals(5, row.packageCount)
        assertEquals(60, row.quantity)
    }

    @Test
    fun `updateQuantity for a location with no row is a no-op`() = runBlocking {
        db.productDao().insertAll(
            listOf(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0))
        )

        repository.updateQuantity("ABC-123", "DOES-NOT-EXIST", ProductEntity.TYPE_UNITS, 0, 0, 15)

        val row = db.productDao().findAllBySku("ABC-123").single()
        assertEquals(0, row.quantity)
    }

    @Test
    fun `findRow returns the exact sku+location row, prefill-ready for the inventory screen`() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0, ProductEntity.TYPE_PACKAGE, 12, 5, 60),
                ProductEntity("ABC-123", "מוצר", "111", "B-02-01", 1)
            )
        )

        val row = repository.findRow("ABC-123", "A-01-05")!!
        assertEquals(60, row.quantity)
        assertEquals(ProductEntity.TYPE_PACKAGE, row.quantityType)
        assertNull(repository.findRow("ABC-123", "NOWHERE"))
    }

    @Test
    fun `loadFromExcel also loads barcode aliases from the ברקודים כפולים sheet`() = runBlocking {
        val sourceUri = writeSourceFile(
            listOf(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0)),
            listOf(BarcodeAliasEntity(barcode = "222", sku = "ABC-123"))
        )

        repository.loadFromExcel(sourceUri)

        val viaAlias = repository.findByBarcode("222")!!
        assertEquals("ABC-123", viaAlias.sku)
    }

    @Test
    fun `saveWorkingCopy persists barcode aliases so they survive a reload`() = runBlocking {
        val sourceUri = writeSourceFile(listOf(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0)))
        repository.loadFromExcel(sourceUri)

        repository.updateProduct("ABC-123", "מוצר", "222", "A-01-05")
        repository.saveWorkingCopy()

        val reloaded = workingCopyFile().inputStream().use { ExcelReader.readProductsFromStream(it) }
        assertEquals(listOf(BarcodeAliasEntity(barcode = "222", sku = "ABC-123")), reloaded.barcodeAliases)
    }

    @Test
    fun `reassignBarcode moves a primary barcode from the wrong sku to the right one`() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity("WRONG-1", "מוצר שגוי", "111", "A-01-05", 0),
                ProductEntity("RIGHT-1", "מוצר נכון", "", "B-02-01", 1)
            )
        )

        repository.reassignBarcode("111", "RIGHT-1")

        assertEquals("RIGHT-1", repository.findByBarcode("111")!!.sku)
        // The old owner no longer carries the misassigned barcode.
        assertEquals("", db.productDao().findAllBySku("WRONG-1").single().barcode)
        assertEquals("111", db.productDao().findAllBySku("RIGHT-1").single().barcode)
    }

    @Test
    fun `reassignBarcode attaches as an alias when the new sku already has a primary barcode`() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity("WRONG-1", "מוצר שגוי", "111", "A-01-05", 0),
                ProductEntity("RIGHT-1", "מוצר נכון", "999", "B-02-01", 1)
            )
        )

        repository.reassignBarcode("111", "RIGHT-1")

        assertEquals("RIGHT-1", repository.findByBarcode("111")!!.sku)
        assertEquals("999", db.productDao().findAllBySku("RIGHT-1").single().barcode) // primary untouched
        assertEquals("", db.productDao().findAllBySku("WRONG-1").single().barcode)
    }

    @Test
    fun `reassignBarcode moves an aliased barcode to the correct sku instead of the mistaken one`() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity("WRONG-1", "מוצר שגוי", "111", "A-01-05", 0),
                ProductEntity("RIGHT-1", "מוצר נכון", "", "B-02-01", 1)
            )
        )
        // "222" was mistakenly learned as an alias of WRONG-1.
        repository.updateProduct("WRONG-1", "מוצר שגוי", "222", "A-01-05")
        assertEquals("WRONG-1", repository.findByBarcode("222")!!.sku)

        repository.reassignBarcode("222", "RIGHT-1")

        assertEquals("RIGHT-1", repository.findByBarcode("222")!!.sku)
        // WRONG-1's own primary barcode is unaffected — only the alias moved.
        assertEquals("111", db.productDao().findAllBySku("WRONG-1").single().barcode)
    }

    @Test
    fun `reassignBarcode to an unknown sku is a no-op, keeping the existing link intact`() = runBlocking {
        db.productDao().insertAll(
            listOf(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0))
        )

        repository.reassignBarcode("111", "DOES-NOT-EXIST")

        assertEquals("ABC-123", repository.findByBarcode("111")!!.sku)
    }

    @Test
    fun `reassignBarcode for a barcode with no existing link simply attaches it fresh`() = runBlocking {
        db.productDao().insertAll(
            listOf(ProductEntity("ABC-123", "מוצר", "", "A-01-05", 0))
        )

        repository.reassignBarcode("555", "ABC-123")

        assertEquals("ABC-123", repository.findByBarcode("555")!!.sku)
    }

    @Test
    fun `removeFromLocation deletes just that row when the sku has other locations too`() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0),
                ProductEntity("ABC-123", "מוצר", "111", "B-02-01", 1)
            )
        )

        repository.removeFromLocation("ABC-123", "A-01-05")

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
                    ProductEntity.TYPE_PACKAGE, 12, 5, 60
                )
            )
        )

        repository.removeFromLocation("ABC-123", "A-01-05")

        // The row survives (sku/description/barcode aren't lost)...
        assertEquals(1, repository.count())
        val row = db.productDao().findAllBySku("ABC-123").single()
        assertEquals("ABC-123", row.sku)
        assertEquals("111", row.barcode)
        // ...but no longer sits at any location, and its quantity was reset.
        assertEquals("", row.location)
        assertEquals(ProductEntity.TYPE_UNITS, row.quantityType)
        assertEquals(0, row.quantity)
        assertTrue(repository.findBySku("ABC-123")!!.existingLocations.isEmpty())
    }

    @Test
    fun `removeFromLocation for a sku with no row at that location is a no-op`() = runBlocking {
        db.productDao().insertAll(
            listOf(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0))
        )

        repository.removeFromLocation("ABC-123", "NOWHERE")

        assertEquals(1, repository.count())
        assertEquals(listOf("A-01-05"), repository.findBySku("ABC-123")!!.existingLocations)
    }

    @Test
    fun `loading a different source file replaces the working copy, not merges with it`() = runBlocking {
        repository.loadFromExcel(writeSourceFile(listOf(ProductEntity("OLD", "old product", "", "A-01-01", 0))))
        repository.loadFromExcel(writeSourceFile(listOf(ProductEntity("NEW", "new product", "", "A-01-02", 0))))

        assertEquals(1, repository.count())
        assertNull(repository.findBySku("OLD"))
        assertEquals("new product", repository.findBySku("NEW")!!.description)
    }
}
