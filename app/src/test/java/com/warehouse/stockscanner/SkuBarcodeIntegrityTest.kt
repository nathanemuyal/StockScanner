package com.warehouse.stockscanner

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.warehouse.stockscanner.data.AppDatabase
import com.warehouse.stockscanner.data.ProductEntity
import com.warehouse.stockscanner.data.SessionPrefs
import com.warehouse.stockscanner.excel.ExcelReader
import com.warehouse.stockscanner.excel.ExcelWriter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.io.FileOutputStream

/**
 * Regression coverage for a reported bug: the locations/quantities working
 * file was seen with the מקט value sitting in the ברקוד column instead of
 * the barcode that was actually scanned. Every realistic journey a scan can
 * take — a fresh product, a second location for a known product, a second
 * (aliased) barcode for a known product, the "not found -> search by
 * description" fallback, and a deliberate barcode reassignment — is driven
 * end-to-end through the real screens here (not just ProductRepository
 * directly), and the physical Excel output is inspected afterwards, since
 * that's exactly what the bug report was about: what actually lands in the
 * saved file, not just what the repository functions do in isolation.
 *
 * All setup goes through [context]'s own `repository` (via [loadCatalog] and
 * [confirmAndRecordQuantity]) rather than a separately-fetched
 * `AppDatabase.getInstance(context)` DAO — those two can reference different
 * underlying Room instances depending on Robolectric's Application lifecycle
 * for a given test run, which silently makes seeded data invisible to the
 * screens under test. Routing everything through the same `repository`
 * object the Activities themselves use avoids that class of flake entirely.
 */
@RunWith(RobolectricTestRunner::class)
class SkuBarcodeIntegrityTest {

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

    private fun awaitUntil(timeoutMs: Long = 6000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        check(condition()) { "condition not met within ${timeoutMs}ms" }
    }

    private fun savedLocationsRows(): List<ProductEntity> {
        val file = context.repository.locationsQuantitiesFile()!!
        return file.inputStream().use { ExcelReader.readProductsFromStream(it) }.products
    }

    /**
     * Loads [products] as the source catalog through the repository itself
     * (exactly like picking a file via SAF would) — this is the only way
     * seeded data is guaranteed visible to the same repository instance the
     * screens under test will use. [workingFileTag] keeps the derived
     * working-file name distinct per test (it's otherwise date-based, not
     * unique per run).
     */
    private fun loadCatalog(products: List<ProductEntity>, workingFileTag: String) {
        val sourceFile = File.createTempFile("source", ".xlsx", context.cacheDir)
        FileOutputStream(sourceFile).use { ExcelWriter.writeProductsToStream(it, products) }
        runBlocking { context.repository.loadFromExcel(Uri.fromFile(sourceFile), "$workingFileTag.xlsx") }
    }

    /** Drives ProductConfirmActivity -> InventoryActivity for [sku]/[scannedBarcode]/[location], entering [quantity]. */
    private fun confirmAndRecordQuantity(
        sku: String,
        description: String,
        existingBarcode: String,
        scannedBarcode: String,
        location: String,
        quantity: Int
    ) {
        val confirmIntent = Intent(context, ProductConfirmActivity::class.java)
            .putExtra(ProductConfirmActivity.EXTRA_SKU, sku)
            .putExtra(ProductConfirmActivity.EXTRA_DESCRIPTION, description)
            .putExtra(ProductConfirmActivity.EXTRA_EXISTING_BARCODE, existingBarcode)
            .putExtra(ProductConfirmActivity.EXTRA_SCANNED_BARCODE, scannedBarcode)
            .putStringArrayListExtra(ProductConfirmActivity.EXTRA_EXISTING_LOCATIONS, ArrayList())
            .putExtra(ProductConfirmActivity.EXTRA_CURRENT_LOCATION, location)

        val confirmActivity = Robolectric.buildActivity(ProductConfirmActivity::class.java, confirmIntent).setup().get()
        confirmActivity.findViewById<Button>(R.id.btnConfirm).performClick()

        awaitUntil { shadowOf(confirmActivity).peekNextStartedActivityForResult() != null }
        val inventoryIntent = shadowOf(confirmActivity).nextStartedActivityForResult.intent

        val inventoryActivity = Robolectric.buildActivity(InventoryActivity::class.java, inventoryIntent).setup().get()
        // InventoryActivity.prefillFromExistingRow() runs asynchronously and
        // itself calls etQuantity.setText(...) once its own DB read
        // completes — setting our own value before that finishes is a race
        // (it can get silently overwritten). Wait for the prefill to land
        // first, exactly like InventoryActivityTest's own helper does.
        awaitUntil {
            inventoryActivity.findViewById<RadioButton>(R.id.rbUnits).isChecked ||
                inventoryActivity.findViewById<RadioButton>(R.id.rbPackage).isChecked
        }
        inventoryActivity.findViewById<EditText>(R.id.etQuantity).setText(quantity.toString())
        inventoryActivity.findViewById<Button>(R.id.btnSaveInventory).performClick()
        awaitUntil { inventoryActivity.isFinishing }
        // Robolectric's "next started activity" queue is shared/global across
        // every Robolectric.buildActivity() instance in this test method, not
        // scoped to confirmActivity/inventoryActivity individually — drain
        // whatever this call queued up (ProductConfirmActivity's own
        // inventoryLauncher.launch(...) records here too, alongside the
        // ...ForResult() queue this helper already consumes above) so it
        // doesn't get mistaken later for some other activity's launch.
        while (shadowOf(inventoryActivity).peekNextStartedActivity() != null) {
            shadowOf(inventoryActivity).nextStartedActivity
        }
    }

