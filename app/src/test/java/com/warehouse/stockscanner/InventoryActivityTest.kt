package com.warehouse.stockscanner

import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.warehouse.stockscanner.data.AppDatabase
import com.warehouse.stockscanner.data.ProductEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

/**
 * The inventory screen shown right after a product is confirmed: it must
 * record a direct unit count, a package breakdown, or both at once
 * ("מעורב" — packages plus the loose singles beside them), auto-computing
 * the resulting unit total, scoped to exactly the (sku, location, barcode)
 * row it was opened for.
 */
@RunWith(RobolectricTestRunner::class)
class InventoryActivityTest {

    private lateinit var context: StockScannerApp

    @Before
    fun setUp() {
        AppDatabase.resetForTests()
        context = ApplicationProvider.getApplicationContext()
        ShadowToast.reset()
    }

    @After
    fun tearDown() {
        AppDatabase.resetForTests()
    }

    private fun awaitUntil(timeoutMs: Long = 2000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        check(condition()) { "condition not met within ${timeoutMs}ms" }
    }

    private fun insertRow(row: ProductEntity) {
        runBlocking { AppDatabase.getInstance(context).productDao().insertAll(listOf(row)) }
    }

    private fun launch(sku: String, location: String, barcode: String = "111"): InventoryActivity {
        val intent = Intent(context, InventoryActivity::class.java)
            .putExtra(InventoryActivity.EXTRA_SKU, sku)
            .putExtra(InventoryActivity.EXTRA_LOCATION, location)
            .putExtra(InventoryActivity.EXTRA_BARCODE, barcode)
        return Robolectric.buildActivity(InventoryActivity::class.java, intent).setup().get()
    }

    @Test
    fun `opens in units mode by default with the package fields hidden`() {
        insertRow(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0, scanned = true))

