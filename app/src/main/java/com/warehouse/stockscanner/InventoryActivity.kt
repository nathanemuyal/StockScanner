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
 * product. Records how much stock sits at that (sku, location) row: either
 * a straight unit count, or a package breakdown (units per package × number
 * of packages), which is converted to a unit count automatically. Nothing
 * is saved until "שמור והמשך" is tapped — and that tap physically writes
 * the row to the Excel working files right away (scan-by-scan), not just to
 * the in-memory database: a sku can have several rows (one per location, or
 * even one per barcode sharing a location — see [ProductEntity]), so every
 * individual scan+quantity needs to land in the file on its own rather than
 * waiting for the whole shelf to be finished. Screen stays open and reports
 * the failure if that physical write fails, mirroring "סיים מיקום" in
 * MainActivity, so nothing is ever reported saved when it wasn't.
 */
class InventoryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SKU = "sku"
        const val EXTRA_LOCATION = "location"
    }

    private lateinit var repository: ProductRepository
    private var sku: String = ""
    private var location: String = ""

    private lateinit var rgQuantityType: RadioGroup
    private lateinit var rbUnits: RadioButton
    private lateinit var rbPackage: RadioButton
    private lateinit var groupUnits: View
    private lateinit var groupPackage: View
    private lateinit var etQuantity: EditText
    private lateinit var etPackageContent: EditText
    private lateinit var etPackageCount: EditText
    private lateinit var tvTotalUnits: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inventory)
        repository = (application as StockScannerApp).repository

        sku = intent.getStringExtra(EXTRA_SKU).orEmpty()
        location = intent.getStringExtra(EXTRA_LOCATION).orEmpty()

        rgQuantityType = findViewById(R.id.rgQuantityType)
        rbUnits = findViewById(R.id.rbUnits)
        rbPackage = findViewById(R.id.rbPackage)
        groupUnits = findViewById(R.id.groupUnits)
        groupPackage = findViewById(R.id.groupPackage)
        etQuantity = findViewById(R.id.etQuantity)
        etPackageContent = findViewById(R.id.etPackageContent)
        etPackageCount = findViewById(R.id.etPackageCount)
        tvTotalUnits = findViewById(R.id.tvTotalUnits)
        val btnSaveInventory = findViewById<Button>(R.id.btnSaveInventory)

        rgQuantityType.setOnCheckedChangeListener { _, checkedId -> updateFieldVisibility(checkedId) }
        etPackageContent.afterTextChanged { recalcTotalUnits() }
        etPackageCount.afterTextChanged { recalcTotalUnits() }

        btnSaveInventory.setOnClickListener { save() }

        prefillFromExistingRow()
    }

    private fun updateFieldVisibility(checkedId: Int) {
        val isPackage = checkedId == R.id.rbPackage
        groupUnits.visibility = if (isPackage) View.GONE else View.VISIBLE
        groupPackage.visibility = if (isPackage) View.VISIBLE else View.GONE
    }

    /** Re-scanning the same product at the same location should show whatever quantity was already recorded. */
    private fun prefillFromExistingRow() {
        lifecycleScope.launch {
            val existing = repository.findRow(sku, location)
            if (existing != null && existing.quantityType == ProductEntity.TYPE_PACKAGE) {
                rbPackage.isChecked = true
                etPackageContent.setText(existing.packageContent.takeIf { it != 0 }?.toString() ?: "")
                etPackageCount.setText(existing.packageCount.takeIf { it != 0 }?.toString() ?: "")
            } else {
                rbUnits.isChecked = true
                etQuantity.setText(existing?.quantity?.takeIf { it != 0 }?.toString() ?: "")
            }
            updateFieldVisibility(rgQuantityType.checkedRadioButtonId)
            recalcTotalUnits()
        }
    }

    private fun recalcTotalUnits() {
        val content = etPackageContent.text.toString().trim().toIntOrNull() ?: 0
        val count = etPackageCount.text.toString().trim().toIntOrNull() ?: 0
        tvTotalUnits.text = (content * count).toString()
    }

    private fun save() {
        if (rbPackage.isChecked) {
            val content = etPackageContent.text.toString().trim().toIntOrNull()
            val count = etPackageCount.text.toString().trim().toIntOrNull()
            if (content == null || content < 0 || count == null || count < 0) {
                Toast.makeText(this, "יש להזין כמות תכולה וכמות חבילות תקינות", Toast.LENGTH_SHORT).show()
                return
            }
            saveAndFinish(ProductEntity.TYPE_PACKAGE, content, count, content * count)
        } else {
            val quantity = etQuantity.text.toString().trim().toIntOrNull()
            if (quantity == null || quantity < 0) {
                Toast.makeText(this, "יש להזין כמות תקינה", Toast.LENGTH_SHORT).show()
                return
            }
            saveAndFinish(ProductEntity.TYPE_UNITS, 0, 0, quantity)
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
    private fun saveAndFinish(quantityType: String, packageContent: Int, packageCount: Int, quantity: Int) {
        lifecycleScope.launch {
            repository.updateQuantity(sku, location, quantityType, packageContent, packageCount, quantity)
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