    // --- 1. A brand-new product, scanned for the first time -----------------

    @Test
    fun `a fresh product's saved row carries the scanned barcode, never the sku`() {
        loadCatalog(listOf(ProductEntity("SKU-9", "מוצר בדיקה", "", "", 0)), "sku-barcode-fresh-product")
        val realBarcode = "7290099998887"

        confirmAndRecordQuantity("SKU-9", "מוצר בדיקה", "", realBarcode, "A-01-01", 3)

        val row = savedLocationsRows().single()
        assertEquals("SKU-9", row.sku)
        assertEquals(realBarcode, row.barcode)
        assertNotEquals("the barcode column must never equal the sku", row.sku, row.barcode)
    }

    // --- 2. The same product confirmed again at a second location -----------

    @Test
    fun `a second location for an already-known product keeps the same real barcode on both rows`() {
        val realBarcode = "111222333"
        loadCatalog(listOf(ProductEntity("ABC-123", "מצבר 12V", "", "", 0)), "sku-barcode-second-location")

        // First scan establishes the primary barcode at the first location...
        confirmAndRecordQuantity("ABC-123", "מצבר 12V", "", realBarcode, "A-01-05", 4)
        // ...confirmed again at a brand-new location, same physical barcode.
        confirmAndRecordQuantity("ABC-123", "מצבר 12V", realBarcode, realBarcode, "B-02-01", 5)

        val rows = savedLocationsRows()
        assertEquals(2, rows.size)
        for (row in rows) {
            assertEquals("ABC-123", row.sku)
            assertEquals(realBarcode, row.barcode)
            assertNotEquals(row.sku, row.barcode)
        }
    }

    // --- 3. A second, different barcode scanned for a known sku -------------

