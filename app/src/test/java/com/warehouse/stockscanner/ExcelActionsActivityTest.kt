package com.warehouse.stockscanner

import android.net.Uri
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.warehouse.stockscanner.data.AppDatabase
import com.warehouse.stockscanner.data.ProductEntity
import com.warehouse.stockscanner.excel.ExcelReader
import com.warehouse.stockscanner.excel.ExcelWriter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.io.FileOutputStream

/**
 * The dedicated "פעולות Excel" screen: it must display the file/product
 * info that's actually loaded (not just static placeholder text), must
 * create the two split working files (locations/quantities and multiple
 * barcodes) the moment a source file is picked, and saving must be a
 * deliberate action that only succeeds — and only writes anything — when
 * there's data to save.
 */
@RunWith(RobolectricTestRunner::class)
class ExcelActionsActivityTest {

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

    /** Loads [products] through the repository exactly as picking a file via SAF would, including creating the two working files. */
    private fun loadSourceFile(products: List<ProductEntity>, displayName: String = "products.xlsx") {
        val sourceFile = File.createTempFile("source", ".xlsx", context.cacheDir)
        FileOutputStream(sourceFile).use { ExcelWriter.writeProductsToStream(it, products) }
        val uri = Uri.fromFile(sourceFile)
        runBlocking { context.repository.loadFromExcel(uri, displayName) }
    }

    @Test
    fun `shows no file selected and zero products before anything is loaded`() {
        val activity = Robolectric.buildActivity(ExcelActionsActivity::class.java).setup().get()
        val tvTotal = activity.findViewById<TextView>(R.id.tvTotalProducts)
        val tvFileName = activity.findViewById<TextView>(R.id.tvFileName)

        awaitUntil { tvTotal.text.toString() == "מוצרים בקובץ: 0" }
        assertEquals("קובץ נבחר: לא נבחר", tvFileName.text.toString())
    }

    @Test
    fun `reflects the file name and product count already loaded through the repository`() {
        loadSourceFile(
            listOf(
                ProductEntity("A", "מוצר א", "", "A-01-01", 0),
                ProductEntity("B", "מוצר ב", "", "", 1)
            )
        )

        val activity = Robolectric.buildActivity(ExcelActionsActivity::class.java).setup().get()
        val tvTotal = activity.findViewById<TextView>(R.id.tvTotalProducts)
        val tvFileName = activity.findViewById<TextView>(R.id.tvFileName)

        awaitUntil { tvTotal.text.toString() == "מוצרים בקובץ: 2" }
        assertEquals("קובץ נבחר: products.xlsx", tvFileName.text.toString())
    }

    @Test
    fun `picking a source file creates and displays both working files right away`() {
        loadSourceFile(listOf(ProductEntity("A", "מוצר א", "", "A-01-01", 0)), "מלאי מרץ.xlsx")

        val activity = Robolectric.buildActivity(ExcelActionsActivity::class.java).setup().get()
        val tvLocations = activity.findViewById<TextView>(R.id.tvLocationsFile)
        val tvBarcodes = activity.findViewById<TextView>(R.id.tvBarcodesFile)

        awaitUntil { tvLocations.text.toString() != "קובץ מיקומים וכמויות: לא נוצר" }
        assertTrue(tvLocations.text.toString().contains("original_locations_quantities.xlsx"))
        assertTrue(tvBarcodes.text.toString().contains("original_multiple_barcodes.xlsx"))
        assertNotNull(context.repository.locationsQuantitiesFile())
        assertNotNull(context.repository.multipleBarcodesFile())
        assertTrue(context.repository.locationsQuantitiesFile()!!.exists())
        assertTrue(context.repository.multipleBarcodesFile()!!.exists())
    }

    @Test
    fun `saving with nothing loaded warns instead of writing anything`() {
        val activity = Robolectric.buildActivity(ExcelActionsActivity::class.java).setup().get()
        activity.findViewById<Button>(R.id.btnSaveExcel).performClick()

        awaitUntil { ShadowToast.getTextOfLatestToast() != null }
        assertEquals("אין נתונים לשמירה, טען קובץ Excel קודם", ShadowToast.getTextOfLatestToast())
        assertNull(context.repository.locationsQuantitiesFile())
        assertNull(context.repository.multipleBarcodesFile())
    }

