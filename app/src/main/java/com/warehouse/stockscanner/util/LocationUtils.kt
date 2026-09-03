package com.warehouse.stockscanner.util

/**
 * A product can now sit in more than one warehouse location at once. Rather
 * than changing the Excel schema (still a single `מיקום` column, one row per
 * sku), multiple locations are stored as a comma-separated list in that same
 * cell, e.g. "A-01-05, A-01-06". This keeps the file format the user asked
 * for unchanged, while supporting many locations per product.
 */
object LocationUtils {
    private const val DELIMITER = ", "

    /** Splits a raw מיקום cell value into its individual locations. */
    fun parse(raw: String): List<String> =
        raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun format(locations: List<String>): String = locations.joinToString(DELIMITER)

    /** Whether [location] is already one of the locations encoded in [raw]. */
    fun contains(raw: String, location: String): Boolean {
        val target = location.trim()
        if (target.isEmpty()) return false
        return parse(raw).any { it == target }
    }

    /**
     * Adds [newLocation] to the set already encoded in [raw], without
     * duplicating it if it's already present. This is what a confirmed scan
     * applies — a product is never moved, only ever gained a location.
     */
    fun add(raw: String, newLocation: String): String {
        val trimmedNew = newLocation.trim()
        if (trimmedNew.isEmpty()) return format(parse(raw))
        val existing = parse(raw)
        if (existing.contains(trimmedNew)) return format(existing)
        return format(existing + trimmedNew)
    }
}
