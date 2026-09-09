package com.warehouse.stockscanner

import android.app.Instrumentation
import android.content.Intent
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.warehouse.stockscanner.data.AppDatabase
import com.warehouse.stockscanner.data.ProductEntity
import com.warehouse.stockscanner.excel.ExcelReader
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device (real Android runtime, not Robolectric) end-to-end proof for the
 * reported bug: a מקט that already has a ברקוד on file, scanned again with a
 * *different* ברקוד at the same מיקום, must land as its own row in the real
 * locations/quantities .xlsx — not merge into or overwrite the existing row,
 * and never show the sku in the barcode column. Drives the actual
 * ProductConfirmActivity -> InventoryActivity screens via real clicks on a
 * real device/emulator, then reads back the real file Room/ExcelWriter wrote
 * to the device's filesystem.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceBarcodeRowTest {

    private val existingBarcode = "7290012345678"
    private val newlyScannedBarcode = "9998887776665"
    private val sku = "T00151-ONDEVICE"

    private lateinit var app: StockScannerApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        runBlocking {
            AppDatabase.getInstance(app).productDao().clearAll()
            AppDatabase.getInstance(app).productDao().insertAll(
                listOf(ProductEntity(sku, "Aluminum Tray", existingBarcode, "A-01-01", 0, scanned = true))
            )
            app.repository.createWorkingFiles("ondevice_test.xlsx")
        }
    }

    @After
    fun tearDown() {
        runBlocking { AppDatabase.getInstance(app).productDao().clearAll() }
    }

    @Test
    fun aDifferentBarcodeScannedAtTheSameLocation_opensItsOwnRealRow_neverTheSkuInTheBarcodeColumn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // Exactly the ProductConfirmActivity intent MainActivity would build
        // after a real camera scan of `newlyScannedBarcode` for this sku.
        val confirmIntent = Intent(app, ProductConfirmActivity::class.java)
            .putExtra(ProductConfirmActivity.EXTRA_SKU, sku)
            .putExtra(ProductConfirmActivity.EXTRA_DESCRIPTION, "Aluminum Tray")
            .putExtra(ProductConfirmActivity.EXTRA_EXISTING_BARCODE, existingBarcode)
            .putExtra(ProductConfirmActivity.EXTRA_SCANNED_BARCODE, newlyScannedBarcode)
            .putStringArrayListExtra(ProductConfirmActivity.EXTRA_EXISTING_LOCATIONS, arrayListOf("A-01-01"))
            .putExtra(ProductConfirmActivity.EXTRA_CURRENT_LOCATION, "A-01-01")

        val inventoryMonitor = instrumentation.addMonitor(InventoryActivity::class.java.name, null, false)

        val confirmScenario = ActivityScenario.launch<ProductConfirmActivity>(confirmIntent)
        confirmScenario.onActivity { it.findViewById<Button>(R.id.btnConfirm).performClick() }

        val inventoryActivity = inventoryMonitor.waitForActivityWithTimeout(10_000) as InventoryActivity?
        assertTrue("InventoryActivity never started after confirming", inventoryActivity != null)

        instrumentation.runOnMainSync {
            inventoryActivity!!.findViewById<EditText>(R.id.etQuantity).setText("4")
        }
        // Wait for the async prefill (InventoryActivity.prefillFromExistingRow) to
        // finish landing before trusting our own typed value is still there.
        var waited = 0
        while (waited < 5000) {
            var checked = false
            instrumentation.runOnMainSync {
                checked = inventoryActivity!!.findViewById<RadioButton>(R.id.rbUnits).isChecked
            }
            if (checked) break
            Thread.sleep(50)
            waited += 50
        }
        instrumentation.runOnMainSync {
            inventoryActivity!!.findViewById<EditText>(R.id.etQuantity).setText("4")
            inventoryActivity.findViewById<Button>(R.id.btnSaveInventory).performClick()
        }

        // Wait for the real, physical Excel write (a real ZipOutputStream to a
        // real file on this device) to finish and the screen to close.
        waited = 0
        while (waited < 10_000) {
            var finishing = false
            instrumentation.runOnMainSync { finishing = inventoryActivity!!.isFinishing }
            if (finishing) break
            Thread.sleep(100)
            waited += 100
        }

        // Not confirmScenario.close() here: ProductConfirmActivity legitimately
        // cycles STOPPED->RESTARTED->STARTED when InventoryActivity finishes
        // and control briefly returns to it before it also finishes — a real,
        // correct lifecycle ActivityScenario's own bookkeeping doesn't expect,
        // so closing it throws internally. The instrumentation process tears
        // everything down when the test finishes regardless.

        // Read back the REAL file this device just wrote.
        val file = app.repository.locationsQuantitiesFile()!!
        assertTrue("locations/quantities file must exist on disk", file.exists())
        val rows = file.inputStream().use { ExcelReader.readProductsFromStream(it) }.products
            .filter { it.sku == sku }

        assertEquals("both the original and the newly-scanned barcode must have their own row", 2, rows.size)
        val originalRow = rows.single { it.barcode == existingBarcode }
        val newRow = rows.single { it.barcode == newlyScannedBarcode }
        assertEquals("A-01-01", originalRow.location)
        assertEquals("A-01-01", newRow.location)
        assertEquals(4, newRow.quantity)
        for (row in rows) {
            assertNotEquals("the ברקוד column must never equal the מקט", row.sku, row.barcode)
        }
    }
}
