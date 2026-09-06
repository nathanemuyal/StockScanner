package com.warehouse.stockscanner.excel

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.warehouse.stockscanner.data.ProductEntity
import com.warehouse.stockscanner.util.LocationUtils
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class ExcelFormatException(message: String) : Exception(message)

/**
 * Result of reading the source file. [duplicateSkuRows] and [duplicateBarcodeRows]
 * let the caller warn the user about data-quality issues instead of silently
 * dropping or mismatching rows.
 */
data class ExcelLoadResult(
    val products: List<ProductEntity>,
    val duplicateSkuRows: Int,
    val duplicateBarcodeRows: Int
)

/**
 * Minimal, dependency-free XLSX reader built directly on java.util.zip and
 * Android's built-in XmlPullParser. This avoids Apache POI, which has known
 * class-loading / AWT-dependency problems on Android.
 *
 * Only the first worksheet of the workbook is read (this app only ever
 * deals with single-sheet product export files).
 */
object ExcelReader {

    private const val COL_SKU = "מקט"
    private const val COL_DESCRIPTION = "תאור"
    private const val COL_BARCODE = "ברקוד"
    private const val COL_LOCATION = "מיקום"

    // A product with more than one location gets extra columns "מיקום 2",
    // "מיקום 3", ... rather than a single delimited cell — this matches the
    // column-per-location layout the file is expected to use.
    private val LOCATION_HEADER_REGEX = Regex("^${Regex.escape(COL_LOCATION)}(?:\\s+(\\d+))?$")

    fun readProducts(context: Context, uri: Uri): ExcelLoadResult {
        val opened = context.contentResolver.openInputStream(uri)
            ?: throw ExcelFormatException("לא ניתן לפתוח את הקובץ שנבחר")
        return opened.use { readProductsFromStream(it) }
    }

