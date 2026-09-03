package com.warehouse.stockscanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs the REAL on-device ML Kit barcode engine (not a mock, not Robolectric)
 * against real photos taken on an actual warehouse shelf — see
 * app/src/androidTest/assets/. This is what uncovered the real bug behind
 * ScannerActivity's location-scan format restriction: the shelf photos show
 * the location label sitting right next to a product's 1D barcode in the same
 * frame, AND that the shelf labels are actually Data Matrix codes, not QR
 * (easy to mix up — both are small square 2D codes). Location scans must
 * therefore accept {QR, Data Matrix} and nothing else, or a nearby 1D barcode
 * can get decoded instead of the real location.
 */
@RunWith(AndroidJUnit4::class)
class RealPhotoScannerTest {

    private fun loadImage(assetName: String): InputImage {
        // Assets under androidTest/ are packaged into the TEST apk, so they're
        // reached via the instrumentation's own context, not the target app's.
        val context = InstrumentationRegistry.getInstrumentation().context
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.assets.open(assetName).use { BitmapFactory.decodeStream(it, null, bounds) }

        // Downscale to roughly match the real camera pipeline's analysis
        // resolution (1280x720 in ScannerActivity) instead of the full
        // multi-megapixel photo — closer to real conditions, and avoids
        // decoding a huge bitmap on a memory-constrained emulator.
        val targetMaxDimension = 1600
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > targetMaxDimension || bounds.outHeight / sampleSize > targetMaxDimension) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap: Bitmap = context.assets.open(assetName).use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: error("Failed to decode $assetName")

        Log.i("RealPhotoScannerTest", "$assetName decoded at ${bitmap.width}x${bitmap.height}")
        return InputImage.fromBitmap(bitmap, 0)
    }

    private fun scan(image: InputImage, formats: IntArray): List<Barcode> {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(formats.first(), *formats.drop(1).toIntArray())
            .build()
        val scanner = BarcodeScanning.getClient(options)
        val latch = CountDownLatch(1)
        var result: List<Barcode> = emptyList()
        scanner.process(image)
            .addOnSuccessListener { result = it; latch.countDown() }
            .addOnFailureListener {
                Log.e("RealPhotoScannerTest", "ML Kit scan failed", it)
                latch.countDown()
            }
        latch.await(15, TimeUnit.SECONDS)
        return result
    }

    /** Exactly what ScannerActivity now uses for MODE_LOCATION. */
    private val locationFormats = intArrayOf(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX)

    private val allProductFormats = intArrayOf(
        Barcode.FORMAT_QR_CODE,
        Barcode.FORMAT_DATA_MATRIX,
        Barcode.FORMAT_EAN_13,
        Barcode.FORMAT_EAN_8,
        Barcode.FORMAT_UPC_A,
        Barcode.FORMAT_UPC_E,
        Barcode.FORMAT_CODE_128,
        Barcode.FORMAT_CODE_39
    )

    @Test
    fun shelfPhoto1_withAllFormatsEnabled_seesBothTheShelfLabelAndANearbyProductBarcode() {
        val image = loadImage("shelf_qr_with_nearby_barcode_1.jpg")
        val results = scan(image, allProductFormats)
        val formats = results.map { it.format }.toSet()

        // Confirms the real-world hazard: the shelf's 2D label and a product's
        // 1D barcode are genuinely both visible/decodable in the same photo.
        assertTrue(
            "Expected to find the shelf's Data Matrix label, found formats: $formats",
            formats.contains(Barcode.FORMAT_DATA_MATRIX)
        )
        assertTrue(
            "Expected to also find a 1D product barcode in the same photo, found formats: $formats",
            formats.any { it != Barcode.FORMAT_DATA_MATRIX && it != Barcode.FORMAT_QR_CODE }
        )
    }

    @Test
    fun shelfPhoto1_locationModeFindsTheShelfLabel_andNeverTheNearbyBarcode() {
        val image = loadImage("shelf_qr_with_nearby_barcode_1.jpg")
        val results = scan(image, locationFormats)

        assertTrue("Expected the shelf label to still be found", results.isNotEmpty())
        for (barcode in results) {
            assertTrue(
                "Location mode must never return a 1D barcode, got format=${barcode.format} value=${barcode.rawValue}",
                barcode.format == Barcode.FORMAT_QR_CODE || barcode.format == Barcode.FORMAT_DATA_MATRIX
            )
        }
        // The label reads "07-01-70-01" next to the Data Matrix code, which
        // encodes the same digits without the dashes.
        assertTrue(
            "Expected to decode 07017001, got: ${results.map { it.rawValue }}",
            results.any { it.rawValue == "07017001" }
        )
    }

    @Test
    fun shelfPhoto2_locationModeFindsTheShelfLabel_andNeverTheNearbyBarcode() {
        val image = loadImage("shelf_qr_with_nearby_barcode_2.jpg")
        val results = scan(image, locationFormats)

        assertTrue("Expected the shelf label to still be found", results.isNotEmpty())
        for (barcode in results) {
            assertTrue(
                "Location mode must never return a 1D barcode, got format=${barcode.format} value=${barcode.rawValue}",
                barcode.format == Barcode.FORMAT_QR_CODE || barcode.format == Barcode.FORMAT_DATA_MATRIX
            )
        }
        assertTrue(
            "Expected to decode 07026001, got: ${results.map { it.rawValue }}",
            results.any { it.rawValue == "07026001" }
        )
    }

    @Test
    fun productPhoto_withNoShelfLabelPresent_locationModeFindsNothing() {
        // A pure product barcode with no shelf label anywhere in frame: if a
        // user accidentally points the LOCATION scanner at a product, it must
        // not falsely produce a "location" value from the 1D barcode.
        val image = loadImage("product_barcode_only.jpg")
        val results = scan(image, locationFormats)
        assertTrue(
            "Location-mode scan of a photo with no shelf label must find nothing, but found: " +
                results.map { it.rawValue },
            results.isEmpty()
        )
    }

    @Test
    fun productPhoto_productModeCorrectlyDecodesTheEan13Barcode() {
        val image = loadImage("product_barcode_only.jpg")
        val results = scan(image, allProductFormats)
        val values = results.mapNotNull { it.rawValue }

        assertFalse("Expected product mode to decode the barcode", results.isEmpty())
        assertTrue(
            "Expected to decode EAN-13 value 7290001165188, got: $values",
            values.contains("7290001165188")
        )
    }
}
