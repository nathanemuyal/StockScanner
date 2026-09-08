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
 */
class SearchResultAdapter(
    private val onRemoveClick: ((ProductEntity) -> Unit)? = null,
    private val onClick: (ProductEntity) -> Unit
) : RecyclerView.Adapter<SearchResultAdapter.ViewHolder>() {

    private var items: List<ProductEntity> = emptyList()

    fun submitList(newItems: List<ProductEntity>) {
        items = newItems
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSku: TextView = view.findViewById(R.id.tvItemSku)
        val tvDescription: TextView = view.findViewById(R.id.tvItemDescription)
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