    /**
     * Core parsing logic, decoupled from Context/Uri so it can also be driven
     * directly from a plain InputStream (e.g. in tests, against real .xlsx
     * fixtures produced by an independent tool).
     */
    fun readProductsFromStream(input: java.io.InputStream): ExcelLoadResult {
        val entries = HashMap<String, ByteArray>()

        ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name == "xl/sharedStrings.xml" ||
                        name.matches(Regex("xl/worksheets/sheet\\d+\\.xml"))
                    ) {
                        val bos = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var len = zip.read(buffer)
                        while (len >= 0) {
                            bos.write(buffer, 0, len)
                            len = zip.read(buffer)
                        }
                        entries[name] = bos.toByteArray()
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

        if (entries.keys.none { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }) {
            throw ExcelFormatException("הקובץ שנבחר אינו קובץ Excel (xlsx) תקין")
        }

        val sharedStrings = entries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()

        val sheetEntryName = entries.keys
            .filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
            .minByOrNull { Regex("\\d+").find(it)?.value?.toIntOrNull() ?: Int.MAX_VALUE }
            ?: throw ExcelFormatException("לא נמצא גיליון עבודה בקובץ")

        val sheetBytes = entries[sheetEntryName]
            ?: throw ExcelFormatException("לא נמצא גיליון עבודה בקובץ")

        return parseSheet(sheetBytes, sharedStrings)
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val result = ArrayList<String>()
        val parser = Xml.newPullParser()
        parser.setInput(bytes.inputStream(), "UTF-8")

        var eventType = parser.eventType
        var currentText: StringBuilder? = null
        var insideSi = false

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "si") {
                        insideSi = true
                        currentText = StringBuilder()
                    }
                }
                XmlPullParser.TEXT -> {
                    if (insideSi) currentText?.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "si") {
                        result.add(currentText?.toString() ?: "")
                        insideSi = false
                        currentText = null
                    }
                }
            }
            eventType = parser.next()
        }
        return result
    }

    private fun parseSheet(bytes: ByteArray, sharedStrings: List<String>): ExcelLoadResult {
        val parser = Xml.newPullParser()
        parser.setInput(bytes.inputStream(), "UTF-8")

        var headerMap: Map<String, Int>? = null
        val dataRows = ArrayList<Map<Int, String>>()

        var eventType = parser.eventType
        var currentRow: HashMap<Int, String>? = null
        var currentCellColumn = -1
        var currentCellType: String? = null
        var currentValue: StringBuilder? = null
        var inValueTag = false
        var isFirstRow = true

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "row" -> currentRow = HashMap()
                        "c" -> {
                            val ref = parser.getAttributeValue(null, "r") ?: ""
                            val letters = ExcelColumns.columnLettersFromRef(ref)
                            currentCellColumn = if (letters.isNotEmpty()) ExcelColumns.letterToIndex(letters) else -1
                            currentCellType = parser.getAttributeValue(null, "t")
                        }
                        "v", "t" -> {
                            inValueTag = true
                            currentValue = StringBuilder()
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inValueTag) currentValue?.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "v", "t" -> {
                            inValueTag = false
                            if (currentCellColumn >= 0 && currentRow != null) {
                                val raw = currentValue?.toString() ?: ""
                                val resolved = if (currentCellType == "s") {
                                    raw.toIntOrNull()?.let { sharedStrings.getOrNull(it) } ?: ""
                                } else {
                                    raw
                                }
                                if (resolved.isNotEmpty() || !currentRow.containsKey(currentCellColumn)) {
                                    currentRow[currentCellColumn] = resolved
                                }
                            }
                            currentValue = null
                        }
                        "c" -> {
                            currentCellColumn = -1
                            currentCellType = null
                        }
                        "row" -> {
                            currentRow?.let { row ->
                                if (isFirstRow) {
                                    headerMap = buildHeaderMap(row)
                                    isFirstRow = false
                                } else {
                                    dataRows.add(row)
                                }
                            }
                            currentRow = null
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        val headers = headerMap ?: throw ExcelFormatException("הקובץ ריק או שאין בו שורת כותרות")
        val skuCol = headers[COL_SKU]
        val descCol = headers[COL_DESCRIPTION]
        val barcodeCol = headers[COL_BARCODE]

        // Every header matching "מיקום" or "מיקום <n>", in ascending order of
        // n (the bare "מיקום" counts as 1), combined into one internal value.
        val locationCols = headers.entries
            .mapNotNull { (header, colIndex) ->
                val match = LOCATION_HEADER_REGEX.find(header) ?: return@mapNotNull null
                val number = match.groupValues[1].toIntOrNull() ?: 1
                number to colIndex
            }
            .sortedBy { it.first }
            .map { it.second }

        val missing = ArrayList<String>()
        if (skuCol == null) missing.add(COL_SKU)
        if (descCol == null) missing.add(COL_DESCRIPTION)
        if (barcodeCol == null) missing.add(COL_BARCODE)
        if (locationCols.isEmpty()) missing.add(COL_LOCATION)
        if (missing.isNotEmpty()) {
            throw ExcelFormatException("בקובץ חסרות העמודות הבאות: ${missing.joinToString(", ")}")
        }

        // Collect every data row first (including any duplicate skus), preserving
        // file order, so duplicates can be reported rather than silently mismatched.
        val rawRows = ArrayList<ProductEntity>()
        var order = 0
        for (row in dataRows) {
            val sku = row[skuCol!!]?.trim().orEmpty()
            if (sku.isEmpty()) continue
            val description = row[descCol!!]?.trim().orEmpty()
            val barcode = row[barcodeCol!!]?.trim().orEmpty()
            val locations = locationCols.mapNotNull { row[it]?.trim() }.filter { it.isNotEmpty() }
            val location = LocationUtils.format(locations.distinct())
            rawRows.add(ProductEntity(sku, description, barcode, location, order))
            order++
        }

        // De-duplicate by sku deterministically: if the same sku appears more than
        // once, the LAST row in the file wins (matches how a re-export would behave),
        // while the row's original position in the file is preserved for write-back.
        val bySku = LinkedHashMap<String, ProductEntity>()
        for (p in rawRows) {
            bySku[p.sku] = p
        }
        val products = bySku.values.mapIndexed { index, p -> p.copy(rowOrder = index) }
        val duplicateSkuRows = rawRows.size - bySku.size

        // Counted on the already-deduplicated products, not the raw rows: the same
        // sku repeated with the same barcode (a re-stated row) is not a real
        // barcode collision between two different products.
        val nonBlankBarcodes = products.map { it.barcode }.filter { it.isNotBlank() }
        val duplicateBarcodeRows = nonBlankBarcodes.size - nonBlankBarcodes.distinct().size

        return ExcelLoadResult(products, duplicateSkuRows, duplicateBarcodeRows)
    }

    private fun buildHeaderMap(row: Map<Int, String>): Map<String, Int> {
        val map = HashMap<String, Int>()
        for ((colIndex, value) in row) {
            val trimmed = value.trim()
            if (trimmed.isNotEmpty()) map[trimmed] = colIndex
        }
        return map
    }
}
