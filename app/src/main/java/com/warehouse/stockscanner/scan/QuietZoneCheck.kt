package com.warehouse.stockscanner.scan

import com.google.mlkit.vision.barcode.common.Barcode
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Rejects an EAN/UPC read that is really a fragment of a longer barcode.
 *
 * Every EAN/UPC symbol must have a blank "quiet zone" (7-11 bar widths)
 * before and after it. A blurred EAN-13 can be misread as a shorter code
 * made from part of its bars — measured on the emulator, a blurred
 * 7290119042128 came back as a (checksum-valid!) UPC-E 12180998,
 * identically in every frame, so ScanConsensus can't catch it. A fragment
 * has bars right past its end(s); a real symbol has blank space.
 *
 * - Short formats (EAN-8, UPC-E) — the ones a fragment turns into — need
 *   blank space on BOTH sides: that fragment shared one end with the real
 *   barcode, so only one of its sides had bars.
 * - EAN-13 / UPC-A are only rejected when BOTH sides have bars, so a
 *   finger, a price tag or printed text on one side of a real barcode
 *   doesn't cost the scan.
 *
 * Works on the code's own axis (from ML Kit's corner points), so tilted
 * and rotated barcodes are handled.
 */
object QuietZoneCheck {

    private val SHORT_FORMATS = setOf(Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_E)

    private val MODULES = mapOf(
        Barcode.FORMAT_EAN_13 to 95,
        Barcode.FORMAT_UPC_A to 95,
        Barcode.FORMAT_EAN_8 to 67,
        Barcode.FORMAT_UPC_E to 51
    )

    /** How far past each end is inspected, in modules (bar widths). */
    private const val FROM_MODULE = 1.5f
    private const val TO_MODULE = 5f

    /** Share of dark samples above which a side counts as "has bars". */
    private const val MAX_DARK_SHARE = 0.2f

    /**
     * @param luma luminance 0-255 at an upright-frame pixel, or null outside the frame
     * @return false when the code has bars where its quiet zone should be
     */
    fun passes(candidate: ScanCandidate, luma: (Int, Int) -> Int?): Boolean {
        val modules = MODULES[candidate.format] ?: return true
        val c = candidate.corners ?: return true
        // Corners are clockwise from the code's own top-left: the left edge
        // is p3->p0, the right edge p1->p2.
        val (x0, y0, x1, y1, x2, y2, x3, y3) = c
        val rows = listOf(0.3f, 0.5f, 0.7f).map { t ->
            // A line across the bars at height t, left end -> right end.
            floatArrayOf(x0 + (x3 - x0) * t, y0 + (y3 - y0) * t, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t)
        }
        val length = rows.map { hypot(it[2] - it[0], it[3] - it[1]) }.average().toFloat()
        if (length < modules) return true // under 1 px per bar: too small to judge
        val module = length / modules

        val (dark, light) = contrast(rows[1], luma) ?: return true
        if (light - dark < 40) return true // no usable contrast
        val threshold = (dark + light) / 2

        var barsOnLeft = 0
        var barsOnRight = 0
        for (r in rows) {
            val ux = (r[2] - r[0]) / length
            val uy = (r[3] - r[1]) / length
            if (hasBars(r[0], r[1], -ux, -uy, module, threshold, luma)) barsOnLeft++
            if (hasBars(r[2], r[3], ux, uy, module, threshold, luma)) barsOnRight++
        }
        // A side "has bars" when the majority of the three rows say so.
        val left = barsOnLeft >= 2
        val right = barsOnRight >= 2
        return if (candidate.format in SHORT_FORMATS) !left && !right else !(left && right)
    }

    private fun hasBars(
        ex: Float, ey: Float, dx: Float, dy: Float,
        module: Float, threshold: Int, luma: (Int, Int) -> Int?
    ): Boolean {
        val steps = ((TO_MODULE - FROM_MODULE) * module).roundToInt().coerceAtLeast(4)
        var dark = 0
        var seen = 0
        for (i in 0..steps) {
            val d = (FROM_MODULE * module) + i * ((TO_MODULE - FROM_MODULE) * module) / steps
            val v = luma((ex + dx * d).roundToInt(), (ey + dy * d).roundToInt()) ?: continue
            seen++
            if (v < threshold) dark++
        }
        if (seen < steps / 2) return false // mostly outside the frame: unknown, don't reject
        return dark.toFloat() / seen > MAX_DARK_SHARE
    }

    /** Darkest and lightest levels along the code's own middle row. */
    private fun contrast(row: FloatArray, luma: (Int, Int) -> Int?): Pair<Int, Int>? {
        val values = (0..40).mapNotNull { i ->
            val t = i / 40f
            luma((row[0] + (row[2] - row[0]) * t).roundToInt(), (row[1] + (row[3] - row[1]) * t).roundToInt())
        }.sorted()
        if (values.size < 10) return null
        return values[values.size / 10] to values[values.size * 9 / 10]
    }

    private operator fun FloatArray.component6() = this[5]
    private operator fun FloatArray.component7() = this[6]
    private operator fun FloatArray.component8() = this[7]
}
