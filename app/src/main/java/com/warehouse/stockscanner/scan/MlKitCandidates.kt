package com.warehouse.stockscanner.scan

import com.google.mlkit.vision.barcode.common.Barcode

/**
 * The single ML Kit -> [ScanCandidate] entry point, shared by ScannerActivity
 * and the on-device accuracy tests so both run the exact same filtering.
 * Returns null for values [BarcodeValidator] rejects.
 */
fun Barcode.toScanCandidate(): ScanCandidate? {
    val value = rawValue
    if (value == null || !BarcodeValidator.isValid(value, format)) return null
    val box = boundingBox ?: return null
    val corners = cornerPoints?.takeIf { it.size == 4 }?.let { pts ->
        FloatArray(8) { i -> if (i % 2 == 0) pts[i / 2].x.toFloat() else pts[i / 2].y.toFloat() }
    }
    return ScanCandidate(
        value, format,
        box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat(),
        corners
    )
}
