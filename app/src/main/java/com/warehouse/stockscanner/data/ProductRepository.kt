package com.warehouse.stockscanner.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.warehouse.stockscanner.excel.ExcelLoadResult
import com.warehouse.stockscanner.excel.ExcelReader
import com.warehouse.stockscanner.excel.ExcelSaveException
import com.warehouse.stockscanner.excel.ExcelWriter
import com.warehouse.stockscanner.util.SearchUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private const val TAG = "ProductRepository"
private const val DEFAULT_ORIGINAL_FILE_NAME = "products.xlsx"

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
    private val aliasDao: BarcodeAliasDao,
    private val prefs: SessionPrefs
) {

    /**
     * The two Excel files this app actually writes to. The file the user
     * originally picked is only ever read once, at import time — it is
     * never opened for writing again. These two working copies are (re)
     * created the moment a new source file is loaded (see
     * [WorkingFileNaming]), and their names are remembered in [prefs] so the
     * very same two files keep being written to for as long as no different
     * source file is loaded — including across the app being closed and
     * reopened. Living in the app's private storage means writing to them
     * never needs a permission dialog, and they can only ever be updated
     * from inside this app.
     */
    fun locationsQuantitiesFile(): File? = prefs.locationsQuantitiesFileName?.let { File(context.filesDir, it) }

    fun multipleBarcodesFile(): File? = prefs.multipleBarcodesFileName?.let { File(context.filesDir, it) }

    /**
     * Loads a new Excel file, replacing whatever was loaded before, and
     * immediately creates fresh working copies for it (see
     * [createWorkingFiles]). [originalFileName] is the display name of the
     * picked file (falls back to its last path segment, then a generic
     * name) — it's what the two working files' names are derived from.
     */
    suspend fun loadFromExcel(uri: Uri, originalFileName: String? = null): ExcelLoadResult {
        val result = ExcelReader.readProducts(context, uri)
        dao.clearAll()
        dao.insertAll(result.products)
        aliasDao.clearAll()
        aliasDao.insertAll(result.barcodeAliases)

        val name = originalFileName ?: uri.lastPathSegment ?: DEFAULT_ORIGINAL_FILE_NAME
        prefs.resetForNewFile(name, uri.toString())
        createWorkingFiles(name)
        return result
    }

    /**
     * (Re)establishes the two working files' names for [originalFileName]
     * and writes both of them right away — used the moment a source file is
     * (re)loaded. See [saveWorkingCopies] for just updating their content
     * without renaming them.
     */
    suspend fun createWorkingFiles(originalFileName: String) {
        val locationsName = WorkingFileNaming.buildFileName(originalFileName, WorkingFileNaming.Kind.LOCATIONS_QUANTITIES)
        val barcodesName = WorkingFileNaming.buildFileName(originalFileName, WorkingFileNaming.Kind.MULTIPLE_BARCODES)
        // Names are remembered before writing so a failed write still leaves
        // the working files pointed at consistent, freshly-derived names
        // rather than the previous source file's stale ones.
        prefs.locationsQuantitiesFileName = locationsName
        prefs.multipleBarcodesFileName = barcodesName
        writeLocationsQuantitiesFile(locationsName)
        writeMultipleBarcodesFile(barcodesName)
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

    /**
     * Undoes a product having been added to [location] — the counterpart to
     * the ADD-only location handling in [updateProduct]. If [sku] has other
     * location rows too, the row for [location] is simply deleted. If this
     * is its only row, the row is kept but cleared back to a blank location
     * (mirroring the blank row an import leaves for a not-yet-placed
     * product), so the sku/description/barcode aren't lost along with the
     * shelf assignment. A no-op if [sku] has no row at [location].
     */
    suspend fun removeFromLocation(sku: String, location: String) {
        val trimmedLocation = location.trim()
        val row = dao.findBySkuAndLocation(sku, trimmedLocation) ?: return

        val otherRows = dao.findAllBySku(sku).any { it.id != row.id }
        if (otherRows) {
            dao.delete(row)
        } else {
            dao.update(
                row.copy(
                    location = "",
                    quantityType = ProductEntity.TYPE_UNITS,
                    packageContent = 0,
                    packageCount = 0,
                    quantity = 0
                )
            )
        }
    }

    suspend fun count(): Int = dao.count()

    /** Every product row currently assigned to exactly [location] — what's on that shelf right now. */
    suspend fun findByLocation(location: String): List<ProductEntity> {
        val trimmed = location.trim()
        if (trimmed.isEmpty()) return emptyList()
        return dao.getAllForSearch().filter { it.location == trimmed }
    }

    /**
     * Writes the current data to both working files. This is what an
     * explicit "שמור Excel" tap does; what happens automatically right after
     * every single scan's quantity is recorded (see InventoryActivity's
     * "שמור והמשך" — scan-by-scan saving, so a row is never left only in
     * memory even for a moment longer than it has to be); and, as a safety
     * net, what "סיים מיקום" also does when a shelf is confirmed done (see
     * MainActivity). Throws [ExcelSaveException] if either file could not
     * actually be written; the in-memory data is never affected by a failed
     * save, and callers must not report success when this throws.
     */
    suspend fun saveWorkingCopies() {
        saveLocationsQuantitiesFile()
        saveMultipleBarcodesFile()
    }

    /** Writes just the locations/quantities file, e.g. to (re)create it on its own from the "create" button. */
    suspend fun saveLocationsQuantitiesFile() {
        val name = prefs.locationsQuantitiesFileName ?: freshFileName(WorkingFileNaming.Kind.LOCATIONS_QUANTITIES)
            .also { prefs.locationsQuantitiesFileName = it }
        writeLocationsQuantitiesFile(name)
    }

    /** Writes just the multiple-barcodes file, e.g. to (re)create it on its own from the "create" button. */
    suspend fun saveMultipleBarcodesFile() {
        val name = prefs.multipleBarcodesFileName ?: freshFileName(WorkingFileNaming.Kind.MULTIPLE_BARCODES)
            .also { prefs.multipleBarcodesFileName = it }
        writeMultipleBarcodesFile(name)
    }

    private fun freshFileName(kind: WorkingFileNaming.Kind): String =
        WorkingFileNaming.buildFileName(prefs.fileName ?: DEFAULT_ORIGINAL_FILE_NAME, kind)

    private suspend fun writeLocationsQuantitiesFile(fileName: String) {
        val all = dao.getAllOrdered()
        val file = File(context.filesDir, fileName)
        try {
            FileOutputStream(file).use { ExcelWriter.writeLocationsQuantitiesToStream(it, all) }
            Log.i(TAG, "Saved locations/quantities working file '$fileName' (${all.size} rows)")
        } catch (e: IOException) {
            Log.e(TAG, "Failed saving locations/quantities working file '$fileName'", e)
            throw ExcelSaveException("שמירת קובץ המיקומים והכמויות נכשלה: ${e.message}", e)
        }
    }

    private suspend fun writeMultipleBarcodesFile(fileName: String) {
        val aliases = aliasDao.getAll()
        val products = dao.getAllOrdered()
        val file = File(context.filesDir, fileName)
        try {
            FileOutputStream(file).use { ExcelWriter.writeMultipleBarcodesToStream(it, aliases, products) }
            Log.i(TAG, "Saved multiple-barcodes working file '$fileName' (${aliases.size} rows)")
        } catch (e: IOException) {
            Log.e(TAG, "Failed saving multiple-barcodes working file '$fileName'", e)
            throw ExcelSaveException("שמירת קובץ הברקודים המרובים נכשלה: ${e.message}", e)
        }
    }
}
