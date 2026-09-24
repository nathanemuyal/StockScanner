package com.warehouse.stockscanner.scan

import com.google.mlkit.vision.barcode.common.Barcode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic "images": a row of 1-module bars at [MODULE] px each, rendered
 * as a luma function. Bars alternate dark/light across [barsFrom, barsTo).
 */
class QuietZoneCheckTest {

    private val module = 3

    private fun image(barsFrom: Int, barsTo: Int, width: Int = 900, height: Int = 200): (Int, Int) -> Int? =
        { x, y ->
            when {
                x < 0 || y < 0 || x >= width || y >= height -> null
                x in barsFrom until barsTo && ((x - barsFrom) / module) % 2 == 0 -> 20
                else -> 235
            }
        }

    /** Axis-aligned candidate covering x in [from, to), y in [50, 150). */
    private fun candidate(format: Int, from: Int, to: Int) = ScanCandidate(
        "x", format, from.toFloat(), 50f, to.toFloat(), 150f,
        floatArrayOf(from.toFloat(), 50f, to.toFloat(), 50f, to.toFloat(), 150f, from.toFloat(), 150f)
    )

    @Test
    fun `a real EAN-13 with blank space on both sides passes`() {
        val from = 300
        val to = from + 95 * module
        assertTrue(QuietZoneCheck.passes(candidate(Barcode.FORMAT_EAN_13, from, to), image(from, to)))
    }

    @Test
    fun `an EAN-8 read from the middle of a longer barcode is rejected`() {
        // Bars run 300..585 (an EAN-13); the "EAN-8" is 67 modules in the middle.
        val bars = image(300, 300 + 95 * module)
        val from = 300 + 14 * module
        val to = from + 67 * module
        assertFalse(QuietZoneCheck.passes(candidate(Barcode.FORMAT_EAN_8, from, to), bars))
    }

    @Test
    fun `a UPC-E fragment sharing one end with the real barcode is rejected`() {
        // The emulator case: bars run 300..585, the "UPC-E" is the last 51
        // modules, so its right side is genuinely blank.
        val bars = image(300, 300 + 95 * module)
        val to = 300 + 95 * module
        val from = to - 51 * module
        assertFalse(QuietZoneCheck.passes(candidate(Barcode.FORMAT_UPC_E, from, to), bars))
    }

    @Test
    fun `something printed on one side only does not reject a real EAN-13`() {
        val from = 300
        val to = from + 95 * module
        val base = image(from, to)
        val withPrint: (Int, Int) -> Int? = { x, y -> if (x in to + 5 until to + 40) 20 else base(x, y) }
        assertTrue(QuietZoneCheck.passes(candidate(Barcode.FORMAT_EAN_13, from, to), withPrint))
    }

    @Test
    fun `a real EAN-8 with blank space on both sides passes`() {
        val from = 300
        val to = from + 67 * module
        assertTrue(QuietZoneCheck.passes(candidate(Barcode.FORMAT_EAN_8, from, to), image(from, to)))
    }

    @Test
    fun `a barcode touching the frame edge is not rejected`() {
        val to = 95 * module
        assertTrue(QuietZoneCheck.passes(candidate(Barcode.FORMAT_EAN_13, 0, to), image(0, to + 300)))
    }

    @Test
    fun `formats without a fixed length and 2D codes are not checked`() {
        val bars = image(0, 900)
        assertTrue(QuietZoneCheck.passes(candidate(Barcode.FORMAT_CODE_128, 300, 500), bars))
        assertTrue(QuietZoneCheck.passes(candidate(Barcode.FORMAT_DATA_MATRIX, 300, 500), bars))
    }

    @Test
    fun `no corner points means no check`() {
        val c = ScanCandidate("x", Barcode.FORMAT_EAN_8, 300f, 50f, 500f, 150f)
        assertTrue(QuietZoneCheck.passes(c, image(0, 900)))
    }
}
