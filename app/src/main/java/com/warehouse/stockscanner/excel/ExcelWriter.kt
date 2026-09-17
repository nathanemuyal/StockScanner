package com.warehouse.stockscanner.excel

import com.warehouse.stockscanner.data.BarcodeAliasEntity
import com.warehouse.stockscanner.data.ProductEntity
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal, dependency-free XLSX writer. Produces valid .xlsx packages by
 * hand (no Apache POI). Every cell is written as an inline string
 * (t="inlineStr") — this keeps the writer simple and, importantly,
 * guarantees sku values like "0001234" keep their leading zeros instead of
 * being reinterpreted as numbers.
 *
 * Two kinds of files are produced, matching the app's two working files
 * (see [com.warehouse.stockscanner.data.ProductRepository]):
 *  - [writeLocationsQuantitiesToStream]: the product/location/quantity table.
 *  - [writeMultipleBarcodesToStream]: the מקט -> extra ברקוד mapping.
 * [writeProductsToStream] additionally produces a single combined workbook
 * (product sheet + alias sheet) — kept for backward compatibility (it's the
 * format [ExcelReader] still accepts for an externally-produced source file)
 * and reused by tests to build fixtures.
 */
object ExcelWriter {

    private const val COL_SKU = "מקט"
    private const val COL_DESCRIPTION = "תאור"
    private const val COL_BARCODE = "ברקוד"
    private const val COL_LOCATION = "מיקום"
    private const val COL_QUANTITY_TYPE = "סוג כמות"
    private const val COL_PACKAGE_CONTENT = "תכולת אריזה"
    private const val COL_PACKAGE_COUNT = "כמות אריזות"
    private const val COL_LOOSE_UNITS = "יחידות בודדות"
    private const val COL_QUANTITY = "כמות יחידות"

    private const val COL_SUMMARY_TOTAL = "סה״כ יחידות"
    private const val SUMMARY_TOTAL_LABEL = "סה״כ"
    private const val SUMMARY_SHEET_NAME = "סיכום"
    private const val BARCODES_SHEET_NAME = "ברקודים כפולים"

    private const val COL_ALIAS_BARCODE = "ברקוד"
    private const val COL_ALIAS_SKU = "מקט"

    // COL_LOOSE_UNITS sits between the package breakdown it belongs to and
    // the COL_QUANTITY total it feeds into: in "מעורב" mode the shelf holds
    // whole packages *and* loose singles, and the total is the sum of both.
    private val PRODUCT_HEADERS = listOf(
        COL_SKU, COL_DESCRIPTION, COL_BARCODE, COL_LOCATION,
        COL_QUANTITY_TYPE, COL_PACKAGE_CONTENT, COL_PACKAGE_COUNT, COL_LOOSE_UNITS, COL_QUANTITY
    )

    private val SUMMARY_HEADERS = listOf(COL_SKU, COL_DESCRIPTION, COL_LOCATION, COL_SUMMARY_TOTAL)

    // Order matches the task spec's example: מק"ט, תיאור, ברקוד.
    private val MULTIPLE_BARCODES_HEADERS = listOf(COL_ALIAS_SKU, COL_DESCRIPTION, COL_ALIAS_BARCODE)

    /**
     * Core writing logic, decoupled from Context/Uri so it can also be driven
     * directly against a plain OutputStream (e.g. in tests). [aliases] are
     * extra barcodes attached to a sku that already has its own (primary)
     * ברקוד — written as a second worksheet ("ברקודים כפולים") rather than
     * extra columns on the product rows, since an alias isn't tied to any
     * one location.
     */
    fun writeProductsToStream(
        output: OutputStream,
        products: List<ProductEntity>,
        aliases: List<BarcodeAliasEntity> = emptyList()
    ) {
        val ordered = products.sortedBy { it.rowOrder }

        writeTwoSheetPackage(
            output,
            PRODUCT_HEADERS, productRows(ordered),
            BARCODES_SHEET_NAME, MULTIPLE_BARCODES_HEADERS, aliasRows(aliases, ordered)
        )
    }

