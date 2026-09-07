package com.warehouse.stockscanner

import android.net.Uri
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.warehouse.stockscanner.data.AppDatabase
import com.warehouse.stockscanner.data.ProductEntity
import com.warehouse.stockscanner.data.SessionPrefs
import com.warehouse.stockscanner.excel.ExcelReader
import com.warehouse.stockscanner.excel.ExcelWriter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 * info that's actually loaded (not just static placeholder text), and
 * saving must be a deliberate action that only succeeds — and only writes
 * anything — when there's data to save.
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

    private fun loadSourceFile(products: List<ProductEntity>, displayName: String = "products.xlsx") {
        val sourceFile = File.createTempFile("source", ".xlsx", context.cacheDir)
        FileOutputStream(sourceFile).use { ExcelWriter.writeProductsToStream(it, products) }
        val uri = Uri.fromFile(sourceFile)
        runBlocking { context.repository.loadFromExcel(uri) }
        SessionPrefs(context).resetForNewFile(displayName, uri.toString())
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
    fun `saving with nothing loaded warns instead of writing anything`() {
        val workingCopy = File(context.filesDir, "working_products.xlsx")

        val activity = Robolectric.buildActivity(ExcelActionsActivity::class.java).setup().get()
        activity.findViewById<Button>(R.id.btnSaveExcel).performClick()

        awaitUntil { ShadowToast.getTextOfLatestToast() != null }
        assertEquals("אין נתונים לשמירה, טען קובץ Excel קודם", ShadowToast.getTextOfLatestToast())
        assertEquals(false, workingCopy.exists())
    }

    @Test
    fun `saving after loading writes the working copy and confirms success`() {
        loadSourceFile(listOf(ProductEntity("A", "מוצר א", "", "A-01-01", 0)))

        val activity = Robolectric.buildActivity(ExcelActionsActivity::class.java).setup().get()
        ShadowToast.reset()
        activity.findViewById<Button>(R.id.btnSaveExcel).performClick()

        awaitUntil { ShadowToast.getTextOfLatestToast() != null }
        assertEquals("הקובץ נשמר בהצלחה", ShadowToast.getTextOfLatestToast())

        val workingCopy = File(context.filesDir, "working_products.xlsx")
        val products = workingCopy.inputStream().use { ExcelReader.readProductsFromStream(it) }.products
        assertEquals(1, products.size)
        assertEquals("A", products.first().sku)
    }

    @Test
    fun `the back button closes the screen`() {
        val activity = Robolectric.buildActivity(ExcelActionsActivity::class.java).setup().get()
        activity.findViewById<Button>(R.id.btnCloseExcelActions).performClick()

        assertEquals(true, activity.isFinishing)
    }
}
