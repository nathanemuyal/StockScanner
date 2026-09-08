package com.warehouse.stockscanner.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BarcodeAliasDao {

    @Query("DELETE FROM barcode_aliases")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(aliases: List<BarcodeAliasEntity>)

    // IGNORE, not REPLACE: barcode is unique, so if it's already aliased to a
    // DIFFERENT sku this silently does nothing rather than steal it away —
    // see BarcodeAliasEntity's kdoc on the one-to-one barcode<->sku rule.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(alias: BarcodeAliasEntity): Long

    @Query("SELECT sku FROM barcode_aliases WHERE barcode = :barcode LIMIT 1")
    suspend fun findSkuByBarcode(barcode: String): String?

    // Used when correcting a barcode that was mistakenly linked to the wrong
    // מקט: its old alias row (if any) is removed before it's attached
    // elsewhere, since barcode is unique across the table.
    @Query("DELETE FROM barcode_aliases WHERE barcode = :barcode")
    suspend fun deleteByBarcode(barcode: String)

    @Query("SELECT * FROM barcode_aliases WHERE sku = :sku ORDER BY id ASC")
    suspend fun findAllBySku(sku: String): List<BarcodeAliasEntity>

    @Query("SELECT * FROM barcode_aliases ORDER BY id ASC")
    suspend fun getAll(): List<BarcodeAliasEntity>
}
