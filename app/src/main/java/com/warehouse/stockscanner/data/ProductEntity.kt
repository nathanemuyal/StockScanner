package com.warehouse.stockscanner.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row of the app's product table — every product loaded from the
 * originally-picked source file, whether or not it's been physically
 * scanned yet. A product with several warehouse locations is represented as
 * *several rows* — one per location, sharing the same sku/description —
 * rather than one row with a combined מיקום cell. sku (מקט) is always
 * treated as a String — it is not necessarily numeric (e.g. "ABC-123",
 * "PRD_00123").
 *
 * [barcode] belongs to the row, not to the sku as a whole: the same מקט can
 * legitimately carry a different physical ברקוד at each location (or even a
 * second ברקוד scanned again at the very same location — e.g. a second
 * sticker, or the same item counted a second time). sku is therefore no
 * longer unique on its own: [id] is the real primary key, and
 * (sku, location, barcode) together are kept unique — a genuinely new
 * (location, barcode) combination for a sku always gets its own row rather
 * than overwriting or merging into an existing one; only an exact repeat of
 * the same (sku, location, barcode) is treated as re-confirming the same row.
 *
 * Stock quantity is tracked per row (i.e. per location+barcode combination),
 * set from the inventory screen shown right after a product is confirmed.
 * [quantity] is always the row's final count in single units, whichever of
 * the three [quantityType] modes it was entered in:
 *  - [TYPE_UNITS]: typed straight in as a unit count; only [quantity] is
 *    meaningful.
 *  - [TYPE_PACKAGE]: whole packages only — [packageContent] × [packageCount].
 *  - [TYPE_MIXED]: the shelf holds both at once (some sealed packages plus
 *    some loose singles, typically sharing one ברקוד) —
 *    [packageContent] × [packageCount] + [looseUnits]. This is the mode that
 *    keeps a second scan of the same (sku, location, barcode) from having to
 *    overwrite the first: both halves live on the one row instead of
 *    competing for it.
 * [looseUnits] is only meaningful in [TYPE_MIXED] and stays 0 in the other
 * two, so every mode always exposes the same final unit count in [quantity].
 *
 * [scanned] is what turns this table into the app's full working set while
 * keeping the *output* locations/quantities Excel file a log of only what
 * was actually scanned: it's false for every row as loaded from the source
 * file — even one that already lists a מיקום (or a ברקוד) there — and only
 * flips to true the moment [ProductRepository.updateProduct] records a real
 * in-app scan confirmation for that exact row. [ProductRepository] writes
 * only [scanned] rows to the physical locations/quantities file, so a worker
 * never sees the whole picked catalog copied into it, only what they've
 * actually counted.
 *
 * [countedAt] is when [ProductRepository.updateQuantity] last recorded a
 * count for this row, as epoch millis, and 0 until it ever has. A count
 * questioned later has to be answerable — which of two numbers is the fresh
 * one, whether a row was touched during this count at all — and neither
 * [scanned] nor the quantity itself can say.
 */
@Entity(
    tableName = "products",
    indices = [Index(value = ["sku", "location", "barcode"], unique = true)]
)
data class ProductEntity(
    val sku: String,
    val description: String,
    val barcode: String,
    val location: String,
    val rowOrder: Int,
    val quantityType: String = TYPE_UNITS,
    val packageContent: Int = 0,
    val packageCount: Int = 0,
    val looseUnits: Int = 0,
    val quantity: Int = 0,
    val scanned: Boolean = false,
    val countedAt: Long = 0,
    @PrimaryKey(autoGenerate = true) val id: Long = 0
) {
    companion object {
        const val TYPE_UNITS = "יחידות"
        const val TYPE_PACKAGE = "אריזות"
        const val TYPE_MIXED = "מעורב"

        /**
         * The unit total a row of this shape adds up to — the single place
         * the three modes' arithmetic lives, so the inventory screen's live
         * preview, what gets stored, and anything recomputing a row later
         * can never drift apart.
         */
        fun totalUnits(quantityType: String, packageContent: Int, packageCount: Int, looseUnits: Int, units: Int): Int =
            when (quantityType) {
                TYPE_PACKAGE -> packageContent * packageCount
                TYPE_MIXED -> packageContent * packageCount + looseUnits
                else -> units
            }
    }
}