    /**
     * The "locations + quantities" working file: sku, description, barcode,
     * location and the quantity breakdown — one row per scan, same shape as
     * this app's product table itself (see
     * [com.warehouse.stockscanner.data.ProductEntity]) — plus a "סיכום"
     * sheet that adds those rows up.
     *
     * The summary is not a convenience. A shelf legitimately produces more
     * than one row for the same מקט — a package barcode and a single-unit
     * barcode are counted separately, and each gets its own row — so
     * "how many are there" is a question the detail sheet cannot answer
     * without someone adding rows up by hand. The detail sheet stays exactly
     * as it was: it is the audit trail showing how each total was reached.
     */
    fun writeLocationsQuantitiesToStream(output: OutputStream, products: List<ProductEntity>) {
        val ordered = groupedBySku(products)
        writeTwoSheetPackage(
            output,
            PRODUCT_HEADERS, productRows(ordered),
            SUMMARY_SHEET_NAME, SUMMARY_HEADERS, summaryRows(ordered)
        )
    }

    /**
     * Keeps a מקט's rows next to each other, in the order they were counted.
     *
     * A row opened later for a מקט counted earlier — a second ברקוד at the
     * same shelf, or the same product found on another shelf — takes
     * maxRowOrder + 1 and so lands at the very end of the table, pages away
     * from the rows it belongs with. Reading a product's shelves then means
     * hunting through the file for lines that should have been adjacent.
     *
     * Sorting at write time rather than renumbering rowOrder keeps this a
     * question of how the file reads: rowOrder still records the order rows
     * were created in, and no migration is needed to reshuffle it.
     */
    private fun groupedBySku(products: List<ProductEntity>): List<ProductEntity> {
        val firstAppearance = HashMap<String, Int>()
        for (product in products.sortedBy { it.rowOrder }) {
            firstAppearance.putIfAbsent(product.sku, product.rowOrder)
        }
        return products.sortedWith(
            compareBy({ firstAppearance[it.sku] ?: it.rowOrder }, { it.rowOrder })
        )
    }

    /**
     * One row per (מקט, מיקום) with its unit total, followed by a total row
     * for the מקט itself. Quantities are already stored in single units
     * whichever mode they were entered in, so this is a plain sum — the
     * arithmetic that turns packages into units happened when the count was
     * recorded, not here.
     *
     * Skus keep the order they appear in the detail sheet, and locations the
     * order they were counted in, so the two sheets read side by side.
     */
    private fun summaryRows(products: List<ProductEntity>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        for ((sku, skuRows) in products.groupBy { it.sku }) {
            val description = skuRows.first().description
            for ((location, locationRows) in skuRows.groupBy { it.location }) {
                rows += listOf(sku, description, location, locationRows.sumOf { it.quantity }.toString())
            }
            rows += listOf(sku, description, SUMMARY_TOTAL_LABEL, skuRows.sumOf { it.quantity }.toString())
        }
        return rows
    }

    /**
     * The "multiple barcodes per מקט" working file: every extra ברקוד
     * aliased to a sku that already has its own primary one, alongside that
     * sku's description for readability (looked up from [products], not
     * stored redundantly on [BarcodeAliasEntity] itself).
     */
    fun writeMultipleBarcodesToStream(
        output: OutputStream,
        aliases: List<BarcodeAliasEntity>,
        products: List<ProductEntity>
    ) {
        writeSingleSheetPackage(output, MULTIPLE_BARCODES_HEADERS, aliasRows(aliases, products))
    }

    private fun productRows(products: List<ProductEntity>): List<List<String>> = products.map { p ->
        listOf(
            p.sku, p.description, p.barcode, p.location,
            p.quantityType, p.packageContent.toString(), p.packageCount.toString(),
            p.looseUnits.toString(), p.quantity.toString()
        )
    }