    @Test
    fun `a different barcode scanned at a location the sku already has opens its own row, neither row's barcode is ever the sku`() {
        val primaryBarcode = "111"
        val secondBarcode = "222"
        loadCatalog(listOf(ProductEntity("ABC-123", "מצבר 12V", "", "", 0)), "sku-barcode-alias")

        // First scan establishes the first barcode at this location...
        confirmAndRecordQuantity("ABC-123", "מצבר 12V", "", primaryBarcode, "A-01-05", 4)
        // ...same location re-confirmed, but with a different scanned barcode
        // (e.g. a second barcode sticker on the very same product/location).
        confirmAndRecordQuantity("ABC-123", "מצבר 12V", primaryBarcode, secondBarcode, "A-01-05", 5)

        // Both barcodes now have their own row at that location — a distinct
        // row per scan, never siloed into a separate "aliases" file.
        val rows = savedLocationsRows()
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.sku == "ABC-123" && it.location == "A-01-05" })
        assertEquals(setOf(primaryBarcode, secondBarcode), rows.map { it.barcode }.toSet())
        assertTrue("neither row's barcode column may ever be the sku", rows.none { it.barcode == it.sku })

        val aliasFile = context.repository.multipleBarcodesFile()!!
        val aliases = aliasFile.inputStream().use { ExcelReader.readMultipleBarcodesFromStream(it) }
        assertTrue("a normal scan no longer writes to the aliases file", aliases.isEmpty())
    }

    // --- 4. Barcode not found -> search by description -> confirm -----------

    @Test
    fun `MainActivity wires the not-found-then-search fallback with the actually-scanned barcode, never the sku`() {
        val unrecognizedBarcode = "9998887776665"
        loadCatalog(listOf(ProductEntity("XYZ-999", "מגב שמשה", "", "", 0)), "sku-barcode-wiring")
        SessionPrefs(context).currentLocation = "C-03-01"

        val mainActivity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        mainActivity.findViewById<Button>(R.id.btnScanProduct).performClick()

        // ScannerActivity "returns" a barcode nothing in the DB matches.
        val scanStarted = shadowOf(mainActivity).nextStartedActivityForResult
        shadowOf(mainActivity).receiveResult(
            scanStarted.intent, Activity.RESULT_OK,
            Intent().putExtra(ScannerActivity.EXTRA_VALUE, unrecognizedBarcode)
        )

        awaitUntil { ShadowDialog.getLatestDialog() != null }
        val notFoundDialog = ShadowDialog.getLatestDialog() as AlertDialog
        notFoundDialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick() // "חפש מוצר לפי תיאור"

        awaitUntil { shadowOf(mainActivity).peekNextStartedActivityForResult() != null }
        val searchStarted = shadowOf(mainActivity).nextStartedActivityForResult
        assertEquals(SearchActivity::class.java.name, searchStarted.intent.component?.className)

        // The worker picks "XYZ-999" from the description search results.
        shadowOf(mainActivity).receiveResult(
            searchStarted.intent, Activity.RESULT_OK,
            Intent().putExtra(SearchActivity.EXTRA_SELECTED_SKU, "XYZ-999")
        )

        awaitUntil { shadowOf(mainActivity).peekNextStartedActivityForResult() != null }
        val confirmStarted = shadowOf(mainActivity).nextStartedActivityForResult
        assertEquals(ProductConfirmActivity::class.java.name, confirmStarted.intent.component?.className)
        assertEquals("XYZ-999", confirmStarted.intent.getStringExtra(ProductConfirmActivity.EXTRA_SKU))
        // The screen must have been handed the barcode that was actually
        // scanned (and not found) — never the sku just picked from search.
        assertEquals(unrecognizedBarcode, confirmStarted.intent.getStringExtra(ProductConfirmActivity.EXTRA_SCANNED_BARCODE))
    }

    /**
     * The same scenario one layer down, driven deterministically through
     * ProductConfirmActivity/InventoryActivity directly (as MainActivity's
     * wiring above hands off to them) — this is what actually determines
     * what lands in the saved Excel row.
     */
    @Test
    fun `the not-found-then-search fallback saves the originally scanned barcode in the Excel row, not the sku picked from search`() {
        val unrecognizedBarcode = "9998887776665"
        loadCatalog(listOf(ProductEntity("XYZ-999", "מגב שמשה", "", "", 0)), "sku-barcode-search-fallback")

        // Exactly what MainActivity hands ProductConfirmActivity in this flow:
        // the sku just chosen from search, but the barcode that was actually
        // scanned (and not recognized) moments earlier.
        confirmAndRecordQuantity("XYZ-999", "מגב שמשה", "", unrecognizedBarcode, "C-03-01", 2)

        val row = savedLocationsRows().single()
        assertEquals("XYZ-999", row.sku)
        assertEquals("the saved row must carry the barcode that was actually scanned, not the sku chosen via search", unrecognizedBarcode, row.barcode)
        assertNotEquals(row.sku, row.barcode)
    }

    // --- 5. Reassigning a mistaken barcode to the right sku ------------------

    @Test
    fun `reassigning a barcode to the correct sku never turns the barcode into the sku value`() {
        val scannedBarcode = "555444333"
        loadCatalog(
            listOf(
                ProductEntity("WRONG-1", "מוצר שגוי", "", "", 0),
                ProductEntity("RIGHT-1", "מוצר נכון", "", "", 1)
            ),
            "sku-barcode-reassign"
        )
        // WRONG-1 is scanned first (mistakenly matched to this barcode)...
        confirmAndRecordQuantity("WRONG-1", "מוצר שגוי", "", scannedBarcode, "A-01-05", 1)
        // ...and RIGHT-1 already sits at a location of its own, no barcode yet.
        confirmAndRecordQuantity("RIGHT-1", "מוצר נכון", "", "", "B-02-01", 1)

        val confirmIntent = Intent(context, ProductConfirmActivity::class.java)
            .putExtra(ProductConfirmActivity.EXTRA_SKU, "WRONG-1")
            .putExtra(ProductConfirmActivity.EXTRA_DESCRIPTION, "מוצר שגוי")
            .putExtra(ProductConfirmActivity.EXTRA_EXISTING_BARCODE, scannedBarcode)
            .putExtra(ProductConfirmActivity.EXTRA_SCANNED_BARCODE, scannedBarcode)
            .putStringArrayListExtra(ProductConfirmActivity.EXTRA_EXISTING_LOCATIONS, ArrayList())
            .putExtra(ProductConfirmActivity.EXTRA_CURRENT_LOCATION, "A-01-05")

        val confirmActivity = Robolectric.buildActivity(ProductConfirmActivity::class.java, confirmIntent).setup().get()
        confirmActivity.findViewById<Button>(R.id.btnChangeSku).performClick()
        val searchStarted = shadowOf(confirmActivity).nextStartedActivityForResult

        shadowOf(confirmActivity).receiveResult(
            searchStarted.intent, Activity.RESULT_OK,
            Intent().putExtra(SearchActivity.EXTRA_SELECTED_SKU, "RIGHT-1")
        )

        awaitUntil { ShadowDialog.getLatestDialog() != null }
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()

        // The screen restarts itself for RIGHT-1 — drain the stale queued
        // launch, then read the reopened intent, exactly like ProductConfirmActivityTest does.
        awaitUntil { shadowOf(confirmActivity).peekNextStartedActivity() != null }
        shadowOf(confirmActivity).nextStartedActivity
        awaitUntil { shadowOf(confirmActivity).peekNextStartedActivity() != null }
        val reopenedIntent = shadowOf(confirmActivity).nextStartedActivity
        assertEquals(scannedBarcode, reopenedIntent.getStringExtra(ProductConfirmActivity.EXTRA_SCANNED_BARCODE))

        val reopenedActivity = Robolectric.buildActivity(ProductConfirmActivity::class.java, reopenedIntent).setup().get()
        reopenedActivity.findViewById<Button>(R.id.btnConfirm).performClick()

        awaitUntil { shadowOf(reopenedActivity).peekNextStartedActivityForResult() != null }
        val inventoryIntent = shadowOf(reopenedActivity).nextStartedActivityForResult.intent
        val inventoryActivity = Robolectric.buildActivity(InventoryActivity::class.java, inventoryIntent).setup().get()
        awaitUntil {
            inventoryActivity.findViewById<RadioButton>(R.id.rbUnits).isChecked ||
                inventoryActivity.findViewById<RadioButton>(R.id.rbPackage).isChecked
        }
        inventoryActivity.findViewById<EditText>(R.id.etQuantity).setText("7")
        inventoryActivity.findViewById<Button>(R.id.btnSaveInventory).performClick()
        awaitUntil { inventoryActivity.isFinishing }

        // RIGHT-1 now has two scanned rows: its original B-02-01 one
        // (unrelated, still blank barcode) and the new A-01-05 one carrying
        // the reassigned barcode — neither is ever the sku itself.
        val rightRows = savedLocationsRows().filter { it.sku == "RIGHT-1" }
        assertEquals(2, rightRows.size)
        assertEquals(scannedBarcode, rightRows.single { it.location == "A-01-05" }.barcode)
        assertEquals("", rightRows.single { it.location == "B-02-01" }.barcode)
        assertTrue(rightRows.none { it.barcode == it.sku })

        // WRONG-1 lost the barcode (moved away), but its own sku must never
        // have leaked into its barcode column either.
        val wrongProduct = runBlocking { context.repository.findBySku("WRONG-1") }!!
        assertNotEquals("WRONG-1", wrongProduct.barcode)
    }
}
