package com.warehouse.stockscanner.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row of the source Excel file.
 * sku (מקט) is always treated as a String — it is not necessarily numeric
 * (e.g. "ABC-123", "PRD_00123").
 */
@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey val sku: String,
    val description: String,
    val barcode: String,
    val location: String,
    val rowOrder: Int
)
