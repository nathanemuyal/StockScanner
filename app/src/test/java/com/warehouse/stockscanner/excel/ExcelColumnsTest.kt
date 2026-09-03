package com.warehouse.stockscanner.excel

import org.junit.Assert.assertEquals
import org.junit.Test

class ExcelColumnsTest {

    @Test
    fun `letterToIndex handles single and double letters`() {
        assertEquals(0, ExcelColumns.letterToIndex("A"))
        assertEquals(1, ExcelColumns.letterToIndex("B"))
        assertEquals(25, ExcelColumns.letterToIndex("Z"))
        assertEquals(26, ExcelColumns.letterToIndex("AA"))
        assertEquals(27, ExcelColumns.letterToIndex("AB"))
    }

    @Test
    fun `indexToLetter is the inverse of letterToIndex`() {
        for (i in 0..200) {
            val letters = ExcelColumns.indexToLetter(i)
            assertEquals(i, ExcelColumns.letterToIndex(letters))
        }
    }

    @Test
    fun `columnLettersFromRef extracts the letters from a cell reference`() {
        assertEquals("B", ExcelColumns.columnLettersFromRef("B7"))
        assertEquals("AA", ExcelColumns.columnLettersFromRef("AA123"))
        assertEquals("", ExcelColumns.columnLettersFromRef("not-a-ref"))
    }
}
