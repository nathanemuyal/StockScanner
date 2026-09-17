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
import com.warehouse.stockscanner.data.BarcodeEntity
import com.warehouse.stockscanner.data.ProductLookup
import com.warehouse.stockscanner.data.ProductRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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
            val trimmedBarcode = barcode.trim()
            // Asked before the row is written, and only for a code nothing
            // has described yet — once per ברקוד for the whole count, not
            // once per scan. Skipped entirely when the product was picked
            // from search rather than scanned (no code to describe).
            val role = if (trimmedBarcode.isNotEmpty() && repository.barcodeInfo(trimmedBarcode) == null) {
                askBarcodeRole(trimmedBarcode)
            } else {
                null
            }

            repository.updateProduct(sku, description, barcode, location)
            // updateProduct registers an unknown code as a plain unit; if the
            // worker said otherwise, that answer replaces the assumption.
            if (role != null && role.first != BarcodeEntity.ROLE_UNIT) {
                repository.setBarcodeRole(trimmedBarcode, role.first, role.second)
            }

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

    /**
     * Asks, once per ברקוד, what the sticker is actually on — the one thing
     * a scan cannot tell you. The same product is routinely coded twice, on
     * the carton and on the single unit, and sometimes a single code serves
     * both; without an answer every code is treated as a single unit, which
     * silently divides a carton count by its contents.
     *
     * Cancelling is a legitimate answer: the code stays a plain unit, the
     * scan still goes through, and the question comes back the next time
     * this code is seen. Nothing here is a stock figure — "how many in a
     * carton" is a fact about the packaging — so it tells the worker nothing
     * about what they are expected to find.
     */
    private suspend fun askBarcodeRole(barcode: String): Pair<String, Int>? =
        suspendCancellableCoroutine { continuation ->
            // One list, one tap, including the way out. Mixing setItems with
            // a button makes the "don't know" path a different gesture from
            // the three real answers for no reason.
            val labels = arrayOf(
                "בודד — הברקוד על יחידה אחת",
                "אריזה — הברקוד על אריזה",
                "גם וגם — אותו ברקוד על שניהם",
                "לא יודע"
            )
            val roles = arrayOf(BarcodeEntity.ROLE_UNIT, BarcodeEntity.ROLE_PACKAGE, BarcodeEntity.ROLE_MIXED, null)

            fun resume(value: Pair<String, Int>?) {
                if (continuation.isActive) continuation.resume(value)
            }

            AlertDialog.Builder(this)
                .setTitle("ברקוד חדש: $barcode")
                .setCancelable(false)
                .setItems(labels) { _, which ->
                    when (val role = roles[which]) {
                        null -> resume(null)
                        BarcodeEntity.ROLE_UNIT -> resume(role to 0)
                        else -> askPackageContent(role, ::resume)
                    }
                }
                .show()
        }

    /** How many single units the package holds — the number that stops being retyped at every scan. */
    private fun askPackageContent(role: String, resume: (Pair<String, Int>?) -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_package_content, null)
        val input = view.findViewById<EditText>(R.id.etPackageContentPrompt)
        AlertDialog.Builder(this)
            .setTitle("כמה יחידות באריזה?")
            .setView(view)
            .setCancelable(false)
            .setPositiveButton("שמור") { _, _ ->
                val content = input.text.toString().trim().toIntOrNull()
                // An unusable answer leaves the code a plain unit rather than
                // recording a carton size nobody actually gave.
                if (content == null || content <= 0) resume(null) else resume(role to content)
            }
            .setNegativeButton("ביטול") { _, _ -> resume(null) }
            .show()
    }
}
