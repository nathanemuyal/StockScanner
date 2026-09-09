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
     * across every row it already has — it's the same product wherever it
     * sits. The (location, barcode) *pair* is only ever ADDED to, never
     * merged or overwritten: if [sku] already has a row at exactly
     * [newLocation] with exactly [newBarcode], that row is simply
     * re-confirmed in place; a not-yet-placed row whose barcode is either
     * unknown or already matches this scan just gets its location filled in
     * (so shelving a product whose barcode was already on file doesn't
     * spawn a duplicate); anything else — a genuinely new location, a
     * different barcode scanned at a location [sku] already has, even a
     * second distinct barcode scanned again at the very same spot — opens a
     * brand-new row (cloned from an existing one), so nothing already
     * recorded is ever lost or silently merged away. Never creates a row for
     * an unknown sku.
     *
     * The row this ends up touching is also marked [ProductEntity.scanned]
     * — this is the one and only place that happens, since this is the one
     * function called for an actual in-app scan confirmation. That's what
     * lets the locations/quantities working file stay a log of what was
     * really scanned instead of a copy of the whole picked source file (see
     * [saveLocationsQuantitiesFile]).
     */
    suspend fun updateProduct(sku: String, newDescription: String, newBarcode: String, newLocation: String) {
        val rowsBeforeSync = dao.findAllBySku(sku)
        if (rowsBeforeSync.isEmpty()) return
        val trimmedLocation = newLocation.trim()
        val trimmedBarcode = newBarcode.trim()

        // Kept in sync with the DB writes below it, not just the DB itself —
        // every subsequent .copy() in this function starts from a row here,
        // so a stale (pre-sync) description can never get written back out
        // by a later step that only meant to touch some other field.
        val existingRows = rowsBeforeSync.map { row ->
            if (row.description != newDescription) {
                val synced = row.copy(description = newDescription)
                dao.update(synced)
                synced
            } else row
        }

        val exactMatch = existingRows.firstOrNull { it.location == trimmedLocation && it.barcode == trimmedBarcode }
        if (exactMatch != null) {
            // This exact (location, barcode) combination was already on
            // record — this scan simply re-confirms it, so it must be marked
            // scanned even if nothing else about it changed just now.
            if (!exactMatch.scanned) dao.update(exactMatch.copy(scanned = true))
            return
        }

        // A single not-yet-placed row (no location yet) whose barcode either
        // isn't known yet or already matches this scan just gets the
        // location filled in, instead of being left behind as an orphaned
        // blank row alongside a new one this scan would otherwise create —
        // covers both a genuinely fresh catalog entry (blank barcode too)
        // and a product whose barcode was already known but never shelved.
        val blankRow = existingRows.singleOrNull {
            it.location.isBlank() && (it.barcode.isBlank() || it.barcode == trimmedBarcode)
        }
        if (blankRow != null) {
            dao.update(
                blankRow.copy(
                    description = newDescription, barcode = trimmedBarcode, location = trimmedLocation, scanned = true
                )
            )
            return
        }

        val template = existingRows.first()
        val nextOrder = (dao.maxRowOrder() ?: -1) + 1
        dao.insert(
            template.copy(
                id = 0,
                description = newDescription,
                barcode = trimmedBarcode,
                location = trimmedLocation,
                scanned = true,
                rowOrder = nextOrder
            )
        )
    }

    /**
     * Fixes a mistaken barcode-to-מקט link: the row currently carrying
     * [barcode] (wherever it sits) is moved to belong to [newSku] instead —
     * its own location stays the same — it just moves under [newSku]
     * instead. Built out of the exact same two operations a worker could do
     * by hand ([removeFromLocation] the wrong row, [updateProduct] the right
     * sku at that same location+barcode), so it behaves identically: the
     * wrong sku's row is deleted if it has other rows, or cleared in place
     * (not lost) if this was its only one; the right sku gets a proper new
     * row for this (location, barcode) — or re-confirms an existing one, if
     * for some reason it already had this exact combination. Used from the
     * confirm screen when the user notices a scan matched the wrong product
     * and picks the right one. A no-op if [newSku] has no rows at all, if
     * nothing currently carries [barcode], or if it already belongs to
     * [newSku].
     */
    suspend fun reassignBarcode(barcode: String, newSku: String) {
        val trimmed = barcode.trim()
        if (trimmed.isEmpty()) return

        val newRows = dao.findAllBySku(newSku)
        if (newRows.isEmpty()) return

        val wrongRow = dao.findByBarcode(trimmed) ?: return
        if (wrongRow.sku == newSku) return

        val newDescription = newRows.first().description
        removeFromLocation(wrongRow)
        updateProduct(newSku, newDescription, trimmed, wrongRow.location)
    }

    /** The exact row for [sku] at [location] with [barcode] — used by the inventory screen to prefill an existing quantity. */
    suspend fun findRow(sku: String, location: String, barcode: String): ProductEntity? =
        dao.findBySkuLocationAndBarcode(sku, location.trim(), barcode.trim())

    /**
     * Records the stock quantity for [sku] at [location] with [barcode]
     * (that row only — quantity is per row, like everything else on it).
     * Never creates a row: the (location, barcode) combination must already
     * have been confirmed via [updateProduct] first.
     */
    suspend fun updateQuantity(
        sku: String,
        location: String,
        barcode: String,
        quantityType: String,
        packageContent: Int,
        packageCount: Int,
        quantity: Int
    ) {
        val row = dao.findBySkuLocationAndBarcode(sku, location.trim(), barcode.trim()) ?: return
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
     * Undoes [row] having been added to its location — the counterpart to
     * the ADD-only handling in [updateProduct]. [row] is the exact row to
     * remove (a sku can have several rows at the very same location, one per
     * distinct barcode, so the row itself — not just sku+location — is what
     * identifies it unambiguously). If [row]'s sku has other rows too, this
     * one is simply deleted. If it's the sku's only row, it's kept but
     * cleared back to a blank, unplaced entry (mirroring the blank row an
     * import leaves for a not-yet-placed product), so the sku/description
     * aren't lost along with the shelf assignment — and
     * [ProductEntity.scanned] is cleared right along with it, since it's no
     * longer a placed/counted row either.
     */
    suspend fun removeFromLocation(row: ProductEntity) {
        val otherRows = dao.findAllBySku(row.sku).any { it.id != row.id }
        if (otherRows) {
            dao.delete(row)
        } else {
            dao.update(
                row.copy(
                    location = "",
                    barcode = "",
                    quantityType = ProductEntity.TYPE_UNITS,
                    packageContent = 0,
                    packageCount = 0,
                    quantity = 0,
                    scanned = false
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

    /**
     * Writes just the locations/quantities file, e.g. to (re)create it on
     * its own from the "create" button. Log-style, not a catalog copy: only
     * rows an actual scan has confirmed ([ProductEntity.scanned], set by
     * [updateProduct]) are written, so a product that merely came in on the
     * originally-picked source file — even one that already listed a מיקום
     * there — never shows up here until it's actually been scanned in this
     * app. The Excel columns themselves are unchanged either way; only which
     * rows qualify for a row at all.
     */
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
        val scanned = dao.getScannedOrdered()
        val file = File(context.filesDir, fileName)
        try {
            FileOutputStream(file).use { ExcelWriter.writeLocationsQuantitiesToStream(it, scanned) }
            Log.i(TAG, "Saved locations/quantities working file '$fileName' (${scanned.size} rows)")
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
