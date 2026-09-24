package com.warehouse.stockscanner.scan

import com.google.mlkit.vision.barcode.common.Barcode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AimSelectorTest {

    private val w = 1080
    private val h = 1920

    private fun code(value: String, format: Int, l: Int, t: Int, r: Int, b: Int) =
        ScanCandidate(value, format, l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())

    @Test
    fun `no candidates gives nothing`() {
        assertNull(AimSelector.pick(emptyList(), w, h))
    }

    @Test
    fun `the code under the crosshair wins regardless of the order ML Kit lists them`() {
        // Modeled on resurse/20260902_151040.jpg: a product barcode on the bin
        // above the shelf label, listed first by the engine.
        val product = code("4007817530627", Barcode.FORMAT_EAN_13, 50, 200, 700, 600)
        val shelf = code("07017001", Barcode.FORMAT_DATA_MATRIX, 480, 900, 620, 1040)
        assertEquals(shelf, AimSelector.pick(listOf(product, shelf), w, h))
        assertEquals(shelf, AimSelector.pick(listOf(shelf, product), w, h))
    }

    @Test
    fun `among the four codes on a shelf label the one nearest the center wins`() {
        val y0 = 900
        val label = listOf(
            code("LEFT", Barcode.FORMAT_DATA_MATRIX, 100, y0, 250, y0 + 150),
            code("MID1", Barcode.FORMAT_DATA_MATRIX, 330, y0 + 40, 420, y0 + 130),
            code("MID2", Barcode.FORMAT_DATA_MATRIX, 560, y0 + 40, 650, y0 + 130),
            code("RIGHT", Barcode.FORMAT_DATA_MATRIX, 800, y0, 950, y0 + 150)
        )
        assertEquals("MID2", AimSelector.pick(label, w, h)!!.value)
    }

    @Test
    fun `with nothing under the crosshair the nearest code is taken`() {
        val far = code("FAR", Barcode.FORMAT_EAN_13, 0, 0, 200, 100)
        val near = code("NEAR", Barcode.FORMAT_EAN_13, 600, 1000, 900, 1100)
        assertEquals("NEAR", AimSelector.pick(listOf(far, near), w, h)!!.value)
    }

    @Test
    fun `a big barcode spanning the crosshair beats a small one whose center is closer`() {
        // The worker filled the frame with one barcode; a small code whose
        // center happens to be closer must not steal the scan.
        val big = code("BIG", Barcode.FORMAT_EAN_13, 100, 800, 1000, 1300)
        val small = code("SMALL", Barcode.FORMAT_QR_CODE, 560, 700, 600, 740)
        assertEquals("BIG", AimSelector.pick(listOf(small, big), w, h)!!.value)
    }
}
