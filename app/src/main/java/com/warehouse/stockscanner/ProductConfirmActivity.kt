package com.warehouse.stockscanner

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.warehouse.stockscanner.data.ProductRepository
import kotlinx.coroutines.launch

/**
 * Confirmation screen shown for every scanned product, whether it was found
 * directly by barcode or picked from the description search results.
 * Nothing is written to storage until the user taps "אישור ושמירה".
 */
class ProductConfirmActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SKU = "sku"
        const val EXTRA_DESCRIPTION = "description"
        const val EXTRA_EXISTING_BARCODE = "existing_barcode"
        const val EXTRA_SCANNED_BARCODE = "scanned_barcode"
        const val EXTRA_EXISTING_LOCATION = "existing_location"
        const val EXTRA_CURRENT_LOCATION = "current_location"
    }

    private lateinit var repository: ProductRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_product_confirm)
        repository = (application as StockScannerApp).repository

        val sku = intent.getStringExtra(EXTRA_SKU).orEmpty()
        val description = intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty()
        val existingBarcode = intent.getStringExtra(EXTRA_EXISTING_BARCODE).orEmpty()
        val scannedBarcode = intent.getStringExtra(EXTRA_SCANNED_BARCODE).orEmpty()
        val existingLocation = intent.getStringExtra(EXTRA_EXISTING_LOCATION).orEmpty()
        val currentLocation = intent.getStringExtra(EXTRA_CURRENT_LOCATION).orEmpty()

        val tvSku = findViewById<TextView>(R.id.tvSku)
        val etDescription = findViewById<EditText>(R.id.etDescription)
        val tvBarcodeLabel = findViewById<TextView>(R.id.tvBarcodeLabel)
        val tvBarcode = findViewById<TextView>(R.id.tvBarcode)
        val tvLocation = findViewById<TextView>(R.id.tvLocation)
        val btnConfirm = findViewById<Button>(R.id.btnConfirm)
        val btnCancel = findViewById<Button>(R.id.btnCancel)

        tvSku.text = sku
        etDescription.setText(description)
        tvBarcode.text = scannedBarcode
        tvBarcodeLabel.text =
            if (existingBarcode.isNotBlank() && existingBarcode != scannedBarcode) "ברקוד שנסרק:" else "ברקוד:"
        tvLocation.text = currentLocation

        btnCancel.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        btnConfirm.setOnClickListener {
            val newDescription = etDescription.text.toString().trim()
            when {
                sku.isBlank() -> Toast.makeText(this, "שגיאה: מקט חסר", Toast.LENGTH_SHORT).show()
                newDescription.isBlank() -> Toast.makeText(this, "יש להזין תיאור", Toast.LENGTH_SHORT).show()
                else -> saveAndFinish(sku, newDescription, scannedBarcode, currentLocation)
            }
        }

        if (existingLocation.isNotBlank() && existingLocation != currentLocation) {
            AlertDialog.Builder(this)
                .setTitle("⚠️ למוצר כבר קיים מיקום")
                .setMessage("מיקום קיים: $existingLocation\nמיקום חדש: $currentLocation\n\nהאם להעביר את המוצר?")
                .setCancelable(false)
                .setPositiveButton("כן, העבר") { dialog, _ -> dialog.dismiss() }
                .setNegativeButton("ביטול") { _, _ ->
                    setResult(RESULT_CANCELED)
                    finish()
                }
                .show()
        }
    }

    private fun saveAndFinish(sku: String, description: String, barcode: String, location: String) {
        lifecycleScope.launch {
            repository.updateProduct(sku, description, barcode, location)
            setResult(RESULT_OK)
            finish()
        }
    }
}
