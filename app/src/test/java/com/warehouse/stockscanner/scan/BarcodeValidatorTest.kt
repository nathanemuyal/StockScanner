package com.warehouse.stockscanner.scan

import com.google.mlkit.vision.barcode.common.Barcode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarcodeValidatorTest {

    @Test
    fun `real product barcodes from the warehouse photos are accepted`() {
        // Values printed on the boxes in resurse/ and androidTest/assets/.
        for (ean in listOf("7290001165188", "4714218000139", "7296015072054", "4007817530627")) {
            assertTrue(ean, BarcodeValidator.isValid(ean, Barcode.FORMAT_EAN_13))
        }
    }

    @Test
    fun `EAN-13 with one wrong digit is rejected`() {
        assertFalse(BarcodeValidator.isValid("7290001165189", Barcode.FORMAT_EAN_13))
        assertFalse(BarcodeValidator.isValid("7290001765188", Barcode.FORMAT_EAN_13))
    }

    @Test
    fun `EAN-13 with a digit dropped or non-digits is rejected`() {
        assertFalse(BarcodeValidator.isValid("729000116518", Barcode.FORMAT_EAN_13))
        assertFalse(BarcodeValidator.isValid("72900011651A8", Barcode.FORMAT_EAN_13))
    }

    @Test
    fun `EAN-8 and UPC-A check digits are verified`() {
        assertTrue(BarcodeValidator.isValid("96385074", Barcode.FORMAT_EAN_8))
        assertFalse(BarcodeValidator.isValid("96385075", Barcode.FORMAT_EAN_8))
        assertTrue(BarcodeValidator.isValid("036000291452", Barcode.FORMAT_UPC_A))
        assertFalse(BarcodeValidator.isValid("036000291453", Barcode.FORMAT_UPC_A))
    }

    @Test
    fun `UPC-E is checked against its expanded UPC-A form`() {
        // 04252614 expands to 042100005264, the textbook example.
        assertEquals("042100005264", BarcodeValidator.expandUpcE("04252614"))
        assertTrue(BarcodeValidator.isValid("04252614", Barcode.FORMAT_UPC_E))
        assertFalse(BarcodeValidator.isValid("04252615", Barcode.FORMAT_UPC_E))
        assertFalse(BarcodeValidator.isValid("24252614", Barcode.FORMAT_UPC_E))
    }

    @Test
    fun `short Code 128 and Code 39 fragments are rejected`() {
        assertFalse(BarcodeValidator.isValid("12", Barcode.FORMAT_CODE_128))
        assertFalse(BarcodeValidator.isValid("A1", Barcode.FORMAT_CODE_39))
        assertTrue(BarcodeValidator.isValid("PRD_00123", Barcode.FORMAT_CODE_128))
        assertTrue(BarcodeValidator.isValid("ABC-123", Barcode.FORMAT_CODE_39))
    }

    @Test
    fun `blank, padded or control-character values are rejected for every format`() {
        for (format in listOf(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_CODE_128)) {
            assertFalse(BarcodeValidator.isValid(null, format))
            assertFalse(BarcodeValidator.isValid("   ", format))
            assertFalse(BarcodeValidator.isValid(" 07017001", format))
            assertFalse(BarcodeValidator.isValid("0701\u00007001", format))
        }
    }

    @Test
    fun `GS1 codes keep their field separator`() {
        // The milk-bottle Data Matrix exactly as ML Kit returns it: a leading
        // GS (FNC1) plus one between fields.
        val gs1 = "\u001D0104607078117294215fGHNL\u001D93H/xN"
        assertTrue(BarcodeValidator.isValid(gs1, Barcode.FORMAT_DATA_MATRIX))
        assertTrue(BarcodeValidator.isValid("0107290001165188\u001D10ABC123", Barcode.FORMAT_CODE_128))
        assertFalse(BarcodeValidator.isValid("0701\u001E7001", Barcode.FORMAT_DATA_MATRIX))
    }

    @Test
    fun `shelf Data Matrix values are accepted as-is`() {
        assertTrue(BarcodeValidator.isValid("07017001", Barcode.FORMAT_DATA_MATRIX))
        assertTrue(BarcodeValidator.isValid("07-02-60-01", Barcode.FORMAT_QR_CODE))
    }

    @Test
    fun `a check digit alone cannot catch every misread`() {
        // An OpenCV decode of resurse/20260902_151045.jpg returned this for a
        // box whose barcode is really 4007817530627: a wrong read that still
        // has a valid UPC-A check digit. Documents why ScanConsensus exists.
        assertTrue(BarcodeValidator.isValid("005817530627", Barcode.FORMAT_UPC_A))
    }
}
