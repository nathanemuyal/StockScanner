package com.warehouse.stockscanner

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.warehouse.stockscanner.data.ProductRepository
import kotlinx.coroutines.launch

/** Fallback flow when a scanned barcode is not found: forgiving free-text search by תאור. */
class SearchActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SELECTED_SKU = "selected_sku"
    }

    private lateinit var repository: ProductRepository
    private lateinit var adapter: SearchResultAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        repository = (application as StockScannerApp).repository

        val etQuery = findViewById<EditText>(R.id.etQuery)
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerResults)

        adapter = SearchResultAdapter { product ->
            val result = Intent().putExtra(EXTRA_SELECTED_SKU, product.sku)
            setResult(RESULT_OK, result)
            finish()
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        etQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                performSearch(s?.toString().orEmpty())
            }
        })

        findViewById<Button>(R.id.btnCancelSearch).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun performSearch(query: String) {
        lifecycleScope.launch {
            val results = if (query.isBlank()) emptyList() else repository.searchByDescription(query)
            adapter.submitList(results)
        }
    }
}
