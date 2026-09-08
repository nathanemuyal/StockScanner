package com.warehouse.stockscanner.excel

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.warehouse.stockscanner.data.BarcodeAliasEntity
import com.warehouse.stockscanner.data.ProductEntity
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class ExcelFormatException(message: String) : Exception(message)

/**
 * Result of reading the source file. [duplicateRows] and [duplicateBarcodeRows]
 * let the caller warn the user about data-quality issues instead of silently
 * dropping or mismatching rows. [barcodeAliases] are extra barcodes attached
 * to a sku that already has its own primary one — read from the "ברקודים
 * כפולים" worksheet this app's own writer produces; empty for a source file
 * that never had one (e.g. a fresh export from another system).
 */
data class ExcelLoadResult(
    val products: List<ProductEntity>,
    val duplicateRows: Int,
    val duplicateBarcodeRows: Int,
    val barcodeAliases: List<BarcodeAliasEntity> = emptyList()
)

/**
 * Minimal, dependency-free XLSX reader built directly on java.util.zip and
 * Android's built-in XmlPullParser. This avoids Apache POI, which has known
 * class-loading / AWT-dependency problems on Android.
 *
 * The first worksheet is the product list. An optional second worksheet
 * ("ברקודים כפולים", written by [com.warehouse.stockscanner.excel.ExcelWriter])
 * carries extra barcodes aliased to a sku that already has its own — it's
 * only ever treated as such when its header row actually matches; otherwise
 * (a source file with some unrelated second sheet) it's silently ignored.
 */
object ExcelReader {

    private const val COL_SKU = "מקט"
    private const val COL_DESCRIPTION = "תאור"
    private const val COL_BARCODE = "ברקוד"
    private const val COL_LOCATION = "מיקום"
    private const val COL_QUANTITY_TYPE = "סוג כמות"
    private const val COL_PACKAGE_CONTENT = "תכולת אריזה"
    private const val COL_PACKAGE_COUNT = "כמות אריזות"
    private const val COL_QUANTITY = "כמות יחידות"

    private const val COL_ALIAS_BARCODE = "ברקוד"
    private const val COL_ALIAS_SKU = "מקט"

    // The working file only ever has a single "מיקום" column — a product at
    // several locations is several rows, not several columns. Numbered
    // headers ("מיקום 2", "מיקום 3", ...) are still recognized here purely
    // for backward compatibility with files produced by an older version of
    // this app; every value found under any of them becomes its own row.
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

        val sheetEntryNames = entries.keys
            .filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
            .sortedBy { Regex("\\d+").find(it)?.value?.toIntOrNull() ?: Int.MAX_VALUE }
        if (sheetEntryNames.isEmpty()) throw ExcelFormatException("לא נמצא גיליון עבודה בקובץ")

        val sheetBytes = entries[sheetEntryNames.first()]
            ?: throw ExcelFormatException("לא נמצא גיליון עבודה בקובץ")
        val productResult = parseSheet(sheetBytes, sharedStrings)

        // A second worksheet, if present, is only ever treated as the
        // ברקודים כפולים sheet when its headers actually match — a random
        // second sheet in a source file from elsewhere is otherwise ignored.
        val aliases = sheetEntryNames.getOrNull(1)
            ?.let { entries[it] }
            ?.let { parseAliasSheet(it, sharedStrings) }
            ?: emptyList()

        return productResult.copy(barcodeAliases = aliases)
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

    /** A worksheet's header row (by name -> column index) plus every data row that follows it. */
    private data class RawSheet(val headers: Map<String, Int>?, val dataRows: List<Map<Int, String>>)

    /** Walks a worksheet's raw XML into rows, resolving shared strings — shared by [parseSheet] and [parseAliasSheet]. */
    private fun parseRawSheet(bytes: ByteArray, sharedStrings: List<String>): RawSheet {
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

        return RawSheet(headerMap, dataRows)
    }

