package com.warehouse.stockscanner.data

import android.content.Context
import android.net.Uri
import com.warehouse.stockscanner.excel.ExcelLoadResult
import com.warehouse.stockscanner.excel.ExcelReader
import com.warehouse.stockscanner.excel.ExcelWriter
import com.warehouse.stockscanner.util.LocationUtils
import com.warehouse.stockscanner.util.SearchUtils

class ProductRepository(private val context: Context, private val dao: ProductDao) {

    /** Loads a new Excel file, replacing whatever was loaded before. */
    suspend fun loadFromExcel(uri: Uri): ExcelLoadResult {
        val result = ExcelReader.readProducts(context, uri)
        dao.clearAll()
        dao.insertAll(result.products)
        return result
    }

    suspend fun findByBarcode(barcode: String): ProductEntity? {
        val trimmed = barcode.trim()
        if (trimmed.isEmpty()) return null
        return dao.findByBarcode(trimmed)
    }

    suspend fun findBySku(sku: String): ProductEntity? = dao.findBySku(sku)

    suspend fun searchByDescription(query: String): List<ProductEntity> {
        val all = dao.getAllForSearch()
        return SearchUtils.search(all, query)
    }

    /**
     * Applies a confirmed scan to the existing row identified by [sku].
     * Never creates a new row and never changes the sku itself.
     */
    suspend fun updateProduct(sku: String, newDescription: String, newBarcode: String, newLocation: String) {
        val existing = dao.findBySku(sku) ?: return
        val updated = existing.copy(
            description = newDescription,
            barcode = newBarcode,
            location = newLocation
        )
        dao.update(updated)
    }

    suspend fun count(): Int = dao.count()

    /**
     * Every product currently assigned to [location] — what's on that shelf
     * right now. A product's מיקום cell can list several locations (see
     * LocationUtils), so this checks membership rather than exact equality —
     * a plain SQL "=" or "LIKE" would either miss multi-location rows or
     * false-match a location that's merely a substring of another (e.g.
     * "A-01-1" inside "A-01-10").
     */
    suspend fun findByLocation(location: String): List<ProductEntity> {
        val trimmed = location.trim()
        if (trimmed.isEmpty()) return emptyList()
        return dao.getAllForSearch().filter { LocationUtils.contains(it.location, trimmed) }
    }

    suspend fun exportToExcel(uri: Uri) {
        val all = dao.getAllOrdered()
        ExcelWriter.writeProducts(context, uri, all)
    }
}
