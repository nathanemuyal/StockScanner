package com.warehouse.stockscanner

import android.content.DialogInterface
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.warehouse.stockscanner.data.AppDatabase
import com.warehouse.stockscanner.data.ProductEntity
import com.warehouse.stockscanner.data.SessionPrefs
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
import org.robolectric.shadows.ShadowDialog

/**
 * Confirms the home screen only ever offers the Excel-actions entry point
 * while no location is currently being scanned — loading a different
 * products file or saving mid-location must not be reachable by mistake.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    @Before
    fun setUp() {
        // Each Robolectric test method gets a fresh Application/storage; make
        // sure the Room singleton doesn't keep pointing at a previous test's.
        AppDatabase.resetForTests()
    }

    @After
    fun tearDown() {
        AppDatabase.resetForTests()
    }

    /** Room's suspend queries hop onto a real background thread; poll instead of asserting immediately. */
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
    fun `Excel actions entry point is visible when no location is active`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SessionPrefs(context).currentLocation = null

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val btn = activity.findViewById<View>(R.id.btnExcelActions)

        awaitUntil { btn.visibility == View.VISIBLE }
    }

    @Test
    fun `Excel actions entry point is hidden while a location is being scanned`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SessionPrefs(context).currentLocation = "A-01-05"

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val btn = activity.findViewById<View>(R.id.btnExcelActions)

        awaitUntil { btn.visibility == View.GONE }
    }

    @Test
    fun `finishing the location brings the Excel actions button back`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = SessionPrefs(context)
        prefs.currentLocation = "A-01-05"

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val btnExcelActions = activity.findViewById<View>(R.id.btnExcelActions)
        awaitUntil { btnExcelActions.visibility == View.GONE }

        activity.findViewById<View>(R.id.btnFinishLocation).performClick()

        awaitUntil { btnExcelActions.visibility == View.VISIBLE }
        assertNull(prefs.currentLocation)
    }

    @Test
    fun `returning to the screen after a location was set elsewhere hides the button on resume`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = SessionPrefs(context)
        prefs.currentLocation = null

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val btnExcelActions = controller.get().findViewById<View>(R.id.btnExcelActions)
        awaitUntil { btnExcelActions.visibility == View.VISIBLE }

        // Mirrors scanLocationLauncher's callback setting a scanned location,
        // then the activity resuming (as it does once that screen returns).
        prefs.currentLocation = "B-02-01"
        controller.pause().resume()

        awaitUntil { btnExcelActions.visibility == View.GONE }
        assertEquals(View.GONE, btnExcelActions.visibility)
    }

    @Test
    fun `confirming removal takes a product off the shelf list for the current location`() {
        val context = ApplicationProvider.getApplicationContext<StockScannerApp>()
        runBlocking {
            AppDatabase.getInstance(context).productDao().insertAll(
                listOf(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0))
            )
        }
        SessionPrefs(context).currentLocation = "A-01-05"

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val recycler = activity.findViewById<RecyclerView>(R.id.recyclerScannedProducts)
        awaitUntil { recycler.adapter?.itemCount == 1 }

        recycler.findViewHolderForAdapterPosition(0)!!.itemView.performClick()

        awaitUntil { ShadowDialog.getLatestDialog() != null }
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()

        awaitUntil { recycler.visibility == View.GONE }
        val remaining = runBlocking {
            AppDatabase.getInstance(context).productDao().findAllBySku("ABC-123")
        }.single()
        assertEquals("", remaining.location) // sku's only row, so it's cleared rather than deleted
    }

    @Test
    fun `declining removal leaves the product on the shelf list`() {
        val context = ApplicationProvider.getApplicationContext<StockScannerApp>()
        runBlocking {
            AppDatabase.getInstance(context).productDao().insertAll(
                listOf(ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0))
            )
        }
        SessionPrefs(context).currentLocation = "A-01-05"

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val recycler = activity.findViewById<RecyclerView>(R.id.recyclerScannedProducts)
        awaitUntil { recycler.adapter?.itemCount == 1 }

        recycler.findViewHolderForAdapterPosition(0)!!.itemView.performClick()

        awaitUntil { ShadowDialog.getLatestDialog() != null }
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, recycler.adapter?.itemCount)
        val untouched = runBlocking {
            AppDatabase.getInstance(context).productDao().findAllBySku("ABC-123")
        }.single()
        assertEquals("A-01-05", untouched.location)
    }
}
