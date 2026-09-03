package com.warehouse.stockscanner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.warehouse.stockscanner.data.ProductRepository
import com.warehouse.stockscanner.data.SessionPrefs
import com.warehouse.stockscanner.excel.ExcelFormatException
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var repository: ProductRepository
    private lateinit var prefs: SessionPrefs

    private lateinit var tvFileName: TextView
    private lateinit var tvTotalProducts: TextView
    private lateinit var tvCurrentLocation: TextView
    private lateinit var tvApprovedCount: TextView
    private lateinit var btnScanLocation: Button
    private lateinit var btnScanProduct: Button
    private lateinit var btnFinishLocation: Button
    private lateinit var btnSaveExcel: Button
    private lateinit var tvScannedProductsLabel: TextView
    private lateinit var recyclerScannedProducts: RecyclerView
    private lateinit var scannedProductsAdapter: SearchResultAdapter

    /** Barcode that was scanned but not found — kept around while the user searches by description. */
    private var pendingScannedBarcode: String? = null

    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) loadExcel(uri)
        }

    private val createDocumentLauncher =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            )
        ) { uri ->
            if (uri != null) saveExcel(uri)
        }

    private val scanLocationLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val value = result.data?.getStringExtra(ScannerActivity.EXTRA_VALUE)
                if (!value.isNullOrBlank()) {
                    prefs.currentLocation = value
                }
            }
            updateUiState()
        }

    private val scanProductLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val value = result.data?.getStringExtra(ScannerActivity.EXTRA_VALUE)
                if (!value.isNullOrBlank()) {
                    handleScannedBarcode(value)
                }
            }
        }

    private val productConfirmLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                prefs.approvedCount = prefs.approvedCount + 1
                updateUiState()
                // Auto-continue to the next product, per the required UX.
                launchScanProduct()
            } else {
                updateUiState()
            }
        }

    private val searchLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val scannedBarcode = pendingScannedBarcode
            pendingScannedBarcode = null
            if (result.resultCode == RESULT_OK && scannedBarcode != null) {
                val sku = result.data?.getStringExtra(SearchActivity.EXTRA_SELECTED_SKU)
                if (sku != null) {
                    lifecycleScope.launch {
                        val product = repository.findBySku(sku)
                        if (product != null) {
                            openConfirmScreen(
                                sku = product.sku,
                                description = product.description,
                                existingBarcode = product.barcode,
                                scannedBarcode = scannedBarcode,
                                existingLocation = product.location
                            )
                        }
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repository = (application as StockScannerApp).repository
        prefs = SessionPrefs(this)

        tvFileName = findViewById(R.id.tvFileName)
        tvTotalProducts = findViewById(R.id.tvTotalProducts)
        tvCurrentLocation = findViewById(R.id.tvCurrentLocation)
        tvApprovedCount = findViewById(R.id.tvApprovedCount)
        btnScanLocation = findViewById(R.id.btnScanLocation)
        btnScanProduct = findViewById(R.id.btnScanProduct)
        btnFinishLocation = findViewById(R.id.btnFinishLocation)
        btnSaveExcel = findViewById(R.id.btnSaveExcel)
        tvScannedProductsLabel = findViewById(R.id.tvScannedProductsLabel)
        recyclerScannedProducts = findViewById(R.id.recyclerScannedProducts)

        scannedProductsAdapter = SearchResultAdapter { /* view-only list, no action on tap */ }
        recyclerScannedProducts.layoutManager = LinearLayoutManager(this)
        recyclerScannedProducts.adapter = scannedProductsAdapter

        findViewById<Button>(R.id.btnLoadExcel).setOnClickListener {
            openDocumentLauncher.launch(
                arrayOf(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/octet-stream"
                )
            )
        }

        btnScanLocation.setOnClickListener {
            lifecycleScope.launch {
                if (repository.count() == 0) {
                    Toast.makeText(this@MainActivity, "יש לטעון קובץ Excel קודם", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val intent = Intent(this@MainActivity, ScannerActivity::class.java)
                    .putExtra(ScannerActivity.EXTRA_MODE, ScannerActivity.MODE_LOCATION)
                scanLocationLauncher.launch(intent)
            }
        }

        btnScanProduct.setOnClickListener { launchScanProduct() }

        btnFinishLocation.setOnClickListener {
            prefs.currentLocation = null
            updateUiState()
        }

        btnSaveExcel.setOnClickListener {
            lifecycleScope.launch {
                if (repository.count() == 0) {
                    Toast.makeText(this@MainActivity, "אין נתונים לשמירה, טען קובץ Excel קודם", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val suggestedName = prefs.fileName ?: "products_updated.xlsx"
                createDocumentLauncher.launch(suggestedName)
            }
        }

        updateUiState()
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
    }

    private fun launchScanProduct() {
        val intent = Intent(this, ScannerActivity::class.java)
            .putExtra(ScannerActivity.EXTRA_MODE, ScannerActivity.MODE_PRODUCT)
            .putExtra(ScannerActivity.EXTRA_CURRENT_LOCATION, prefs.currentLocation)
        scanProductLauncher.launch(intent)
    }

    private fun loadExcel(uri: Uri) {
        lifecycleScope.launch {
            try {
                val result = repository.loadFromExcel(uri)
                val name = queryFileName(uri) ?: "products.xlsx"
                prefs.resetForNewFile(name, uri.toString())
                updateUiState()

                if (result.duplicateSkuRows > 0 || result.duplicateBarcodeRows > 0) {
                    val warnings = ArrayList<String>()
                    if (result.duplicateSkuRows > 0) {
                        warnings.add("${result.duplicateSkuRows} שורות עם מקט כפול (נלקחה השורה האחרונה עבור כל מקט)")
                    }
                    if (result.duplicateBarcodeRows > 0) {
                        warnings.add("${result.duplicateBarcodeRows} שורות עם ברקוד כפול (בסריקה ייבחר מוצר אחד מביניהם)")
                    }
                    showError(
                        "נטענו ${result.products.size} מוצרים — לתשומת לבכם",
                        warnings.joinToString("\n")
                    )
                } else {
                    Toast.makeText(this@MainActivity, "נטענו ${result.products.size} מוצרים", Toast.LENGTH_SHORT).show()
                }
            } catch (e: ExcelFormatException) {
                showError("שגיאה בטעינת הקובץ", e.message ?: "שגיאה לא ידועה")
            } catch (e: Exception) {
                showError("שגיאה בטעינת הקובץ", e.message ?: "שגיאה לא ידועה")
            }
        }
    }

    private fun queryFileName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun saveExcel(uri: Uri) {
        lifecycleScope.launch {
            try {
                repository.exportToExcel(uri)
                Toast.makeText(this@MainActivity, "הקובץ נשמר בהצלחה", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                showError("שגיאה בשמירת הקובץ", e.message ?: "שגיאה לא ידועה")
            }
        }
    }

    private fun handleScannedBarcode(barcode: String) {
        lifecycleScope.launch {
            val product = repository.findByBarcode(barcode)
            if (product != null) {
                openConfirmScreen(
                    sku = product.sku,
                    description = product.description,
                    existingBarcode = product.barcode,
                    scannedBarcode = barcode,
                    existingLocation = product.location
                )
            } else {
                showBarcodeNotFound(barcode)
            }
        }
    }

    private fun showBarcodeNotFound(barcode: String) {
        AlertDialog.Builder(this)
            .setTitle("⚠️ ברקוד לא נמצא")
            .setMessage("ברקוד: $barcode")
            .setPositiveButton("חפש מוצר לפי תיאור") { _, _ ->
                pendingScannedBarcode = barcode
                searchLauncher.launch(Intent(this, SearchActivity::class.java))
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun openConfirmScreen(
        sku: String,
        description: String,
        existingBarcode: String,
        scannedBarcode: String,
        existingLocation: String
    ) {
        val currentLocation = prefs.currentLocation ?: return
        val intent = Intent(this, ProductConfirmActivity::class.java)
            .putExtra(ProductConfirmActivity.EXTRA_SKU, sku)
            .putExtra(ProductConfirmActivity.EXTRA_DESCRIPTION, description)
            .putExtra(ProductConfirmActivity.EXTRA_EXISTING_BARCODE, existingBarcode)
            .putExtra(ProductConfirmActivity.EXTRA_SCANNED_BARCODE, scannedBarcode)
            .putExtra(ProductConfirmActivity.EXTRA_EXISTING_LOCATION, existingLocation)
            .putExtra(ProductConfirmActivity.EXTRA_CURRENT_LOCATION, currentLocation)
        productConfirmLauncher.launch(intent)
    }

    private fun showError(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("אישור", null)
            .show()
    }

    private fun updateUiState() {
        lifecycleScope.launch {
            val total = repository.count()
            tvFileName.text = "קובץ: ${prefs.fileName ?: "לא נבחר"}"
            tvTotalProducts.text = "מוצרים בקובץ: $total"
            tvApprovedCount.text = "מוצרים שאושרו: ${prefs.approvedCount}"

            val location = prefs.currentLocation
            if (location.isNullOrBlank()) {
                tvCurrentLocation.text = "📍 אין מיקום פעיל"
                btnScanLocation.visibility = View.VISIBLE
                btnScanProduct.visibility = View.GONE
                btnFinishLocation.visibility = View.GONE
                tvScannedProductsLabel.visibility = View.GONE
                recyclerScannedProducts.visibility = View.GONE
                scannedProductsAdapter.submitList(emptyList())
            } else {
                tvCurrentLocation.text = "📍 מיקום נוכחי: $location"
                btnScanLocation.visibility = View.GONE
                btnScanProduct.visibility = if (total > 0) View.VISIBLE else View.GONE
                btnFinishLocation.visibility = View.VISIBLE

                val productsHere = repository.findByLocation(location)
                if (productsHere.isEmpty()) {
                    tvScannedProductsLabel.visibility = View.GONE
                    recyclerScannedProducts.visibility = View.GONE
                } else {
                    tvScannedProductsLabel.text = "מוצרים במיקום זה (${productsHere.size}):"
                    tvScannedProductsLabel.visibility = View.VISIBLE
                    recyclerScannedProducts.visibility = View.VISIBLE
                    scannedProductsAdapter.submitList(productsHere)
                }
            }
        }
    }
}
