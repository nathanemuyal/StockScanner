package com.warehouse.stockscanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
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
import com.warehouse.stockscanner.scan.ScanConsensus
import com.warehouse.stockscanner.scan.toScanCandidate
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
 * The hard requirement is ZERO wrong values: a missed scan costs the worker
 * one more second, a wrong one silently corrupts the stock count.
 */
@RunWith(AndroidJUnit4::class)
class ScanAccuracyTest {

    companion object {
        private const val TAG = "ScanAccuracyTest"
        private const val MIN_DETECTION_RATE = 0.85

        private lateinit var productScanner: BarcodeScanner

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
        }

        @AfterClass
        @JvmStatic
        fun tearDown() = productScanner.close()

        /** Photo -> the value printed under its barcode (read off the photo by eye). */
        val LABELED = mapOf(
            "warehouse_151045.jpg" to "4007817530627",
            "warehouse_151101.jpg" to "7290019629528",
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
    }

    private fun loadBitmap(name: String): Bitmap {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        return assets.open("accuracy/$name").use { BitmapFactory.decodeStream(it) }
            ?: error("Failed to decode $name")
    }

    private fun detect(bitmap: Bitmap): List<Barcode> {
        val latch = CountDownLatch(1)
        var result: List<Barcode> = emptyList()
        productScanner.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result = it }
            .addOnCompleteListener { latch.countDown() }
        assertTrue("ML Kit timed out", latch.await(20, TimeUnit.SECONDS))
        return result
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

    /** Runs the exact pipeline ScannerActivity runs, frame by frame. */
    private fun scanBurst(name: String): String? {
        val consensus = ScanConsensus()
        for (frame in burst(loadBitmap(name))) {
            val candidates = detect(frame).mapNotNull { it.toScanCandidate() }
            val confirmed = consensus.offer(AimSelector.pick(candidates, frame.width, frame.height))
            if (confirmed != null) return confirmed
        }
        return null
    }

    @Test
    fun labeledPhotos_neverProduceAWrongValue_andMostAreRead() {
        val wrong = mutableListOf<String>()
        var read = 0
        for ((name, expected) in LABELED) {
            val got = scanBurst(name)
            Log.i(TAG, "$name expected=$expected got=$got")
            when (got) {
                null -> Unit
                expected -> read++
                else -> wrong += "$name: expected $expected, got $got"
            }
        }
        val rate = read.toDouble() / LABELED.size
        Log.i(TAG, "Detection rate: $read/${LABELED.size} = ${"%.0f".format(rate * 100)}%")
        assertEquals("Wrong values accepted:\n" + wrong.joinToString("\n"), 0, wrong.size)
        assertTrue(
            "Detection rate $read/${LABELED.size} is below ${MIN_DETECTION_RATE * 100}%",
            rate >= MIN_DETECTION_RATE
        )
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
        assertTrue("Expected the Data Matrix on the bottle cap to be read", !got.isNullOrBlank())
    }

    @Test
    fun blurredPhotos_mayBeMissed_butAreNeverMisread() {
        // Heavy blur is where 1D decoders guess. Downscale-then-upscale
        // smears the thinnest bars the way a moving hand does.
        val wrong = mutableListOf<String>()
        for ((name, expected) in LABELED.filterValues { it.all(Char::isDigit) }) {
            val src = loadBitmap(name)
            val small = Bitmap.createScaledBitmap(src, src.width / 5, src.height / 5, true)
            val blurred = Bitmap.createScaledBitmap(small, src.width, src.height, true)
            val consensus = ScanConsensus()
            var got: String? = null
            for (frame in burst(blurred)) {
                val candidates = detect(frame).mapNotNull { it.toScanCandidate() }
                got = consensus.offer(AimSelector.pick(candidates, frame.width, frame.height)) ?: continue
                break
            }
            Log.i(TAG, "blurred $name expected=$expected got=$got")
            if (got != null && got != expected) wrong += "$name: expected $expected, got $got"
        }
        assertEquals("Blur produced wrong values:\n" + wrong.joinToString("\n"), 0, wrong.size)
    }
}
