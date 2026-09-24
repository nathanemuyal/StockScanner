package com.warehouse.stockscanner.scan

import com.google.mlkit.vision.barcode.common.Barcode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfLabelFilterTest {

    private fun code(value: String, format: Int) = ScanCandidate(value, format, 0f, 0f, 1f, 1f)

    @Test
    fun `the current shelf's own label is filtered`() {
        assertTrue(ShelfLabelFilter.isShelfLabel(code("07017001", Barcode.FORMAT_DATA_MATRIX), "07017001"))
    }

    @Test
    fun `a neighboring shelf's label with the same shape is filtered`() {
        // Real values seen in product mode on the warehouse photos.
        assertTrue(ShelfLabelFilter.isShelfLabel(code("07016001", Barcode.FORMAT_DATA_MATRIX), "07017001"))
        assertTrue(ShelfLabelFilter.isShelfLabel(code("07028001", Barcode.FORMAT_QR_CODE), "07017001"))
        assertTrue(ShelfLabelFilter.isShelfLabel(code("B-02-03", Barcode.FORMAT_QR_CODE), "A-01-01"))
    }

    @Test
    fun `1D product barcodes are never filtered, even with the same digit count`() {
        assertFalse(ShelfLabelFilter.isShelfLabel(code("65833254", Barcode.FORMAT_EAN_8), "07017001"))
        assertFalse(ShelfLabelFilter.isShelfLabel(code("07017001", Barcode.FORMAT_CODE_128), "07017001"))
    }

    @Test
    fun `2D product codes with a different shape are kept`() {
        assertFalse(
            ShelfLabelFilter.isShelfLabel(
                code("Wikipedia, the free encyclopedia", Barcode.FORMAT_DATA_MATRIX), "07017001"
            )
        )
        assertFalse(ShelfLabelFilter.isShelfLabel(code("0701700", Barcode.FORMAT_DATA_MATRIX), "07017001"))
    }

    @Test
    fun `nothing is filtered when there is no current location`() {
        assertFalse(ShelfLabelFilter.isShelfLabel(code("07017001", Barcode.FORMAT_DATA_MATRIX), null))
        assertFalse(ShelfLabelFilter.isShelfLabel(code("07017001", Barcode.FORMAT_DATA_MATRIX), ""))
    }
}
