package com.warehouse.stockscanner.excel

import com.warehouse.stockscanner.data.BarcodeEntity
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

    /** Raw text of a written sheet, to check actual column headers/cells. */
    private fun sheetXmlOf(bytes: ByteArray, entryName: String = "sheet1.xml"): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "xl/worksheets/$entryName") {
                    return zip.readBytes().toString(Charsets.UTF_8)
                }
                entry = zip.nextEntry
            }
        }
        error("$entryName not found in written archive")
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
            ProductEntity("UNITS-1", "מוצר ביחידות", "111", "A-01-05", 0, ProductEntity.TYPE_UNITS, 0, 0, quantity = 15),
            ProductEntity("PKG-1", "מוצר באריזות", "222", "A-01-06", 1, ProductEntity.TYPE_PACKAGE, 12, 5, quantity = 60)
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
    fun `mixed mode round-trips the packages, the loose units and their combined total`() {
        val products = listOf(
            ProductEntity("MIX-1", "מוצר מעורב", "333", "A-01-07", 0, ProductEntity.TYPE_MIXED, 12, 5, 7, 67)
        )
        val bytes = ByteArrayOutputStream().also {
            ExcelWriter.writeProductsToStream(it, products)
        }.toByteArray()

        // The loose units get their own column, between the package
        // breakdown they sit beside and the total they feed into.
        assertTrue(sheetXmlOf(bytes).contains("<t xml:space=\"preserve\">יחידות בודדות</t>"))

        val mixed = ByteArrayInputStream(bytes).use { ExcelReader.readProductsFromStream(it) }.products.single()
        assertEquals(ProductEntity.TYPE_MIXED, mixed.quantityType)
        assertEquals(12, mixed.packageContent)
        assertEquals(5, mixed.packageCount)
        assertEquals(7, mixed.looseUnits)
        assertEquals(67, mixed.quantity)
    }

    @Test
    fun `a loose-units value on a row that isn't mixed is dropped rather than read back`() {
        // Only מעורב rows have loose units; a stray value under any other
        // mode (hand-edited file, older export) must not silently attach
        // itself to a row whose total never counted it.
        val products = listOf(
            ProductEntity("PKG-2", "מוצר באריזות", "444", "A-01-08", 0, ProductEntity.TYPE_PACKAGE, 12, 5, 7, 60)
        )
        val bytes = ByteArrayOutputStream().also {
            ExcelWriter.writeProductsToStream(it, products)
        }.toByteArray()

        val row = ByteArrayInputStream(bytes).use { ExcelReader.readProductsFromStream(it) }.products.single()
        assertEquals(ProductEntity.TYPE_PACKAGE, row.quantityType)
        assertEquals(0, row.looseUnits)
        assertEquals(60, row.quantity)
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
            BarcodeEntity(barcode = "222", sku = "ABC-123"),
            BarcodeEntity(barcode = "333", sku = "ABC-123")
        )
        val bytes = ByteArrayOutputStream().also {
            ExcelWriter.writeProductsToStream(it, products, aliases)
        }.toByteArray()

        val result = ByteArrayInputStream(bytes).use { ExcelReader.readProductsFromStream(it) }
        assertEquals(1, result.products.size) // aliases never turn into extra product rows
        assertEquals(setOf("222", "333"), result.barcodes.map { it.barcode }.toSet())
        assertTrue(result.barcodes.all { it.sku == "ABC-123" })
    }

    @Test
    fun `writing with no aliases still produces a readable file with no aliases on reload`() {
        val products = listOf(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0))
        val bytes = ByteArrayOutputStream().also {
            ExcelWriter.writeProductsToStream(it, products)
        }.toByteArray()

        val result = ByteArrayInputStream(bytes).use { ExcelReader.readProductsFromStream(it) }
        assertTrue(result.barcodes.isEmpty())
    }

    /**
     * The barcodes working file is what a worker hands back, so a role
     * discovered during a count has to survive the trip out and back in —
     * otherwise the next count re-asks every question this one answered.
     */
    @Test
    fun `packaging roles survive a write and read of the barcodes file`() {
        val barcodes = listOf(
            BarcodeEntity(barcode = "222", sku = "ABC-123", role = BarcodeEntity.ROLE_PACKAGE, packageContent = 12),
            BarcodeEntity(barcode = "333", sku = "ABC-123", role = BarcodeEntity.ROLE_MIXED, packageContent = 6),
            BarcodeEntity(barcode = "444", sku = "XYZ-9", role = BarcodeEntity.ROLE_UNIT)
        )
        val products = listOf(
            ProductEntity("ABC-123", "פילטר שמן", "111", "A-01-05", 0),
            ProductEntity("XYZ-9", "אום", "444", "B-02-01", 1)
        )

        val bytes = ByteArrayOutputStream().use { out ->
            ExcelWriter.writeMultipleBarcodesToStream(out, barcodes, products)
            out.toByteArray()
        }
        val readBack = ExcelReader.readMultipleBarcodesFromStream(ByteArrayInputStream(bytes))

        assertEquals(listOf("222", "333", "444"), readBack.map { it.barcode })
        assertEquals(listOf("אריזה", "מעורב", "בודד"), readBack.map { it.role })
        assertEquals(listOf(12, 6, 0), readBack.map { it.packageContent })
    }

    /**
     * A person opens this file too. A code on a single unit has no package,
     * so its תכולה cell is left empty rather than stating a zero — most of
     * the file is such codes, and a column of zeroes reads as a real figure
     * worth checking. Asserted on the written cell, since reading it back
     * cannot tell an empty cell from a 0.
     */
    @Test
    fun `a single-unit code leaves the package content cell empty`() {
        val barcodes = listOf(
            BarcodeEntity(barcode = "444", sku = "XYZ-9", role = BarcodeEntity.ROLE_UNIT),
            BarcodeEntity(barcode = "222", sku = "XYZ-9", role = BarcodeEntity.ROLE_PACKAGE, packageContent = 12)
        )
        val products = listOf(ProductEntity("XYZ-9", "אום", "444", "B-02-01", 0))

        val bytes = ByteArrayOutputStream().use { out ->
            ExcelWriter.writeMultipleBarcodesToStream(out, barcodes, products)
            out.toByteArray()
        }
        val sheet = sheetXmlOf(bytes)

        // Row 2 is the בודד code: no תכולה cell value at all.
        assertFalse("a בודד row should carry no package content", sheet.contains(">0<"))
        // Row 3 is the אריזה code, which still states its 12.
        assertTrue("an אריזה row must still state its content", sheet.contains(">12<"))
    }

    /**
     * The stamp has to reach the file a worker hands back, not just the
     * database. Settling which of two counts is the fresh one happens over
     * the spreadsheet, and until now the column simply was not there.
     */
    @Test
    fun `when a row was counted survives a write and read`() {
        val counted = 1_726_000_000_000L
        val products = listOf(
            ProductEntity("ABC-123", "פילטר", "111", "A-01", 0, ProductEntity.TYPE_UNITS, 0, 0, 0, 10, true, counted),
            // Placed but never counted — no date belongs on it.
            ProductEntity("XYZ-9", "אום", "222", "B-02", 1, ProductEntity.TYPE_UNITS, 0, 0, 0, 0, true, 0L)
        )

        val bytes = ByteArrayOutputStream().use { out ->
            ExcelWriter.writeLocationsQuantitiesToStream(out, products)
            out.toByteArray()
        }
        val readBack = ExcelReader.readProductsFromStream(ByteArrayInputStream(bytes)).products.associateBy { it.sku }

        // Written for people, so it round-trips to the minute rather than the
        // millisecond — the database keeps the exact value.
        assertEquals(counted / 60000L, readBack.getValue("ABC-123").countedAt / 60000L)
        assertEquals(0L, readBack.getValue("XYZ-9").countedAt)
    }

    /** A file written before the column existed still loads, reading as never counted. */
    @Test
    fun `a detail sheet without the counted-at column still loads`() {
        val bytes = ByteArrayOutputStream().use { out ->
            // writeProductsToStream is the legacy shape the reader still accepts.
            ExcelWriter.writeProductsToStream(
                out,
                listOf(ProductEntity("ABC-123", "פילטר", "111", "A-01", 0, ProductEntity.TYPE_UNITS, 0, 0, 0, 10, true, 0L))
            )
            out.toByteArray()
        }

        val row = ExcelReader.readProductsFromStream(ByteArrayInputStream(bytes)).products.single()
        assertEquals(10, row.quantity)
        assertEquals(0L, row.countedAt)
    }

    /** Reads the "סיכום" sheet of a locations file back as raw rows. */
    private fun summarySheetOf(bytes: ByteArray): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "xl/worksheets/sheet2.xml") {
                    val xml = zip.readBytes().toString(Charsets.UTF_8)
                    for (row in Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
                        rows += Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                            .findAll(row.groupValues[1]).map { it.groupValues[1] }.toList()
                    }
                }
                entry = zip.nextEntry
            }
        }
        return rows
    }

    /**
     * The count's actual answer. A shelf can hold the same מקט under two
     * barcodes — a package code and a single-unit code — and each is counted
     * on its own row, so the detail sheet alone cannot say how many there
     * are without someone adding rows up by hand.
     */
    @Test
    fun `the locations file carries a summary that totals each sku by location and overall`() {
        val products = listOf(
            // One shelf, two barcodes: 3 packages of 12, plus 7 loose singles.
            ProductEntity("ABC-123", "פילטר", "111", "A-01", 0, ProductEntity.TYPE_PACKAGE, 12, 3, 0, 36, true),
            ProductEntity("ABC-123", "פילטר", "222", "A-01", 1, ProductEntity.TYPE_UNITS, 0, 0, 0, 7, true),
            // ...and the same sku on a second shelf.
            ProductEntity("ABC-123", "פילטר", "111", "B-03", 2, ProductEntity.TYPE_UNITS, 0, 0, 0, 15, true),
            ProductEntity("XYZ-9", "אום", "444", "A-01", 3, ProductEntity.TYPE_UNITS, 0, 0, 0, 5, true)
        )

        val bytes = ByteArrayOutputStream().use { out ->
            ExcelWriter.writeLocationsQuantitiesToStream(out, products)
            out.toByteArray()
        }

        val summary = summarySheetOf(bytes)
        assertEquals(listOf("מקט", "תאור", "מיקום", "סה״כ יחידות"), summary.first())

        val body = summary.drop(1).map { listOf(it[0], it[2], it[3]) }
        assertEquals(
            listOf(
                listOf("ABC-123", "A-01", "43"),   // 36 packaged + 7 loose, one shelf, two codes
                listOf("ABC-123", "B-03", "15"),
                listOf("ABC-123", "סה״כ", "58"),
                listOf("XYZ-9", "A-01", "5"),
                listOf("XYZ-9", "סה״כ", "5")
            ),
            body
        )
    }

    /** The detail sheet is the audit trail and must not change shape because a summary was added. */
    @Test
    fun `adding the summary leaves the detail sheet readable exactly as before`() {
        val products = listOf(
            ProductEntity("ABC-123", "פילטר", "111", "A-01", 0, ProductEntity.TYPE_PACKAGE, 12, 3, 0, 36, true)
        )

        val bytes = ByteArrayOutputStream().use { out ->
            ExcelWriter.writeLocationsQuantitiesToStream(out, products)
            out.toByteArray()
        }
        val readBack = ExcelReader.readProductsFromStream(ByteArrayInputStream(bytes)).products

        val row = readBack.single()
        assertEquals("ABC-123", row.sku)
        assertEquals("A-01", row.location)
        assertEquals(ProductEntity.TYPE_PACKAGE, row.quantityType)
        assertEquals(36, row.quantity)
    }

    /**
     * Reloading the locations file must not mistake its summary for barcodes.
     *
     * The reader treats the *second* worksheet as the ברקודים כפולים sheet,
     * and this file now has one — the summary. It is ignored only because
     * parseBarcodeSheet insists on a ברקוד column the summary has no reason
     * to carry, which is a load-bearing coincidence rather than a decision.
     * Pinning it here so that giving the summary a barcode column one day
     * fails as a test instead of as a warehouse full of aliases nobody made.
     */
    @Test
    fun `reloading the locations file does not read its summary as barcodes`() {
        val products = listOf(
            ProductEntity("ABC-123", "פילטר", "111", "A-01", 0, ProductEntity.TYPE_UNITS, 0, 0, 0, 43, true),
            ProductEntity("ABC-123", "פילטר", "222", "B-03", 1, ProductEntity.TYPE_UNITS, 0, 0, 0, 15, true)
        )

        val bytes = ByteArrayOutputStream().use { out ->
            ExcelWriter.writeLocationsQuantitiesToStream(out, products)
            out.toByteArray()
        }
        // The summary really is sheet 2 of this file, so the risk is live.
        assertTrue(sheetXmlOf(bytes, "sheet2.xml").contains("סה״כ"))

        val result = ExcelReader.readProductsFromStream(ByteArrayInputStream(bytes))

        assertEquals(2, result.products.size)
        assertTrue("the summary must not become barcode rows", result.barcodes.isEmpty())
    }
}
