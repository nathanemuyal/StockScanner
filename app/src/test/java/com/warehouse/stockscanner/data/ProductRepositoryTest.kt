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
        repository = ProductRepository(context, db.productDao())
    }

    /** Writes [products] as a real .xlsx to a temp file and returns a Uri to it, as if picked via SAF. */
    private fun writeSourceFile(products: List<ProductEntity>): Uri {
        val file = File.createTempFile("source", ".xlsx", context.cacheDir)
        FileOutputStream(file).use { ExcelWriter.writeProductsToStream(it, products) }
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
    fun `updateProduct keeps description and barcode in sync across every location row`() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity("ABC-123", "ישן", "111", "A-01-05", 0),
                ProductEntity("ABC-123", "ישן", "111", "B-02-01", 1)
            )
        )

        repository.updateProduct("ABC-123", "חדש", "222", "C-03-01")

        assertEquals(3, repository.count())
        val rows = db.productDao().findAllBySku("ABC-123")
        assertTrue(rows.all { it.description == "חדש" && it.barcode == "222" })
        assertEquals(setOf("A-01-05", "B-02-01", "C-03-01"), rows.map { it.location }.toSet())
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
    fun `loading a different source file replaces the working copy, not merges with it`() = runBlocking {
        repository.loadFromExcel(writeSourceFile(listOf(ProductEntity("OLD", "old product", "", "A-01-01", 0))))
        repository.loadFromExcel(writeSourceFile(listOf(ProductEntity("NEW", "new product", "", "A-01-02", 0))))

        assertEquals(1, repository.count())
        assertNull(repository.findBySku("OLD"))
        assertEquals("new product", repository.findBySku("NEW")!!.description)
    }
}
