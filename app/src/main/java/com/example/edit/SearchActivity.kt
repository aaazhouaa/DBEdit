package com.example.edit

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.edit.databinding.ActivitySearchBinding
import com.example.edit.databinding.ItemHitBinding

/** 全库搜索：在所有表的文本列里找关键字，点结果可跳到对应表 */
class SearchActivity : BaseActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var adapter: HitAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        setBarTitle("全库搜索", showBack = true)
        toolbar.setNavigationOnClickListener { finish() }

        adapter = HitAdapter { hit ->
            // 跳到所在表（简单起见只打开表，行位置由用户再搜索）
            startActivity(
                Intent(this, TableDataActivity::class.java)
                    .putExtra(EXTRA_TABLE, hit.table)
            )
        }
        binding.rvHits.layoutManager = LinearLayoutManager(this)
        binding.rvHits.adapter = adapter

        binding.btnGo.setOnClickListener { doSearch() }
        binding.etKeyword.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                doSearch(); true
            } else false
        }
        binding.tvResult.text = "在所有表的文本字段中搜索"
    }

    private fun doSearch() {
        val kw = binding.etKeyword.text.toString().trim()
        if (kw.isEmpty()) {
            binding.tvResult.text = "请输入搜索内容"
            adapter.submit(emptyList())
            return
        }
        binding.tvResult.text = "搜索中…"
        try {
            val hits = manager.searchAll(kw)
            adapter.submit(hits)
            binding.tvResult.text = if (hits.isEmpty()) {
                "没有找到包含「$kw」的记录"
            } else {
                "找到 ${hits.size} 条包含「$kw」的记录（最多显示 300 条）"
            }
        } catch (e: Exception) {
            binding.tvResult.text = "搜索失败：${e.message}"
        }
    }

    override fun onBackPressed() {
        finish()
    }

    private class HitAdapter(
        private val onClick: (SearchHit) -> Unit
    ) : RecyclerView.Adapter<HitAdapter.VH>() {

        private var items: List<SearchHit> = emptyList()

        fun submit(list: List<SearchHit>) {
            items = list
            notifyDataSetChanged()
        }

        class VH(val binding: ItemHitBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemHitBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val hit = items[position]
            holder.binding.tvWhere.text = "${hit.table} › ${hit.column}   (${hit.key})"
            holder.binding.tvValue.text = hit.value.ifBlank { "(空)" }
            holder.binding.root.setOnClickListener { onClick(hit) }
        }

        override fun getItemCount() = items.size
    }
}
