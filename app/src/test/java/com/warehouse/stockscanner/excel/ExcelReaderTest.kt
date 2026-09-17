package com.warehouse.stockscanner.excel

import com.warehouse.stockscanner.data.BarcodeEntity
import com.warehouse.stockscanner.data.ProductEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Validates ExcelReader against REAL .xlsx files produced by an independent
 * tool (Python's openpyxl — see scripts/make_fixtures.py), not just against
 * this app's own writer. Runs under Robolectric so android.util.Xml (used
 * internally by ExcelReader) behaves as it does on a real device, without
 * needing an emulator.
 */
@RunWith(RobolectricTestRunner::class)
class ExcelReaderTest {

    private fun fixture(name: String) =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")
            ?: error("Missing test fixture: $name")

    @Test
    fun `reads columns by header name regardless of order, ignoring extra columns`() {
        val result = fixture("sample_normal.xlsx").use { ExcelReader.readProductsFromStream(it) }

        // 4 real data rows + 1 fully-blank trailing row that must be skipped.
        assertEquals(4, result.products.size)
        assertEquals(0, result.duplicateRows)
        assertEquals(0, result.duplicateBarcodeRows)

        val bySku = result.products.associateBy { it.sku }
        assertTrue("ABC-123" in bySku)
        assertEquals("פילטר שמן טויוטה", bySku.getValue("ABC-123").description)
        assertEquals("7290012345678", bySku.getValue("ABC-123").barcode)
        assertEquals("", bySku.getValue("ABC-123").location)
    }

    @Test
    fun `sku with leading zeros is preserved as text, never treated as a number`() {
        val result = fixture("sample_normal.xlsx").use { ExcelReader.readProductsFromStream(it) }
        val bySku = result.products.associateBy { it.sku }
        assertTrue("0001234" in bySku)
        assertEquals("A-01-01", bySku.getValue("0001234").location)
    }

    @Test
    fun `internal whitespace in a description is preserved, only edges are trimmed`() {
        val result = fixture("sample_normal.xlsx").use { ExcelReader.readProductsFromStream(it) }
        val bySku = result.products.associateBy { it.sku }
        // Source cell was "  מצבר   12V  " — outer spaces trimmed, inner spacing untouched.
        assertEquals("מצבר   12V", bySku.getValue("PRD_00123").description)
    }

    @Test
    fun `blank barcode cell is read as an empty string, not null or crash`() {
        val result = fixture("sample_normal.xlsx").use { ExcelReader.readProductsFromStream(it) }
        val bySku = result.products.associateBy { it.sku }
        assertEquals("", bySku.getValue("A12-B45").barcode)
    }

    @Test
    fun `a file with no quantity columns defaults every row to units mode with zero quantity`() {
        val result = fixture("sample_normal.xlsx").use { ExcelReader.readProductsFromStream(it) }
        val bySku = result.products.associateBy { it.sku }
        val row = bySku.getValue("ABC-123")
        assertEquals(ProductEntity.TYPE_UNITS, row.quantityType)
        assertEquals(0, row.packageContent)
        assertEquals(0, row.packageCount)
        assertEquals(0, row.quantity)
    }

    @Test
    fun `duplicate sku+location rows are de-duplicated, last row wins, and the count is reported`() {
        val result = fixture("sample_duplicates.xlsx").use { ExcelReader.readProductsFromStream(it) }

        assertEquals(3, result.products.size) // SKU-1, SKU-2, SKU-3
        assertEquals(1, result.duplicateRows)
        assertEquals(1, result.duplicateBarcodeRows) // "2222" used by both SKU-2 and SKU-3

        val bySku = result.products.associateBy { it.sku }
        assertEquals("מוצר ראשון (מעודכן)", bySku.getValue("SKU-1").description)
    }

    @Test
    fun `a single-sheet source file from elsewhere has no barcode aliases, not a crash`() {
        val result = fixture("sample_normal.xlsx").use { ExcelReader.readProductsFromStream(it) }
        assertTrue(result.barcodes.isEmpty())
    }

    @Test
    fun `missing required column raises a clear, specific error`() {
        try {
            fixture("sample_missing_column.xlsx").use { ExcelReader.readProductsFromStream(it) }
            fail("Expected ExcelFormatException")
        } catch (e: ExcelFormatException) {
            assertTrue(e.message!!.contains("ברקוד"))
        }
    }

    @Test
    fun `a legacy file with numbered location columns expands each value into its own row`() {
        // Fixture headers are: מקט, מיקום 3, תאור, ברקוד, מיקום, מיקום 2 — the
        // numbered columns are scattered and out of numeric order on purpose.
        // Only kept for backward compatibility with files an older version of
        // this app produced; the current writer never emits numbered columns.
        val result = fixture("sample_multi_location.xlsx").use { ExcelReader.readProductsFromStream(it) }
        val byMulti1 = result.products.filter { it.sku == "MULTI-1" }

        assertEquals(3, byMulti1.size)
        assertEquals(setOf("A-01-05", "B-02-01", "C-03-01"), byMulti1.map { it.location }.toSet())
        assertTrue(byMulti1.all { it.description == "מוצר בשלושה מקומות" && it.barcode == "111" })

        val bySku = result.products.associateBy { it.sku }
        assertEquals("A-01-06", bySku.getValue("SINGLE-1").location)
        assertEquals("", bySku.getValue("NONE-1").location)
    }

    /**
     * What a scan of a code means is the only thing a barcode cannot say for
     * itself, so the source file gets to state it. A role the app does not
     * recognise falls back to a plain unit rather than failing the load —
     * one odd cell must not cost a worker the whole file.
     */
    @Test
    fun `reads the packaging role and content from the barcodes sheet`() {
        val result = fixture("sample_barcode_roles.xlsx").use { ExcelReader.readProductsFromStream(it) }

        val byBarcode = result.barcodes.associateBy { it.barcode }
        assertEquals(setOf("222", "333", "555", "666"), byBarcode.keys)

        assertEquals(BarcodeEntity.ROLE_PACKAGE, byBarcode.getValue("222").role)
        assertEquals(12, byBarcode.getValue("222").packageContent)

        assertEquals(BarcodeEntity.ROLE_MIXED, byBarcode.getValue("333").role)
        assertEquals(6, byBarcode.getValue("333").packageContent)

        // No role given at all.
        assertEquals(BarcodeEntity.ROLE_UNIT, byBarcode.getValue("555").role)
        assertEquals(0, byBarcode.getValue("555").packageContent)

        // "קרטון" is not one of the three roles; it reads as a plain unit,
        // and its content goes with it rather than lingering unexplained.
        assertEquals(BarcodeEntity.ROLE_UNIT, byBarcode.getValue("666").role)
        assertEquals(0, byBarcode.getValue("666").packageContent)
    }

    /** A file exported before roles existed still loads; every code in it is a plain unit. */
    @Test
    fun `a barcodes sheet without the role columns still loads`() {
        val result = fixture("sample_barcode_roles_legacy.xlsx").use { ExcelReader.readProductsFromStream(it) }

        val only = result.barcodes.single()
        assertEquals("222", only.barcode)
        assertEquals("ABC-123", only.sku)
        assertEquals(BarcodeEntity.ROLE_UNIT, only.role)
        assertEquals(0, only.packageContent)
    }
}
