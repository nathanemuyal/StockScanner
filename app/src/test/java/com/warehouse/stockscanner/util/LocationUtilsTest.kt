package com.warehouse.stockscanner.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationUtilsTest {

    @Test
    fun `parse splits a single location`() {
        assertEquals(listOf("A-01-05"), LocationUtils.parse("A-01-05"))
    }

    @Test
    fun `parse splits multiple comma-separated locations`() {
        assertEquals(listOf("A-01-05", "A-01-06"), LocationUtils.parse("A-01-05, A-01-06"))
    }

    @Test
    fun `parse tolerates extra whitespace, empty segments and blank input`() {
        assertEquals(listOf("A-01-05", "A-01-06"), LocationUtils.parse("  A-01-05 ,, A-01-06  ,"))
        assertEquals(emptyList<String>(), LocationUtils.parse(""))
        assertEquals(emptyList<String>(), LocationUtils.parse("   "))
    }

    @Test
    fun `parse removes duplicates`() {
        assertEquals(listOf("A-01-05", "A-01-06"), LocationUtils.parse("A-01-05, A-01-06, A-01-05"))
    }

    @Test
    fun `contains matches an exact location, not a substring of another`() {
        assertTrue(LocationUtils.contains("A-01-05, A-01-10", "A-01-10"))
        assertTrue(LocationUtils.contains("A-01-1", "A-01-1"))
        // "A-01-1" must not falsely match because it's a text-prefix of "A-01-10".
        assertFalse(LocationUtils.contains("A-01-10", "A-01-1"))
        assertFalse(LocationUtils.contains("", "A-01-05"))
        assertFalse(LocationUtils.contains("A-01-05", ""))
    }

    @Test
    fun `add appends a new location to an existing one`() {
        assertEquals("A-01-05, A-01-06", LocationUtils.add("A-01-05", "A-01-06"))
    }

    @Test
    fun `add sets the location when there was none yet`() {
        assertEquals("A-01-05", LocationUtils.add("", "A-01-05"))
    }

    @Test
    fun `add does not duplicate a location the product is already at`() {
        assertEquals("A-01-05", LocationUtils.add("A-01-05", "A-01-05"))
        assertEquals("A-01-05, A-01-06", LocationUtils.add("A-01-05, A-01-06", "A-01-06"))
    }

    @Test
    fun `add preserves already-recorded locations when adding a third`() {
        assertEquals(
            "A-01-05, A-01-06, B-02-01",
            LocationUtils.add("A-01-05, A-01-06", "B-02-01")
        )
    }
}
