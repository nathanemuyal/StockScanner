package com.warehouse.stockscanner.scan

import com.google.mlkit.vision.barcode.common.Barcode

/**
 * Keeps shelf labels out of product scans.
 *
 * Product mode accepts QR/Data Matrix too (some products carry one), and a
 * 2D code is accepted from a single frame. On a real shelf the location
 * label sits right under the products (see resurse/), so while the product
 * barcode was still out of focus, the label's Data Matrix got captured as
 * the "product" — the emulator run on the warehouse photos returned
 * 07016001 / 07028001 (shelf labels) in product mode.
 *
 * The worker scanned the current shelf's label just before, so its value
 * tells us what a label looks like here: a 2D code that is that label, or
 * has the same shape (same length, digits/letters/separators in the same
 * places, e.g. "07017001" vs "07028001" for the next shelf), is a label.
 */
object ShelfLabelFilter {

    fun isShelfLabel(candidate: ScanCandidate, currentLocation: String?): Boolean {
        if (currentLocation.isNullOrBlank()) return false
        if (candidate.format != Barcode.FORMAT_DATA_MATRIX && candidate.format != Barcode.FORMAT_QR_CODE) {
            return false
        }
        return candidate.value == currentLocation || shape(candidate.value) == shape(currentLocation)
    }

    private fun shape(value: String) = value.map {
        when {
            it.isDigit() -> '9'
            it.isLetter() -> 'A'
            else -> it
        }
    }.joinToString("")
}
