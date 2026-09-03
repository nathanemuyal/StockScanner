package com.warehouse.stockscanner.util

import com.warehouse.stockscanner.data.ProductEntity

/**
 * Forgiving free-text search over the תאור (description) column:
 * case-insensitive, ignores extra whitespace, matches partial text and
 * matches by individual words, best matches first.
 */
object SearchUtils {

    private fun normalize(text: String): String =
        text.trim().replace(Regex("\\s+"), " ").lowercase()

    private data class ScoredProduct(val product: ProductEntity, val score: Int)

    fun search(products: List<ProductEntity>, query: String, limit: Int = 30): List<ProductEntity> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isEmpty()) return emptyList()

        val tokens = normalizedQuery.split(" ").filter { it.isNotEmpty() }

        val scored = products.mapNotNull { product ->
            val normalizedDesc = normalize(product.description)
            if (normalizedDesc.isEmpty()) return@mapNotNull null

            val score = when {
                normalizedDesc == normalizedQuery -> 10000
                normalizedDesc.contains(normalizedQuery) -> 5000 - normalizedDesc.length
                else -> {
                    val matchedTokens = tokens.count { normalizedDesc.contains(it) }
                    if (matchedTokens > 0) {
                        (matchedTokens * 100 / tokens.size) - (normalizedDesc.length / 10)
                    } else {
                        0
                    }
                }
            }

            if (score > 0) ScoredProduct(product, score) else null
        }

        return scored.sortedByDescending { it.score }.take(limit).map { it.product }
    }
}
