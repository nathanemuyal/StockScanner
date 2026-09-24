package com.warehouse.stockscanner.scan

/**
 * One decoded code in a camera frame, in the upright frame's pixel space
 * (the same orientation the worker sees on screen).
 */
data class ScanCandidate(
    val value: String,
    val format: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}

/**
 * Picks the code the worker is actually aiming at.
 *
 * Real shelf frames are crowded: every shelf label carries four Data Matrix
 * codes side by side, and the product boxes right above it carry their own
 * 1D barcodes (see resurse/ and androidTest/assets/). ML Kit returns them in
 * no particular order, so taking "the first one" captured whichever code
 * the engine happened to list first — often not the one in the middle of
 * the screen. The worker aims with the on-screen frame, so the code under
 * the center of the image wins; otherwise the one closest to it.
 */
object AimSelector {

    fun pick(candidates: List<ScanCandidate>, frameWidth: Int, frameHeight: Int): ScanCandidate? {
        if (candidates.isEmpty()) return null
        val cx = frameWidth / 2f
        val cy = frameHeight / 2f
        val underCrosshair = candidates.filter { it.contains(cx, cy) }
        val pool = underCrosshair.ifEmpty { candidates }
        // Normalize by frame size so a portrait frame doesn't favor codes
        // that are merely off-center along the short axis.
        return pool.minByOrNull {
            val dx = (it.centerX - cx) / frameWidth
            val dy = (it.centerY - cy) / frameHeight
            dx * dx + dy * dy
        }
    }
}
