package com.warehouse.stockscanner.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row of the working Excel file. A product with several warehouse
 * locations is represented as *several rows* — one per location, sharing
 * the same sku/description/barcode — rather than one row with a combined
 * מיקום cell. sku (מקט) is always treated as a String — it is not
 * necessarily numeric (e.g. "ABC-123", "PRD_00123").
 *
 * sku is therefore no longer unique on its own: [id] is the real primary
 * key, and (sku, location) is kept unique so the same product can never
 * end up with two rows for the same location.
 */
@Entity(
    tableName = "products",
    indices = [Index(value = ["sku", "location"], unique = true)]
)
data class ProductEntity(
    val sku: String,
    val description: String,
    val barcode: String,
    val location: String,
    val rowOrder: Int,
    @PrimaryKey(autoGenerate = true) val id: Long = 0
)
