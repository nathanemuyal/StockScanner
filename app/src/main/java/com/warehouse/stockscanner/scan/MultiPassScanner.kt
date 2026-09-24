package com.warehouse.stockscanner.scan

import android.graphics.Bitmap
import android.graphics.Color
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.common.InputImage

/**
 * Decodes one camera frame in up to three passes: the whole frame, then the
 * center half, then the center quarter.
 *
 * ML Kit shrinks a large frame internally before looking for codes, so a
 * small label (or a normal one held far away) that covers only ~10% of a
 * 1920x1080 frame gets lost: measured on the emulator, the same small codes
 * were read 95% of the time as a small image on their own but only 46% of
 * the time when centered in a full frame. Cropping the center hands ML Kit
 * the same pixels at a size it doesn't shrink. The crops are only tried
 * when the previous pass found nothing valid, so a normal-size barcode
 * still costs a single pass. (Enlarging the crops and sharpening them were
 * both measured too, and gained nothing.)
 *
 * Every EAN/UPC read also has to pass [QuietZoneCheck] against the frame's
 * pixels.
 *
 * Blocking — call it from a background thread (the camera analysis
 * executor), never the main thread.
 */
class MultiPassScanner(
    /** Used for the whole frame (may carry ML Kit's zoom suggestion). */
    private val fullFrameScanner: BarcodeScanner,
    /** Used for the crops; must not carry zoom suggestion (its ratio would be relative to the crop). */
    private val cropScanner: BarcodeScanner = fullFrameScanner
) {
    companion object {
        /** Fraction of each side kept per pass. */
        val PASSES = floatArrayOf(1f, 0.5f, 0.25f)

        /** ML Kit rejects images under 32 px; crops smaller than this are skipped. */
        private const val MIN_CROP_SIDE = 64
    }

    /**
     * Valid candidates from the first pass that found any, in the upright
     * frame's coordinates (upright = [frame] rotated by [rotationDegrees]).
     */
    fun scan(frame: Bitmap, rotationDegrees: Int): List<ScanCandidate> {
        val uprightW = if (rotationDegrees % 180 == 0) frame.width else frame.height
        val uprightH = if (rotationDegrees % 180 == 0) frame.height else frame.width
        val luma = uprightLuma(frame, rotationDegrees)
        for (fraction in PASSES) {
            if (fraction < 1f && minOf(frame.width, frame.height) * fraction < MIN_CROP_SIDE) break
            val crop = if (fraction == 1f) frame else centerCrop(frame, fraction)
            val scanner = if (fraction == 1f) fullFrameScanner else cropScanner
            val barcodes = Tasks.await(scanner.process(InputImage.fromBitmap(crop, rotationDegrees)))
            // A centered crop is centered in upright space too, so mapping
            // back is a plain offset.
            val dx = (uprightW - uprightW * fraction) / 2f
            val dy = (uprightH - uprightH * fraction) / 2f
            val found = barcodes.mapNotNull { it.toScanCandidate() }
                .map { it.offsetBy(dx, dy) }
                .filter { QuietZoneCheck.passes(it, luma) }
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private fun ScanCandidate.offsetBy(dx: Float, dy: Float) = copy(
        left = left + dx, top = top + dy, right = right + dx, bottom = bottom + dy,
        corners = corners?.let { c -> FloatArray(8) { i -> c[i] + if (i % 2 == 0) dx else dy } }
    )

    private fun centerCrop(src: Bitmap, fraction: Float): Bitmap {
        val w = (src.width * fraction).toInt()
        val h = (src.height * fraction).toInt()
        return Bitmap.createBitmap(src, (src.width - w) / 2, (src.height - h) / 2, w, h)
    }

    /**
     * Luminance at an UPRIGHT-frame pixel, read from the sensor-oriented
     * [frame]. [rotationDegrees] is how far the frame must turn clockwise to
     * be upright, as InputImage defines it.
     */
    private fun uprightLuma(frame: Bitmap, rotationDegrees: Int): (Int, Int) -> Int? = { ux, uy ->
        val (sx, sy) = when (rotationDegrees) {
            90 -> uy to frame.height - 1 - ux
            180 -> frame.width - 1 - ux to frame.height - 1 - uy
            270 -> frame.width - 1 - uy to ux
            else -> ux to uy
        }
        if (sx < 0 || sy < 0 || sx >= frame.width || sy >= frame.height) {
            null
        } else {
            val p = frame.getPixel(sx, sy)
            (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
        }
    }
}