    private fun parseSheet(bytes: ByteArray, sharedStrings: List<String>): ExcelLoadResult {
        val raw = parseRawSheet(bytes, sharedStrings)
        val dataRows = raw.dataRows
        val headers = raw.headers ?: throw ExcelFormatException("הקובץ ריק או שאין בו שורת כותרות")
        val skuCol = headers[COL_SKU]
        val descCol = headers[COL_DESCRIPTION]
        val barcodeCol = headers[COL_BARCODE]

        // Quantity columns are optional — a file exported before the
        // inventory screen existed, or one edited by hand, simply won't have
        // them, and every row then falls back to "יחידות" / 0, same as a
        // freshly-confirmed row whose quantity hasn't been entered yet.
        val quantityTypeCol = headers[COL_QUANTITY_TYPE]
        val packageContentCol = headers[COL_PACKAGE_CONTENT]
        val packageCountCol = headers[COL_PACKAGE_COUNT]
        val quantityCol = headers[COL_QUANTITY]

        // Every header matching "מיקום" or "מיקום <n>", in ascending order of
        // n (the bare "מיקום" counts as 1) — each becomes its own row for the
        // sku (see the expansion below), rather than one merged value.
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

        // Collect every data row, expanding a row that lists several locations
        // (legacy "מיקום 2", "מיקום 3" columns) into one tuple per location —
        // a product with N locations becomes N rows, not one row with a
        // combined cell. A row with no location at all still becomes one row,
        // with a blank מיקום, so the product exists even before it's shelved.
        data class RawTuple(
            val sku: String,
            val description: String,
            val barcode: String,
            val location: String,
            val quantityType: String,
            val packageContent: Int,
            val packageCount: Int,
            val quantity: Int
        )

        val rawTuples = ArrayList<RawTuple>()
        for (row in dataRows) {
            val sku = row[skuCol!!]?.trim().orEmpty()
            if (sku.isEmpty()) continue
            val description = row[descCol!!]?.trim().orEmpty()
            val barcode = row[barcodeCol!!]?.trim().orEmpty()
            val locations = locationCols.mapNotNull { row[it]?.trim() }.filter { it.isNotEmpty() }.distinct()

            val quantityType = quantityTypeCol?.let { row[it]?.trim() }
                .takeIf { it == ProductEntity.TYPE_PACKAGE }
                ?: ProductEntity.TYPE_UNITS
            val packageContent = packageContentCol?.let { row[it]?.trim()?.toIntOrNull() } ?: 0
            val packageCount = packageCountCol?.let { row[it]?.trim()?.toIntOrNull() } ?: 0
            val quantity = quantityCol?.let { row[it]?.trim()?.toIntOrNull() } ?: 0

            if (locations.isEmpty()) {
                rawTuples.add(RawTuple(sku, description, barcode, "", quantityType, packageContent, packageCount, quantity))
            } else {
                for (location in locations) {
                    rawTuples.add(RawTuple(sku, description, barcode, location, quantityType, packageContent, packageCount, quantity))
                }
            }
        }

        // De-duplicate by (sku, location) deterministically: if the exact same
        // product/location pair appears more than once, the LAST row in the
        // file wins (matches how a re-export would behave), while the file
        // order otherwise decides row-write-back order.
        val bySkuAndLocation = LinkedHashMap<Pair<String, String>, RawTuple>()
        for (t in rawTuples) {
            bySkuAndLocation[t.sku to t.location] = t
        }
        val products = bySkuAndLocation.values.mapIndexed { index, t ->
            ProductEntity(
                t.sku, t.description, t.barcode, t.location, index,
                t.quantityType, t.packageContent, t.packageCount, t.quantity
            )
        }
        val duplicateRows = rawTuples.size - bySkuAndLocation.size

        // A barcode collision only matters between two DIFFERENT products —
        // the same sku legitimately repeats its barcode across its own
        // location rows, so this is checked per distinct sku (its first
        // non-blank barcode), not per row.
        val barcodeBySku = LinkedHashMap<String, String>()
        for (p in products) {
            if (p.barcode.isNotBlank()) barcodeBySku.putIfAbsent(p.sku, p.barcode)
        }
        val barcodes = barcodeBySku.values.toList()
        val duplicateBarcodeRows = barcodes.size - barcodes.distinct().size

        return ExcelLoadResult(products, duplicateRows, duplicateBarcodeRows)
    }

    /**
     * The "ברקודים כפולים" sheet: one row per extra barcode aliased to a sku
     * that already has its own primary one. Only recognized when the header
     * row actually has both expected columns — otherwise (an unrelated
     * second sheet, or none of these headers) this quietly returns nothing,
     * exactly like a legacy source file with no such sheet at all.
     */
    private fun parseAliasSheet(bytes: ByteArray, sharedStrings: List<String>): List<BarcodeAliasEntity> {
        val raw = parseRawSheet(bytes, sharedStrings)
        val headers = raw.headers ?: return emptyList()
        val barcodeCol = headers[COL_ALIAS_BARCODE] ?: return emptyList()
        val skuCol = headers[COL_ALIAS_SKU] ?: return emptyList()

        // De-duplicated by barcode, last row wins — same spirit as the
        // product sheet's (sku, location) de-duplication.
        val byBarcode = LinkedHashMap<String, String>()
        for (row in raw.dataRows) {
            val barcode = row[barcodeCol]?.trim().orEmpty()
            val sku = row[skuCol]?.trim().orEmpty()
            if (barcode.isEmpty() || sku.isEmpty()) continue
            byBarcode[barcode] = sku
        }
        return byBarcode.map { (barcode, sku) -> BarcodeAliasEntity(barcode = barcode, sku = sku) }
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
