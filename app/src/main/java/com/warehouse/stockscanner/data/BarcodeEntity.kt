package com.warehouse.stockscanner.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Every ברקוד the app knows, and what one scan of it means.
 *
 * This is the whole barcode->מקט mapping in one place: a product's own
 * (primary) code and any extra ones — an old label, a supplier's code, a
 * case barcode — are all rows here, so resolving a scan is one lookup
 * rather than a product-row query falling back to a separate alias table.
 * [barcode] is unique: a code can never point at two different products.
 *
 * [role] is what a scan of this code says about *packaging*, which is the
 * thing a barcode alone cannot otherwise tell you:
 *  - [ROLE_UNIT]: it is always stuck on a single unit.
 *  - [ROLE_PACKAGE]: it is always stuck on a package holding
 *    [packageContent] units.
 *  - [ROLE_MIXED]: the very same code is used for both — a sealed package
 *    and a loose single carry identical stickers — so the scan itself
 *    cannot say which, and only the worker at the shelf can split the two.
 *    [packageContent] still says how many units a package holds.
 *
 * The three roles line up one-to-one with [ProductEntity]'s three quantity
 * modes, which is the point: the role is what a shelf's count should
 * default to for this code, so the worker stops re-entering a packaging
 * fact that never changes. It is a default and not a verdict — the shelf
 * always wins, and a worker who finds loose units under a [ROLE_PACKAGE]
 * code counts them as they are.
 *
 * [packageContent] is a property of the packaging, never a stock figure, so
 * showing it costs a stock count nothing: it says a carton holds 12, not
 * that 12 are expected to be found.
 */
@Entity(
    tableName = "barcodes",
    indices = [Index(value = ["barcode"], unique = true)]
)
data class BarcodeEntity(
    val barcode: String,
    val sku: String,
    val role: String = ROLE_UNIT,
    val packageContent: Int = 0,
    @PrimaryKey(autoGenerate = true) val id: Long = 0
) {
    companion object {
        const val ROLE_UNIT = "בודד"
        const val ROLE_PACKAGE = "אריזה"
        const val ROLE_MIXED = "מעורב"

        /** The roles a source file (or a worker) may legitimately name; anything else falls back to [ROLE_UNIT]. */
        val ROLES = listOf(ROLE_UNIT, ROLE_PACKAGE, ROLE_MIXED)
    }
}
