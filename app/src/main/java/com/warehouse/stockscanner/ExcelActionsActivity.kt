package com.warehouse.stockscanner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.warehouse.stockscanner.data.ProductRepository
import com.warehouse.stockscanner.data.SessionPrefs
import com.warehouse.stockscanner.excel.ExcelFormatException
import com.warehouse.stockscanner.excel.ExcelSaveException
import com.warehouse.stockscanner.util.showErrorDialog
import kotlinx.coroutines.launch

/**
 * A separate, deliberate screen for everything to do with the Excel files
 * themselves — picking a new source file and saving the two working copies
 * derived from it — kept off the main scanning screen so neither can happen
 * with a stray tap while scanning. Reached from MainActivity, and only
 * while no location is currently being scanned there.
 */
class ExcelActionsActivity : AppCompatActivity() {

    private lateinit var repository: ProductRepository
    private lateinit var prefs: SessionPrefs

    private lateinit var tvFileName: TextView
    private lateinit var tvTotalProducts: TextView
    private lateinit var tvLocationsFile: TextView
    private lateinit var tvBarcodesFile: TextView

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
        tvLocationsFile = findViewById(R.id.tvLocationsFile)
        tvBarcodesFile = findViewById(R.id.tvBarcodesFile)

        findViewById<Button>(R.id.btnLoadExcel).setOnClickListener { pickSourceFile() }

        findViewById<Button>(R.id.btnSaveExcel).setOnClickListener { saveExcel() }

        // Each of these two buttons either (re)writes just its own file from
        // the source already loaded, or — if nothing has been picked yet —
        // falls back to picking one, exactly like בחר קובץ Excel מקורי
        // (which creates both files right away).
        findViewById<Button>(R.id.btnCreateLocationsFile).setOnClickListener {
            if (prefs.fileUri != null) saveOneFile(isLocationsFile = true) else pickSourceFile()
        }
        findViewById<Button>(R.id.btnCreateBarcodesFile).setOnClickListener {
            if (prefs.fileUri != null) saveOneFile(isLocationsFile = false) else pickSourceFile()
        }

        findViewById<Button>(R.id.btnExportFiles).setOnClickListener { exportFiles() }

        findViewById<Button>(R.id.btnCloseExcelActions).setOnClickListener { finish() }

