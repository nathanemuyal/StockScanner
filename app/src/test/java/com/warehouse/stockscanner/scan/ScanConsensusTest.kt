package com.warehouse.stockscanner.scan

import com.google.mlkit.vision.barcode.common.Barcode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScanConsensusTest {

    private fun ean(value: String) = ScanCandidate(value, Barcode.FORMAT_EAN_13, 0f, 0f, 1f, 1f)
    private fun upc(value: String) = ScanCandidate(value, Barcode.FORMAT_UPC_A, 0f, 0f, 1f, 1f)
    private fun dm(value: String) = ScanCandidate(value, Barcode.FORMAT_DATA_MATRIX, 0f, 0f, 1f, 1f)

    @Test
    fun `a 1D code needs two agreeing frames`() {
        val c = ScanConsensus()
        assertNull(c.offer(ean("7290001165188")))
        assertEquals("7290001165188", c.offer(ean("7290001165188")))
    }

    @Test
    fun `a 2D code is accepted on the first frame`() {
        assertEquals("07017001", ScanConsensus().offer(dm("07017001")))
    }

    @Test
    fun `a single-frame misread never gets through`() {
        // The real sequence a shaky hand produces on resurse/20260902_151045.jpg:
        // one frame misread as a (checksum-valid) UPC-A, then correct reads.
        val c = ScanConsensus()
        assertNull(c.offer(upc("005817530627")))
        assertNull(c.offer(ean("4007817530627")))
        assertEquals("4007817530627", c.offer(ean("4007817530627")))
    }

    @Test
    fun `alternating different reads never confirm`() {
        val c = ScanConsensus()
        repeat(10) {
            assertNull(c.offer(ean("4714218000139")))
            assertNull(c.offer(ean("4714218000146")))
        }
    }

    @Test
    fun `a few empty frames between matching reads are tolerated`() {
        val c = ScanConsensus(maxMissedFrames = 3)
        assertNull(c.offer(ean("7296015072054")))
        repeat(3) { assertNull(c.offer(null)) }
        assertEquals("7296015072054", c.offer(ean("7296015072054")))
    }

    @Test
    fun `too many empty frames start the count over`() {
        val c = ScanConsensus(maxMissedFrames = 3)
        assertNull(c.offer(ean("7296015072054")))
        repeat(4) { assertNull(c.offer(null)) }
        assertNull(c.offer(ean("7296015072054")))
        assertEquals("7296015072054", c.offer(ean("7296015072054")))
    }
}