    private fun aliasRows(aliases: List<BarcodeAliasEntity>, products: List<ProductEntity>): List<List<String>> {
        val descriptionBySku = products.groupBy { it.sku }.mapValues { (_, rows) -> rows.first().description }
        return aliases.map { alias -> listOf(alias.sku, descriptionBySku[alias.sku].orEmpty(), alias.barcode) }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun escapeXml(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> if (isValidXmlChar(ch)) sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun isValidXmlChar(ch: Char): Boolean {
        val code = ch.code
        return code == 0x9 || code == 0xA || code == 0xD ||
            (code in 0x20..0xD7FF) || (code in 0xE000..0xFFFD)
    }

    private fun cell(colLetter: String, rowIndex: Int, value: String): String {
        val ref = "$colLetter$rowIndex"
        return "<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escapeXml(value)}</t></is></c>"
    }

    /** A single worksheet's XML: a header row followed by one row per entry of [rows] (values in header order). */
    private fun genericSheetXml(headers: List<String>, rows: List<List<String>>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        val lastRow = rows.size + 1
        val lastColLetter = ExcelColumns.indexToLetter(headers.size - 1)
        sb.append("<dimension ref=\"A1:$lastColLetter$lastRow\"/>")
        sb.append("<sheetData>")

        sb.append("<row r=\"1\">")
        headers.forEachIndexed { index, header ->
            sb.append(cell(ExcelColumns.indexToLetter(index), 1, header))
        }
        sb.append("</row>")

        var rowNum = 2
        for (row in rows) {
            sb.append("<row r=\"$rowNum\">")
            row.forEachIndexed { index, value ->
                sb.append(cell(ExcelColumns.indexToLetter(index), rowNum, value))
            }
            sb.append("</row>")
            rowNum++
        }

        sb.append("</sheetData>")
        sb.append("</worksheet>")
        return sb.toString()
    }

    /** A complete, valid single-sheet .xlsx package containing just [headers]/[rows]. */
    private fun writeTwoSheetPackage(
        output: OutputStream,
        headers: List<String>,
        rows: List<List<String>>,
        secondSheetName: String,
        secondHeaders: List<String>,
        secondRows: List<List<String>>
    ) {
        BufferedOutputStream(output).use { buffered ->
            ZipOutputStream(buffered).use { zip ->
                writeEntry(zip, "[Content_Types].xml", contentTypesXml(twoSheets = true))
                writeEntry(zip, "_rels/.rels", relsXml())
                writeEntry(zip, "xl/workbook.xml", workbookXml(secondSheetName))
                writeEntry(zip, "xl/_rels/workbook.xml.rels", workbookRelsXml(twoSheets = true))
                writeEntry(zip, "xl/styles.xml", stylesXml())
                writeEntry(zip, "xl/worksheets/sheet1.xml", genericSheetXml(headers, rows))
                writeEntry(zip, "xl/worksheets/sheet2.xml", genericSheetXml(secondHeaders, secondRows))
            }
        }
    }

    private fun writeSingleSheetPackage(output: OutputStream, headers: List<String>, rows: List<List<String>>) {
        BufferedOutputStream(output).use { buffered ->
            ZipOutputStream(buffered).use { zip ->
                writeEntry(zip, "[Content_Types].xml", contentTypesXml(twoSheets = false))
                writeEntry(zip, "_rels/.rels", relsXml())
                writeEntry(zip, "xl/workbook.xml", singleSheetWorkbookXml())
                writeEntry(zip, "xl/_rels/workbook.xml.rels", workbookRelsXml(twoSheets = false))
                writeEntry(zip, "xl/styles.xml", stylesXml())
                writeEntry(zip, "xl/worksheets/sheet1.xml", genericSheetXml(headers, rows))
            }
        }
    }

    private fun contentTypesXml(twoSheets: Boolean): String {
        val sheet2Override = if (twoSheets) {
            "<Override PartName=\"/xl/worksheets/sheet2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
        } else {
            ""
        }
        return """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
        <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
        <Default Extension="xml" ContentType="application/xml"/>
        <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
        <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
        <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
        $sheet2Override
        </Types>
        """.trimIndent()
    }

    private fun relsXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
        </Relationships>
    """.trimIndent()

    private fun workbookXml(secondSheetName: String): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
        <sheets>
        <sheet name="Sheet1" sheetId="1" r:id="rId1"/>
        <sheet name="${escapeXml(secondSheetName)}" sheetId="2" r:id="rId3"/>
        </sheets>
        </workbook>
    """.trimIndent()

    private fun singleSheetWorkbookXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
        <sheets>
        <sheet name="Sheet1" sheetId="1" r:id="rId1"/>
        </sheets>
        </workbook>
    """.trimIndent()

    private fun workbookRelsXml(twoSheets: Boolean): String {
        val sheet2Rel = if (twoSheets) {
            "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/>"
        } else {
            ""
        }
        return """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
        <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
        $sheet2Rel
        </Relationships>
        """.trimIndent()
    }

    private fun stylesXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
        <fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>
        <fills count="1"><fill><patternFill patternType="none"/></fill></fills>
        <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
        <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
        <cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>
        </styleSheet>
    """.trimIndent()
}
