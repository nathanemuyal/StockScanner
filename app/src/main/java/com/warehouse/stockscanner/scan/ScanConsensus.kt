package com.warehouse.stockscanner.scan

import com.google.mlkit.vision.barcode.common.Barcode

/**
 * Accepts a value only after it has been read the same way in more than one
 * frame.
 *
 * A 1D check digit only catches a single wrong digit — two misread bars can
 * still add up to a "valid" but wrong EAN, and Code 128/39 often have no
 * check at all. Requiring agreement across consecutive frames (≈70ms at
 * normal frame rates, so the worker doesn't feel it) filters out those
 * one-off misreads. QR / Data Matrix are error-corrected, so a single frame
 * is already trustworthy and is accepted immediately.
 *
 * Not thread-safe: fed only from ML Kit's success callbacks, which all run
 * on the main thread.
 */
class ScanConsensus(
    private val requiredHits1D: Int = 2,
    private val requiredHits2D: Int = 1,
    /** Empty/other frames tolerated between matching reads before starting over. */
    private val maxMissedFrames: Int = 3
) {
    private var current: String? = null
    private var hits = 0
    private var misses = 0

    /** Feed one frame's picked candidate (or null). Returns the value once confirmed. */
    fun offer(candidate: ScanCandidate?): String? {
        if (candidate == null) {
            if (++misses > maxMissedFrames) reset()
            return null
        }
        if (candidate.value == current) {
            hits++
        } else {
            current = candidate.value
            hits = 1
        }
        misses = 0
        return if (hits >= requiredHits(candidate.format)) candidate.value else null
    }

    fun reset() {
        current = null
        hits = 0
        misses = 0
    }

    private fun requiredHits(format: Int) = when (format) {
        Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_AZTEC, Barcode.FORMAT_PDF417 ->
            requiredHits2D
        else -> requiredHits1D
    }
}
