package com.warehouse.stockscanner.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ProductDao {

    @Query("DELETE FROM products")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(products: List<ProductEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(product: ProductEntity): Long

    @Query("SELECT * FROM products WHERE barcode = :barcode LIMIT 1")
    suspend fun findByBarcode(barcode: String): ProductEntity?

    /** Every row for [sku] — one per location it's currently assigned to. */
    @Query("SELECT * FROM products WHERE sku = :sku ORDER BY rowOrder ASC")
    suspend fun findAllBySku(sku: String): List<ProductEntity>

    /**
     * The single row for [sku] at exactly [location] with exactly [barcode]
     * — quantity lives on this row. A sku can have several rows at the same
     * [location] (a different [barcode] scanned there), so all three are
     * needed to pick out one unambiguous row.
     */
    @Query("SELECT * FROM products WHERE sku = :sku AND location = :location AND barcode = :barcode LIMIT 1")
    suspend fun findBySkuLocationAndBarcode(sku: String, location: String, barcode: String): ProductEntity?

    @Query("SELECT * FROM products ORDER BY rowOrder ASC")
    suspend fun getAllOrdered(): List<ProductEntity>

    /** Only rows an actual in-app scan has confirmed — what the locations/quantities working file is written from. */
    @Query("SELECT * FROM products WHERE scanned = 1 ORDER BY rowOrder ASC")
    suspend fun getScannedOrdered(): List<ProductEntity>

    @Query("SELECT * FROM products")
    suspend fun getAllForSearch(): List<ProductEntity>

    @Query("SELECT COUNT(*) FROM products")
    suspend fun count(): Int

    @Query("SELECT MAX(rowOrder) FROM products")
    suspend fun maxRowOrder(): Int?

    @Update
    suspend fun update(product: ProductEntity)

    @Delete
    suspend fun delete(product: ProductEntity)
}
