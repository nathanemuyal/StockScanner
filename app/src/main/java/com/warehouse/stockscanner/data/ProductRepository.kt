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

class ProductRepository(
    private val context: Context,
    private val dao: ProductDao,
    private val aliasDao: BarcodeAliasDao
) {

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
        aliasDao.clearAll()
        aliasDao.insertAll(result.barcodeAliases)
        writeWorkingCopy()
        return result
    }

    /**
     * Resolves [barcode] to its product, checking the product's own (primary)
     * ברקוד first and, if nothing matches there, the extra barcodes aliased
     * to a sku via [BarcodeAliasEntity] — several different physical codes
     * can point at the very same product.
     */
    suspend fun findByBarcode(barcode: String): ProductLookup? {
        val trimmed = barcode.trim()
        if (trimmed.isEmpty()) return null
        val sku = dao.findByBarcode(trimmed)?.sku ?: aliasDao.findSkuByBarcode(trimmed) ?: return null
        return lookupFor(sku)
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
     * Applies a confirmed scan. The product's description is kept in sync
     * across every location it already has — it's the same product wherever
     * it sits. The barcode, however, is only ever ADDED to, never
     * overwritten: a still-blank primary ברקוד gets set from [newBarcode],
     * but once a sku already has one, a *different* [newBarcode] (e.g. the
     * product was found via description search after an unrecognized scan)
     * is recorded as an extra alias for [sku] instead of replacing it — so
     * either barcode keeps resolving to the same product afterwards. The
     * location itself is also only ever ADDED to: if [sku] already has a row
     * at [newLocation], that row is refreshed in place; otherwise a
     * brand-new row is opened for it (cloned from an existing row), so a
     * product can sit in more than one location at once without ever losing
     * an older one. Never creates a row for an unknown sku.
     */
    suspend fun updateProduct(sku: String, newDescription: String, newBarcode: String, newLocation: String) {
        val existingRows = dao.findAllBySku(sku)
        if (existingRows.isEmpty()) return
        val trimmedLocation = newLocation.trim()
        val trimmedBarcode = newBarcode.trim()

        for (row in existingRows) {
            if (row.description != newDescription) {
                dao.update(row.copy(description = newDescription))
            }
        }

        if (trimmedBarcode.isNotEmpty()) {
            val currentPrimary = existingRows.first().barcode
            if (currentPrimary.isBlank()) {
                // No primary ברקוד yet for this sku — this is the first one, so
                // it becomes the primary on every row, same as before.
                for (row in existingRows) {
                    if (row.barcode != trimmedBarcode) dao.update(row.copy(barcode = trimmedBarcode))
                }
            } else if (currentPrimary != trimmedBarcode) {
                aliasDao.insert(BarcodeAliasEntity(barcode = trimmedBarcode, sku = sku))
            }
        }

        if (existingRows.any { it.location == trimmedLocation }) return

        // A single row that has no location yet just gets this one filled
        // in, instead of being left behind as an orphaned blank row. The
        // primary barcode (not necessarily newBarcode — see above) is what
        // every row for this sku carries, this one included.
        val primaryBarcode = existingRows.first().barcode.let { if (it.isBlank()) trimmedBarcode else it }
        val blankRow = existingRows.singleOrNull { it.location.isBlank() }
        if (blankRow != null) {
            dao.update(blankRow.copy(description = newDescription, barcode = primaryBarcode, location = trimmedLocation))
            return
        }

        val template = existingRows.first()
        val nextOrder = (dao.maxRowOrder() ?: -1) + 1
        dao.insert(
            template.copy(
                id = 0,
                description = newDescription,
                barcode = primaryBarcode,
                location = trimmedLocation,
                rowOrder = nextOrder
            )
        )
    }

    /**
     * Fixes a mistaken barcode-to-מקט link: [barcode] is detached from
     * whatever product it currently resolves to (as either a primary ברקוד
     * or an alias — see [BarcodeAliasEntity]) and reattached to [newSku]
     * instead, the same way a fresh scan would attach it (primary if [newSku]
     * has none yet, otherwise an alias). Used from the confirm screen when
     * the user notices a scan matched the wrong product and picks the right
     * one. A no-op if [newSku] has no rows to attach to.
     */
    suspend fun reassignBarcode(barcode: String, newSku: String) {
        val trimmed = barcode.trim()
        if (trimmed.isEmpty()) return

        // Checked before anything is detached below, so an unknown newSku
        // leaves the barcode's existing link untouched instead of orphaning it.
        val newRows = dao.findAllBySku(newSku)
        if (newRows.isEmpty()) return

        val currentOwner = dao.findByBarcode(trimmed)
        if (currentOwner != null && currentOwner.sku != newSku) {
            for (row in dao.findAllBySku(currentOwner.sku)) {
                if (row.barcode == trimmed) dao.update(row.copy(barcode = ""))
            }
        }
        aliasDao.deleteByBarcode(trimmed)

        val newPrimary = newRows.first().barcode
        if (newPrimary.isBlank()) {
            for (row in newRows) {
                if (row.barcode != trimmed) dao.update(row.copy(barcode = trimmed))
            }
        } else if (newPrimary != trimmed) {
            aliasDao.insert(BarcodeAliasEntity(barcode = trimmed, sku = newSku))
        }
    }

    /** The exact row for [sku] at [location] — used by the inventory screen to prefill an existing quantity. */
    suspend fun findRow(sku: String, location: String): ProductEntity? =
        dao.findBySkuAndLocation(sku, location.trim())

    /**
     * Records the stock quantity for [sku] at [location] (that row only —
     * quantity is per location, like everything else on a row). Never
     * creates a row: the location must already have been confirmed via
     * [updateProduct] first.
     */
    suspend fun updateQuantity(
        sku: String,
        location: String,
        quantityType: String,
        packageContent: Int,
        packageCount: Int,
        quantity: Int
    ) {
        val row = dao.findBySkuAndLocation(sku, location.trim()) ?: return
        dao.update(
            row.copy(
                quantityType = quantityType,
                packageContent = packageContent,
                packageCount = packageCount,
                quantity = quantity
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
        val aliases = aliasDao.getAll()
        FileOutputStream(workingFile).use { ExcelWriter.writeProductsToStream(it, all, aliases) }
    }
}
