package com.warehouse.stockscanner.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ProductEntity.totalUnits] is the one place the three quantity modes'
 * arithmetic lives — the inventory screen's live preview and what actually
 * gets stored both go through it, so they can never disagree about what a
 * row adds up to.
 */
class ProductEntityTest {

    @Test
    fun `units mode is the typed count, ignoring any package fields`() {
        assertEquals(15, ProductEntity.totalUnits(ProductEntity.TYPE_UNITS, 12, 5, 7, 15))
    }

    @Test
    fun `package mode multiplies content by count, ignoring the typed unit count`() {
        assertEquals(60, ProductEntity.totalUnits(ProductEntity.TYPE_PACKAGE, 12, 5, 7, 15))
    }

    @Test
    fun `mixed mode adds the loose units on top of the packages`() {
        assertEquals(67, ProductEntity.totalUnits(ProductEntity.TYPE_MIXED, 12, 5, 7, 0))
    }

    @Test
    fun `mixed mode with nothing loose is just the packages`() {
        assertEquals(60, ProductEntity.totalUnits(ProductEntity.TYPE_MIXED, 12, 5, 0, 0))
    }

    @Test
    fun `mixed mode with no packages is just the loose units`() {
        assertEquals(7, ProductEntity.totalUnits(ProductEntity.TYPE_MIXED, 0, 0, 7, 0))
    }

    @Test
    fun `an unrecognized mode falls back to the plain unit count`() {
        assertEquals(15, ProductEntity.totalUnits("משהו אחר", 12, 5, 7, 15))
    }
}
