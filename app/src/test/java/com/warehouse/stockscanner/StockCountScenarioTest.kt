package com.warehouse.stockscanner

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.warehouse.stockscanner.data.AppDatabase
import com.warehouse.stockscanner.data.BarcodeEntity
import com.warehouse.stockscanner.data.ProductEntity
import com.warehouse.stockscanner.data.ProductRepository
import com.warehouse.stockscanner.data.SessionPrefs
import com.warehouse.stockscanner.excel.ExcelReader
import com.warehouse.stockscanner.excel.ExcelWriter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * End-to-end cover for the counting situations this app was actually built
 * for, driven through the repository the screens call rather than against
 * the dao — a load, then scans, then the files a worker hands back.
 *
 * Each test is one situation a warehouse really produces: the same מקט on
 * two shelves; a package code and a single-unit code on one shelf; one code
 * serving both; a code first seen mid-count. They exist because those cases
 * only work if several separate pieces agree — the row-per-(sku, location,
 * barcode) rule, the barcodes table, the emptying rule and the summary —
 * and a unit test of any one of them can pass while the combination is
 * wrong.
 */
@RunWith(RobolectricTestRunner::class)
class StockCountScenarioTest {

    private lateinit var context: android.content.Context
    private lateinit var db: AppDatabase
    private lateinit var repository: ProductRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = ProductRepository(context, db.productDao(), db.barcodeDao(), SessionPrefs(context))
    }

    @After
    fun tearDown() = db.close()

    /** Writes a real .xlsx and loads it exactly as picking a file would. */
    private fun load(products: List<ProductEntity>, barcodes: List<BarcodeEntity> = emptyList()) = runBlocking {
        val file = File.createTempFile("source", ".xlsx", context.cacheDir)
        FileOutputStream(file).use { ExcelWriter.writeProductsToStream(it, products, barcodes) }
        repository.loadFromExcel(Uri.fromFile(file), "stock.xlsx")
    }

    /** One scan all the way through, in package or mixed mode. */
    private fun scanAndCount(
        sku: String,
        description: String,
        barcode: String,
        location: String,
        type: String,
        packageContent: Int,
        packageCount: Int,
        looseUnits: Int = 0
    ) = runBlocking {
        repository.updateProduct(sku, description, barcode, location)
        repository.updateQuantity(
            sku, location, barcode, type, packageContent, packageCount, looseUnits,
            ProductEntity.totalUnits(type, packageContent, packageCount, looseUnits, 0)
        )
    }

    /** One scan all the way through, as a plain unit count. */
    private fun scanAndCountUnits(sku: String, description: String, barcode: String, location: String, units: Int) =
        runBlocking {
            repository.updateProduct(sku, description, barcode, location)
            repository.updateQuantity(sku, location, barcode, ProductEntity.TYPE_UNITS, 0, 0, 0, units)
        }

    private fun savedDetailRows(): List<ProductEntity> = runBlocking {
        repository.saveWorkingCopies()
        repository.locationsQuantitiesFile()!!.inputStream().use { ExcelReader.readProductsFromStream(it) }.products
    }

    /** The "סיכום" sheet as raw cell rows, header included. */
    private fun savedSummaryRows(): List<List<String>> {
        runBlocking { repository.saveWorkingCopies() }
        val rows = mutableListOf<List<String>>()
        ZipInputStream(repository.locationsQuantitiesFile()!!.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "xl/worksheets/sheet2.xml") {
                    val xml = zip.readBytes().toString(Charsets.UTF_8)
                    for (row in Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
                        rows += Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                            .findAll(row.groupValues[1]).map { it.groupValues[1] }.toList()
                    }
                }
                entry = zip.nextEntry
            }
        }
        return rows
    }

    private fun summaryTotalFor(sku: String, location: String): String? =
        savedSummaryRows().drop(1).firstOrNull { it[0] == sku && it[2] == location }?.get(3)

    // ---------- the same מקט on two shelves ----------

    /**
     * The original question. Two shelves, two counts, neither touching the
     * other — plus a total, because that is what the count is for.
     */
    @Test
    fun `the same sku counted on two shelves keeps both counts and totals them`() {
        load(listOf(ProductEntity("ABC-123", "פילטר", "111", "", 0)))

        scanAndCountUnits("ABC-123", "פילטר", "111", "A-01", 40)
        scanAndCountUnits("ABC-123", "פילטר", "111", "B-03", 15)

        val rows = savedDetailRows()
        assertEquals(2, rows.size)
        assertEquals(40, rows.first { it.location == "A-01" }.quantity)
        assertEquals(15, rows.first { it.location == "B-03" }.quantity)

        assertEquals("40", summaryTotalFor("ABC-123", "A-01"))
        assertEquals("15", summaryTotalFor("ABC-123", "B-03"))
        assertEquals("55", summaryTotalFor("ABC-123", "סה״כ"))
    }

    // ---------- a package code and a unit code ----------

    /**
     * The case that started the barcode work: one shelf, two stickers, one
     * on cartons of twelve and one on loose singles. Both must be counted,
     * both must stay their own row, and the total must come out in units.
     */
    @Test
    fun `a package code and a unit code on one shelf are counted separately and summed in units`() {
        load(
            listOf(ProductEntity("ABC-123", "פילטר", "B-UNIT", "", 0)),
            listOf(
                BarcodeEntity(barcode = "B-PACK", sku = "ABC-123", role = BarcodeEntity.ROLE_PACKAGE, packageContent = 12),
                BarcodeEntity(barcode = "B-UNIT", sku = "ABC-123", role = BarcodeEntity.ROLE_UNIT)
            )
        )

        // 3 cartons of 12...
        scanAndCount("ABC-123", "פילטר", "B-PACK", "A-01", ProductEntity.TYPE_PACKAGE, 12, 3)
        // ...and 7 loose singles, same shelf.
        scanAndCountUnits("ABC-123", "פילטר", "B-UNIT", "A-01", 7)

        val rows = savedDetailRows().filter { it.location == "A-01" }
        assertEquals(2, rows.size)
        assertEquals(36, rows.first { it.barcode == "B-PACK" }.quantity)
        assertEquals(7, rows.first { it.barcode == "B-UNIT" }.quantity)

        // The answer the worker actually needs, in single units.
        assertEquals("43", summaryTotalFor("ABC-123", "A-01"))
    }

    /** Two codes on different shelves stay two rows, and each shelf totals on its own. */
    @Test
    fun `a package code on one shelf and a unit code on another are kept apart`() {
        load(
            listOf(ProductEntity("ABC-123", "פילטר", "B-UNIT", "", 0)),
            listOf(BarcodeEntity(barcode = "B-PACK", sku = "ABC-123", role = BarcodeEntity.ROLE_PACKAGE, packageContent = 12))
        )

        scanAndCount("ABC-123", "פילטר", "B-PACK", "A-01", ProductEntity.TYPE_PACKAGE, 12, 2)
        scanAndCountUnits("ABC-123", "פילטר", "B-UNIT", "B-03", 5)

        assertEquals("24", summaryTotalFor("ABC-123", "A-01"))
        assertEquals("5", summaryTotalFor("ABC-123", "B-03"))
        assertEquals("29", summaryTotalFor("ABC-123", "סה״כ"))
    }

    /**
     * One sticker on both cartons and loose singles — the case a second scan
     * cannot express, since it is the same (מקט, מיקום, ברקוד) row. Both
     * halves live on that one row instead of competing for it.
     */
    @Test
    fun `one code used for both packages and singles is counted on a single row`() {
        load(
            listOf(ProductEntity("XYZ-9", "אום", "BOTH", "", 0)),
            listOf(BarcodeEntity(barcode = "BOTH", sku = "XYZ-9", role = BarcodeEntity.ROLE_MIXED, packageContent = 6))
        )

        // 4 sealed packs of 6, plus 5 loose beside them.
        scanAndCount("XYZ-9", "אום", "BOTH", "A-02", ProductEntity.TYPE_MIXED, 6, 4, looseUnits = 5)

        val row = savedDetailRows().single()
        assertEquals(ProductEntity.TYPE_MIXED, row.quantityType)
        assertEquals(5, row.looseUnits)
        assertEquals(29, row.quantity)
        assertEquals("29", summaryTotalFor("XYZ-9", "A-02"))
    }

    // ---------- blind counting ----------

    /**
     * The governing rule. A source file carrying מיקום, ברקוד and a quantity
     * must never hand that quantity back as a starting point, and must not
     * reach the output file as though someone had counted it.
     */
    @Test
    fun `a quantity that came in on the source file is never treated as a count`() {
        load(
            listOf(
                ProductEntity(
                    "ABC-123", "פילטר", "111", "A-01", 0,
                    ProductEntity.TYPE_PACKAGE, 12, 5, 0, quantity = 60
                )
            )
        )

        // Nothing counted yet, so the file's own row is not in the output.
        assertTrue(savedDetailRows().isEmpty())

        // The first scan of that very shelf empties it before anyone types.
        runBlocking { repository.updateProduct("ABC-123", "פילטר", "111", "A-01") }
        val placed = runBlocking { repository.findRow("ABC-123", "A-01", "111") }!!
        assertEquals(0, placed.quantity)
        assertEquals(0, placed.packageContent)
        assertEquals(ProductEntity.TYPE_UNITS, placed.quantityType)
        assertEquals(0L, placed.countedAt)
    }

    /** A shelf genuinely counted as empty is a real count, and says so. */
    @Test
    fun `a shelf counted as zero is recorded as counted, not as missing`() {
        load(listOf(ProductEntity("ABC-123", "פילטר", "111", "", 0)))

        scanAndCountUnits("ABC-123", "פילטר", "111", "A-01", 0)

        val row = savedDetailRows().single()
        assertEquals(0, row.quantity)
        assertTrue("a zero count still has to be dated", row.countedAt > 0L)
        assertEquals("0", summaryTotalFor("ABC-123", "A-01"))
    }

    // ---------- recounting ----------

    /** Re-scanning the same spot corrects that count; it never adds a row or doubles a total. */
    @Test
    fun `recounting the same shelf and code replaces the number rather than duplicating it`() {
        load(listOf(ProductEntity("ABC-123", "פילטר", "111", "", 0)))

        scanAndCountUnits("ABC-123", "פילטר", "111", "A-01", 40)
        scanAndCountUnits("ABC-123", "פילטר", "111", "A-01", 37)

        assertEquals(1, savedDetailRows().size)
        assertEquals(37, savedDetailRows().single().quantity)
        assertEquals("37", summaryTotalFor("ABC-123", "A-01"))
    }

    // ---------- codes discovered mid-count ----------

    /**
     * The discovery a count exists to make. A sticker nobody had on file has
     * to resolve on the next scan and reach the barcodes file carrying what
     * the worker said it was.
     */
    @Test
    fun `a code first seen at a shelf is registered and reaches the barcodes file`() {
        load(listOf(ProductEntity("ABC-123", "פילטר", "111", "", 0)))

        scanAndCountUnits("ABC-123", "פילטר", "999", "A-01", 8)
        runBlocking { repository.setBarcodeRole("999", BarcodeEntity.ROLE_PACKAGE, 24) }

        assertEquals("ABC-123", runBlocking { repository.findByBarcode("999") }!!.sku)

        runBlocking { repository.saveWorkingCopies() }
        val written = repository.multipleBarcodesFile()!!.inputStream()
            .use { ExcelReader.readMultipleBarcodesFromStream(it) }
            .associateBy { it.barcode }
        assertEquals(setOf("111", "999"), written.keys)
        assertEquals(BarcodeEntity.ROLE_PACKAGE, written.getValue("999").role)
        assertEquals(24, written.getValue("999").packageContent)
    }

    /** Correcting a wrong product match keeps what the sticker is physically on. */
    @Test
    fun `moving a code to the right sku keeps its packaging`() {
        load(
            listOf(
                ProductEntity("WRONG-1", "שגוי", "", "", 0),
                ProductEntity("RIGHT-1", "נכון", "", "", 1)
            )
        )
        scanAndCountUnits("WRONG-1", "שגוי", "555", "A-01", 3)
        runBlocking { repository.setBarcodeRole("555", BarcodeEntity.ROLE_PACKAGE, 12) }

        runBlocking { repository.reassignBarcode("555", "RIGHT-1") }

        val moved = runBlocking { repository.barcodeInfo("555") }!!
        assertEquals("RIGHT-1", moved.sku)
        assertEquals(BarcodeEntity.ROLE_PACKAGE, moved.role)
        assertEquals(12, moved.packageContent)
        // The wrong product no longer holds a count at that shelf.
        assertTrue(savedDetailRows().none { it.sku == "WRONG-1" && it.location == "A-01" })
    }

    // ---------- file shape ----------

    /** A row opened late still sits with the rest of its מקט, so the file reads one product at a time. */
    @Test
    fun `rows for one sku stay together however late they were opened`() {
        load(
            listOf(
                ProductEntity("ABC-123", "פילטר", "111", "", 0),
                ProductEntity("XYZ-9", "אום", "222", "", 1),
                ProductEntity("QRS-5", "בורג", "333", "", 2)
            )
        )

        scanAndCountUnits("ABC-123", "פילטר", "111", "A-01", 4)
        scanAndCountUnits("XYZ-9", "אום", "222", "A-01", 5)
        scanAndCountUnits("QRS-5", "בורג", "333", "A-02", 6)
        // Much later: a second code for the first product, same shelf.
        scanAndCountUnits("ABC-123", "פילטר", "444", "A-01", 7)

        assertEquals(listOf("ABC-123", "ABC-123", "XYZ-9", "QRS-5"), savedDetailRows().map { it.sku })
    }

    // ---------- ambiguous input ----------

    /** Two מקטים claiming one code is reported at load, not resolved in silence. */
    @Test
    fun `a code two skus claim is reported when the file is loaded`() {
        val result = load(
            listOf(
                ProductEntity("ABC-123", "פילטר", "111", "A-01", 0),
                ProductEntity("XYZ-9", "אום", "222", "B-02", 1)
            ),
            listOf(BarcodeEntity(barcode = "222", sku = "ABC-123"))
        )

        assertEquals(1, result.duplicateBarcodeRows)
        assertEquals("ABC-123", runBlocking { repository.findByBarcode("222") }!!.sku)
    }

    /** An unknown code never blocks a scan; it counts as a plain unit until someone says otherwise. */
    @Test
    fun `an undescribed code still counts, as plain units`() {
        load(listOf(ProductEntity("ABC-123", "פילטר", "", "", 0)))

        scanAndCountUnits("ABC-123", "פילטר", "777", "A-01", 9)

        assertEquals(9, savedDetailRows().single().quantity)
        assertEquals(BarcodeEntity.ROLE_UNIT, runBlocking { repository.barcodeInfo("777") }!!.role)
    }

    /** Unshelving the only row for a מקט clears the count without losing the product. */
    @Test
    fun `removing the last row of a sku keeps the product but drops it from the count`() {
        load(listOf(ProductEntity("ABC-123", "פילטר", "111", "", 0)))
        scanAndCountUnits("ABC-123", "פילטר", "111", "A-01", 12)

        runBlocking { repository.removeFromLocation(db.productDao().findAllBySku("ABC-123").single()) }

        assertTrue(savedDetailRows().isEmpty())
        assertEquals(1, runBlocking { db.productDao().findAllBySku("ABC-123") }.size)
        assertNull(runBlocking { repository.findRow("ABC-123", "A-01", "111") })
    }

    // ---------- all three modes at once ----------

    /**
     * One מקט counted three times, each shelf in a different mode: cartons,
     * loose singles, and a shelf holding both. Each mode does its own
     * arithmetic on the way in, so the only thing that can make the total
     * right is that all three stored a unit figure — which is exactly the
     * agreement the summary depends on and no single-mode test can show.
     */
    @Test
    fun `one sku counted in packages, units and mixed totals correctly across all three`() {
        load(
            listOf(ProductEntity("ABC-123", "פילטר", "B-UNIT", "", 0)),
            listOf(
                BarcodeEntity(barcode = "B-PACK", sku = "ABC-123", role = BarcodeEntity.ROLE_PACKAGE, packageContent = 12),
                BarcodeEntity(barcode = "B-UNIT", sku = "ABC-123", role = BarcodeEntity.ROLE_UNIT),
                BarcodeEntity(barcode = "B-BOTH", sku = "ABC-123", role = BarcodeEntity.ROLE_MIXED, packageContent = 6)
            )
        )

        // A-01: 3 cartons of 12 = 36
        scanAndCount("ABC-123", "פילטר", "B-PACK", "A-01", ProductEntity.TYPE_PACKAGE, 12, 3)
        // B-03: 7 loose singles
        scanAndCountUnits("ABC-123", "פילטר", "B-UNIT", "B-03", 7)
        // C-07: 4 packs of 6 plus 5 loose = 29
        scanAndCount("ABC-123", "פילטר", "B-BOTH", "C-07", ProductEntity.TYPE_MIXED, 6, 4, looseUnits = 5)

        assertEquals("36", summaryTotalFor("ABC-123", "A-01"))
        assertEquals("7", summaryTotalFor("ABC-123", "B-03"))
        assertEquals("29", summaryTotalFor("ABC-123", "C-07"))
        assertEquals("72", summaryTotalFor("ABC-123", "סה״כ"))

        // Three shelves, three rows, three different modes on record.
        val rows = savedDetailRows()
        assertEquals(3, rows.size)
        assertEquals(
            setOf(ProductEntity.TYPE_PACKAGE, ProductEntity.TYPE_UNITS, ProductEntity.TYPE_MIXED),
            rows.map { it.quantityType }.toSet()
        )
    }

    /**
     * Two different מקטים sharing one shelf. The summary is grouped by מקט
     * first, so a per-shelf total that leaked across products would be
     * invisible in the detail sheet and wrong in the only sheet anyone adds
     * up.
     */
    @Test
    fun `two skus on the same shelf are totalled separately`() {
        load(
            listOf(
                ProductEntity("ABC-123", "פילטר", "111", "", 0),
                ProductEntity("XYZ-9", "אום", "444", "", 1)
            )
        )

        scanAndCountUnits("ABC-123", "פילטר", "111", "A-01", 10)
        scanAndCountUnits("XYZ-9", "אום", "444", "A-01", 3)

        assertEquals("10", summaryTotalFor("ABC-123", "A-01"))
        assertEquals("3", summaryTotalFor("XYZ-9", "A-01"))
        assertEquals("10", summaryTotalFor("ABC-123", "סה״כ"))
        assertEquals("3", summaryTotalFor("XYZ-9", "סה״כ"))
    }

    /**
     * A worker who first counted cartons finds loose units behind them and
     * recounts the shelf as מעורב. The row has to become the new shape
     * outright — a leftover packageCount from the first pass would be added
     * to the loose units and inflate the shelf.
     */
    @Test
    fun `recounting a shelf in a different mode replaces the whole breakdown`() {
        load(
            listOf(ProductEntity("ABC-123", "פילטר", "B-PACK", "", 0)),
            listOf(BarcodeEntity(barcode = "B-PACK", sku = "ABC-123", role = BarcodeEntity.ROLE_PACKAGE, packageContent = 12))
        )

        scanAndCount("ABC-123", "פילטר", "B-PACK", "A-01", ProductEntity.TYPE_PACKAGE, 12, 3)
        assertEquals("36", summaryTotalFor("ABC-123", "A-01"))

        // Same shelf, same code, recounted: 2 cartons and 5 singles.
        scanAndCount("ABC-123", "פילטר", "B-PACK", "A-01", ProductEntity.TYPE_MIXED, 12, 2, looseUnits = 5)

        val row = savedDetailRows().single()
        assertEquals(ProductEntity.TYPE_MIXED, row.quantityType)
        assertEquals(2, row.packageCount)
        assertEquals(5, row.looseUnits)
        assertEquals(29, row.quantity)
        assertEquals("29", summaryTotalFor("ABC-123", "A-01"))
    }

    /**
     * The shelf wins, but only for its own row. A worker who finds cartons
     * of 10 under a code described as 12 counts what is in front of them;
     * the other shelf's count must not move, and the code itself must keep
     * saying 12 — one odd shelf does not redefine a ברקוד for the rest of
     * the count.
     */
    @Test
    fun `overriding the package size on one shelf leaves the other shelf and the code alone`() {
        load(
            listOf(ProductEntity("ABC-123", "פילטר", "B-PACK", "", 0)),
            listOf(BarcodeEntity(barcode = "B-PACK", sku = "ABC-123", role = BarcodeEntity.ROLE_PACKAGE, packageContent = 12))
        )

        scanAndCount("ABC-123", "פילטר", "B-PACK", "A-01", ProductEntity.TYPE_PACKAGE, 12, 2)
        // Same code, another shelf, cartons of 10 this time.
        scanAndCount("ABC-123", "פילטר", "B-PACK", "B-03", ProductEntity.TYPE_PACKAGE, 10, 3)

        assertEquals("24", summaryTotalFor("ABC-123", "A-01"))
        assertEquals("30", summaryTotalFor("ABC-123", "B-03"))
        assertEquals("54", summaryTotalFor("ABC-123", "סה״כ"))
        // The code keeps what the file said; the override belonged to one row.
        assertEquals(12, runBlocking { repository.barcodeInfo("B-PACK") }!!.packageContent)
    }
}