    @Test
    fun `saving after loading writes both working files and confirms success`() {
        loadSourceFile(listOf(ProductEntity("A", "מוצר א", "", "A-01-01", 0)))
        // The locations/quantities file is a log of what's actually been
        // scanned, not a copy of the picked source file — so scan it first.
        runBlocking { context.repository.updateProduct("A", "מוצר א", "", "A-01-01") }

        val activity = Robolectric.buildActivity(ExcelActionsActivity::class.java).setup().get()
        ShadowToast.reset()
        activity.findViewById<Button>(R.id.btnSaveExcel).performClick()

        awaitUntil { ShadowToast.getTextOfLatestToast() != null }
        assertEquals("הקובץ נשמר בהצלחה", ShadowToast.getTextOfLatestToast())

        val locationsFile = context.repository.locationsQuantitiesFile()!!
        val products = locationsFile.inputStream().use { ExcelReader.readProductsFromStream(it) }.products
        assertEquals(1, products.size)
        assertEquals("A", products.first().sku)
        assertTrue(context.repository.multipleBarcodesFile()!!.exists())
    }

    /**
     * The source file merely listing a מיקום must not be enough on its own
     * — the working file only ever reflects rows actually confirmed via an
     * in-app scan (see [com.warehouse.stockscanner.data.ProductEntity.scanned]).
     */
    @Test
    fun `saving right after loading, with nothing actually scanned yet, writes an empty log`() {
        loadSourceFile(listOf(ProductEntity("A", "מוצר א", "", "A-01-01", 0)))

        val activity = Robolectric.buildActivity(ExcelActionsActivity::class.java).setup().get()
        ShadowToast.reset()
        activity.findViewById<Button>(R.id.btnSaveExcel).performClick()

        awaitUntil { ShadowToast.getTextOfLatestToast() != null }
        val locationsFile = context.repository.locationsQuantitiesFile()!!
        val products = locationsFile.inputStream().use { ExcelReader.readProductsFromStream(it) }.products
        assertTrue("nothing was scanned, so the saved file must have no product rows", products.isEmpty())
    }

    @Test
    fun `create-locations-file button re-derives just that file when a source is already loaded`() {
        loadSourceFile(listOf(ProductEntity("A", "מוצר א", "111", "A-01-01", 0)))
        runBlocking { context.repository.updateProduct("A", "מוצר א", "111", "B-02-01") } // in memory only so far

        val activity = Robolectric.buildActivity(ExcelActionsActivity::class.java).setup().get()
        ShadowToast.reset()
        activity.findViewById<Button>(R.id.btnCreateLocationsFile).performClick()

        awaitUntil { ShadowToast.getTextOfLatestToast() != null }
        // Only the newly-scanned B-02-01 row shows up — A-01-01 came only
        // from the source file and was never itself (re)scanned.
        val products = context.repository.locationsQuantitiesFile()!!
            .inputStream().use { ExcelReader.readProductsFromStream(it) }.products
        assertEquals(1, products.size)
        assertEquals("B-02-01", products.single().location)
    }

    @Test
    fun `create-barcodes-file button without any source loaded opens the file picker instead of crashing`() {
        val activity = Robolectric.buildActivity(ExcelActionsActivity::class.java).setup().get()
        activity.findViewById<Button>(R.id.btnCreateBarcodesFile).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        // No source picked (no fake SAF result delivered) -> nothing should
        // have been created, and the activity must not have crashed.
        assertNull(context.repository.locationsQuantitiesFile())
        assertTrue(!activity.isFinishing)
    }

    @Test
    fun `exporting with no working files yet warns instead of crashing`() {
        val activity = Robolectric.buildActivity(ExcelActionsActivity::class.java).setup().get()
        activity.findViewById<Button>(R.id.btnExportFiles).performClick()

        awaitUntil { ShadowToast.getTextOfLatestToast() != null }
        assertEquals("אין עדיין קבצים לייצוא — יש ליצור אותם קודם", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `exporting after loading starts a share chooser for both working files`() {
        loadSourceFile(listOf(ProductEntity("A", "מוצר א", "", "A-01-01", 0)))

        val activity = Robolectric.buildActivity(ExcelActionsActivity::class.java).setup().get()
        activity.findViewById<Button>(R.id.btnExportFiles).performClick()

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull("expected a share chooser to be started", started)
        assertEquals(android.content.Intent.ACTION_CHOOSER, started.action)
    }

    @Test
    fun `the back button closes the screen`() {
        val activity = Robolectric.buildActivity(ExcelActionsActivity::class.java).setup().get()
        activity.findViewById<Button>(R.id.btnCloseExcelActions).performClick()

        assertEquals(true, activity.isFinishing)
    }
}
