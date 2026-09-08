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
 * record either a direct unit count or a package breakdown (auto-computing
 * the resulting unit total), scoped to exactly the (sku, location) row it
 * was opened for.
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

    private fun launch(sku: String, location: String): InventoryActivity {
        val intent = Intent(context, InventoryActivity::class.java)
            .putExtra(InventoryActivity.EXTRA_SKU, sku)
            .putExtra(InventoryActivity.EXTRA_LOCATION, location)
        return Robolectric.buildActivity(InventoryActivity::class.java, intent).setup().get()
    }

    @Test
    fun `opens in units mode by default with the package fields hidden`() {
        insertRow(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0))

        val activity = launch("ABC-123", "A-01-05")
        awaitUntil { activity.findViewById<RadioButton>(R.id.rbUnits).isChecked }

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.groupUnits).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.groupPackage).visibility)
    }

    @Test
    fun `saving a quantity in units mode stores it on that row only`() {
        insertRow(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0))
        insertRow(ProductEntity("ABC-123", "מוצר", "111", "B-02-01", 1))

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
        insertRow(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0))

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
            ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0, ProductEntity.TYPE_PACKAGE, 12, 5, 60)
        )

        val activity = launch("ABC-123", "A-01-05")
        awaitUntil { activity.findViewById<RadioButton>(R.id.rbPackage).isChecked }

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.groupPackage).visibility)
        assertEquals("12", activity.findViewById<EditText>(R.id.etPackageContent).text.toString())
        assertEquals("5", activity.findViewById<EditText>(R.id.etPackageCount).text.toString())
        assertEquals("60", activity.findViewById<TextView>(R.id.tvTotalUnits).text.toString())
    }

    @Test
    fun `an invalid quantity warns instead of saving`() {
        insertRow(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0))

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
        insertRow(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0))
        assertNull(context.repository.findRow("ABC-123", "NOWHERE"))
    }
}
