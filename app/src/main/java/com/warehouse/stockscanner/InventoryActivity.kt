package com.warehouse.stockscanner

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.warehouse.stockscanner.data.ProductEntity
import com.warehouse.stockscanner.data.ProductRepository
import com.warehouse.stockscanner.excel.ExcelSaveException
import com.warehouse.stockscanner.util.showErrorDialog
import kotlinx.coroutines.launch

/**
 * Inventory screen shown right after a product scan is confirmed
 * ([ProductConfirmActivity]) and before the app returns to scan the next
 * product. Records how much stock sits at that exact (sku, location,
 * barcode) row, in one of three modes: a straight unit count ("יחידות"), a
 * package breakdown ("אריזות" — units per package × number of packages), or
 * both at once ("מעורב" — that same breakdown plus however many loose
 * singles sit next to the packages). Every mode is converted to a unit total
 * automatically. "מעורב" is what a shelf holding sealed packages *and* loose
 * units under a single ברקוד needs: without it the worker would have to scan
 * that barcode twice at the same spot, and the second quantity would
 * overwrite the first (it's the same row — see [ProductEntity]) instead of
 * adding to it. Nothing is saved until "שמור והמשך" is tapped — and that
 * tap physically writes the row to the Excel working files right away
 * (scan-by-scan), not just to the in-memory database: a sku can have several
 * rows (one per location, or even several at the very same location if
 * different barcodes were scanned there — see [ProductEntity]), so every
 * individual scan+quantity needs to land in the file on its own rather than
 * waiting for the whole shelf to be finished. Screen stays open and reports
 * the failure if that physical write fails, mirroring "סיים מיקום" in
 * MainActivity, so nothing is ever reported saved when it wasn't.
 */
class InventoryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SKU = "sku"
        const val EXTRA_LOCATION = "location"
        const val EXTRA_BARCODE = "barcode"
    }

    private lateinit var repository: ProductRepository
    private var sku: String = ""
    private var location: String = ""
    private var barcode: String = ""

    private lateinit var rgQuantityType: RadioGroup
    private lateinit var rbUnits: RadioButton
    private lateinit var rbPackage: RadioButton
    private lateinit var rbMixed: RadioButton
    private lateinit var groupUnits: View
    private lateinit var groupPackage: View
    private lateinit var groupLoose: View
    private lateinit var groupTotal: View
    private lateinit var etQuantity: EditText
    private lateinit var etPackageContent: EditText
    private lateinit var etPackageCount: EditText
    private lateinit var etLooseUnits: EditText
    private lateinit var tvTotalUnits: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inventory)
        repository = (application as StockScannerApp).repository

        sku = intent.getStringExtra(EXTRA_SKU).orEmpty()
        location = intent.getStringExtra(EXTRA_LOCATION).orEmpty()
        barcode = intent.getStringExtra(EXTRA_BARCODE).orEmpty()

        rgQuantityType = findViewById(R.id.rgQuantityType)
        rbUnits = findViewById(R.id.rbUnits)
        rbPackage = findViewById(R.id.rbPackage)
        rbMixed = findViewById(R.id.rbMixed)
        groupUnits = findViewById(R.id.groupUnits)
        groupPackage = findViewById(R.id.groupPackage)
        groupLoose = findViewById(R.id.groupLoose)
        groupTotal = findViewById(R.id.groupTotal)
        etQuantity = findViewById(R.id.etQuantity)
        etPackageContent = findViewById(R.id.etPackageContent)
        etPackageCount = findViewById(R.id.etPackageCount)
        etLooseUnits = findViewById(R.id.etLooseUnits)
        tvTotalUnits = findViewById(R.id.tvTotalUnits)
        val btnSaveInventory = findViewById<Button>(R.id.btnSaveInventory)

        rgQuantityType.setOnCheckedChangeListener { _, checkedId ->
            updateFieldVisibility(checkedId)
            // The same package fields add up differently in אריזות vs מעורב
            // (the latter adds the loose units on top), so the preview has to
            // be recomputed on a mode switch, not only on a keystroke.
            recalcTotalUnits()
        }
        etPackageContent.afterTextChanged { recalcTotalUnits() }
        etPackageCount.afterTextChanged { recalcTotalUnits() }
        etLooseUnits.afterTextChanged { recalcTotalUnits() }

        btnSaveInventory.setOnClickListener { save() }

        // Only on a genuinely fresh screen. After a rotation the views are
        // restored with whatever the worker had already typed, and this
        // query answers asynchronously — it would come back *after* that
        // restore and reset the screen to what's on the row (i.e. to
        // nothing, since none of it is saved until "שמור והמשך").
        if (savedInstanceState == null) prefillFromExistingRow()
    }

    /**
     * מעורב shows the very same package fields as אריזות — it just adds the
     * loose-units field underneath them — so the two modes share
     * [groupPackage] and [groupTotal] instead of duplicating either.
     */
    private fun updateFieldVisibility(checkedId: Int) {
        val usesPackages = checkedId == R.id.rbPackage || checkedId == R.id.rbMixed
        val isMixed = checkedId == R.id.rbMixed
        groupUnits.visibility = if (usesPackages) View.GONE else View.VISIBLE
        groupPackage.visibility = if (usesPackages) View.VISIBLE else View.GONE
        groupLoose.visibility = if (isMixed) View.VISIBLE else View.GONE
        groupTotal.visibility = if (usesPackages) View.VISIBLE else View.GONE
    }

    private fun selectedQuantityType(): String = when (rgQuantityType.checkedRadioButtonId) {
        R.id.rbPackage -> ProductEntity.TYPE_PACKAGE
        R.id.rbMixed -> ProductEntity.TYPE_MIXED
        else -> ProductEntity.TYPE_UNITS
    }

    /** Re-scanning the same product at the same location should show whatever quantity was already recorded. */
    private fun prefillFromExistingRow() {
        lifecycleScope.launch {
            val existing = repository.findRow(sku, location, barcode)
            when (existing?.quantityType) {
                ProductEntity.TYPE_PACKAGE, ProductEntity.TYPE_MIXED -> {
                    val isMixed = existing.quantityType == ProductEntity.TYPE_MIXED
                    if (isMixed) rbMixed.isChecked = true else rbPackage.isChecked = true
                    etPackageContent.setText(existing.packageContent.takeIf { it != 0 }?.toString() ?: "")
                    etPackageCount.setText(existing.packageCount.takeIf { it != 0 }?.toString() ?: "")
                    if (isMixed) etLooseUnits.setText(existing.looseUnits.takeIf { it != 0 }?.toString() ?: "")
                }
                else -> {
                    rbUnits.isChecked = true
                    etQuantity.setText(existing?.quantity?.takeIf { it != 0 }?.toString() ?: "")
                }
            }
            updateFieldVisibility(rgQuantityType.checkedRadioButtonId)
            recalcTotalUnits()
        }
    }

    private fun recalcTotalUnits() {
        val content = etPackageContent.text.toString().trim().toIntOrNull() ?: 0
        val count = etPackageCount.text.toString().trim().toIntOrNull() ?: 0
        val loose = etLooseUnits.text.toString().trim().toIntOrNull() ?: 0
        tvTotalUnits.text = ProductEntity.totalUnits(selectedQuantityType(), content, count, loose, 0).toString()
    }

    private fun save() {
        if (rbPackage.isChecked || rbMixed.isChecked) {
            val content = etPackageContent.text.toString().trim().toIntOrNull()
            val count = etPackageCount.text.toString().trim().toIntOrNull()
            if (content == null || content < 0 || count == null || count < 0) {
                Toast.makeText(this, "יש להזין כמות תכולה וכמות חבילות תקינות", Toast.LENGTH_SHORT).show()
                return
            }
            // מעורב is that same package breakdown plus the loose singles
            // beside it; אריזות is the same thing with nothing loose.
            val loose = if (rbMixed.isChecked) etLooseUnits.text.toString().trim().toIntOrNull() else 0
            if (loose == null || loose < 0) {
                Toast.makeText(this, "יש להזין כמות יחידות בודדות תקינה", Toast.LENGTH_SHORT).show()
                return
            }
            val type = if (rbMixed.isChecked) ProductEntity.TYPE_MIXED else ProductEntity.TYPE_PACKAGE
            saveAndFinish(type, content, count, loose, ProductEntity.totalUnits(type, content, count, loose, 0))
        } else {
            val quantity = etQuantity.text.toString().trim().toIntOrNull()
            if (quantity == null || quantity < 0) {
                Toast.makeText(this, "יש להזין כמות תקינה", Toast.LENGTH_SHORT).show()
                return
            }
            saveAndFinish(ProductEntity.TYPE_UNITS, 0, 0, 0, quantity)
        }
    }

    /**
     * Records the quantity, then immediately writes it to the physical Excel
     * working files — this is the "scan by scan" save: the worker must not
     * have to reach "סיים מיקום" (or the separate "שמור Excel" screen) for a
     * row they just scanned to actually be on disk. On a failed write the
     * quantity stays recorded in the database (nothing scanned is lost) but
     * the screen stays open and reports the error, so success is never
     * claimed for a row that isn't really saved yet.
     */
    private fun saveAndFinish(
        quantityType: String,
        packageContent: Int,
        packageCount: Int,
        looseUnits: Int,
        quantity: Int
    ) {
        lifecycleScope.launch {
            repository.updateQuantity(
                sku, location, barcode, quantityType, packageContent, packageCount, looseUnits, quantity
            )
            try {
                repository.saveWorkingCopies()
                setResult(RESULT_OK)
                finish()
            } catch (e: ExcelSaveException) {
                showErrorDialog("שמירת השורה נכשלה", (e.message ?: "שגיאה לא ידועה") + "\n\nהנתונים לא אבדו — ניתן לנסות שוב.")
            }
        }
    }
}

private fun EditText.afterTextChanged(action: () -> Unit) {
    addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) = action()
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    })
}
