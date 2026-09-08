package com.warehouse.stockscanner

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.os.Looper
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.warehouse.stockscanner.data.AppDatabase
import com.warehouse.stockscanner.data.ProductEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

/**
 * Confirming a product must open the inventory screen right away (instead of
 * finishing straight back to MainActivity), so the quantity is recorded for
 * the same (sku, location) row that was just confirmed.
 */
@RunWith(RobolectricTestRunner::class)
class ProductConfirmActivityTest {

    private lateinit var context: StockScannerApp

    @Before
    fun setUp() {
        AppDatabase.resetForTests()
        context = ApplicationProvider.getApplicationContext()
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

    @Test
    fun `confirming a product opens the inventory screen for the same sku and location, without finishing yet`() {
        runBlocking {
            AppDatabase.getInstance(context).productDao().insertAll(
                listOf(ProductEntity("ABC-123", "מוצר ישן", "111", "", 0))
            )
        }

        val intent = Intent(context, ProductConfirmActivity::class.java)
            .putExtra(ProductConfirmActivity.EXTRA_SKU, "ABC-123")
            .putExtra(ProductConfirmActivity.EXTRA_DESCRIPTION, "מוצר חדש")
            .putExtra(ProductConfirmActivity.EXTRA_EXISTING_BARCODE, "111")
            .putExtra(ProductConfirmActivity.EXTRA_SCANNED_BARCODE, "111")
            .putStringArrayListExtra(ProductConfirmActivity.EXTRA_EXISTING_LOCATIONS, ArrayList())
            .putExtra(ProductConfirmActivity.EXTRA_CURRENT_LOCATION, "A-01-05")

        val activity = Robolectric.buildActivity(ProductConfirmActivity::class.java, intent).setup().get()
        activity.findViewById<Button>(R.id.btnConfirm).performClick()

        awaitUntil { shadowOf(activity).peekNextStartedActivityForResult() != null }
        val started = shadowOf(activity).nextStartedActivityForResult
        assertEquals(InventoryActivity::class.java.name, started.intent.component?.className)
        assertEquals("ABC-123", started.intent.getStringExtra(InventoryActivity.EXTRA_SKU))
        assertEquals("A-01-05", started.intent.getStringExtra(InventoryActivity.EXTRA_LOCATION))

        // Still waiting on the inventory screen's result — must not finish yet.
        assertFalse(activity.isFinishing)

        val updated = runBlocking { AppDatabase.getInstance(context).productDao().findAllBySku("ABC-123") }.single()
        assertEquals("מוצר חדש", updated.description)
        assertEquals("A-01-05", updated.location)
    }

    @Test
    fun `change-sku button opens the search screen, to fix a barcode matched to the wrong מקט`() {
        runBlocking {
            AppDatabase.getInstance(context).productDao().insertAll(
                listOf(ProductEntity("WRONG-1", "מוצר שגוי", "111", "", 0))
            )
        }

        val intent = Intent(context, ProductConfirmActivity::class.java)
            .putExtra(ProductConfirmActivity.EXTRA_SKU, "WRONG-1")
            .putExtra(ProductConfirmActivity.EXTRA_DESCRIPTION, "מוצר שגוי")
            .putExtra(ProductConfirmActivity.EXTRA_EXISTING_BARCODE, "111")
            .putExtra(ProductConfirmActivity.EXTRA_SCANNED_BARCODE, "111")
            .putStringArrayListExtra(ProductConfirmActivity.EXTRA_EXISTING_LOCATIONS, ArrayList())
            .putExtra(ProductConfirmActivity.EXTRA_CURRENT_LOCATION, "A-01-05")

        val activity = Robolectric.buildActivity(ProductConfirmActivity::class.java, intent).setup().get()
        activity.findViewById<Button>(R.id.btnChangeSku).performClick()

        val started = shadowOf(activity).nextStartedActivityForResult
        assertEquals(SearchActivity::class.java.name, started.intent.component?.className)
    }

    @Test
    fun `picking a different product via change-sku reassigns the scanned barcode and reopens for it`() {
        runBlocking {
            AppDatabase.getInstance(context).productDao().insertAll(
                listOf(
                    ProductEntity("WRONG-1", "מוצר שגוי", "111", "A-01-05", 0),
                    ProductEntity("RIGHT-1", "מוצר נכון", "", "B-02-01", 1)
                )
            )
        }

        val intent = Intent(context, ProductConfirmActivity::class.java)
            .putExtra(ProductConfirmActivity.EXTRA_SKU, "WRONG-1")
            .putExtra(ProductConfirmActivity.EXTRA_DESCRIPTION, "מוצר שגוי")
            .putExtra(ProductConfirmActivity.EXTRA_EXISTING_BARCODE, "111")
            .putExtra(ProductConfirmActivity.EXTRA_SCANNED_BARCODE, "111")
            .putStringArrayListExtra(ProductConfirmActivity.EXTRA_EXISTING_LOCATIONS, ArrayList())
            .putExtra(ProductConfirmActivity.EXTRA_CURRENT_LOCATION, "A-01-05")

        val activity = Robolectric.buildActivity(ProductConfirmActivity::class.java, intent).setup().get()
        activity.findViewById<Button>(R.id.btnChangeSku).performClick()
        val started = shadowOf(activity).nextStartedActivityForResult

        // Simulate SearchActivity returning with "RIGHT-1" picked.
        shadowOf(activity).receiveResult(
            started.intent,
            Activity.RESULT_OK,
            Intent().putExtra(SearchActivity.EXTRA_SELECTED_SKU, "RIGHT-1")
        )

        // The confirmation dialog only appears once the async lookup of RIGHT-1 completes.
        awaitUntil { ShadowDialog.getLatestDialog() != null }
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()

        awaitUntil { shadowOf(activity).peekNextStartedActivity() != null }
        // startActivityForResult's own launch is also recorded in this same
        // generic queue (alongside its result-specific one) — drain that
        // stale SearchActivity entry before reaching the actual restart.
        shadowOf(activity).nextStartedActivity
        awaitUntil { shadowOf(activity).peekNextStartedActivity() != null }
        val reopened = shadowOf(activity).nextStartedActivity
        assertEquals(ProductConfirmActivity::class.java.name, reopened.component?.className)
        assertEquals("RIGHT-1", reopened.getStringExtra(ProductConfirmActivity.EXTRA_SKU))
        assertEquals("111", reopened.getStringExtra(ProductConfirmActivity.EXTRA_SCANNED_BARCODE))
        assertTrue(activity.isFinishing)

        // The barcode now resolves to the newly-picked product, not the old one.
        val dao = AppDatabase.getInstance(context).productDao()
        runBlocking {
            assertEquals("RIGHT-1", dao.findByBarcode("111")!!.sku)
            assertEquals("", dao.findAllBySku("WRONG-1").single().barcode)
        }
    }

    @Test
    fun `declining the reassign confirmation dialog leaves the barcode link untouched`() {
        runBlocking {
            AppDatabase.getInstance(context).productDao().insertAll(
                listOf(
                    ProductEntity("WRONG-1", "מוצר שגוי", "111", "A-01-05", 0),
                    ProductEntity("RIGHT-1", "מוצר נכון", "", "B-02-01", 1)
                )
            )
        }

        val intent = Intent(context, ProductConfirmActivity::class.java)
            .putExtra(ProductConfirmActivity.EXTRA_SKU, "WRONG-1")
            .putExtra(ProductConfirmActivity.EXTRA_DESCRIPTION, "מוצר שגוי")
            .putExtra(ProductConfirmActivity.EXTRA_EXISTING_BARCODE, "111")
            .putExtra(ProductConfirmActivity.EXTRA_SCANNED_BARCODE, "111")
            .putStringArrayListExtra(ProductConfirmActivity.EXTRA_EXISTING_LOCATIONS, ArrayList())
            .putExtra(ProductConfirmActivity.EXTRA_CURRENT_LOCATION, "A-01-05")

        val activity = Robolectric.buildActivity(ProductConfirmActivity::class.java, intent).setup().get()
        activity.findViewById<Button>(R.id.btnChangeSku).performClick()
        val started = shadowOf(activity).nextStartedActivityForResult

        shadowOf(activity).receiveResult(
            started.intent,
            Activity.RESULT_OK,
            Intent().putExtra(SearchActivity.EXTRA_SELECTED_SKU, "RIGHT-1")
        )

        awaitUntil { ShadowDialog.getLatestDialog() != null }
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse("declining must not finish or restart the screen", activity.isFinishing)
        val dao = AppDatabase.getInstance(context).productDao()
        runBlocking { assertEquals("WRONG-1", dao.findByBarcode("111")!!.sku) }
    }

    @Test
    fun `change-sku button is hidden when there is no scanned barcode to reassign`() {
        val intent = Intent(context, ProductConfirmActivity::class.java)
            .putExtra(ProductConfirmActivity.EXTRA_SKU, "ABC-123")
            .putExtra(ProductConfirmActivity.EXTRA_DESCRIPTION, "מוצר")
            .putExtra(ProductConfirmActivity.EXTRA_EXISTING_BARCODE, "")
            .putExtra(ProductConfirmActivity.EXTRA_SCANNED_BARCODE, "")
            .putStringArrayListExtra(ProductConfirmActivity.EXTRA_EXISTING_LOCATIONS, ArrayList())
            .putExtra(ProductConfirmActivity.EXTRA_CURRENT_LOCATION, "A-01-05")

        val activity = Robolectric.buildActivity(ProductConfirmActivity::class.java, intent).setup().get()

        assertEquals(android.view.View.GONE, activity.findViewById<Button>(R.id.btnChangeSku).visibility)
    }
}
