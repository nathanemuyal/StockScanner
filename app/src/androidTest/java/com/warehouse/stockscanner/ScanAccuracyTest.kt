package com.warehouse.stockscanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.warehouse.stockscanner.scan.AimSelector
import com.warehouse.stockscanner.scan.MultiPassScanner
import com.warehouse.stockscanner.scan.ScanConsensus
import com.warehouse.stockscanner.scan.ShelfLabelFilter
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end accuracy of the scan pipeline ScannerActivity runs — the real
 * on-device ML Kit engine, then BarcodeValidator, AimSelector and
 * ScanConsensus — on real photos: the warehouse shelves (resurse/) plus
 * product barcodes photographed in Israeli stores from Wikimedia Commons
 * (see assets/accuracy/ATTRIBUTION.md).
 *
 * Each photo is fed as a short burst of slightly different "frames" (small
 * scale / brightness / tilt changes, like a hand-held phone produces) so
 * the multi-frame consensus is exercised the way the camera exercises it.
 *
 * Requirements:
 * - ZERO wrong values, everywhere: a missed scan costs the worker one more
 *   second, a wrong one silently corrupts the stock count.
 * - At least 95% read for what the camera actually delivers: real photos,
 *   and small codes that still have enough pixels per bar (~1.5+).
 * - Degraded images (tiny codes, whole photos shrunk to 640/480 px, heavy
 *   blur) are only required to be never misread; their rate is logged.
 *   Measured on the emulator, their misses have ~0.5-1.1 px per bar vs.
 *   1.2-2 for the reads — below ~1 px per bar neighboring bars merge and
 *   the value is no longer in the image at all. In the app that is solved
 *   by getting more pixels (1920x1080 frames, center crops, auto-zoom).
 */
@RunWith(AndroidJUnit4::class)
class ScanAccuracyTest {

