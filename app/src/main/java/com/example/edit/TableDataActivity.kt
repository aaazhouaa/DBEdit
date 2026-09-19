package com.example.edit

import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.edit.databinding.ActivityTableDataBinding

class TableDataActivity : BaseActivity() {

    private lateinit var binding: ActivityTableDataBinding
    private lateinit var table: String
    private var columns: List<ColumnInfo> = emptyList()
    private var colNames: List<String> = emptyList()
    private var widths: IntArray = IntArray(0)
    private var page = 0
    private var total = 0
    private var keyword = ""
    private var readOnly = false
    private lateinit var adapter: RowAdapter

    private val pageSize = DbManager.PAGE_SIZE
    private val density get() = resources.displayMetrics.density

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            uri ?: return@registerForActivityResult
            runCatching {
                contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    manager.exportCsv(table, out)
                } ?: throw IllegalStateException("无法写入")
            }.onSuccess { toast("已导出 $it 行到 CSV") }
                .onFailure { showError("导出失败", it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTableDataBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        table = intent.getStringExtra(EXTRA_TABLE) ?: run {
            finish(); return
        }
        // 返回时若本页内有未保存的改动，先问是否保存
        setBarTitle(table, showBack = true)
        toolbar.setNavigationOnClickListener { confirmLeave { finish() } }

        columns = manager.columns(table)
        colNames = columns.map { it.name }
        readOnly = manager.isView(table)
        widths = computeWidths()
        buildHeader()

        adapter = RowAdapter()
        binding.rvData.layoutManager = LinearLayoutManager(this)
        binding.rvData.adapter = adapter

        binding.btnSearch.setOnClickListener { applySearch() }
        binding.btnClear.setOnClickListener {
            keyword = ""
            page = 0
            binding.etSearch.setText("")
            reload()
        }
        binding.etSearch.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                applySearch(); true
            } else false
        }
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (s.isNullOrEmpty() && keyword.isNotEmpty()) {
                    keyword = ""
                    page = 0
                    reload()
                }
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        binding.btnPrev.setOnClickListener {
            if (page > 0) {
                page--; reload()
            }
        }
        binding.btnNext.setOnClickListener {
            if ((page + 1) * pageSize < total) {
                page++; reload()
            }
        }
        binding.btnAddRow.setOnClickListener {
            if (readOnly) toast("视图是只读的") else editRow(null, null, null)
        }
        binding.btnAddRow.isEnabled = !readOnly

        reload()
    }

    private fun applySearch() {
        keyword = binding.etSearch.text.toString().trim()
        page = 0
        reload()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) reload()
    }

    override fun onDatabaseSaved() = toast("已保存")

    // ---------------- 布局尺寸 ----------------

    private fun computeWidths(): IntArray {
        val sample = try {
            manager.queryPage(table, 0, 30, "", colNames).rows
        } catch (_: Exception) {
            emptyList<RowData>()
        }
        return IntArray(colNames.size) { i ->
            var maxLen = colNames[i].length
            sample.forEach { r ->
                val v = r.values.getOrNull(i)
                if (v != null) maxLen = maxOf(maxLen, minOf(v.length, 40))
            }
            val dp = (maxLen * 8.5f + 24f).coerceIn(90f, 260f)
            (dp * density).toInt()
        }
    }

    private fun px(dp: Float) = (dp * density).toInt()

    private fun makeCell(text: String, width: Int, header: Boolean = false, center: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            layoutParams = LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = if (center) Gravity.CENTER else Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(px(8f), px(10f), px(8f), px(10f))
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            textSize = if (header) 13f else 14f
            if (header) {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(0xFF144FA8.toInt())
            }
        }

    private fun buildHeader() {
        val row = binding.rowHeader
        row.removeAllViews()
        row.addView(makeCell("#", px(46f), header = true, center = true))
        colNames.forEachIndexed { i, name ->
            val col = columns[i]
            row.addView(makeCell(if (col.isPk) "$name 🔑" else name, widths[i], header = true))
        }
        row.addView(makeCell("操作", px(48f), header = true, center = true))
        // 与数据区保持一致：固定总宽 + 固定高度，避免每次数据变化都全量重新测量
        binding.rowHeader.layoutParams = LinearLayout.LayoutParams(rowWidth(), px(44f))
        binding.rvData.layoutParams = LinearLayout.LayoutParams(rowWidth(), 0).apply {
            weight = 1f
        }
    }

    private fun rowWidth(): Int {
        var w = px(46f) /* 序号 */ + px(48f) /* 操作 */
        widths.forEach { w += it }
        return w
    }

    // ---------------- 数据加载 ----------------

    private fun reload() {
        try {
            val data = manager.queryPage(table, page * pageSize, pageSize, keyword, colNames)
            total = data.total
            adapter.submit(data)
            val pages = if (total == 0) 1 else (total + pageSize - 1) / pageSize
            binding.tvPage.text = "第 ${page + 1}/$pages 页 · 共 $total 行"
            binding.tvStatus.text = buildString {
                append("${colNames.size} 列 · 定位方式 ${manager.keySpec(table).label}")
                if (readOnly) append(" · 视图（只读）")
                if (keyword.isNotEmpty()) append(" · 搜索「$keyword」命中 $total 行")
                if (manager.dirty) append(" · ⚠ 有未保存修改")
            }
            val empty = data.rows.isEmpty()
            binding.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
            binding.rvData.visibility = if (empty) View.GONE else View.VISIBLE
            binding.tvEmpty.text = when {
                readOnly && keyword.isEmpty() -> "这个视图没有返回任何行"
                keyword.isEmpty() -> "这张表没有数据，点下方「＋行」新增"
                else -> "没有匹配「$keyword」的行"
            }
            binding.btnPrev.isEnabled = page > 0
            binding.btnNext.isEnabled = (page + 1) * pageSize < total
        } catch (e: Exception) {
            showError("读取数据失败", e)
        }
    }

    // ---------------- 编辑 ----------------

    /**
     * 编辑/新增一行。
     * @param row 为 null 表示新增
     * @param key 编辑已有行时的定位键
     * @param focusCol 打开后聚焦的列名
     */
    private fun editRow(row: RowData?, key: RowKey?, focusCol: String?) {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20f), px(8f), px(20f), px(8f))
        }
        scroll.addView(container)

        val edits = ArrayList<EditText>(columns.size)
        val nullBoxes = ArrayList<CheckBox>(columns.size)

        columns.forEachIndexed { i, col ->
            val isAutoPk = col.isPk && col.declType.equals("INTEGER", ignoreCase = true)
            container.addView(TextView(this).apply {
                text = buildString {
                    append(col.name)
                    append("   ")
                    append(col.declType.ifBlank { "无类型" })
                    if (col.isPk) append("   🔑主键")
                    if (col.notNull) append("   NOT NULL")
                    col.defaultValue?.let { append("   DEFAULT $it") }
                }
                textSize = 11f
                setTextColor(0xFF666666.toInt())
                setPadding(0, px(10f), 0, px(2f))
            })

            val edit = EditText(this).apply {
                setText(if (row == null) "" else row.values.getOrNull(i) ?: "")
                textSize = 14f
                hint = if (isAutoPk && row == null) "留空自动生成" else col.declType
                maxLines = 4
                isEnabled = !(isAutoPk && row == null)
            }
            container.addView(edit)
            edits.add(edit)

            val mounted = row != null
            val alreadyNull = mounted && row!!.nulls.getOrNull(i) == true
            val cb = CheckBox(this).apply {
                text = "设为 NULL"
                textSize = 12f
                isChecked = alreadyNull
                visibility = if (mounted || !isAutoPk) View.VISIBLE else View.INVISIBLE
                setOnCheckedChangeListener { _, checked ->
                    edit.isEnabled = !checked && !(isAutoPk && row == null)
                    if (checked) edit.setText("")
                }
            }
            container.addView(cb)
            nullBoxes.add(cb)
            if (alreadyNull) {
                edit.isEnabled = false
                edit.setText("")
            }
        }

        val positiveLabel = if (row == null) "插入" else "保存修改"
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (row == null) "新增行 · $table" else "编辑行 · $table")
            .setView(scroll)
            .setPositiveButton(positiveLabel, null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (row == null) doInsert(dialog, edits, nullBoxes)
                else doUpdate(dialog, row, key ?: return@setOnClickListener, edits, nullBoxes)
            }
        }
        dialog.show()

        focusCol?.let { name ->
            val idx = colNames.indexOf(name)
            if (idx >= 0 && edits[idx].isEnabled) {
                edits[idx].requestFocus()
                edits[idx].setSelection(edits[idx].text.length)
            }
        }
    }

    private fun doInsert(dialog: AlertDialog, edits: List<EditText>, nullBoxes: List<CheckBox>) {
        val values = LinkedHashMap<String, String?>()
        columns.forEachIndexed { i, col ->
            val isAutoPk = col.isPk && col.declType.equals("INTEGER", ignoreCase = true)
            if (isAutoPk && edits[i].text.isNullOrEmpty()) return@forEachIndexed
            values[col.name] = if (nullBoxes[i].isChecked) null else edits[i].text.toString()
        }
        runCatching { manager.insertRow(table, values) }
            .onSuccess {
                toast("已插入（记得保存）")
                dialog.dismiss()
                reload()
            }
            .onFailure { showError("插入失败", it) }
    }

    private fun doUpdate(
        dialog: AlertDialog,
        row: RowData,
        key: RowKey,
        edits: List<EditText>,
        nullBoxes: List<CheckBox>
    ) {
        val pkNames = columns.filter { it.isPk }.map { it.name }
        val changed = LinkedHashMap<String, String?>()
        columns.forEachIndexed { i, col ->
            if (col.name in pkNames) return@forEachIndexed // 主键不允许改
            val newVal = if (nullBoxes[i].isChecked) null else edits[i].text.toString()
            val oldVal = if (row.nulls.getOrNull(i) == true) null else row.values.getOrNull(i)
            if (newVal != oldVal) changed[col.name] = newVal
        }
        if (changed.isEmpty()) {
            toast("没有修改")
            dialog.dismiss()
            return
        }
        runCatching { manager.updateRow(table, key, changed) }
            .onSuccess {
                toast("已更新（记得保存）")
                dialog.dismiss()
                reload()
            }
            .onFailure { showError("更新失败", it) }
    }

    private fun deleteRowWithConfirm(key: RowKey) {
        confirm("删除行", "确定删除这一行？") {
            runCatching { manager.deleteRow(table, key) }
                .onSuccess {
                    toast("已删除（记得保存）")
                    reload()
                }
                .onFailure { showError("删除失败", it) }
        }
    }

    private fun showRowMenu(row: RowData, key: RowKey) {
        if (readOnly) {
            copyToClipboard(
                colNames.indices.joinToString("\n") { i ->
                    "${colNames[i]}: " +
                            if (row.nulls.getOrNull(i) == true) "NULL" else row.values.getOrNull(i)
                }
            )
            return
        }
        AlertDialog.Builder(this)
            .setTitle("行 $key")
            .setItems(arrayOf("编辑本行", "复制本行为文本", "删除本行")) { _, which ->
                when (which) {
                    0 -> editRow(row, key, null)
                    1 -> copyToClipboard(
                        colNames.indices.joinToString("\n") { i ->
                            "${colNames[i]}: " +
                                    if (row.nulls.getOrNull(i) == true) "NULL" else row.values.getOrNull(i)
                        }
                    )
                    else -> deleteRowWithConfirm(key)
                }
            }
            .show()
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("cell", text))
        toast("已复制到剪贴板")
    }

    // ---------------- 菜单 ----------------

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.menu_table_data, menu)
        // 视图是只读的：隐藏所有写操作入口
        menu.findItem(R.id.action_schema)?.isVisible = !readOnly
        menu.findItem(R.id.action_clear_table)?.isVisible = !readOnly
        menu.findItem(R.id.action_drop_table)?.isVisible = !readOnly
        return true
    }

    private val schemaLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) reloadSchemaAndData()
    }

    private fun reloadSchemaAndData() {
        columns = manager.columns(table)
        colNames = columns.map { it.name }
        widths = computeWidths()
        buildHeader()
        page = 0
        reload()
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_schema -> {
                schemaLauncher.launch(
                    android.content.Intent(this, ColumnsEditorActivity::class.java)
                        .putExtra(ColumnsEditorActivity.EXTRA_TABLE, table)
                )
                return true
            }
            R.id.action_refresh -> {
                reloadSchemaAndData(); return true
            }
            R.id.action_clear_table -> {
                confirm("清空数据", "确定清空表「$table」的全部数据？") {
                    runCatching { manager.clearTable(table) }
                        .onSuccess { toast("已清空（记得保存）"); reload() }
                        .onFailure { showError("清空失败", it) }
                }
                return true
            }
            R.id.action_drop_table -> {
                confirm("删除表", "确定删除表「$table」？该操作不可撤销。") {
                    runCatching { manager.dropTable(table) }
                        .onSuccess {
                            toast("已删除（记得保存）")
                            finish()
                        }
                        .onFailure { showError("删除失败", it) }
                }
                return true
            }
            R.id.action_export_csv -> {
                exportLauncher.launch("$table.csv"); return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    @Deprecated("兼容旧版行为")
    override fun onBackPressed() {
        confirmLeave { @Suppress("DEPRECATION") super.onBackPressed() }
    }

    // ---------------- 表格适配器 ----------------

    /** 单元格视图复用：行内单元格数量固定，避免每次绑定都重建 View */
    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {
        private var data: PageData = PageData(emptyList(), emptyList(), emptyList(), 0)

        fun submit(p: PageData) {
            data = p
            notifyDataSetChanged()
        }

        inner class VH(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
            val content: LinearLayout = root.getChildAt(0) as LinearLayout
            val divider: View = root.getChildAt(1)
            val seq: TextView = content.getChildAt(0) as TextView
            val del: TextView = content.getChildAt(content.childCount - 1) as TextView
            var cells: MutableList<TextView> = mutableListOf()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val content = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            content.addView(makeCell("", px(46f), center = true))
            // 数据列单元格占位，后续按需补齐
            content.addView(makeCell("", px(48f), center = true))
            val divider = View(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                )
                setBackgroundColor(0xFFEEEEEE.toInt())
            }
            val root = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    rowWidth(), RecyclerView.LayoutParams.WRAP_CONTENT
                )
                addView(content)
                addView(divider)
            }
            val vh = VH(root)
            rebuildCells(vh)
            return vh
        }

        /** 根据列数重建单元格视图（列结构变化时） */
        private fun rebuildCells(vh: VH) {
            // 只重建数据列：保留首尾（序号 / 删除）
            val head = vh.content.getChildAt(0)
            val tail = vh.content.getChildAt(vh.content.childCount - 1)
            vh.content.removeAllViews()
            vh.content.addView(head)
            vh.cells = ArrayList(colNames.size)
            colNames.indices.forEach { i ->
                val tv = makeCell("", widths.getOrElse(i) { px(120f) })
                vh.cells.add(tv)
                vh.content.addView(tv)
            }
            vh.content.addView(tail)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            if (holder.cells.size != colNames.size) rebuildCells(holder)

            val row = data.rows.getOrNull(position) ?: return
            val key = data.keys.getOrNull(position) ?: RowKey.Rowid(-1)

            holder.seq.text = "${page * pageSize + position + 1}"
            holder.seq.setTextColor(0xFF888888.toInt())
            holder.seq.setBackgroundColor(0xFFF0F0F0.toInt())

            colNames.indices.forEach { i ->
                val tv = holder.cells[i]
                val isNull = row.nulls.getOrNull(i) == true
                tv.text = SqlUtil.displayCell(row.values.getOrNull(i), isNull)
                tv.setTextColor(if (isNull) 0xFF999999.toInt() else 0xFF222222.toInt())
                tv.layoutParams = LinearLayout.LayoutParams(
                    widths.getOrElse(i) { px(120f) },
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                val col = colNames[i]
                tv.setOnClickListener { if (!readOnly) editRow(row, key, col) }
                tv.setOnLongClickListener {
                    showRowMenu(row, key)
                    true
                }
            }

            holder.del.text = "✕"
            holder.del.setTextColor(0xFFC62828.toInt())
            holder.del.visibility = if (readOnly) View.GONE else View.VISIBLE
            holder.del.setOnClickListener { deleteRowWithConfirm(key) }
        }

        override fun getItemCount() = data.rows.size
    }
}
