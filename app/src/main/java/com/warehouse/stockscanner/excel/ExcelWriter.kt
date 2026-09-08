package com.warehouse.stockscanner.excel

import com.warehouse.stockscanner.data.BarcodeAliasEntity
import com.warehouse.stockscanner.data.ProductEntity
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal, dependency-free XLSX writer. Produces a valid single-sheet
 * .xlsx package by hand (no Apache POI). Every cell is written as an
 * inline string (t="inlineStr") — this keeps the writer simple and,
 * importantly, guarantees sku values like "0001234" keep their leading
 * zeros instead of being reinterpreted as numbers.
 */
object ExcelWriter {

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

        BufferedOutputStream(output).use { buffered ->
            ZipOutputStream(buffered).use { zip ->
                writeEntry(zip, "[Content_Types].xml", contentTypesXml())
                writeEntry(zip, "_rels/.rels", relsXml())
                writeEntry(zip, "xl/workbook.xml", workbookXml())
                writeEntry(zip, "xl/_rels/workbook.xml.rels", workbookRelsXml())
                writeEntry(zip, "xl/styles.xml", stylesXml())
                writeEntry(zip, "xl/worksheets/sheet1.xml", sheetXml(ordered))
                writeEntry(zip, "xl/worksheets/sheet2.xml", aliasSheetXml(aliases))
            }
        }
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

    private fun sheetXml(products: List<ProductEntity>): String {
        // A product with several locations is several rows sharing the same
        // sku/description/barcode, each with its own single מיקום value —
        // never a combined cell or extra numbered columns. Quantity is also
        // per row: either entered directly (סוג כמות = יחידות) or derived
        // from a package breakdown (סוג כמות = אריזות), with כמות יחידות
        // always holding the final unit count either way.
        val headers = listOf(
            COL_SKU, COL_DESCRIPTION, COL_BARCODE, COL_LOCATION,
            COL_QUANTITY_TYPE, COL_PACKAGE_CONTENT, COL_PACKAGE_COUNT, COL_QUANTITY
        )

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        val lastRow = products.size + 1
        val lastColLetter = ExcelColumns.indexToLetter(headers.size - 1)
        sb.append("<dimension ref=\"A1:$lastColLetter$lastRow\"/>")
        sb.append("<sheetData>")

        sb.append("<row r=\"1\">")
        headers.forEachIndexed { index, header ->
            sb.append(cell(ExcelColumns.indexToLetter(index), 1, header))
        }
        sb.append("</row>")

        var rowNum = 2
        for (product in products) {
            sb.append("<row r=\"$rowNum\">")
            sb.append(cell("A", rowNum, product.sku))
            sb.append(cell("B", rowNum, product.description))
            sb.append(cell("C", rowNum, product.barcode))
            sb.append(cell("D", rowNum, product.location))
            sb.append(cell("E", rowNum, product.quantityType))
            sb.append(cell("F", rowNum, product.packageContent.toString()))
            sb.append(cell("G", rowNum, product.packageCount.toString()))
            sb.append(cell("H", rowNum, product.quantity.toString()))
            sb.append("</row>")
            rowNum++
        }

        sb.append("</sheetData>")
        sb.append("</worksheet>")
        return sb.toString()
    }

    /** "ברקודים כפולים": one row per extra barcode aliased to a sku that already has its own. */
    private fun aliasSheetXml(aliases: List<BarcodeAliasEntity>): String {
        val headers = listOf(COL_ALIAS_BARCODE, COL_ALIAS_SKU)

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        val lastRow = aliases.size + 1
        val lastColLetter = ExcelColumns.indexToLetter(headers.size - 1)
        sb.append("<dimension ref=\"A1:$lastColLetter$lastRow\"/>")
        sb.append("<sheetData>")

        sb.append("<row r=\"1\">")
        headers.forEachIndexed { index, header ->
            sb.append(cell(ExcelColumns.indexToLetter(index), 1, header))
        }
        sb.append("</row>")

        var rowNum = 2
        for (alias in aliases) {
            sb.append("<row r=\"$rowNum\">")
            sb.append(cell("A", rowNum, alias.barcode))
            sb.append(cell("B", rowNum, alias.sku))
            sb.append("</row>")
            rowNum++
        }

        sb.append("</sheetData>")
        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun contentTypesXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
        <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
        <Default Extension="xml" ContentType="application/xml"/>
        <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
        <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
        <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
        <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
        </Types>
    """.trimIndent()

    private fun relsXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
        </Relationships>
    """.trimIndent()

    private fun workbookXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
        <sheets>
        <sheet name="Sheet1" sheetId="1" r:id="rId1"/>
        <sheet name="ברקודים כפולים" sheetId="2" r:id="rId3"/>
        </sheets>
        </workbook>
    """.trimIndent()

    private fun workbookRelsXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
        <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
        <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
        </Relationships>
    """.trimIndent()

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
