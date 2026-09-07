package com.warehouse.stockscanner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.warehouse.stockscanner.data.ProductLookup
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
    private lateinit var btnLoadExcel: Button
    private lateinit var btnScanLocation: Button
    private lateinit var btnScanProduct: Button
    private lateinit var btnFinishLocation: Button
    private lateinit var tvScannedProductsLabel: TextView
    private lateinit var recyclerScannedProducts: RecyclerView
    private lateinit var scannedProductsAdapter: SearchResultAdapter

    /** Barcode that was scanned but not found — kept around while the user searches by description. */
    private var pendingScannedBarcode: String? = null

    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) loadExcel(uri)
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
                            openConfirmScreen(product, scannedBarcode)
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

        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))

        tvFileName = findViewById(R.id.tvFileName)
        tvTotalProducts = findViewById(R.id.tvTotalProducts)
        tvCurrentLocation = findViewById(R.id.tvCurrentLocation)
        tvApprovedCount = findViewById(R.id.tvApprovedCount)
        btnScanLocation = findViewById(R.id.btnScanLocation)
        btnScanProduct = findViewById(R.id.btnScanProduct)
        btnFinishLocation = findViewById(R.id.btnFinishLocation)
        tvScannedProductsLabel = findViewById(R.id.tvScannedProductsLabel)
        recyclerScannedProducts = findViewById(R.id.recyclerScannedProducts)

        scannedProductsAdapter = SearchResultAdapter { /* view-only list, no action on tap */ }
        recyclerScannedProducts.layoutManager = LinearLayoutManager(this)
        recyclerScannedProducts.adapter = scannedProductsAdapter

        btnLoadExcel = findViewById(R.id.btnLoadExcel)
        btnLoadExcel.setOnClickListener {
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

        updateUiState()
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // Saving to Excel is only offered once the worker has stepped away
        // from an active location scan — never mid-location, where a save
        // could too easily happen by mistake.
        menu.findItem(R.id.action_save_excel)?.isVisible = prefs.currentLocation.isNullOrBlank()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_save_excel) {
            saveExcel()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * Writes the current data to the working copy — a deliberate action the
     * worker takes from the overflow menu, typically once at the end of the
     * whole process. Never happens automatically after a scan.
     */
    private fun saveExcel() {
        lifecycleScope.launch {
            if (repository.count() == 0) {
                Toast.makeText(this@MainActivity, "אין נתונים לשמירה, טען קובץ Excel קודם", Toast.LENGTH_LONG).show()
                return@launch
            }
            try {
                repository.saveWorkingCopy()
                Toast.makeText(this@MainActivity, "הקובץ נשמר בהצלחה", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                showError("שגיאה בשמירת הקובץ", e.message ?: "שגיאה לא ידועה")
            }
        }
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

                // The picked file itself is never opened for writing again —
                // loadFromExcel already created a fresh working copy for it.
                prefs.resetForNewFile(name, uri.toString())
                updateUiState()

                if (result.duplicateRows > 0 || result.duplicateBarcodeRows > 0) {
                    val warnings = ArrayList<String>()
                    if (result.duplicateRows > 0) {
                        warnings.add("${result.duplicateRows} שורות כפולות (אותו מקט ואותו מיקום — נלקחה השורה האחרונה)")
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

    private fun handleScannedBarcode(barcode: String) {
        lifecycleScope.launch {
            val product = repository.findByBarcode(barcode)
            if (product != null) {
                openConfirmScreen(product, barcode)
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

    private fun openConfirmScreen(product: ProductLookup, scannedBarcode: String) {
        val currentLocation = prefs.currentLocation ?: return
        val intent = Intent(this, ProductConfirmActivity::class.java)
            .putExtra(ProductConfirmActivity.EXTRA_SKU, product.sku)
            .putExtra(ProductConfirmActivity.EXTRA_DESCRIPTION, product.description)
            .putExtra(ProductConfirmActivity.EXTRA_EXISTING_BARCODE, product.barcode)
            .putExtra(ProductConfirmActivity.EXTRA_SCANNED_BARCODE, scannedBarcode)
            .putStringArrayListExtra(ProductConfirmActivity.EXTRA_EXISTING_LOCATIONS, ArrayList(product.existingLocations))
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
            // Loading a new file (and saving the current one — see
            // onPrepareOptionsMenu) is only offered when no location scan is
            // in progress, so neither can happen by mistake mid-location.
            btnLoadExcel.visibility = if (location.isNullOrBlank()) View.VISIBLE else View.GONE
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
            invalidateOptionsMenu()
        }
    }
}
