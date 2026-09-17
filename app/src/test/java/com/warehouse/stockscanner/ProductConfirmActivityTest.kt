package com.warehouse.stockscanner

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.widget.EditText
import android.os.Looper
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.warehouse.stockscanner.data.AppDatabase
import com.warehouse.stockscanner.data.BarcodeEntity
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
            val db = AppDatabase.getInstance(context)
            db.productDao().insertAll(listOf(ProductEntity("ABC-123", "מוצר ישן", "111", "", 0)))
            // An import seeds the barcodes table from the product rows, and a
            // scan registers whatever it finds; inserting straight into the
            // dao skips both, and an unknown code is asked about on confirm.
            db.barcodeDao().insert(BarcodeEntity(barcode = "111", sku = "ABC-123"))
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

    private fun showingDialog(): AlertDialog? =
        (ShadowDialog.getLatestDialog() as? AlertDialog)?.takeIf { it.isShowing }

    private fun confirmScreenFor(sku: String, scannedBarcode: String, location: String): ProductConfirmActivity {
        val intent = Intent(context, ProductConfirmActivity::class.java)
            .putExtra(ProductConfirmActivity.EXTRA_SKU, sku)
            .putExtra(ProductConfirmActivity.EXTRA_DESCRIPTION, "מוצר")
            .putExtra(ProductConfirmActivity.EXTRA_EXISTING_BARCODE, scannedBarcode)
            .putExtra(ProductConfirmActivity.EXTRA_SCANNED_BARCODE, scannedBarcode)
            .putStringArrayListExtra(ProductConfirmActivity.EXTRA_EXISTING_LOCATIONS, ArrayList())
            .putExtra(ProductConfirmActivity.EXTRA_CURRENT_LOCATION, location)
        return Robolectric.buildActivity(ProductConfirmActivity::class.java, intent).setup().get()
    }

    private fun pickRoleItem(index: Int) {
        val list = showingDialog()!!.listView!!
        list.performItemClick(null, index, list.adapter.getItemId(index))
    }

    /**
     * The one thing a scan cannot tell you. Without an answer every code is
     * a single unit, which silently divides a carton count by its contents.
     */
    @Test
    fun `an unrecognised barcode is asked about, and the answer is remembered`() {
        runBlocking {
            AppDatabase.getInstance(context).productDao().insertAll(
                listOf(ProductEntity("ABC-123", "מוצר", "", "", 0))
            )
        }

        val activity = confirmScreenFor("ABC-123", "999", "A-01-05")
        activity.findViewById<Button>(R.id.btnConfirm).performClick()

        awaitUntil { showingDialog()?.listView != null }
        pickRoleItem(1) // אריזה

        awaitUntil { showingDialog()?.findViewById<EditText>(R.id.etPackageContentPrompt) != null }
        showingDialog()!!.findViewById<EditText>(R.id.etPackageContentPrompt)!!.setText("12")
        showingDialog()!!.getButton(DialogInterface.BUTTON_POSITIVE).performClick()

        awaitUntil { shadowOf(activity).peekNextStartedActivityForResult() != null }

        val stored = runBlocking { AppDatabase.getInstance(context).barcodeDao().findByBarcode("999") }!!
        assertEquals(BarcodeEntity.ROLE_PACKAGE, stored.role)
        assertEquals(12, stored.packageContent)
        assertEquals("ABC-123", stored.sku)
    }

    /** Once per ברקוד for the whole count, not once per scan. */
    @Test
    fun `a barcode already on file raises no question`() {
        runBlocking {
            val db = AppDatabase.getInstance(context)
            db.productDao().insertAll(listOf(ProductEntity("ABC-123", "מוצר", "111", "", 0)))
            db.barcodeDao().insert(BarcodeEntity(barcode = "111", sku = "ABC-123"))
        }

        val activity = confirmScreenFor("ABC-123", "111", "A-01-05")
        activity.findViewById<Button>(R.id.btnConfirm).performClick()

        awaitUntil { shadowOf(activity).peekNextStartedActivityForResult() != null }
        assertEquals(null, showingDialog()?.listView)
    }

    /**
     * "לא יודע" is a real answer, not a failure: the scan goes through, the
     * code stays a plain unit, and the question comes back next time.
     */
    @Test
    fun `declining to describe a barcode still lets the scan through`() {
        runBlocking {
            AppDatabase.getInstance(context).productDao().insertAll(
                listOf(ProductEntity("ABC-123", "מוצר", "", "", 0))
            )
        }

        val activity = confirmScreenFor("ABC-123", "999", "A-01-05")
        activity.findViewById<Button>(R.id.btnConfirm).performClick()

        awaitUntil { showingDialog()?.listView != null }
        pickRoleItem(3) // לא יודע

        awaitUntil { shadowOf(activity).peekNextStartedActivityForResult() != null }

        val stored = runBlocking { AppDatabase.getInstance(context).barcodeDao().findByBarcode("999") }!!
        assertEquals(BarcodeEntity.ROLE_UNIT, stored.role)
        assertEquals(0, stored.packageContent)
    }
}
