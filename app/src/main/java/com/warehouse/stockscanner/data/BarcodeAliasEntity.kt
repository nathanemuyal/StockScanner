package com.warehouse.stockscanner.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An extra ברקוד attached to an existing מקט, on top of that product's own
 * (primary) [ProductEntity.barcode]. Lets several different physical
 * barcodes — an old label, a supplier's own code, a case barcode, ... — all
 * resolve to the very same product instead of one scan silently overwriting
 * another.
 *
 * A מקט is always one-to-many with its aliases (a product can collect
 * several), but a ברקוד is one-to-one with a מקט: [barcode] is unique, so
 * the same code can never be aliased to two different products.
 */
@Entity(
    tableName = "barcode_aliases",
    indices = [Index(value = ["barcode"], unique = true)]
)
data class BarcodeAliasEntity(
    val barcode: String,
    val sku: String,
    @PrimaryKey(autoGenerate = true) val id: Long = 0
)
