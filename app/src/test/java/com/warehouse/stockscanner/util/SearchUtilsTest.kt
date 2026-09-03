package com.warehouse.stockscanner.util

import com.warehouse.stockscanner.data.ProductEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchUtilsTest {

    private fun product(sku: String, description: String) =
        ProductEntity(sku, description, "", "", 0)

    private val catalog = listOf(
        product("ABC-123", "פילטר שמן טויוטה"),
        product("ABC-456", "פילטר שמן מנוע טויוטה"),
        product("XYZ-789", "פילטר שמן טויוטה דיזל"),
        product("Q-1", "מצבר 12V"),
        product("Q-2", "פנס אחורי ימין")
    )

    @Test
    fun `exact substring match ranks above partial word match`() {
        val results = SearchUtils.search(catalog, "פילטר שמן טויוטה")
        assertTrue(results.isNotEmpty())
        assertEquals("ABC-123", results.first().sku)
    }

    @Test
    fun `is case and whitespace insensitive`() {
        val results = SearchUtils.search(catalog, "   פילטר   שמן   ")
        assertTrue(results.any { it.sku == "ABC-123" })
        assertTrue(results.any { it.sku == "ABC-456" })
        assertTrue(results.any { it.sku == "XYZ-789" })
    }

    @Test
    fun `matches by individual word even when order differs`() {
        val results = SearchUtils.search(catalog, "טויוטה דיזל")
        assertTrue(results.any { it.sku == "XYZ-789" })
    }

    @Test
    fun `unrelated query returns no results`() {
        val results = SearchUtils.search(catalog, "משהו שלא קיים בכלל")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `blank query returns no results`() {
        assertTrue(SearchUtils.search(catalog, "   ").isEmpty())
    }
}
