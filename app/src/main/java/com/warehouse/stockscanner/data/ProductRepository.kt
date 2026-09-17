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
    private val barcodeDao: BarcodeDao,
    private val prefs: SessionPrefs,
    /** Overridable so a test can pin the [ProductEntity.countedAt] stamp instead of racing the wall clock. */
    private val now: () -> Long = System::currentTimeMillis
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
        barcodeDao.clearAll()
        // The barcodes sheet first: it states outright which מקט owns a code,
        // and says what a scan of it means. A products.barcode only implies
        // ownership by sitting on a row, so it fills gaps rather than
        // overriding — insert's IGNORE keeps the explicit one. Seeding both
        // is what lets a scan resolve from this table alone, and leaves a
        // freshly loaded file agreeing with a database that got here by
        // migration instead.
        barcodeDao.insertAll(result.barcodes)
        barcodeDao.insertAll(
            result.products
                .filter { it.barcode.isNotBlank() }
                .distinctBy { it.barcode }
                .map { BarcodeEntity(barcode = it.barcode, sku = it.sku) }
        )

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
     * to a sku via [BarcodeEntity] — several different physical codes
     * can point at the very same product.
     */
    suspend fun findByBarcode(barcode: String): ProductLookup? {
        val trimmed = barcode.trim()
        if (trimmed.isEmpty()) return null
        val sku = barcodeDao.findSkuByBarcode(trimmed) ?: return null
        return lookupFor(sku)
    }

    /**
     * What a scan of [barcode] says about packaging, or null for a code this
     * count has never seen. The confirm and inventory screens ask for this so
     * a shelf's count can default to how the code is actually stickered
     * instead of making the worker restate it at every scan.
     */
    suspend fun barcodeInfo(barcode: String): BarcodeEntity? {
        val trimmed = barcode.trim()
        if (trimmed.isEmpty()) return null
        return barcodeDao.findByBarcode(trimmed)
    }

    /**
     * Describes a ברקוד already on file — what a scan of it means. Separate
     * from [registerBarcode] because that one deliberately never overwrites
     * an existing row: a scan must not silently move a code between מקטים,
     * whereas a worker answering "what is this sticker on?" is saying exactly
     * what this code means and should be taken at their word.
     *
     * Only the packaging changes; the מקט the code belongs to never does.
     */
    suspend fun setBarcodeRole(barcode: String, role: String, packageContent: Int) {
        val trimmed = barcode.trim()
        if (trimmed.isEmpty() || role !in BarcodeEntity.ROLES) return
        barcodeDao.setRole(
            trimmed,
            role,
            if (role == BarcodeEntity.ROLE_UNIT) 0 else packageContent.coerceAtLeast(0)
        )
    }

    /**
     * Records a ברקוד discovered mid-count — one that was scanned at a shelf
     * without ever appearing in the source file. Without this the code would
     * live only on the product row it created and resolve nowhere on the next
     * scan, and it would never reach the multiple-barcodes working file,
     * losing exactly the discovery a count is most valuable for. Registering
     * it under a [role] the worker chose beats guessing, but an unregistered
     * code still defaults to a plain single unit rather than blocking a scan.
     */
    suspend fun registerBarcode(
        barcode: String,
        sku: String,
        role: String = BarcodeEntity.ROLE_UNIT,
        packageContent: Int = 0
    ) {
        val trimmed = barcode.trim()
        if (trimmed.isEmpty() || sku.isBlank()) return
        // Both fields decided from the sanitised role, not the raw argument:
        // an unrecognised one falls back to a plain unit, and a package
        // content kept beside it would contradict the very rule the reader
        // enforces — a unit has no package to hold anything.
        val safeRole = role.takeIf { it in BarcodeEntity.ROLES } ?: BarcodeEntity.ROLE_UNIT
        barcodeDao.insert(
            BarcodeEntity(
                barcode = trimmed,
                sku = sku,
                role = safeRole,
                packageContent = if (safeRole == BarcodeEntity.ROLE_UNIT) 0 else packageContent.coerceAtLeast(0)
            )
        )
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
     * recorded is ever lost or silently merged away. Whenever this places a
     * row somewhere for the first time — filling in a blank one or cloning a
     * new one — that row carries the product's identity only: its quantity
     * starts empty rather than inheriting a count that belongs to some other
     * shelf, or one that merely rode in on the source file. Only
     * [updateQuantity], driven by the inventory screen, ever puts a count on
     * a row. Re-confirming a row that is already at this exact (location,
     * barcode) leaves its count alone — that one really was counted here.
     * Never creates a row for an unknown sku.
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

        // Resolution now runs off the barcodes table alone, so a code first
        // seen at a shelf has to land there or the very next scan of it finds
        // nothing. IGNORE inside the dao means a code already owned by
        // another מקט is left where it is rather than stolen.
        registerBarcode(trimmedBarcode, sku)

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
            // Placed for the first time, so its count starts here too. A
            // quantity that rode in on the source file was never counted at
            // this shelf — there wasn't one — and keeping it would put units
            // nobody counted into the locations/quantities file the moment
            // this row becomes scanned, exactly like cloning one would below.
            dao.update(
                blankRow.copy(
                    description = newDescription,
                    barcode = trimmedBarcode,
                    location = trimmedLocation,
                    quantityType = ProductEntity.TYPE_UNITS,
                    packageContent = 0,
                    packageCount = 0,
                    looseUnits = 0,
                    quantity = 0,
                    scanned = true
                )
            )
            return
        }

        // Cloned for the sku/description it carries — never for its
        // quantity. That count was made at the template's own location, and
        // copying it would put units nobody counted here into the
        // locations/quantities file (this row is written to it immediately,
        // being scanned = true) and prefill the inventory screen as if they
        // had already been confirmed.
        val template = existingRows.first()
        val nextOrder = (dao.maxRowOrder() ?: -1) + 1
        dao.insert(
            template.copy(
                id = 0,
                description = newDescription,
                barcode = trimmedBarcode,
                location = trimmedLocation,
                quantityType = ProductEntity.TYPE_UNITS,
                packageContent = 0,
                packageCount = 0,
                looseUnits = 0,
                quantity = 0,
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
        // What the code means physically is not in question here — only
        // which product it belongs to. The sticker is still on the same
        // carton of twelve whoever owns it, and re-registration below would
        // otherwise reset it to a plain single unit. That answer can be a
        // worker's own, given once when the code was first seen, and it
        // would not be asked for again: the code counts as known from then
        // on, so every later scan would quietly divide the carton by its
        // contents.
        val packaging = barcodeDao.findByBarcode(trimmed)
        removeFromLocation(wrongRow)
        // The code itself has to change hands too, or it would keep
        // resolving to the מקט this call exists to move it away from.
        barcodeDao.deleteByBarcode(trimmed)
        updateProduct(newSku, newDescription, trimmed, wrongRow.location)
        if (packaging != null && packaging.role != BarcodeEntity.ROLE_UNIT) {
            setBarcodeRole(trimmed, packaging.role, packaging.packageContent)
        }
    }

    /** The exact row for [sku] at [location] with [barcode] — used by the inventory screen to prefill an existing quantity. */
    suspend fun findRow(sku: String, location: String, barcode: String): ProductEntity? =
        dao.findBySkuLocationAndBarcode(sku, location.trim(), barcode.trim())

    /**
     * Records the stock quantity for [sku] at [location] with [barcode]
     * (that row only — quantity is per row, like everything else on it).
     * [quantity] is always the row's final count in single units;
     * [packageContent]/[packageCount]/[looseUnits] are the breakdown it was
     * derived from (see [ProductEntity] for what each mode uses), kept so
     * the inventory screen can prefill exactly what was typed. Never creates
     * a row: the (location, barcode) combination must already have been
     * confirmed via [updateProduct] first.
     *
     * Stamps [ProductEntity.countedAt] — this is the only place a count is
     * ever recorded, so it is the only place that can date one.
     */
    suspend fun updateQuantity(
        sku: String,
        location: String,
        barcode: String,
        quantityType: String,
        packageContent: Int,
        packageCount: Int,
        looseUnits: Int,
        quantity: Int
    ) {
        val row = dao.findBySkuLocationAndBarcode(sku, location.trim(), barcode.trim()) ?: return
        dao.update(
            row.copy(
                quantityType = quantityType,
                packageContent = packageContent,
                packageCount = packageCount,
                looseUnits = looseUnits,
                quantity = quantity,
                countedAt = now()
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
                    looseUnits = 0,
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
        val aliases = barcodeDao.getAll()
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
