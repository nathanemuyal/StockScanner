package com.warehouse.stockscanner

import android.content.Intent
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
import com.warehouse.stockscanner.data.ProductEntity
import com.warehouse.stockscanner.data.ProductLookup
import com.warehouse.stockscanner.data.ProductRepository
import com.warehouse.stockscanner.data.SessionPrefs
import com.warehouse.stockscanner.excel.ExcelSaveException
import com.warehouse.stockscanner.util.showErrorDialog
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var repository: ProductRepository
    private lateinit var prefs: SessionPrefs

    private lateinit var btnExcelActions: Button
    private lateinit var tvFileName: TextView
    private lateinit var tvTotalProducts: TextView
    private lateinit var tvCurrentLocation: TextView
    private lateinit var tvApprovedCount: TextView
    private lateinit var btnScanLocation: Button
    private lateinit var btnScanProduct: Button
    private lateinit var btnFinishLocation: Button
    private lateinit var tvScannedProductsLabel: TextView
    private lateinit var recyclerScannedProducts: RecyclerView
    private lateinit var scannedProductsAdapter: SearchResultAdapter

    /** Barcode that was scanned but not found — kept around while the user searches by description. */
    private var pendingScannedBarcode: String? = null

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

        btnExcelActions = findViewById(R.id.btnExcelActions)
        tvFileName = findViewById(R.id.tvFileName)
        tvTotalProducts = findViewById(R.id.tvTotalProducts)
        tvCurrentLocation = findViewById(R.id.tvCurrentLocation)
        tvApprovedCount = findViewById(R.id.tvApprovedCount)
        btnScanLocation = findViewById(R.id.btnScanLocation)
        btnScanProduct = findViewById(R.id.btnScanProduct)
        btnFinishLocation = findViewById(R.id.btnFinishLocation)
        tvScannedProductsLabel = findViewById(R.id.tvScannedProductsLabel)
        recyclerScannedProducts = findViewById(R.id.recyclerScannedProducts)

        // The row itself stays view-only (tapping it does nothing) — only its
        // red "−" button offers to undo the product having been added to this
        // shelf, e.g. because it was scanned by mistake or actually pulled off.
        scannedProductsAdapter = SearchResultAdapter(
            onClick = { /* view-only row, removal is via the "−" button only */ },
            onRemoveClick = { product -> confirmRemoveFromLocation(product) }
        )
        recyclerScannedProducts.layoutManager = LinearLayoutManager(this)
        recyclerScannedProducts.adapter = scannedProductsAdapter

        // Picking and saving the Excel file both live on their own dedicated
        // screen — kept off the main scanning flow so neither can happen
        // with a stray tap while scanning.
        btnExcelActions.setOnClickListener {
            startActivity(Intent(this, ExcelActionsActivity::class.java))
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

        // "סיים מיקום" is the shelf-save confirmation: it must physically
        // write the newly-scanned data to the Excel working files before the
        // location is considered done — not just leave it in the in-memory
        // database — and must never tell the user it succeeded if it didn't.
        btnFinishLocation.setOnClickListener { finishLocation() }

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

    /**
     * The shelf-save confirmation ("סיים מיקום"): writes everything scanned
     * so far to the physical Excel working files, and only clears the
     * active location — and only tells the user it's done — once that write
     * actually succeeded. On failure the location stays active (nothing
     * scanned is lost either way, since it's already in the database) so the
     * worker can simply try again.
     */
    private fun finishLocation() {
        lifecycleScope.launch {
            try {
                repository.saveWorkingCopies()
                prefs.currentLocation = null
                updateUiState()
                Toast.makeText(this@MainActivity, "המדף נשמר בהצלחה בקובץ ה-Excel", Toast.LENGTH_SHORT).show()
            } catch (e: ExcelSaveException) {
                showErrorDialog("שמירת המדף נכשלה", (e.message ?: "שגיאה לא ידועה") + "\n\nהנתונים לא אבדו — ניתן לנסות שוב.")
            }
        }
    }

    /** Confirms, then undoes [product] having been added to the current location's shelf. */
    private fun confirmRemoveFromLocation(product: ProductEntity) {
        AlertDialog.Builder(this)
            .setTitle("הסרת מוצר מהמדף")
            .setMessage("להסיר את \"${product.description}\" (${product.sku}) מהמיקום הנוכחי?")
            .setPositiveButton("כן, הסר") { _, _ ->
                lifecycleScope.launch {
                    repository.removeFromLocation(product.sku, product.location)
                    updateUiState()
                }
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun updateUiState() {
        lifecycleScope.launch {
            val total = repository.count()
            tvFileName.text = "קובץ: ${prefs.fileName ?: "לא נבחר"}"
            tvTotalProducts.text = "מוצרים בקובץ: $total"
            tvApprovedCount.text = "מוצרים שאושרו: ${prefs.approvedCount}"

            val location = prefs.currentLocation
            // Excel actions (choosing/saving a file) are only offered when no
            // location scan is in progress, so neither can happen by mistake
            // mid-location.
            btnExcelActions.visibility = if (location.isNullOrBlank()) View.VISIBLE else View.GONE
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
