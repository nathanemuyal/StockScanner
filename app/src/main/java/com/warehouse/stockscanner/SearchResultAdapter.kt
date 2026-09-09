package com.warehouse.stockscanner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.warehouse.stockscanner.data.ProductEntity

/**
 * [onRemoveClick] is optional: when given, every row shows a red "−" button
 * that calls it (used for the shelf list on the main screen, to undo a
 * product having been added to the current location); left null, the button
 * stays hidden and rows are select-only via [onClick] (e.g. description
 * search results). Either way, tapping the row itself only ever runs
 * [onClick] — removal always requires the dedicated button, never a tap on
 * the row/cell as a whole.
 *
 * A sku can now appear more than once in [items] — several rows at the same
 * מיקום, one per distinct ברקוד scanned there. When that happens, each such
 * row also shows its ברקוד (otherwise hidden) so it's clear which one a tap
 * on "−" would remove; a sku with only one row in the list stays as clean as
 * before.
 */
class SearchResultAdapter(
    private val onRemoveClick: ((ProductEntity) -> Unit)? = null,
    private val onClick: (ProductEntity) -> Unit
) : RecyclerView.Adapter<SearchResultAdapter.ViewHolder>() {

    private var items: List<ProductEntity> = emptyList()
    private var skusNeedingBarcode: Set<String> = emptySet()

    fun submitList(newItems: List<ProductEntity>) {
        items = newItems
        skusNeedingBarcode = newItems.groupingBy { it.sku }.eachCount()
            .filterValues { it > 1 }
            .keys
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSku: TextView = view.findViewById(R.id.tvItemSku)
        val tvDescription: TextView = view.findViewById(R.id.tvItemDescription)
        val tvBarcode: TextView = view.findViewById(R.id.tvItemBarcode)
        val btnRemove: TextView = view.findViewById(R.id.btnRemoveFromLocation)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_result, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val product = items[position]
        holder.tvSku.text = product.sku
        holder.tvDescription.text = product.description
        holder.itemView.setOnClickListener { onClick(product) }
        if (product.sku in skusNeedingBarcode) {
            holder.tvBarcode.text = "ברקוד: ${product.barcode.ifBlank { "—" }}"
            holder.tvBarcode.visibility = View.VISIBLE
        } else {
            holder.tvBarcode.visibility = View.GONE
        }
        if (onRemoveClick != null) {
            holder.btnRemove.visibility = View.VISIBLE
            holder.btnRemove.setOnClickListener { onRemoveClick.invoke(product) }
        } else {
            holder.btnRemove.visibility = View.GONE
            holder.btnRemove.setOnClickListener(null)
        }
    }

    override fun getItemCount(): Int = items.size
}
