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
import com.warehouse.stockscanner.data.BarcodeEntity
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

        /** Whether [prefillFromExistingRow] already had its say before the screen was recreated. */
        private const val STATE_PREFILLED = "prefilled"
    }

    private lateinit var repository: ProductRepository
    private var sku: String = ""
    private var location: String = ""
    private var barcode: String = ""
    private var prefilled = false

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
    private lateinit var tvBarcodeRole: TextView

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
        tvBarcodeRole = findViewById(R.id.tvBarcodeRole)
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

        // Only until it has actually run once. After a rotation the views
        // are restored with whatever the worker had already typed, and this
        // query answers asynchronously — it would come back *after* that
        // restore and reset the screen to what's on the row (i.e. to
        // nothing, since none of it is saved until "שמור והמשך").
        //
        // Keyed on whether the prefill got to apply rather than on
        // savedInstanceState being null, because those aren't the same
        // thing: rotating in the moment between opening the screen and the
        // row coming back would otherwise skip the prefill for good and
        // leave an already-counted row looking empty.
        prefilled = savedInstanceState?.getBoolean(STATE_PREFILLED) == true
        if (!prefilled) prefillFromExistingRow()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PREFILLED, prefilled)
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

    /**
     * Opens the screen on whichever of the two has something to say.
     *
     * A row that was genuinely counted here before — [ProductEntity.countedAt]
     * is set — comes back exactly as it was typed, so re-scanning a shelf is
     * how a worker fixes their own number. That is a count someone made, not
     * an expectation, so showing it costs the count nothing.
     *
     * A row with no count yet takes its shape from the scanned code instead:
     * the mode its [BarcodeEntity.role] implies, with [packageContent]
     * filled in. Both are packaging facts — this code is stuck on cartons of
     * 12 — never stock figures, so the fields the worker actually counts into
     * stay empty. That is the whole saving: a package count stops being four
     * keystrokes and a mode choice, and nobody retypes a 12 that never
     * changes (nor mistypes it as a 10 halfway through a count).
     *
     * Either way the mode stays a default and not a verdict. The radio
     * buttons are live, and a worker who finds loose units under a code
     * marked אריזה switches to מעורב and counts what is there — the shelf
     * wins. That override belongs to this row alone; it never writes back to
     * the code, since one odd shelf should not redefine a barcode for the
     * rest of the count.
     */
    private fun prefillFromExistingRow() {
        lifecycleScope.launch {
            val existing = repository.findRow(sku, location, barcode)
            val barcodeInfo = repository.barcodeInfo(barcode)
            showBarcodeRole(barcodeInfo)

            if (existing != null && existing.wasCountedHere()) {
                applyCountedRow(existing)
            } else {
                applyBarcodeDefault(barcodeInfo)
            }
            updateFieldVisibility(rgQuantityType.checkedRadioButtonId)
            recalcTotalUnits()
            prefilled = true
        }
    }

    /**
     * Whether this row carries a count someone actually made here.
     *
     * [ProductEntity.countedAt] is the direct answer, but it only exists from
     * v8 on: a database that upgraded mid-count has 0 there for rows that
     * were genuinely counted before the upgrade, and shaping the screen from
     * the barcode instead would drop a worker's own number in front of them.
     * Carrying a non-zero count is the other way to tell, and it is only
     * reliable because every path in ProductRepository.updateProduct empties
     * a row it is placing for the first time — including the one that
     * confirms a row the source file already gave a מיקום, a ברקוד and a
     * quantity. Without that last one this fallback would read the file's
     * expected figure as a count and put it straight in front of the worker,
     * so this screen depends on it rather than merely benefiting from it.
     *
     * The one case this misses is a shelf genuinely counted as zero before
     * an upgrade, which reads as uncounted. countedAt covers it from here on.
     */
    private fun ProductEntity.wasCountedHere(): Boolean =
        countedAt > 0L || quantity > 0 || packageCount > 0 || looseUnits > 0

    /** A count really made at this shelf, restored exactly as it was typed. */
    private fun applyCountedRow(existing: ProductEntity) {
        when (existing.quantityType) {
            ProductEntity.TYPE_PACKAGE, ProductEntity.TYPE_MIXED -> {
                val isMixed = existing.quantityType == ProductEntity.TYPE_MIXED
                if (isMixed) rbMixed.isChecked = true else rbPackage.isChecked = true
                etPackageContent.setText(existing.packageContent.takeIf { it != 0 }?.toString() ?: "")
                etPackageCount.setText(existing.packageCount.takeIf { it != 0 }?.toString() ?: "")
                if (isMixed) etLooseUnits.setText(existing.looseUnits.takeIf { it != 0 }?.toString() ?: "")
            }
            else -> {
                rbUnits.isChecked = true
                etQuantity.setText(existing.quantity.takeIf { it != 0 }?.toString() ?: "")
            }
        }
    }

    /**
     * Nothing counted here yet: shape the screen from the code's packaging
     * and leave every counting field empty. An unknown code falls back to
     * plain units, which is what the screen always did.
     */
    private fun applyBarcodeDefault(info: BarcodeEntity?) {
        when (info?.role) {
            BarcodeEntity.ROLE_PACKAGE -> rbPackage.isChecked = true
            BarcodeEntity.ROLE_MIXED -> rbMixed.isChecked = true
            else -> rbUnits.isChecked = true
        }
        val content = info?.packageContent?.takeIf { it > 0 }?.toString().orEmpty()
        etPackageContent.setText(content)
        etPackageCount.setText("")
        etLooseUnits.setText("")
        etQuantity.setText("")
    }

    /** Says what was scanned, so the worker can tell a carton code from a single-unit one. */
    private fun showBarcodeRole(info: BarcodeEntity?) {
        val label = when (info?.role) {
            BarcodeEntity.ROLE_PACKAGE -> "ברקוד אריזה"
            BarcodeEntity.ROLE_MIXED -> "ברקוד אריזה ובודד"
            BarcodeEntity.ROLE_UNIT -> "ברקוד בודד"
            else -> null
        }
        if (info == null || label == null) {
            tvBarcodeRole.visibility = View.GONE
            return
        }
        val content = info.packageContent.takeIf { it > 0 }
        tvBarcodeRole.text = if (content != null) "$label · $content יח׳ באריזה" else label
        tvBarcodeRole.visibility = View.VISIBLE
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
