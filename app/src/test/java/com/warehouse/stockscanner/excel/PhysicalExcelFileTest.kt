package com.warehouse.stockscanner.excel

import com.warehouse.stockscanner.data.BarcodeAliasEntity
import com.warehouse.stockscanner.data.ProductEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * The task spec (section 8) explicitly calls for a test that: creates a real
 * temp .xlsx file, writes test data into it, runs the app's save mechanism,
 * *reopens the physical file from disk*, reads it back, and checks the data
 * matches — not just an in-memory ByteArrayOutputStream. This uses plain
 * java.io.File/FileOutputStream/FileInputStream against the JVM's own temp
 * directory, independent of any Android Context, so there is no ambiguity
 * about whether the file actually hit disk.
 */
@RunWith(RobolectricTestRunner::class)
class PhysicalExcelFileTest {

    @Test
    fun `locations-quantities data written to a real file on disk reads back identical after reopening it`() {
        val realFile = File.createTempFile("locations_quantities_physical_test", ".xlsx")
        try {
            val products = listOf(
                ProductEntity("0001234", "פילטר שמן טויוטה", "7290012345678", "A-01-05", 0, ProductEntity.TYPE_PACKAGE, 12, 5, 60),
                ProductEntity("ABC-123", "מצבר 12V", "111", "B-02-01", 1)
            )

            // Write: the app's actual save mechanism, against a real FileOutputStream.
            FileOutputStream(realFile).use { out -> ExcelWriter.writeLocationsQuantitiesToStream(out, products) }

            // Reopen a brand-new stream on the same physical path (never reuse
            // the writer's stream) and read the bytes actually persisted to disk.
            val reopened = File(realFile.absolutePath)
            val readBack = FileInputStream(reopened).use { ExcelReader.readProductsFromStream(it) }.products

            assertEquals(2, readBack.size)
            val bySku = readBack.associateBy { it.sku }
            assertEquals("פילטר שמן טויוטה", bySku.getValue("0001234").description)
            assertEquals("A-01-05", bySku.getValue("0001234").location)
            assertEquals(60, bySku.getValue("0001234").quantity)
            assertEquals(ProductEntity.TYPE_PACKAGE, bySku.getValue("0001234").quantityType)
            assertEquals("B-02-01", bySku.getValue("ABC-123").location)
        } finally {
            realFile.delete()
        }
    }

    @Test
    fun `multiple-barcodes data written to a real file on disk reads back identical after reopening it`() {
        val realFile = File.createTempFile("multiple_barcodes_physical_test", ".xlsx")
        try {
            val products = listOf(ProductEntity("ABC-123", "פילטר שמן", "111", "A-01-05", 0))
            val aliases = listOf(
                BarcodeAliasEntity(barcode = "222", sku = "ABC-123"),
                BarcodeAliasEntity(barcode = "333", sku = "ABC-123")
            )

            FileOutputStream(realFile).use { out -> ExcelWriter.writeMultipleBarcodesToStream(out, aliases, products) }

            val reopened = File(realFile.absolutePath)
            val readBack = FileInputStream(reopened).use { ExcelReader.readMultipleBarcodesFromStream(it) }

            assertEquals(2, readBack.size)
            assertEquals(setOf("222", "333"), readBack.map { it.barcode }.toSet())
            assertEquals(setOf("ABC-123"), readBack.map { it.sku }.toSet())
            assertEquals(setOf("פילטר שמן"), readBack.map { it.description }.toSet())
        } finally {
            realFile.delete()
        }
    }

    @Test
    fun `the file on disk actually has non-zero size and a valid zip-xlsx signature, not just an empty placeholder`() {
        val realFile = File.createTempFile("nonempty_physical_test", ".xlsx")
        try {
            FileOutputStream(realFile).use { out ->
                ExcelWriter.writeLocationsQuantitiesToStream(out, listOf(ProductEntity("A", "x", "", "", 0)))
            }

            assertEquals(true, realFile.length() > 0)
            // .xlsx is a zip archive; every zip file starts with "PK".
            val header = ByteArray(2)
            FileInputStream(realFile).use { it.read(header) }
            assertEquals('P', header[0].toInt().toChar())
            assertEquals('K', header[1].toInt().toChar())
        } finally {
            realFile.delete()
        }
    }
}
