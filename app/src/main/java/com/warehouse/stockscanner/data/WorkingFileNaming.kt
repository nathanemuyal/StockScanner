package com.warehouse.stockscanner.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the file names for the two working copies derived from an
 * originally-picked Excel file — e.g.
 * "2026-09-09_שם_הקובץ_original_locations_quantities.xlsx". The name is
 * computed once, when a working file is first created, and then kept as-is
 * in [SessionPrefs] for as long as the same source file is being worked on
 * (so repeated saves keep updating the same file, not a new one every time).
 *
 * The original file's own name is sanitized so the result is a valid file
 * name on every OS: no path separators or other reserved/control
 * characters, no leading/trailing dots or spaces, and a bounded length.
 */
object WorkingFileNaming {

    enum class Kind(val suffix: String) {
        LOCATIONS_QUANTITIES("original_locations_quantities"),
        MULTIPLE_BARCODES("original_multiple_barcodes")
    }

    // Characters invalid in a file name on Windows, plus control characters
    // (also invalid, and harmless to strip everywhere else including
    // Linux/Android/macOS).
    private val INVALID_CHARS = Regex("[\\\\/:*?\"<>|\\x00-\\x1F]")
    private val WHITESPACE = Regex("\\s+")

    /** Maximum length kept from the sanitized original base name, comfortably under any OS path-length limit. */
    private const val MAX_BASE_NAME_LENGTH = 80

    /** Strips the original file's extension and makes what remains safe to embed in a new file name. */
    fun sanitizeBaseName(originalFileName: String): String {
        val withoutExtension = originalFileName.substringBeforeLast('.', originalFileName)
        val cleaned = withoutExtension
            .replace(INVALID_CHARS, "_")
            .replace(WHITESPACE, "_")
            .trim('_', ' ', '.')
        val safe = cleaned.ifBlank { "excel" }
        return safe.take(MAX_BASE_NAME_LENGTH)
    }

    /** e.g. buildFileName("מלאי מרץ.xlsx", Kind.LOCATIONS_QUANTITIES) -> "2026-09-09_מלאי_מרץ_original_locations_quantities.xlsx" */
    fun buildFileName(originalFileName: String, kind: Kind, date: Date = Date()): String {
        val datePart = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
        val baseName = sanitizeBaseName(originalFileName)
        return "${datePart}_${baseName}_${kind.suffix}.xlsx"
    }
}