    companion object {
        private const val TAG = "ScanAccuracyTest"
        /** Required detection rate for photos and readable-size codes. */
        private const val MIN_DETECTION_RATE = 0.95

        private lateinit var productScanner: BarcodeScanner
        private lateinit var locationScanner: BarcodeScanner

        @BeforeClass
        @JvmStatic
        fun setUp() {
            productScanner = BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(
                        Barcode.FORMAT_QR_CODE,
                        Barcode.FORMAT_DATA_MATRIX,
                        Barcode.FORMAT_EAN_13,
                        Barcode.FORMAT_EAN_8,
                        Barcode.FORMAT_UPC_A,
                        Barcode.FORMAT_UPC_E,
                        Barcode.FORMAT_CODE_128,
                        Barcode.FORMAT_CODE_39
                    )
                    .build()
            )
            locationScanner = BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX)
                    .build()
            )
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            productScanner.close()
            locationScanner.close()
        }

        /** Photo -> the value printed under its barcode (read off the photo by eye). */
        val LABELED = mapOf(
            "warehouse_151107.jpg" to "7290019629528",
            "warehouse_151109.jpg" to "7290019629528",
            "warehouse_151118.jpg" to "4714218000139",
            "warehouse_151125.jpg" to "4714218000139",
            "warehouse_151155.jpg" to "7296015072054",
            "warehouse_151158.jpg" to "7296015072054",
            "web_israel_food_01.jpg" to "7290112357113",
            "web_israel_food_02.jpg" to "7290112357113",
            "web_israel_food_03.jpg" to "7290119042128",
            "web_israel_food_04.jpg" to "7290011126469",
            "web_israel_food_06.jpg" to "7290018752609",
            "web_israel_food_07.jpg" to "7290011498795",
            "web_israel_food_08.jpg" to "7290001491096",
            "web_israel_food_09.jpg" to "7290001491096",
            "web_israel_food_10.jpg" to "7290001491096",
            "web_israel_food_11.jpg" to "7290001491096",
            "web_israel_food_12.jpg" to "7290112490759",
            "web_israel_food_13.jpg" to "7290112490759",
            "web_israel_food_14.jpg" to "6940152200405",
            "web_israel_food_15.jpg" to "7290015161190",
            "web_israel_food_16.jpg" to "7296073293583",
            "web_israel_food_17.jpg" to "7296073293583",
            "web_israel_food_18.jpg" to "7296073293583",
            "web_israel_food_19.jpg" to "7290003485222",
            // A plain decoder returned five different values for these two;
            // the printed digits are 7 290003 060047.
            "web_israel_food_20.jpg" to "7290003060047",
            "web_israel_food_21.jpg" to "7290003060047",
            "web_israel_food_22.jpg" to "7290005437632",
            "web_israel_food_24.jpg" to "7290112357113",
            "web_qr-code-wikimedia_commons_photo_challenges_are_fun.jpg" to
                "Wikimedia Commons photo challenges are fun!"
        )

        /**
         * Small, low-resolution codes (assets/accuracy/small/). What limits a
         * decoder is pixels per bar ("module"): an EAN-13 is 95 modules wide,
         * so at 160 px each bar is ~1.7 px, at 120 px barely more than one.
         * This tier is small but still has enough pixels to decode.
         */
        val SMALL_READABLE = mapOf(
            "small_ean13_240px.jpg" to "5901234123457",
            "small_ean13_160px.jpg" to "5901234123457",
            "small_upca_240px.jpg" to "036000291452",
            "small_upca_160px.jpg" to "036000291452",
            "small_ean8_160px.jpg" to "65833254",
            "small_ean8_110px.jpg" to "65833254",
            "small_code39_176px.jpg" to "1234567890",
            "small_qr_100px.jpg" to "http://en.m.wikipedia.org",
            "small_qr_64px.jpg" to "http://en.m.wikipedia.org",
            "small_datamatrix_88px.jpg" to "Wikipedia, the free encyclopedia",
            "small_datamatrix_66px.jpg" to "Wikipedia, the free encyclopedia",
            "small_isbn_234px.jpg" to "9783161484100",
            "small_photo_ean_obst_250px.jpg" to "2404105001722"
        )

        /** ~1 px per module or less: may be missed, must never be misread. */
        val SMALL_TINY = mapOf(
            "small_ean13_120px.jpg" to "5901234123457",
            "small_upca_120px.jpg" to "036000291452",
            "small_ean8_80px.jpg" to "65833254",
            "small_code39_130px.jpg" to "1234567890",
            "small_qr_48px.jpg" to "http://en.m.wikipedia.org",
            "small_datamatrix_44px.jpg" to "Wikipedia, the free encyclopedia"
        )
    }

    private fun loadBitmap(name: String): Bitmap {
        if (name.startsWith("small_")) return loadAsset("accuracy/small/$name")
        return loadAsset("accuracy/$name")
    }

    private fun loadAsset(path: String): Bitmap {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        return assets.open(path).use { BitmapFactory.decodeStream(it) }
            ?: error("Failed to decode $path")
    }


    /** Small hand-held variations of one shot: none, 90% scale, darker, 4° tilt. */
    private fun burst(src: Bitmap): List<Bitmap> {
        fun transformed(m: Matrix) = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        val darker = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888).also {
            val paint = Paint().apply {
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.7f, 0.7f, 0.7f, 1f) })
            }
            Canvas(it).drawBitmap(src, 0f, 0f, paint)
        }
        return listOf(
            src,
            transformed(Matrix().apply { setScale(0.9f, 0.9f) }),
            darker,
            transformed(Matrix().apply { setRotate(4f) })
        )
    }

    /**
     * Runs the exact pipeline ScannerActivity runs, frame by frame:
     * multi-pass decode -> shelf-label filter (product mode) -> aim -> consensus.
     */
    private fun scanBurst(
        bitmap: Bitmap,
        locationMode: Boolean = false,
        currentLocation: String? = null
    ): String? {
        val multiPass = MultiPassScanner(if (locationMode) locationScanner else productScanner)
        val consensus = ScanConsensus()
        for (frame in burst(bitmap)) {
            var candidates = multiPass.scan(frame, 0)
            if (!locationMode) candidates = candidates.filterNot { ShelfLabelFilter.isShelfLabel(it, currentLocation) }
            val confirmed = consensus.offer(AimSelector.pick(candidates, frame.width, frame.height))
            if (confirmed != null) return confirmed
        }
        return null
    }

    private fun scanBurst(name: String): String? = scanBurst(loadBitmap(name))

    /**
     * The code as the camera actually delivers it: a small area in the middle
     * of a full 1920x1080 analysis frame, on a light-gray "shelf" background.
     */
    private fun inCameraFrame(code: Bitmap): Bitmap =
        Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888).also {
            val canvas = Canvas(it)
            canvas.drawColor(Color.rgb(190, 190, 185))
            canvas.drawBitmap(code, (1920 - code.width) / 2f, (1080 - code.height) / 2f, null)
        }

    /** Downscales so the long side is [longSide] px (a low-resolution camera or a heavy crop). */
    private fun lowRes(src: Bitmap, longSide: Int): Bitmap {
        val k = longSide.toFloat() / maxOf(src.width, src.height)
        return Bitmap.createScaledBitmap(src, (src.width * k).toInt(), (src.height * k).toInt(), true)
    }

    private class Tally(val label: String) {
        var total = 0
        var read = 0
        val wrong = mutableListOf<String>()
        val rate get() = if (total == 0) 0.0 else read.toDouble() / total

        fun record(name: String, expected: String, got: String?) {
            total++
            Log.i(TAG, "$label $name expected=$expected got=$got")
            when (got) {
                null -> Unit
                expected -> read++
                else -> wrong += "$name: expected $expected, got $got"
            }
        }

        fun assertNoWrongValues() {
            Log.i(TAG, "$label detection rate: $read/$total = ${"%.0f".format(rate * 100)}%")
            assertEquals("$label - wrong values accepted:\n" + wrong.joinToString("\n"), 0, wrong.size)
        }

        fun assertRateAtLeast(min: Double) = assertTrue(
            "$label detection rate $read/$total is below ${min * 100}%",
            rate >= min
        )
    }

    @Test
    fun labeledPhotos_neverProduceAWrongValue_andMostAreRead() {
        val tally = Tally("photos")
        for ((name, expected) in LABELED) tally.record(name, expected, scanBurst(name))
        tally.assertNoWrongValues()
        tally.assertRateAtLeast(MIN_DETECTION_RATE)
    }

    @Test
    fun shelfPhotos_locationModeReadsTheLabelUnderTheCrosshair() {
        // Shelf photos with product barcodes above the label; the crosshair
        // is on/next to the label.
        assertEquals("07016001", scanBurst(loadBitmap("warehouse_151045.jpg"), locationMode = true))
        assertEquals("07028001", scanBurst(loadBitmap("warehouse_151101.jpg"), locationMode = true))
    }

    @Test
    fun shelfPhotos_productModeNeverReturnsAShelfLabel() {
        // The worker is on shelf 07-01-70-01 (label 07017001). The label of
        // this or any neighboring shelf in view must not come back as a
        // "product" — before ShelfLabelFilter these returned 07016001 / 07028001.
        for (name in listOf("warehouse_151045.jpg", "warehouse_151101.jpg")) {
            val got = scanBurst(loadBitmap(name), currentLocation = "07017001")
            Log.i(TAG, "product mode on shelf photo $name -> $got")
            assertTrue("$name returned shelf label $got as a product", got !in setOf("07016001", "07028001"))
        }
    }

    @Test
    fun photoWithNoBarcode_producesNothing() {
        // A hand-held scanner device with a keypad and a screen — lots of
        // stripy, high-contrast detail but no barcode.
        assertNull(scanBurst("web_package_tracking_barcode_scanner.jpg"))
    }

    @Test
    fun dataMatrixOnAProduct_isReadInProductMode() {
        val got = scanBurst("web_datamatrix_code_on_a_bottle_of_milk.jpg")
        Log.i(TAG, "milk bottle Data Matrix -> $got")
        // A GS1 marking code, exactly as ML Kit returns it: a leading ASCII 29
        // (FNC1), GTIN 04607078117294 (AI 01), a serial (AI 21), ASCII 29, AI 93.
        assertEquals("\u001D0104607078117294215fGHNL\u001D93H/xN", got)
    }

    @Test
    fun blurredPhotos_mayBeMissed_butAreNeverMisread() {
        // Heavy blur is where 1D decoders guess. Downscale-then-upscale
        // smears the thinnest bars the way a moving hand does.
        val tally = Tally("blurred")
        for ((name, expected) in LABELED.filterValues { it.all(Char::isDigit) }) {
            val src = loadBitmap(name)
            val small = Bitmap.createScaledBitmap(src, src.width / 5, src.height / 5, true)
            tally.record(name, expected, scanBurst(Bitmap.createScaledBitmap(small, src.width, src.height, true)))
        }
        tally.assertNoWrongValues()
    }

    @Test
    fun smallBarcodes_inAFullCameraFrame_areReadAndNeverMisread() {
        // The realistic case: a small label, or a normal one held far away,
        // covers only a few hundred pixels of the 1920x1080 frame.
        val readable = Tally("small-in-frame")
        for ((name, expected) in SMALL_READABLE) {
            readable.record(name, expected, scanBurst(inCameraFrame(loadBitmap(name))))
        }
        val tiny = Tally("tiny-in-frame")
        for ((name, expected) in SMALL_TINY) {
            tiny.record(name, expected, scanBurst(inCameraFrame(loadBitmap(name))))
        }
        for (t in listOf(readable, tiny)) t.assertNoWrongValues()
        readable.assertRateAtLeast(MIN_DETECTION_RATE)
    }

    @Test
    fun smallLowResolutionImagesOnTheirOwn_areReadAndNeverMisread() {
        // The image file itself is tiny (down to ~50x50 px), not just the code.
        val readable = Tally("small-image")
        for ((name, expected) in SMALL_READABLE) readable.record(name, expected, scanBurst(name))
        val tiny = Tally("tiny-image")
        for ((name, expected) in SMALL_TINY) tiny.record(name, expected, scanBurst(name))
        for (t in listOf(readable, tiny)) t.assertNoWrongValues()
        readable.assertRateAtLeast(MIN_DETECTION_RATE)
    }

    @Test
    fun lowResolutionPhotos_areNeverMisread() {
        // Whole shelf/product photos at 640 and 480 px: every barcode in them
        // becomes small and soft, as with a cheap or low-resolution camera.
        val tallies = listOf(640, 480).map { longSide ->
            Tally("lowres-$longSide").also { tally ->
                for ((name, expected) in LABELED) {
                    tally.record(name, expected, scanBurst(lowRes(loadBitmap(name), longSide)))
                }
            }
        }
        for (t in tallies) t.assertNoWrongValues()
    }
}
