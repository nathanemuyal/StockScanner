package com.warehouse.stockscanner.data

import android.content.Context
import android.net.Uri
import com.warehouse.stockscanner.excel.ExcelLoadResult
import com.warehouse.stockscanner.excel.ExcelReader
import com.warehouse.stockscanner.excel.ExcelWriter
import com.warehouse.stockscanner.util.SearchUtils
import java.io.File
import java.io.FileOutputStream

/**
 * A product plus every location it's already recorded at, aggregated across
 * however many rows that spans internally. Used wherever the UI needs to
 * show "this product already has a location" without caring about rows.
 */
data class ProductLookup(
    val sku: String,
    val description: String,
    val barcode: String,
    val existingLocations: List<String>
)

class ProductRepository(private val context: Context, private val dao: ProductDao) {

    /**
     * The Excel file this app actually writes to. The file the user
     * originally picked is only ever read once, at import time — it is
     * never opened for writing again. This working copy is (re)created the
     * moment a new source file is loaded, and stays "the" file for as long
     * as no different products file is loaded in its place. Living in the
     * app's private storage means writing to it never needs a permission
     * dialog, and it can only ever be updated from inside this app.
     */
    private val workingFile: File
        get() = File(context.filesDir, "working_products.xlsx")

    /** Loads a new Excel file, replacing whatever was loaded before, and opens a fresh working copy for it. */
    suspend fun loadFromExcel(uri: Uri): ExcelLoadResult {
        val result = ExcelReader.readProducts(context, uri)
        dao.clearAll()
        dao.insertAll(result.products)
        writeWorkingCopy()
        return result
    }

    suspend fun findByBarcode(barcode: String): ProductLookup? {
        val trimmed = barcode.trim()
        if (trimmed.isEmpty()) return null
        val row = dao.findByBarcode(trimmed) ?: return null
        return lookupFor(row.sku)
    }

    suspend fun findBySku(sku: String): ProductLookup? = lookupFor(sku)

    private suspend fun lookupFor(sku: String): ProductLookup? {
        val rows = dao.findAllBySku(sku)
        if (rows.isEmpty()) return null
        val first = rows.first()
        val locations = rows.map { it.location }.filter { it.isNotBlank() }.distinct()
        return ProductLookup(first.sku, first.description, first.barcode, locations)
    }

    /** One result per product, regardless of how many locations it has rows for. */
    suspend fun searchByDescription(query: String): List<ProductEntity> {
        val representative = dao.getAllForSearch()
            .groupBy { it.sku }
            .values
            .map { rows -> rows.minByOrNull { it.rowOrder }!! }
        return SearchUtils.search(representative, query)
    }

    /**
     * Applies a confirmed scan. The product's description/barcode are kept
     * in sync across every location it already has — it's the same product
     * wherever it sits. The location itself is only ever ADDED to: if [sku]
     * already has a row at [newLocation], that row is refreshed in place;
     * otherwise a brand-new row is opened for it (cloned from an existing
     * row), so a product can sit in more than one location at once without
     * ever losing an older one. Never creates a row for an unknown sku.
     */
    suspend fun updateProduct(sku: String, newDescription: String, newBarcode: String, newLocation: String) {
        val existingRows = dao.findAllBySku(sku)
        if (existingRows.isEmpty()) return
        val trimmedLocation = newLocation.trim()

        for (row in existingRows) {
            if (row.description != newDescription || row.barcode != newBarcode) {
                dao.update(row.copy(description = newDescription, barcode = newBarcode))
            }
        }

        if (existingRows.any { it.location == trimmedLocation }) return

        // A single row that has no location yet just gets this one filled
        // in, instead of being left behind as an orphaned blank row.
        val blankRow = existingRows.singleOrNull { it.location.isBlank() }
        if (blankRow != null) {
            dao.update(blankRow.copy(description = newDescription, barcode = newBarcode, location = trimmedLocation))
            return
        }

        val template = existingRows.first()
        val nextOrder = (dao.maxRowOrder() ?: -1) + 1
        dao.insert(
            template.copy(
                id = 0,
                description = newDescription,
                barcode = newBarcode,
                location = trimmedLocation,
                rowOrder = nextOrder
            )
        )
    }

    suspend fun count(): Int = dao.count()

    /** Every product row currently assigned to exactly [location] — what's on that shelf right now. */
    suspend fun findByLocation(location: String): List<ProductEntity> {
        val trimmed = location.trim()
        if (trimmed.isEmpty()) return emptyList()
        return dao.getAllForSearch().filter { it.location == trimmed }
    }

    /**
     * Writes the current data to the working copy. This is a deliberate,
     * explicit action — nothing else in this class calls it — so a save
     * only ever happens when the worker asks for it, typically once at the
     * end of the whole process, never automatically after each scan.
     */
    suspend fun saveWorkingCopy() {
        writeWorkingCopy()
    }

    private suspend fun writeWorkingCopy() {
        val all = dao.getAllOrdered()
        FileOutputStream(workingFile).use { ExcelWriter.writeProductsToStream(it, all) }
    }
}
