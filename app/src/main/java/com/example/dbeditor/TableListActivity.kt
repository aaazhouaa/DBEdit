package com.example.dbeditor

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.dbeditor.databinding.ActivityTableListBinding
import com.example.dbeditor.databinding.ItemTableBinding

class TableListActivity : BaseActivity() {

    private lateinit var binding: ActivityTableListBinding
    private var allTables: List<TableInfo> = emptyList()
    private lateinit var adapter: TableAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTableListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        setBarTitle("数据表 · ${manager.sourceName}")

        adapter = TableAdapter { info -> openTable(info.name) }
        binding.rvTables.layoutManager = LinearLayoutManager(this)
        binding.rvTables.adapter = adapter

        binding.tvDbName.text =
            "文件：${manager.sourceName}　　路径：${manager.workingFile?.absolutePath ?: "-"}" +
                    "\n点击打开数据；长按可重命名 / 改结构 / 删除"

        binding.etFilter.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString() ?: "")
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        binding.btnSearchAll.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        binding.btnSql.setOnClickListener { showSqlConsole() }
        binding.btnInfo.setOnClickListener { showDbInfo() }

        adapter.onLongClick = { info -> showTableMenu(info) }
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.menu_table_list, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_new_table -> {
                newTableLauncher.launch(
                    android.content.Intent(this, ColumnsEditorActivity::class.java)
                        .putExtra(ColumnsEditorActivity.EXTRA_IS_NEW, true)
                )
                return true
            }
            R.id.action_refresh -> {
                load(); return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private val schemaLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) load()
    }

    private val newTableLauncher = schemaLauncher

    private fun load() {
        runCatching { manager.listTables() }
            .onSuccess {
                allTables = it
                applyFilter(binding.etFilter.text?.toString() ?: "")
            }
            .onFailure { showError("读取表失败", it) }
    }

    private fun applyFilter(kw: String) {
        val list = if (kw.isBlank()) allTables
        else allTables.filter { it.name.contains(kw, ignoreCase = true) }
        adapter.submit(list)
        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.rvTables.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        binding.tvEmpty.text = if (allTables.isEmpty()) "这个数据库里还没有表" else "没有匹配的表"
    }

    private fun openTable(name: String) {
        startActivity(Intent(this, TableDataActivity::class.java).putExtra(EXTRA_TABLE, name))
    }

    private fun editSchema(name: String) {
        schemaLauncher.launch(
            android.content.Intent(this, ColumnsEditorActivity::class.java)
                .putExtra(ColumnsEditorActivity.EXTRA_TABLE, name)
        )
    }

    private fun showTableMenu(info: TableInfo) {
        val items = if (info.isView) {
            arrayOf("查看数据", "删除视图")
        } else {
            arrayOf("打开数据", "编辑表结构（加/删/改列）", "重命名", "清空数据", "删除表")
        }
        AlertDialog.Builder(this)
            .setTitle(if (info.isView) "${info.name}（视图）" else info.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openTable(info.name)
                    1 -> if (info.isView) confirmDropView(info.name) else editSchema(info.name)
                    2 -> renameTable(info.name)
                    3 -> confirm("清空数据", "确定清空表「${info.name}」中的全部数据？") {
                        runCatching { manager.clearTable(info.name) }
                            .onSuccess { toast("已清空（记得保存）"); load() }
                            .onFailure { showError("清空失败", it) }
                    }
                    4 -> confirm("删除表", "确定删除表「${info.name}」？该操作不可撤销。") {
                        runCatching { manager.dropTable(info.name) }
                            .onSuccess { toast("已删除（记得保存）"); load() }
                            .onFailure { showError("删除失败", it) }
                    }
                }
            }
            .show()
    }

    private fun confirmDropView(name: String) {
        confirm("删除视图", "确定删除视图「$name」？（只删视图，不影响原始表）") {
            runCatching { manager.dropView(name) }
                .onSuccess { toast("已删除（记得保存）"); load() }
                .onFailure { showError("删除失败", it) }
        }
    }

    private fun renameTable(oldName: String) {
        val input = EditText(this).apply {
            setText(oldName)
            setSelection(oldName.length)
        }
        AlertDialog.Builder(this)
            .setTitle("重命名表")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty() || newName == oldName) return@setPositiveButton
                runCatching { manager.renameTable(oldName, newName) }
                    .onSuccess { toast("已重命名（记得保存）"); load() }
                    .onFailure { showError("重命名失败", it) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDbInfo() {
        val lines = manager.databaseStats().joinToString("\n") { "${it.first}: ${it.second}" }
        AlertDialog.Builder(this)
            .setTitle("数据库信息")
            .setMessage("文件：${manager.sourceName}\n${manager.workingFile?.absolutePath}\n\n$lines")
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun showSqlConsole() {
        val input = EditText(this).apply {
            hint = "输入 SQL，如：SELECT * FROM users LIMIT 10"
            minLines = 4
            maxLines = 8
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        AlertDialog.Builder(this)
            .setTitle("执行 SQL")
            .setView(input)
            .setPositiveButton("执行") { _, _ ->
                runSql(input.text.toString())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runSql(sql: String) {
        try {
            val result = manager.runSql(sql)
            if (!result.isQuery) {
                toast(result.message)
                load()
                return
            }
            val msg = buildString {
                append(result.message).append("\n\n")
                append(result.columns.joinToString(" | ")).append("\n")
                append("-".repeat(24)).append("\n")
                result.rows.take(50).forEach { row ->
                    append(row.joinToString(" | ") { it ?: "NULL" }).append("\n")
                }
                if (result.rows.size > 50) append("\n…仅显示前 50 行")
            }
            AlertDialog.Builder(this)
                .setTitle("查询结果")
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .show()
        } catch (e: Exception) {
            showError("SQL 执行失败", e)
        }
    }

    private class TableAdapter(
        private val onClick: (TableInfo) -> Unit
    ) : RecyclerView.Adapter<TableAdapter.VH>() {

        var onLongClick: ((TableInfo) -> Unit)? = null
        private var items: List<TableInfo> = emptyList()

        fun submit(list: List<TableInfo>) {
            items = list
            notifyDataSetChanged()
        }

        class VH(val binding: ItemTableBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemTableBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val info = items[position]
            val res = holder.itemView.context
            holder.binding.tvName.text = if (info.isView) {
                "${info.name}   [${res.getString(R.string.view_readonly)}]"
            } else {
                info.name
            }
            holder.binding.tvSub.text = when {
                info.isView -> res.getString(R.string.view_readonly)
                info.rowCount >= 0 -> res.getString(R.string.row_count_format, info.rowCount)
                else -> res.getString(R.string.row_count_unknown)
            }
            // 视图用眼睛图标，与表区分开
            holder.binding.ivIcon.setImageResource(
                if (info.isView) R.drawable.ic_eye else R.drawable.ic_database
            )
            holder.binding.root.setOnClickListener { onClick(info) }
            holder.binding.root.setOnLongClickListener {
                onLongClick?.invoke(info)
                true
            }
        }

        override fun getItemCount() = items.size
    }
}
