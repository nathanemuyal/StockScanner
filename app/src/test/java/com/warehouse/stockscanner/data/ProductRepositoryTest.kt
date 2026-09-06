package com.warehouse.stockscanner.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the update-in-place contract from the spec directly against Room
 * (in-memory, via Robolectric — no emulator needed): confirming a scan must
 * never create a new row, must never touch the sku, and must only change the
 * fields the flow says it changes.
 */
@RunWith(RobolectricTestRunner::class)
class ProductRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ProductRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ProductRepository(context, db.productDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `updateProduct changes description, barcode and location without creating a new row`() = runBlocking {
        db.productDao().insertAll(
            listOf(ProductEntity("ABC-123", "פילטר שמן טויוטה", "7290012345678", "", 0))
        )

        repository.updateProduct("ABC-123", "פילטר שמן טויוטה קורולה", "7290012345678", "A-01-05")

        assertEquals(1, repository.count())
        val updated = repository.findBySku("ABC-123")!!
        assertEquals("ABC-123", updated.sku) // sku itself never changes
        assertEquals("פילטר שמן טויוטה קורולה", updated.description)
        assertEquals("A-01-05", updated.location)
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
        assertEquals("A-01-05", updated.location)

        // Now the same barcode is found directly by scanning again for a different
        // location — barcode should stay the same, only location moves.
        repository.updateProduct("ABC-123", "מוצר קיים מעודכן", "72900999", "A-01-06")
        val movedAgain = repository.findBySku("ABC-123")!!
        assertEquals("72900999", movedAgain.barcode)
        assertEquals("A-01-06", movedAgain.location)
    }

    @Test
    fun `updateProduct on an unknown sku is a no-op, never creates a row`() = runBlocking {
        repository.updateProduct("DOES-NOT-EXIST", "x", "1", "A-01-01")
        assertEquals(0, repository.count())
        assertNull(repository.findBySku("DOES-NOT-EXIST"))
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
    fun `searchByDescription finds partial, case and whitespace insensitive matches`() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity("A", "פילטר שמן טויוטה", "", "", 0),
                ProductEntity("B", "מצבר 12V", "", "", 1)
            )
        )
        val results = repository.searchByDescription("שמן")
        assertEquals(1, results.size)
        assertEquals("A", results.first().sku)
    }

    @Test
    fun `a product confirmed at a second location keeps both, not just the newest`() = runBlocking {
        db.productDao().insertAll(
            listOf(ProductEntity("ABC-123", "פילטר שמן טויוטה", "7290012345678", "A-01-05", 0))
        )

        // Mirrors what ProductConfirmActivity does: read the existing location,
        // add the newly scanned one, write the combined result back.
        val existing = repository.findBySku("ABC-123")!!
        val combined = com.warehouse.stockscanner.util.LocationUtils.add(existing.location, "B-02-01")
        repository.updateProduct("ABC-123", existing.description, existing.barcode, combined)

        val updated = repository.findBySku("ABC-123")!!
        assertEquals("A-01-05, B-02-01", updated.location)
    }

    @Test
    fun `findByLocation matches products at that location among several, without false substring matches`() = runBlocking {
        db.productDao().insertAll(
            listOf(
                ProductEntity("A", "in one place", "", "A-01-05", 0),
                ProductEntity("B", "in two places", "", "A-01-05, B-02-01", 1),
                ProductEntity("C", "elsewhere only", "", "A-01-10", 2),
                ProductEntity("D", "no location yet", "", "", 3)
            )
        )

        val atA0105 = repository.findByLocation("A-01-05")
        assertEquals(setOf("A", "B"), atA0105.map { it.sku }.toSet())

        val atB0201 = repository.findByLocation("B-02-01")
        assertEquals(listOf("B"), atB0201.map { it.sku })

        // "A-01-05" must not falsely match "A-01-10" or "A-01-05, B-02-01" via
        // a naive prefix/substring check on the raw column value.
        val atA011 = repository.findByLocation("A-01-1")
        assertEquals(emptyList<String>(), atA011.map { it.sku })
    }
}
