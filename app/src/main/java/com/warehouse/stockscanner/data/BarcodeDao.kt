package com.warehouse.stockscanner.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BarcodeDao {

    @Query("DELETE FROM barcodes")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(barcodes: List<BarcodeEntity>)

    // IGNORE, not REPLACE: barcode is unique, so a code already pointing at a
    // DIFFERENT sku is left alone rather than silently stolen away — see
    // BarcodeEntity's kdoc on the one-to-one barcode<->sku rule.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(barcode: BarcodeEntity): Long

    @Query("SELECT * FROM barcodes WHERE barcode = :barcode LIMIT 1")
    suspend fun findByBarcode(barcode: String): BarcodeEntity?

    @Query("SELECT sku FROM barcodes WHERE barcode = :barcode LIMIT 1")
    suspend fun findSkuByBarcode(barcode: String): String?

    // Used when correcting a code that was mistakenly linked to the wrong
    // מקט: its row is removed before it is attached elsewhere, since
    // barcode is unique across the table.
    @Query("DELETE FROM barcodes WHERE barcode = :barcode")
    suspend fun deleteByBarcode(barcode: String)

    @Query("SELECT * FROM barcodes WHERE sku = :sku ORDER BY id ASC")
    suspend fun findAllBySku(sku: String): List<BarcodeEntity>

    @Query("UPDATE barcodes SET role = :role, packageContent = :packageContent WHERE barcode = :barcode")
    suspend fun setRole(barcode: String, role: String, packageContent: Int)

    @Query("SELECT * FROM barcodes ORDER BY id ASC")
    suspend fun getAll(): List<BarcodeEntity>
}
