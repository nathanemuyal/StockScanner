package com.warehouse.stockscanner

import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.warehouse.stockscanner.data.ProductEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises [SearchResultAdapter] directly (no Activity needed): the "−"
 * removal button must only ever exist where the caller actually wants it
 * (MainActivity's shelf list), stay hidden everywhere else (e.g.
 * SearchActivity's select-only results), and never fire the row's own
 * [onClick] when it's the button — not the row — that was tapped.
 */
@RunWith(RobolectricTestRunner::class)
class SearchResultAdapterTest {

    private val product = ProductEntity("ABC-123", "מוצר", "111", "A-01-05", 0)

    // The row layout references ?attr/selectableItemBackground(Borderless),
    // which only resolves under the app's own theme — the plain application
    // context (no theme applied) fails to inflate it, same as it would for
    // any view inflated outside an Activity.
    private fun bindSingleItem(adapter: SearchResultAdapter): View {
        val themedContext = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(),
            R.style.Theme_StockScanner
        )
        val parent = FrameLayout(themedContext)
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.submitList(listOf(product))
        adapter.onBindViewHolder(holder, 0)
        return holder.itemView
    }

    @Test
    fun `remove button is hidden when the adapter is given no onRemoveClick, as in the search results list`() {
        val adapter = SearchResultAdapter(onClick = {})
        val itemView = bindSingleItem(adapter)

        assertEquals(View.GONE, itemView.findViewById<View>(R.id.btnRemoveFromLocation).visibility)
    }

    @Test
    fun `remove button is visible and shown when onRemoveClick is given, as in the shelf list`() {
        val adapter = SearchResultAdapter(onClick = {}, onRemoveClick = {})
        val itemView = bindSingleItem(adapter)

        assertEquals(View.VISIBLE, itemView.findViewById<View>(R.id.btnRemoveFromLocation).visibility)
    }

    @Test
    fun `tapping the remove button calls onRemoveClick with that product, never onClick`() {
        var removeClicked: ProductEntity? = null
        var rowClicked: ProductEntity? = null
        val adapter = SearchResultAdapter(
            onClick = { rowClicked = it },
            onRemoveClick = { removeClicked = it }
        )
        val itemView = bindSingleItem(adapter)

        itemView.findViewById<View>(R.id.btnRemoveFromLocation).performClick()

        assertEquals(product, removeClicked)
        assertNull("the row's own onClick must not also fire", rowClicked)
    }

    @Test
    fun `tapping the row itself calls onClick, never onRemoveClick`() {
        var removeClicked: ProductEntity? = null
        var rowClicked: ProductEntity? = null
        val adapter = SearchResultAdapter(
            onClick = { rowClicked = it },
            onRemoveClick = { removeClicked = it }
        )
        val itemView = bindSingleItem(adapter)

        itemView.performClick()

        assertEquals(product, rowClicked)
        assertNull("tapping the row/cell as a whole must never remove it", removeClicked)
    }
}
