package com.warehouse.stockscanner

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.warehouse.stockscanner.data.ProductRepository
import com.warehouse.stockscanner.data.SessionPrefs
import com.warehouse.stockscanner.excel.ExcelFormatException
import kotlinx.coroutines.launch

/**
 * A separate, deliberate screen for everything to do with the Excel file
 * itself — picking a new source file and saving the working copy — kept off
 * the main scanning screen so neither can happen with a stray tap while
 * scanning. Reached from MainActivity, and only while no location is
 * currently being scanned there.
 */
class ExcelActionsActivity : AppCompatActivity() {

    private lateinit var repository: ProductRepository
    private lateinit var prefs: SessionPrefs

    private lateinit var tvFileName: TextView
    private lateinit var tvTotalProducts: TextView

    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) loadExcel(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_excel_actions)

        repository = (application as StockScannerApp).repository
        prefs = SessionPrefs(this)

        tvFileName = findViewById(R.id.tvFileName)
        tvTotalProducts = findViewById(R.id.tvTotalProducts)

        findViewById<Button>(R.id.btnLoadExcel).setOnClickListener {
            openDocumentLauncher.launch(
                arrayOf(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/octet-stream"
                )
            )
        }

        findViewById<Button>(R.id.btnSaveExcel).setOnClickListener { saveExcel() }

        findViewById<Button>(R.id.btnCloseExcelActions).setOnClickListener { finish() }

        updateUiState()
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
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
                    Toast.makeText(this@ExcelActionsActivity, "נטענו ${result.products.size} מוצרים", Toast.LENGTH_SHORT).show()
                }
            } catch (e: ExcelFormatException) {
                showError("שגיאה בטעינת הקובץ", e.message ?: "שגיאה לא ידועה")
            } catch (e: Exception) {
                showError("שגיאה בטעינת הקובץ", e.message ?: "שגיאה לא ידועה")
            }
        }
    }

    /**
     * Writes the current data to the working copy — a deliberate action the
     * worker takes from this dedicated screen, typically once at the end of
     * the whole process. Never happens automatically after a scan.
     */
    private fun saveExcel() {
        lifecycleScope.launch {
            if (repository.count() == 0) {
                Toast.makeText(this@ExcelActionsActivity, "אין נתונים לשמירה, טען קובץ Excel קודם", Toast.LENGTH_LONG).show()
                return@launch
            }
            try {
                repository.saveWorkingCopy()
                Toast.makeText(this@ExcelActionsActivity, "הקובץ נשמר בהצלחה", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                showError("שגיאה בשמירת הקובץ", e.message ?: "שגיאה לא ידועה")
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

    private fun showError(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("אישור", null)
            .show()
    }

    private fun updateUiState() {
        lifecycleScope.launch {
            tvFileName.text = "קובץ נבחר: ${prefs.fileName ?: "לא נבחר"}"
            tvTotalProducts.text = "מוצרים בקובץ: ${repository.count()}"
        }
    }
}
