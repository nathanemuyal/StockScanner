package com.warehouse.stockscanner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.warehouse.stockscanner.data.ProductEntity

class SearchResultAdapter(
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
    }

    override fun getItemCount(): Int = items.size
}
