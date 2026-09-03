package com.warehouse.stockscanner

import android.app.Application
import com.warehouse.stockscanner.data.AppDatabase
import com.warehouse.stockscanner.data.ProductRepository

class StockScannerApp : Application() {

    lateinit var repository: ProductRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.getInstance(this)
        repository = ProductRepository(this, db.productDao())
    }
}
