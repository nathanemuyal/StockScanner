package com.warehouse.stockscanner.excel

import com.warehouse.stockscanner.data.ProductEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
class ExcelWriterRoundTripTest {

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
}
