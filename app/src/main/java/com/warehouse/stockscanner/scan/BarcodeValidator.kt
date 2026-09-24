package com.warehouse.stockscanner.scan

import com.google.mlkit.vision.barcode.common.Barcode

/**
 * Rejects values that can't be a real, complete read of their format.
 *
 * A camera pointed at a shelf regularly produces partial or garbled 1D
 * reads (motion blur, a glare stripe across the bars, a label at a steep
 * angle). This is the cheap first filter: a value is only accepted when its
 * structure and check digit hold for the format it was decoded as, and
 * short Code 128/39 fragments are dropped. It is NOT enough on its own —
 * decoding the real warehouse photos in resurse/ produced "005817530627"
 * off a box whose barcode is really 4007817530627, and that misread has a
 * valid UPC-A check digit. [ScanConsensus] is what catches those.
 */
object BarcodeValidator {

    /** Code 128 / Code 39 have no mandatory check digit, so very short reads are almost always fragments. */
    const val MIN_FREE_TEXT_LENGTH = 4

    /**
     * GS1 Data Matrix / GS1-128 (product marking codes) start with, and
     * separate their variable-length fields with, ASCII 29 — part of the
     * real value, not noise (Java even counts it as whitespace, so trim()
     * would eat the leading one). Found by the milk-bottle photo in
     * ScanAccuracyTest, whose code was being thrown away.
     */
    const val GS1_SEPARATOR = '\u001D'

    fun isValid(value: String?, format: Int): Boolean {
        if (value.isNullOrBlank()) return false
        if (value != value.trim { it.isWhitespace() && it != GS1_SEPARATOR }) return false
        if (value.any { Character.isISOControl(it) && it != GS1_SEPARATOR }) return false
        return when (format) {
            Barcode.FORMAT_EAN_13 -> value.length == 13 && hasValidGtinCheckDigit(value)
            Barcode.FORMAT_EAN_8 -> value.length == 8 && hasValidGtinCheckDigit(value)
            Barcode.FORMAT_UPC_A -> value.length == 12 && hasValidGtinCheckDigit(value)
            Barcode.FORMAT_UPC_E -> isValidUpcE(value)
            Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39 -> value.length >= MIN_FREE_TEXT_LENGTH
            // QR / Data Matrix carry Reed-Solomon error correction — a decoded
            // value is either right or not returned at all.
            else -> true
        }
    }

    /** Standard GS1 mod-10 check: weights 3,1,3,1… from the digit left of the check digit. */
    fun hasValidGtinCheckDigit(digits: String): Boolean {
        if (digits.length < 2 || !digits.all { it in '0'..'9' }) return false
        val body = digits.dropLast(1)
        var sum = 0
        for ((i, c) in body.reversed().withIndex()) {
            sum += (c - '0') * if (i % 2 == 0) 3 else 1
        }
        val expected = (10 - sum % 10) % 10
        return expected == digits.last() - '0'
    }

    /**
     * UPC-E is a compressed UPC-A; its check digit is the one of the expanded
     * 12-digit code. ML Kit returns the 8-digit form (number system + 6 + check).
     */
    private fun isValidUpcE(value: String): Boolean {
        if (value.length != 8 || !value.all { it in '0'..'9' }) return false
        if (value[0] != '0' && value[0] != '1') return false
        return hasValidGtinCheckDigit(expandUpcE(value))
    }

    fun expandUpcE(upcE: String): String {
        val ns = upcE[0]
        val d = upcE.substring(1, 7)
        val check = upcE[7]
        val manufacturerAndProduct = when (d[5]) {
            '0', '1', '2' -> "${d[0]}${d[1]}${d[5]}0000${d[2]}${d[3]}${d[4]}"
            '3' -> "${d[0]}${d[1]}${d[2]}00000${d[3]}${d[4]}"
            '4' -> "${d[0]}${d[1]}${d[2]}${d[3]}00000${d[4]}"
            else -> "${d[0]}${d[1]}${d[2]}${d[3]}${d[4]}0000${d[5]}"
        }
        return "$ns$manufacturerAndProduct$check"
    }
}