        updateUiState()
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
    }

    private fun pickSourceFile() {
        openDocumentLauncher.launch(
            arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/octet-stream"
            )
        )
    }

    private fun loadExcel(uri: Uri) {
        lifecycleScope.launch {
            try {
                val name = queryFileName(uri) ?: "products.xlsx"
                // Reads the picked file, replaces the current data with it,
                // and immediately (re)creates both working files for it.
                val result = repository.loadFromExcel(uri, name)
                updateUiState()

                if (result.duplicateRows > 0 || result.duplicateBarcodeRows > 0) {
                    val warnings = ArrayList<String>()
                    if (result.duplicateRows > 0) {
                        warnings.add("${result.duplicateRows} שורות כפולות (אותו מקט ואותו מיקום — נלקחה השורה האחרונה)")
                    }
                    if (result.duplicateBarcodeRows > 0) {
                        warnings.add("${result.duplicateBarcodeRows} שורות עם ברקוד כפול (בסריקה ייבחר מוצר אחד מביניהם)")
                    }
                    showErrorDialog(
                        "נטענו ${result.products.size} מוצרים — לתשומת לבכם",
                        warnings.joinToString("\n")
                    )
                } else {
                    Toast.makeText(
                        this@ExcelActionsActivity,
                        "נטענו ${result.products.size} מוצרים ונוצרו קבצי העבודה",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: ExcelFormatException) {
                showErrorDialog("שגיאה בטעינת הקובץ", e.message ?: "שגיאה לא ידועה")
            } catch (e: ExcelSaveException) {
                // The source file itself loaded fine — only writing the
                // working copies afterward failed. Never claim full success.
                showErrorDialog("הקובץ נטען אך יצירת קבצי העבודה נכשלה", e.message ?: "שגיאה לא ידועה")
                updateUiState()
            } catch (e: Exception) {
                showErrorDialog("שגיאה בטעינת הקובץ", e.message ?: "שגיאה לא ידועה")
            }
        }
    }

    /**
     * Writes just the one file the user asked for, from whatever is
     * currently in the database — used when a source file is already
     * loaded and the worker wants to (re)create/refresh only that file.
     */
    private fun saveOneFile(isLocationsFile: Boolean) {
        lifecycleScope.launch {
            if (repository.count() == 0) {
                Toast.makeText(this@ExcelActionsActivity, "אין נתונים לשמירה, טען קובץ Excel קודם", Toast.LENGTH_LONG).show()
                return@launch
            }
            try {
                if (isLocationsFile) repository.saveLocationsQuantitiesFile() else repository.saveMultipleBarcodesFile()
                updateUiState()
                val label = if (isLocationsFile) "קובץ המיקומים והכמויות" else "קובץ הברקודים המרובים"
                Toast.makeText(this@ExcelActionsActivity, "$label נשמר בהצלחה", Toast.LENGTH_SHORT).show()
            } catch (e: ExcelSaveException) {
                val label = if (isLocationsFile) "קובץ המיקומים והכמויות" else "קובץ הברקודים המרובים"
                showErrorDialog("שגיאה בשמירת $label", e.message ?: "שגיאה לא ידועה")
            }
        }
    }

    /**
     * Writes the current data to both working files — a deliberate action
     * the worker takes from this dedicated screen. Only ever reports success
     * once both files were actually written to disk.
     */
    private fun saveExcel() {
        lifecycleScope.launch {
            if (repository.count() == 0) {
                Toast.makeText(this@ExcelActionsActivity, "אין נתונים לשמירה, טען קובץ Excel קודם", Toast.LENGTH_LONG).show()
                return@launch
            }
            try {
                repository.saveWorkingCopies()
                updateUiState()
                Toast.makeText(this@ExcelActionsActivity, "הקובץ נשמר בהצלחה", Toast.LENGTH_LONG).show()
            } catch (e: ExcelSaveException) {
                showErrorDialog("שגיאה בשמירת הקובץ", e.message ?: "שגיאה לא ידועה")
            } catch (e: Exception) {
                showErrorDialog("שגיאה בשמירת הקובץ", e.message ?: "שגיאה לא ידועה")
            }
        }
    }

    /**
     * Shares the two working files out of the app's private storage (Drive,
     * email, "Save to device", ...) via a FileProvider content Uri — the
     * only way to hand a worker their data back without asking for broad
     * storage permissions.
     */
    private fun exportFiles() {
        val files = listOfNotNull(repository.locationsQuantitiesFile(), repository.multipleBarcodesFile())
            .filter { it.exists() }
        if (files.isEmpty()) {
            Toast.makeText(this, "אין עדיין קבצים לייצוא — יש ליצור אותם קודם", Toast.LENGTH_LONG).show()
            return
        }
        val authority = "$packageName.fileprovider"
        val uris = ArrayList(files.map { FileProvider.getUriForFile(this, authority, it) })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "ייצוא קבצי Excel"))
    }

    private fun queryFileName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun updateUiState() {
        lifecycleScope.launch {
            tvFileName.text = "קובץ נבחר: ${prefs.fileName ?: "לא נבחר"}"
            tvTotalProducts.text = "מוצרים בקובץ: ${repository.count()}"
            tvLocationsFile.text = "קובץ מיקומים וכמויות: ${prefs.locationsQuantitiesFileName ?: "לא נוצר"}"
            tvBarcodesFile.text = "קובץ ברקודים מרובים: ${prefs.multipleBarcodesFileName ?: "לא נוצר"}"
        }
    }
}
