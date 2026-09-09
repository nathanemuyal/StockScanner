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
 * set from the inventory screen shown right after a product is confirmed. It
 * is either entered directly as a unit count ([quantityType] == [TYPE_UNITS],
 * only [quantity] is meaningful) or derived from a package breakdown
 * ([quantityType] == [TYPE_PACKAGE]: [packageContent] × [packageCount],
 * with the result kept in [quantity] so both modes always expose the same
 * final unit count).
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
    val quantity: Int = 0,
    val scanned: Boolean = false,
    @PrimaryKey(autoGenerate = true) val id: Long = 0
) {
    companion object {
        const val TYPE_UNITS = "יחידות"
        const val TYPE_PACKAGE = "אריזות"
    }
}
