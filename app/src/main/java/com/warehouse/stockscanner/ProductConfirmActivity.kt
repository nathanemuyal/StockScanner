package com.warehouse.stockscanner

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.warehouse.stockscanner.data.ProductLookup
import com.warehouse.stockscanner.data.ProductRepository
import kotlinx.coroutines.launch

/**
 * Confirmation screen shown for every scanned product, whether it was found
 * directly by barcode or picked from the description search results.
 * Nothing is written to storage until the user taps "אישור ושמירה". Once
 * confirmed, the inventory screen ([InventoryActivity]) opens right away to
 * record the stock quantity for this row, before control returns to
 * MainActivity (which then auto-continues to the next product).
 */
class ProductConfirmActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SKU = "sku"
        const val EXTRA_DESCRIPTION = "description"
        const val EXTRA_EXISTING_BARCODE = "existing_barcode"
        const val EXTRA_SCANNED_BARCODE = "scanned_barcode"
        const val EXTRA_EXISTING_LOCATIONS = "existing_locations"
        const val EXTRA_CURRENT_LOCATION = "current_location"
    }

    private lateinit var repository: ProductRepository

    // Whatever the inventory screen reports (it's always a save, never a
    // cancel), that's what this whole confirm flow reports back to MainActivity.
    private val inventoryLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            setResult(result.resultCode)
            finish()
        }

    // Picking a different מקט from the search screen re-links the scanned
    // barcode to it, then this screen restarts itself with the new product's
    // data — same as if that product had matched the scan to begin with.
    private lateinit var scannedBarcode: String
    private lateinit var currentLocation: String
    private val changeSkuLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val newSku = result.data?.getStringExtra(SearchActivity.EXTRA_SELECTED_SKU)
                if (!newSku.isNullOrBlank()) confirmAndApplySkuChange(newSku)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_product_confirm)
        repository = (application as StockScannerApp).repository

        val sku = intent.getStringExtra(EXTRA_SKU).orEmpty()
        val description = intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty()
        val existingBarcode = intent.getStringExtra(EXTRA_EXISTING_BARCODE).orEmpty()
        scannedBarcode = intent.getStringExtra(EXTRA_SCANNED_BARCODE).orEmpty()
        val existingLocations = intent.getStringArrayListExtra(EXTRA_EXISTING_LOCATIONS).orEmpty()
        currentLocation = intent.getStringExtra(EXTRA_CURRENT_LOCATION).orEmpty()

        val tvSku = findViewById<TextView>(R.id.tvSku)
        val etDescription = findViewById<EditText>(R.id.etDescription)
        val tvBarcodeLabel = findViewById<TextView>(R.id.tvBarcodeLabel)
        val tvBarcode = findViewById<TextView>(R.id.tvBarcode)
        val tvLocation = findViewById<TextView>(R.id.tvLocation)
        val btnConfirm = findViewById<Button>(R.id.btnConfirm)
        val btnChangeSku = findViewById<Button>(R.id.btnChangeSku)
        val btnCancel = findViewById<Button>(R.id.btnCancel)

        // A product can sit in more than one location at once — a confirmed
        // scan always ADDS the current location as a new row alongside the
        // product's existing ones, it never replaces or merges them.
        val alreadyAtThisLocation = existingLocations.contains(currentLocation.trim())

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

        // Fixes a wrong barcode->מקט match (or a wrong pick from search):
        // choose the right product instead, and the scanned barcode follows.
        // Nothing to reassign without an actual scanned barcode, so hide it then.
        btnChangeSku.visibility = if (scannedBarcode.isBlank()) View.GONE else View.VISIBLE
        btnChangeSku.setOnClickListener {
            changeSkuLauncher.launch(Intent(this, SearchActivity::class.java))
        }

        btnConfirm.setOnClickListener {
            val newDescription = etDescription.text.toString().trim()
            when {
                sku.isBlank() -> Toast.makeText(this, "שגיאה: מקט חסר", Toast.LENGTH_SHORT).show()
                newDescription.isBlank() -> Toast.makeText(this, "יש להזין תיאור", Toast.LENGTH_SHORT).show()
                else -> saveAndFinish(sku, newDescription, scannedBarcode, currentLocation)
            }
        }

        if (existingLocations.isNotEmpty() && !alreadyAtThisLocation) {
            AlertDialog.Builder(this)
                .setTitle("⚠️ למוצר כבר יש מיקום קיים")
                .setMessage(
                    "מיקומים קיימים: ${existingLocations.joinToString(", ")}\n" +
                        "מיקום נוסף: $currentLocation\n\n" +
                        "האם להוסיף את המיקום החדש למוצר?"
                )
                .setCancelable(false)
                .setPositiveButton("כן, הוסף") { dialog, _ -> dialog.dismiss() }
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
            val intent = Intent(this@ProductConfirmActivity, InventoryActivity::class.java)
                .putExtra(InventoryActivity.EXTRA_SKU, sku)
                .putExtra(InventoryActivity.EXTRA_LOCATION, location)
                // Trimmed to match exactly what updateProduct just stored on
                // the row — InventoryActivity needs it to find that same row
                // again, since a sku can have several rows at this very
                // location (one per distinct barcode).
                .putExtra(InventoryActivity.EXTRA_BARCODE, barcode.trim())
            inventoryLauncher.launch(intent)
        }
    }

    /** [newSku] was just picked from search as the correct product for [scannedBarcode]. */
    private fun confirmAndApplySkuChange(newSku: String) {
        lifecycleScope.launch {
            val newProduct = repository.findBySku(newSku)
            if (newProduct == null) {
                Toast.makeText(this@ProductConfirmActivity, "מקט לא נמצא", Toast.LENGTH_SHORT).show()
                return@launch
            }
            AlertDialog.Builder(this@ProductConfirmActivity)
                .setTitle("שיוך ברקוד למוצר אחר")
                .setMessage(
                    "לשייך את הברקוד $scannedBarcode למקט ${newProduct.sku} (${newProduct.description})?\n" +
                        "השיוך הקודם של הברקוד הזה יוסר."
                )
                .setPositiveButton("כן, החלף") { _, _ -> applySkuChange(newSku, newProduct) }
                .setNegativeButton("ביטול", null)
                .show()
        }
    }

    private fun applySkuChange(newSku: String, fallback: ProductLookup) {
        lifecycleScope.launch {
            repository.reassignBarcode(scannedBarcode, newSku)
            // Re-read after reassigning: existingBarcode/existingLocations may
            // now differ from what was looked up just before the reassignment.
            val refreshed = repository.findBySku(newSku) ?: fallback
            val intent = Intent(this@ProductConfirmActivity, ProductConfirmActivity::class.java)
                .putExtra(EXTRA_SKU, refreshed.sku)
                .putExtra(EXTRA_DESCRIPTION, refreshed.description)
                .putExtra(EXTRA_EXISTING_BARCODE, refreshed.barcode)
                .putExtra(EXTRA_SCANNED_BARCODE, scannedBarcode)
                .putStringArrayListExtra(EXTRA_EXISTING_LOCATIONS, ArrayList(refreshed.existingLocations))
                .putExtra(EXTRA_CURRENT_LOCATION, currentLocation)
            startActivity(intent)
            finish()
        }
    }
}
