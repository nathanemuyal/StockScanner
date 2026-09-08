package com.warehouse.stockscanner.excel

import com.warehouse.stockscanner.data.BarcodeAliasEntity
import com.warehouse.stockscanner.data.ProductEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
class ExcelWriterRoundTripTest {

    /** Raw text of the written sheet, to check actual column headers/cells. */
    private fun sheetXmlOf(bytes: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "xl/worksheets/sheet1.xml") {
                    return zip.readBytes().toString(Charsets.UTF_8)
                }
                entry = zip.nextEntry
            }
        }
        error("sheet1.xml not found in written archive")
    }

    @Test
    fun `writing then reading back preserves every field exactly, including XML-special characters`() {
        val products = listOf(
            ProductEntity("0001234", "פילטר שמן <טויוטה> \"קורולה\" & בנזין", "7290012345678", "A-01-05", 0),
            ProductEntity("ABC-123", "מוצר עם גרש בודד ' בתיאור", "111", "A-01-06", 1),
            ProductEntity("PRD_00123", "", "", "", 2) // blank fields must stay blank, not crash
        )

        val bytes = ByteArrayOutputStream().also {
            ExcelWriter.writeProductsToStream(it, products)
        }.toByteArray()

        val result = ByteArrayInputStream(bytes).use { ExcelReader.readProductsFromStream(it) }

        assertEquals(3, result.products.size)
        val bySku = result.products.associateBy { it.sku }

        assertEquals(
            "פילטר שמן <טויוטה> \"קורולה\" & בנזין",
            bySku.getValue("0001234").description
        )
        assertEquals("A-01-05", bySku.getValue("0001234").location)
        assertEquals("מוצר עם גרש בודד ' בתיאור", bySku.getValue("ABC-123").description)
        assertEquals("", bySku.getValue("PRD_00123").description)
        assertEquals("", bySku.getValue("PRD_00123").barcode)
    }

    @Test
    fun `row order is preserved regardless of insertion order`() {
        val products = listOf(
            ProductEntity("SECOND", "second", "", "", 1),
            ProductEntity("FIRST", "first", "", "", 0)
        )
        val bytes = ByteArrayOutputStream().also {
            ExcelWriter.writeProductsToStream(it, products)
        }.toByteArray()

        val result = ByteArrayInputStream(bytes).use { ExcelReader.readProductsFromStream(it) }
        assertEquals(listOf("FIRST", "SECOND"), result.products.map { it.sku })
    }

    @Test
    fun `only ever writes a single מיקום column, never numbered extras`() {
        val products = listOf(
            ProductEntity("A", "x", "", "A-01-05", 0),
            ProductEntity("B", "y", "", "", 1)
        )
        val bytes = ByteArrayOutputStream().also {
            ExcelWriter.writeProductsToStream(it, products)
        }.toByteArray()

        val sheetXml = sheetXmlOf(bytes)
        assertTrue(sheetXml.contains("<t xml:space=\"preserve\">מיקום</t>"))
        assertFalse(sheetXml.contains("מיקום 2"))
    }

    @Test
    fun `quantity fields round-trip exactly, both for units and package mode`() {
        val products = listOf(
            ProductEntity("UNITS-1", "מוצר ביחידות", "111", "A-01-05", 0, ProductEntity.TYPE_UNITS, 0, 0, 15),
            ProductEntity("PKG-1", "מוצר באריזות", "222", "A-01-06", 1, ProductEntity.TYPE_PACKAGE, 12, 5, 60)
        )
        val bytes = ByteArrayOutputStream().also {
            ExcelWriter.writeProductsToStream(it, products)
        }.toByteArray()

        val result = ByteArrayInputStream(bytes).use { ExcelReader.readProductsFromStream(it) }
        val bySku = result.products.associateBy { it.sku }

        val units = bySku.getValue("UNITS-1")
        assertEquals(ProductEntity.TYPE_UNITS, units.quantityType)
        assertEquals(0, units.packageContent)
        assertEquals(0, units.packageCount)
        assertEquals(15, units.quantity)

        val pkg = bySku.getValue("PKG-1")
        assertEquals(ProductEntity.TYPE_PACKAGE, pkg.quantityType)
        assertEquals(12, pkg.packageContent)
        assertEquals(5, pkg.packageCount)
        assertEquals(60, pkg.quantity)
    }

    @Test
    fun `a product with several locations round-trips as several rows sharing the same sku`() {
        val products = listOf(
            ProductEntity("MULTI-1", "מוצר משותף", "111", "A-01-05", 0),
            ProductEntity("MULTI-1", "מוצר משותף", "111", "B-02-01", 1),
            ProductEntity("MULTI-1", "מוצר משותף", "111", "C-03-01", 2),
            ProductEntity("SINGLE-1", "מוצר יחיד", "222", "A-01-06", 3)
        )
        val bytes = ByteArrayOutputStream().also {
            ExcelWriter.writeProductsToStream(it, products)
        }.toByteArray()

        val result = ByteArrayInputStream(bytes).use { ExcelReader.readProductsFromStream(it) }
        val multiRows = result.products.filter { it.sku == "MULTI-1" }
        assertEquals(3, multiRows.size)
        assertEquals(setOf("A-01-05", "B-02-01", "C-03-01"), multiRows.map { it.location }.toSet())

        val bySku = result.products.associateBy { it.sku }
        assertEquals("A-01-06", bySku.getValue("SINGLE-1").location)
    }

    @Test
    fun `barcode aliases round-trip through their own sheet, separate from the product rows`() {
        val products = listOf(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0))
        val aliases = listOf(
            BarcodeAliasEntity(barcode = "222", sku = "ABC-123"),
            BarcodeAliasEntity(barcode = "333", sku = "ABC-123")
        )
        val bytes = ByteArrayOutputStream().also {
            ExcelWriter.writeProductsToStream(it, products, aliases)
        }.toByteArray()

        val result = ByteArrayInputStream(bytes).use { ExcelReader.readProductsFromStream(it) }
        assertEquals(1, result.products.size) // aliases never turn into extra product rows
        assertEquals(setOf("222", "333"), result.barcodeAliases.map { it.barcode }.toSet())
        assertTrue(result.barcodeAliases.all { it.sku == "ABC-123" })
    }

    @Test
    fun `writing with no aliases still produces a readable file with no aliases on reload`() {
        val products = listOf(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0))
        val bytes = ByteArrayOutputStream().also {
            ExcelWriter.writeProductsToStream(it, products)
        }.toByteArray()

        val result = ByteArrayInputStream(bytes).use { ExcelReader.readProductsFromStream(it) }
        assertTrue(result.barcodeAliases.isEmpty())
    }
}