        val activity = launch("ABC-123", "A-01-05")
        awaitUntil { activity.findViewById<RadioButton>(R.id.rbUnits).isChecked }

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.groupUnits).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.groupPackage).visibility)
    }

    @Test
    fun `saving a quantity in units mode stores it on that row only`() {
        insertRow(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0, scanned = true))
        insertRow(ProductEntity("ABC-123", "מוצר", "111", "B-02-01", 1, scanned = true))

        val activity = launch("ABC-123", "A-01-05")
        awaitUntil { activity.findViewById<RadioButton>(R.id.rbUnits).isChecked }

        activity.findViewById<EditText>(R.id.etQuantity).setText("15")
        activity.findViewById<Button>(R.id.btnSaveInventory).performClick()
        awaitUntil { activity.isFinishing }

        val rows = runBlocking { AppDatabase.getInstance(context).productDao().findAllBySku("ABC-123") }
        val updated = rows.first { it.location == "A-01-05" }
        val untouched = rows.first { it.location == "B-02-01" }
        assertEquals(ProductEntity.TYPE_UNITS, updated.quantityType)
        assertEquals(15, updated.quantity)
        assertEquals(0, untouched.quantity)
    }

    @Test
    fun `switching to package mode computes the total live and saves it`() {
        insertRow(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0, scanned = true))

        val activity = launch("ABC-123", "A-01-05")
        awaitUntil { activity.findViewById<RadioButton>(R.id.rbUnits).isChecked }

        activity.findViewById<RadioButton>(R.id.rbPackage).performClick()
        awaitUntil { activity.findViewById<View>(R.id.groupPackage).visibility == View.VISIBLE }

        activity.findViewById<EditText>(R.id.etPackageContent).setText("12")
        activity.findViewById<EditText>(R.id.etPackageCount).setText("5")
        awaitUntil { activity.findViewById<TextView>(R.id.tvTotalUnits).text.toString() == "60" }

        activity.findViewById<Button>(R.id.btnSaveInventory).performClick()
        awaitUntil { activity.isFinishing }

        val row = runBlocking { AppDatabase.getInstance(context).productDao().findAllBySku("ABC-123") }.single()
        assertEquals(ProductEntity.TYPE_PACKAGE, row.quantityType)
        assertEquals(12, row.packageContent)
        assertEquals(5, row.packageCount)
        assertEquals(60, row.quantity)
    }

    @Test
    fun `re-opening for the same row prefills the previously saved package quantity`() {
        insertRow(
            ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0, ProductEntity.TYPE_PACKAGE, 12, 5, quantity = 60, scanned = true)
        )

        val activity = launch("ABC-123", "A-01-05")
        awaitUntil { activity.findViewById<RadioButton>(R.id.rbPackage).isChecked }

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.groupPackage).visibility)
        assertEquals("12", activity.findViewById<EditText>(R.id.etPackageContent).text.toString())
        assertEquals("5", activity.findViewById<EditText>(R.id.etPackageCount).text.toString())
        assertEquals("60", activity.findViewById<TextView>(R.id.tvTotalUnits).text.toString())
    }

    @Test
    fun `mixed mode shows the package fields plus the loose-units field`() {
        insertRow(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0, scanned = true))

        val activity = launch("ABC-123", "A-01-05")
        awaitUntil { activity.findViewById<RadioButton>(R.id.rbUnits).isChecked }

        activity.findViewById<RadioButton>(R.id.rbMixed).performClick()
        awaitUntil { activity.findViewById<View>(R.id.groupLoose).visibility == View.VISIBLE }

        assertEquals(View.GONE, activity.findViewById<View>(R.id.groupUnits).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.groupPackage).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.groupTotal).visibility)
    }

    /**
     * A shelf with 5 sealed packages of 12 plus 7 loose singles is 67 units
     * on ONE row — the case that used to force a second scan of the same
     * ברקוד, whose quantity then overwrote the first one.
     */
    @Test
    fun `mixed mode adds the loose units on top of the package total and saves the breakdown`() {
        insertRow(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0, scanned = true))

        val activity = launch("ABC-123", "A-01-05")
        awaitUntil { activity.findViewById<RadioButton>(R.id.rbUnits).isChecked }

        activity.findViewById<RadioButton>(R.id.rbMixed).performClick()
        awaitUntil { activity.findViewById<View>(R.id.groupLoose).visibility == View.VISIBLE }

        activity.findViewById<EditText>(R.id.etPackageContent).setText("12")
        activity.findViewById<EditText>(R.id.etPackageCount).setText("5")
        activity.findViewById<EditText>(R.id.etLooseUnits).setText("7")
        awaitUntil { activity.findViewById<TextView>(R.id.tvTotalUnits).text.toString() == "67" }

        activity.findViewById<Button>(R.id.btnSaveInventory).performClick()
        awaitUntil { activity.isFinishing }

        val row = runBlocking { AppDatabase.getInstance(context).productDao().findAllBySku("ABC-123") }.single()
        assertEquals(ProductEntity.TYPE_MIXED, row.quantityType)
        assertEquals(12, row.packageContent)
        assertEquals(5, row.packageCount)
        assertEquals(7, row.looseUnits)
        assertEquals(67, row.quantity)
    }

    @Test
    fun `switching between packages and mixed recomputes the total for the same fields`() {
        insertRow(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0, scanned = true))

        val activity = launch("ABC-123", "A-01-05")
        awaitUntil { activity.findViewById<RadioButton>(R.id.rbUnits).isChecked }

        activity.findViewById<RadioButton>(R.id.rbMixed).performClick()
        awaitUntil { activity.findViewById<View>(R.id.groupLoose).visibility == View.VISIBLE }
        activity.findViewById<EditText>(R.id.etPackageContent).setText("12")
        activity.findViewById<EditText>(R.id.etPackageCount).setText("5")
        activity.findViewById<EditText>(R.id.etLooseUnits).setText("7")
        awaitUntil { activity.findViewById<TextView>(R.id.tvTotalUnits).text.toString() == "67" }

        // Back to plain אריזות: the loose field is hidden and stops counting,
        // even though its text is still there for a switch back.
        activity.findViewById<RadioButton>(R.id.rbPackage).performClick()
        awaitUntil { activity.findViewById<View>(R.id.groupLoose).visibility == View.GONE }
        assertEquals("60", activity.findViewById<TextView>(R.id.tvTotalUnits).text.toString())

        activity.findViewById<RadioButton>(R.id.rbMixed).performClick()
        awaitUntil { activity.findViewById<TextView>(R.id.tvTotalUnits).text.toString() == "67" }
    }

    @Test
    fun `re-opening for the same row prefills the previously saved mixed quantity`() {
        insertRow(
            ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0, ProductEntity.TYPE_MIXED, 12, 5, 7, 67, scanned = true)
        )

        val activity = launch("ABC-123", "A-01-05")
        awaitUntil { activity.findViewById<RadioButton>(R.id.rbMixed).isChecked }

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.groupPackage).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.groupLoose).visibility)
        assertEquals("12", activity.findViewById<EditText>(R.id.etPackageContent).text.toString())
        assertEquals("5", activity.findViewById<EditText>(R.id.etPackageCount).text.toString())
        assertEquals("7", activity.findViewById<EditText>(R.id.etLooseUnits).text.toString())
        assertEquals("67", activity.findViewById<TextView>(R.id.tvTotalUnits).text.toString())
    }

    @Test
    fun `an invalid loose-units value warns instead of saving`() {
        insertRow(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0, scanned = true))

        val activity = launch("ABC-123", "A-01-05")
        awaitUntil { activity.findViewById<RadioButton>(R.id.rbUnits).isChecked }

        activity.findViewById<RadioButton>(R.id.rbMixed).performClick()
        awaitUntil { activity.findViewById<View>(R.id.groupLoose).visibility == View.VISIBLE }
        activity.findViewById<EditText>(R.id.etPackageContent).setText("12")
        activity.findViewById<EditText>(R.id.etPackageCount).setText("5")
        activity.findViewById<EditText>(R.id.etLooseUnits).setText("")
        activity.findViewById<Button>(R.id.btnSaveInventory).performClick()

        awaitUntil { ShadowToast.getTextOfLatestToast() != null }
        assertEquals("יש להזין כמות יחידות בודדות תקינה", ShadowToast.getTextOfLatestToast())
        assertEquals(false, activity.isFinishing)

        val row = runBlocking { AppDatabase.getInstance(context).productDao().findAllBySku("ABC-123") }.single()
        assertEquals(0, row.quantity)
        assertEquals(ProductEntity.TYPE_UNITS, row.quantityType)
    }

    @Test
    fun `an invalid quantity warns instead of saving`() {
        insertRow(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0, scanned = true))

        val activity = launch("ABC-123", "A-01-05")
        awaitUntil { activity.findViewById<RadioButton>(R.id.rbUnits).isChecked }

        activity.findViewById<EditText>(R.id.etQuantity).setText("")
        activity.findViewById<Button>(R.id.btnSaveInventory).performClick()

        awaitUntil { ShadowToast.getTextOfLatestToast() != null }
        assertEquals("יש להזין כמות תקינה", ShadowToast.getTextOfLatestToast())
        assertEquals(false, activity.isFinishing)

        val row = runBlocking { AppDatabase.getInstance(context).productDao().findAllBySku("ABC-123") }.single()
        assertEquals(0, row.quantity)
    }

    @Test
    fun `updateQuantity is scoped to this location, findRow for a different one stays null`() = runBlocking {
        insertRow(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0, scanned = true))
        assertNull(context.repository.findRow("ABC-123", "NOWHERE", "111"))
    }

    /**
     * Regression coverage for "scan by scan" saving: a worker must not have
     * to reach "סיים מיקום" for a row they just scanned to actually be on
     * disk — every confirmed quantity is meant to be written to the physical
     * Excel working file right away, since the same מקט can have several
     * rows (one per location, or per barcode) that each need their own
     * quantity recorded independently.
     */
    @Test
    fun `saving a quantity physically writes it to the Excel working file right away`() {
        insertRow(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0, scanned = true))
        runBlocking { context.repository.createWorkingFiles("products.xlsx") }

        val activity = launch("ABC-123", "A-01-05")
        awaitUntil { activity.findViewById<RadioButton>(R.id.rbUnits).isChecked }

        activity.findViewById<EditText>(R.id.etQuantity).setText("15")
        activity.findViewById<Button>(R.id.btnSaveInventory).performClick()
        awaitUntil { activity.isFinishing }

        val locationsFile = context.repository.locationsQuantitiesFile()
        assertEquals(true, locationsFile != null && locationsFile.exists())
        val savedProducts = locationsFile!!.inputStream()
            .use { com.warehouse.stockscanner.excel.ExcelReader.readProductsFromStream(it) }.products
        val savedRow = savedProducts.first { it.location == "A-01-05" }
        assertEquals(15, savedRow.quantity)
    }

    @Test
    fun `a failed physical save reports an error and keeps the screen open`() {
        insertRow(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0, scanned = true))
        runBlocking { context.repository.createWorkingFiles("products.xlsx") }

        // Force the write to fail: put a directory where the working file
        // needs to go, exactly like the MainActivity/repository-level tests do.
        val target = context.repository.locationsQuantitiesFile()!!
        target.delete()
        target.mkdirs()
        try {
            val activity = launch("ABC-123", "A-01-05")
            awaitUntil { activity.findViewById<RadioButton>(R.id.rbUnits).isChecked }

            activity.findViewById<EditText>(R.id.etQuantity).setText("15")
            activity.findViewById<Button>(R.id.btnSaveInventory).performClick()

            awaitUntil { org.robolectric.shadows.ShadowDialog.getLatestDialog() != null }
            assertEquals(false, activity.isFinishing)

            // The quantity is still safe in the database even though the
            // physical write failed.
            val row = runBlocking { AppDatabase.getInstance(context).productDao().findAllBySku("ABC-123") }.single()
            assertEquals(15, row.quantity)
        } finally {
            target.delete()
        }
    }
}
