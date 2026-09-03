package com.warehouse.stockscanner.excel

/** Helpers for converting between spreadsheet column letters ("A", "AA", ...) and 0-based indices. */
object ExcelColumns {

    // "A" -> 0, "B" -> 1, "AA" -> 26
    fun letterToIndex(letters: String): Int {
        var result = 0
        for (ch in letters) {
            result = result * 26 + (ch.uppercaseChar() - 'A' + 1)
        }
        return result - 1
    }

    // 0 -> "A", 1 -> "B", 26 -> "AA"
    fun indexToLetter(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.insert(0, ('A' + rem))
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private val CELL_REF_REGEX = Regex("^([A-Za-z]+)(\\d+)$")

    // "B7" -> "B"
    fun columnLettersFromRef(cellRef: String): String {
        val match = CELL_REF_REGEX.find(cellRef) ?: return ""
        return match.groupValues[1]
    }
}
