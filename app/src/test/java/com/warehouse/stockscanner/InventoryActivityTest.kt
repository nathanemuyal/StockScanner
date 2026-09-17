package com.warehouse.stockscanner

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.warehouse.stockscanner.data.AppDatabase
import com.warehouse.stockscanner.data.BarcodeEntity
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
import org.robolectric.android.controller.ActivityController
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

    /** Puts a code on file with the packaging it carries, as an import or a first scan would. */
    private fun registerBarcode(barcode: String, sku: String, role: String, packageContent: Int = 0) {
        runBlocking {
            AppDatabase.getInstance(context).barcodeDao().insert(
                BarcodeEntity(barcode = barcode, sku = sku, role = role, packageContent = packageContent)
            )
        }
    }

    private fun controllerFor(
        sku: String,
        location: String,
        barcode: String = "111"
    ): ActivityController<InventoryActivity> {
        val intent = Intent(context, InventoryActivity::class.java)
            .putExtra(InventoryActivity.EXTRA_SKU, sku)
            .putExtra(InventoryActivity.EXTRA_LOCATION, location)
            .putExtra(InventoryActivity.EXTRA_BARCODE, barcode)
        return Robolectric.buildActivity(InventoryActivity::class.java, intent).setup()
            .also { settlePrefill() }
    }

    /**
     * The prefill is a coroutine over a background Room read, so it lands
     * some time after the screen is up. A test that starts tapping before it
     * comes back would have its taps silently undone by it — which is a race
     * in the test, not the behaviour under test. Drained here so every test
     * starts from a screen the prefill has already finished with.
     */
    private fun settlePrefill() {
        repeat(30) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    private fun launch(sku: String, location: String, barcode: String = "111"): InventoryActivity =
        controllerFor(sku, location, barcode).get()

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

    /**
     * The screen is recreated on rotation, and nothing typed here is saved
     * until "שמור והמשך" — so the row the prefill query answers with is
     * still empty. Coming back after the view state was restored, it must
     * not reset the screen to that empty row and throw away a count the
     * worker has already typed (in מעורב, three fields' worth).
     */
    @Test
    fun `a rotation keeps what was typed instead of the prefill resetting it`() {
        insertRow(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0, scanned = true))

        val controller = controllerFor("ABC-123", "A-01-05")
        val activity = controller.get()
        awaitUntil { activity.findViewById<RadioButton>(R.id.rbUnits).isChecked }

        activity.findViewById<RadioButton>(R.id.rbMixed).performClick()
        awaitUntil { activity.findViewById<View>(R.id.groupLoose).visibility == View.VISIBLE }
        activity.findViewById<EditText>(R.id.etPackageContent).setText("12")
        activity.findViewById<EditText>(R.id.etPackageCount).setText("5")
        activity.findViewById<EditText>(R.id.etLooseUnits).setText("7")
        awaitUntil { activity.findViewById<TextView>(R.id.tvTotalUnits).text.toString() == "67" }

        controller.recreate()

        val rotated = controller.get()
        awaitUntil { rotated.findViewById<RadioButton>(R.id.rbMixed).isChecked }
        assertEquals("12", rotated.findViewById<EditText>(R.id.etPackageContent).text.toString())
        assertEquals("5", rotated.findViewById<EditText>(R.id.etPackageCount).text.toString())
        assertEquals("7", rotated.findViewById<EditText>(R.id.etLooseUnits).text.toString())
        assertEquals("67", rotated.findViewById<TextView>(R.id.tvTotalUnits).text.toString())
        assertEquals(View.VISIBLE, rotated.findViewById<View>(R.id.groupLoose).visibility)
    }

    /**
     * The other side of the rotation guard: skipping the prefill is only
     * right once it has actually had its say. A screen destroyed in the
     * window between opening and the row coming back saves state that says
     * so, and must prefill on the way back up — otherwise an already-counted
     * row comes back looking like it was never counted at all.
     *
     * Driven by that saved state rather than by real timing: under
     * Robolectric the prefill always wins the race, so the window can't be
     * reproduced by rotating quickly — but a Bundle with no "already
     * prefilled" mark is exactly what a screen torn down inside it leaves.
     */
    @Test
    fun `a rotation that interrupted the prefill still fills in the saved quantity`() {
        insertRow(
            ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0, ProductEntity.TYPE_MIXED, 12, 5, 7, 67, scanned = true)
        )

        val intent = Intent(context, InventoryActivity::class.java)
            .putExtra(InventoryActivity.EXTRA_SKU, "ABC-123")
            .putExtra(InventoryActivity.EXTRA_LOCATION, "A-01-05")
            .putExtra(InventoryActivity.EXTRA_BARCODE, "111")
        val restored = Robolectric.buildActivity(InventoryActivity::class.java, intent)
            .setup(Bundle())
            .also { settlePrefill() }
            .get()

        awaitUntil { restored.findViewById<RadioButton>(R.id.rbMixed).isChecked }
        assertEquals("12", restored.findViewById<EditText>(R.id.etPackageContent).text.toString())
        assertEquals("5", restored.findViewById<EditText>(R.id.etPackageCount).text.toString())
        assertEquals("7", restored.findViewById<EditText>(R.id.etLooseUnits).text.toString())
        assertEquals("67", restored.findViewById<TextView>(R.id.tvTotalUnits).text.toString())
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

    /**
     * A package code opens straight into package mode with the carton size
     * already in, so the worker answers one question — how many cartons —
     * instead of choosing a mode and retyping a 12 that never changes.
     */
    @Test
    fun `a package barcode opens in package mode with the carton size filled in`() {
        insertRow(ProductEntity("ABC-123", "מוצר", "222", "A-01-05", 0, scanned = true))
        registerBarcode("222", "ABC-123", BarcodeEntity.ROLE_PACKAGE, 12)

        val activity = launch("ABC-123", "A-01-05", "222")
        awaitUntil { activity.findViewById<RadioButton>(R.id.rbPackage).isChecked }

        assertEquals("12", activity.findViewById<EditText>(R.id.etPackageContent).text.toString())
        // The counting field itself stays empty — packaging is a fact about
        // the carton, a count is not.
        assertEquals("", activity.findViewById<EditText>(R.id.etPackageCount).text.toString())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.tvBarcodeRole).visibility)
    }

    /** One code on both cartons and loose singles opens both fields at once. */
    @Test
    fun `a mixed barcode opens with both the package and the loose fields`() {
        insertRow(ProductEntity("ABC-123", "מוצר", "333", "A-01-05", 0, scanned = true))
        registerBarcode("333", "ABC-123", BarcodeEntity.ROLE_MIXED, 6)

        val activity = launch("ABC-123", "A-01-05", "333")
        awaitUntil { activity.findViewById<RadioButton>(R.id.rbMixed).isChecked }

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.groupLoose).visibility)
        assertEquals("6", activity.findViewById<EditText>(R.id.etPackageContent).text.toString())
        assertEquals("", activity.findViewById<EditText>(R.id.etPackageCount).text.toString())
        assertEquals("", activity.findViewById<EditText>(R.id.etLooseUnits).text.toString())
    }

    /** A code nobody has described yet behaves exactly as the screen always did. */
    @Test
    fun `an unknown barcode still opens in units mode and says nothing about packaging`() {
        insertRow(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0, scanned = true))

        val activity = launch("ABC-123", "A-01-05", "111")
        awaitUntil { activity.findViewById<RadioButton>(R.id.rbUnits).isChecked }

        assertEquals(View.GONE, activity.findViewById<View>(R.id.tvBarcodeRole).visibility)
        assertEquals("", activity.findViewById<EditText>(R.id.etQuantity).text.toString())
    }

    /**
     * The code proposes, the shelf decides. A worker who finds loose units
     * under a carton code switches mode and counts what is actually there.
     */
    @Test
    fun `the worker can override the mode the barcode implied`() {
        insertRow(ProductEntity("ABC-123", "מוצר", "222", "A-01-05", 0, scanned = true))
        registerBarcode("222", "ABC-123", BarcodeEntity.ROLE_PACKAGE, 12)

        val activity = launch("ABC-123", "A-01-05", "222")
        awaitUntil { activity.findViewById<RadioButton>(R.id.rbPackage).isChecked }

        activity.findViewById<RadioButton>(R.id.rbMixed).performClick()
        awaitUntil { activity.findViewById<View>(R.id.groupLoose).visibility == View.VISIBLE }

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.groupLoose).visibility)
    }

    /**
     * A count already made here outranks the code's default — re-scanning a
     * shelf is how a worker corrects their own number, and that number was
     * made by a person rather than expected by a file.
     */
    @Test
    fun `a count already made here wins over the barcode's default`() {
        insertRow(
            ProductEntity(
                "ABC-123", "מוצר", "222", "A-01-05", 0,
                ProductEntity.TYPE_UNITS, 0, 0, 0, quantity = 43, scanned = true, countedAt = 1_726_000_000_000L
            )
        )
        registerBarcode("222", "ABC-123", BarcodeEntity.ROLE_PACKAGE, 12)

        val activity = launch("ABC-123", "A-01-05", "222")
        awaitUntil { activity.findViewById<RadioButton>(R.id.rbUnits).isChecked }

        assertEquals("43", activity.findViewById<EditText>(R.id.etQuantity).text.toString())
    }

    /**
     * Guards the seam between this screen and the emptying rule in
     * ProductRepository.updateProduct.
     *
     * A row the source file gave a מיקום, a ברקוד *and* a quantity reaches
     * the screen with countedAt still 0, so the fallback in wasCountedHere()
     * has only the quantity to go on. If updateProduct stopped emptying such
     * a row on first confirmation, that quantity would read as a count and
     * the worker would be shown the figure the file expects before counting
     * anything — the whole thing this app must not do. Driving the real
     * confirmation path rather than inserting a pre-emptied row is what makes
     * this a guard instead of a restatement.
     */
    @Test
    fun `a quantity that came from the source file is never shown as a count`() {
        insertRow(
            ProductEntity(
                "ABC-123", "מוצר", "111", "A-01-05", 0,
                ProductEntity.TYPE_PACKAGE, 12, 5, 0, quantity = 60, scanned = false
            )
        )
        registerBarcode("111", "ABC-123", BarcodeEntity.ROLE_UNIT)
        // The scan that confirms this shelf for the first time.
        runBlocking {
            (context.applicationContext as StockScannerApp).repository
                .updateProduct("ABC-123", "מוצר", "111", "A-01-05")
        }

        val activity = launch("ABC-123", "A-01-05", "111")
        awaitUntil { activity.findViewById<RadioButton>(R.id.rbUnits).isChecked }

        assertEquals("", activity.findViewById<EditText>(R.id.etQuantity).text.toString())
        assertEquals("", activity.findViewById<EditText>(R.id.etPackageContent).text.toString())
    }
}
