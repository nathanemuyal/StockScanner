package com.warehouse.stockscanner.excel

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
        assertEquals(0, result.duplicateSkuRows)
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
    fun `duplicate skus are de-duplicated, last row wins, and the count is reported`() {
        val result = fixture("sample_duplicates.xlsx").use { ExcelReader.readProductsFromStream(it) }

        assertEquals(3, result.products.size) // SKU-1, SKU-2, SKU-3
        assertEquals(1, result.duplicateSkuRows)
        assertEquals(1, result.duplicateBarcodeRows) // "2222" used by both SKU-2 and SKU-3

        val bySku = result.products.associateBy { it.sku }
        assertEquals("מוצר ראשון (מעודכן)", bySku.getValue("SKU-1").description)
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
    fun `combines numbered location columns regardless of their physical order in the file`() {
        // Fixture headers are: מקט, מיקום 3, תאור, ברקוד, מיקום, מיקום 2 — the
        // numbered columns are scattered and out of numeric order on purpose.
        val result = fixture("sample_multi_location.xlsx").use { ExcelReader.readProductsFromStream(it) }
        val bySku = result.products.associateBy { it.sku }

        assertEquals("A-01-05, B-02-01, C-03-01", bySku.getValue("MULTI-1").location)
        assertEquals("A-01-06", bySku.getValue("SINGLE-1").location)
        assertEquals("", bySku.getValue("NONE-1").location)
    }
}
