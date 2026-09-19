package com.example.dbedit

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import com.example.dbedit.SqlUtil.DdlColumn

/**
 * 列定义编辑界面，同时用于「修改表结构」和「新建表」。
 *
 * 用代码搭 UI 而不是 XML：每行列定义结构固定、且行可增删，代码里更好维护。
 */
class ColumnsEditorActivity : BaseActivity() {

    companion object {
        const val EXTRA_TABLE = "table"
        const val EXTRA_IS_NEW = "is_new"
        const val NEW_COLUMN_LABEL = "(新列，无数据)"
    }

    private lateinit var container: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var tableNameEdit: EditText
    private val rows = mutableListOf<ColumnRow>()

    private var table: String = ""
    private var isNew = false

    /** 一行的控件引用 */
    private class ColumnRow(
        val root: LinearLayout,
        val name: EditText,
        val typeSpinner: Spinner,
        val typeEdit: EditText,
        val pkBox: CheckBox,
        val notNullBox: CheckBox,
        val autoIncBox: CheckBox,
        val defaultEdit: EditText,
        val sourceSpinner: Spinner?,
        val deleteBtn: Button
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_columns_editor)

        table = intent.getStringExtra(EXTRA_TABLE) ?: ""
        isNew = intent.getBooleanExtra(EXTRA_IS_NEW, false)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        setBarTitle(if (isNew) "新建表" else "结构 · $table")
        toolbar.setNavigationOnClickListener { finish() }

        container = findViewById(R.id.columnContainer)
        scroll = findViewById(R.id.scroll)
        tableNameEdit = findViewById(R.id.etTableName)

        if (isNew) {
            tableNameEdit.visibility = View.VISIBLE
            tableNameEdit.setText("new_table")
        }
        findViewById<TextView>(R.id.tvHint).text = if (isNew) {
            "新建表：勾选主键即可。自增只能用于单个 INTEGER 主键。"
        } else {
            "改动会重建这张表。数据按「数据来源」搬迁；新列若无默认值请允许为空，否则可能插入失败。"
        }

        findViewById<Button>(R.id.btnAddColumn).setOnClickListener {
            addRow(DdlColumn(name = "new_col", type = "TEXT"), allowDelete = true)
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        findViewById<Button>(R.id.btnApply).setOnClickListener { onApplyClicked() }

        loadExisting()
    }

    private fun loadExisting() {
        val columns = if (isNew) {
            listOf(
                DdlColumn("id", "INTEGER", pkPosition = 1, autoIncrement = true),
                DdlColumn("name", "TEXT", notNull = true)
            )
        } else {
            try {
                manager.tableSchema(table).columns
            } catch (e: Exception) {
                showError("读取结构失败", e)
                finish()
                return
            }
        }
        if (columns.isEmpty()) {
            toast("这张表没有可编辑的列")
            finish()
            return
        }
        columns.forEach { addRow(it, allowDelete = columns.size > 1) }
    }

    /** 原表已有的列名（用于「数据来源」下拉） */
    private fun originalColumnNames(): List<String> = if (isNew) emptyList() else
        runCatching { manager.columns(table).map { it.name } }.getOrDefault(emptyList())

    private fun addRow(col: DdlColumn, allowDelete: Boolean) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            setBackgroundColor(0xFFF7F7F7.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8f) }
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val title = TextView(this).apply {
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val deleteBtn = Button(this).apply {
            text = "删除此列"
            textSize = 12f
            isEnabled = allowDelete
            setOnClickListener {
                val row = rows.firstOrNull { it.root === root } ?: return@setOnClickListener
                rows.remove(row)
                container.removeView(root)
                renumber()
            }
        }
        header.addView(title)
        header.addView(deleteBtn)
        root.addView(header)

        root.addView(sectionLabel("列名"))
        val nameEdit = EditText(this).apply {
            setText(col.name)
            hint = "列名，如 title"
            textSize = 14f
        }
        root.addView(nameEdit)

        root.addView(sectionLabel("类型（可从下拉选，也可自己填）"))
        val typeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ColumnsEditorActivity,
                android.R.layout.simple_spinner_dropdown_item,
                SqlUtil.COMMON_TYPES + "自定义"
            )
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val typeEdit = EditText(this).apply {
            setText(col.type)
            hint = "类型"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
        }
        val presetIdx = SqlUtil.COMMON_TYPES.indexOfFirst { it.equals(col.type, true) }
        typeSpinner.setSelection(if (presetIdx >= 0) presetIdx else SqlUtil.COMMON_TYPES.size)
        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos < SqlUtil.COMMON_TYPES.size) {
                    typeEdit.setText(SqlUtil.COMMON_TYPES[pos])
                }
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        typeRow.addView(typeSpinner)
        typeRow.addView(typeEdit)
        root.addView(typeRow)

        val pkBox = CheckBox(this).apply { text = "主键 PRIMARY KEY"; isChecked = col.isPk; textSize = 13f }
        val notNullBox = CheckBox(this).apply { text = "非空 NOT NULL"; isChecked = col.notNull; textSize = 13f }
        val autoIncBox = CheckBox(this).apply {
            text = "自增 AUTOINCREMENT（仅单个 INTEGER 主键可用）"
            isChecked = col.autoIncrement
            textSize = 13f
        }
        root.addView(pkBox)
        root.addView(notNullBox)
        root.addView(autoIncBox)

        autoIncBox.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                pkBox.isChecked = true
                typeEdit.setText("INTEGER")
                typeSpinner.setSelection(SqlUtil.COMMON_TYPES.indexOf("INTEGER"))
            }
        }

        root.addView(sectionLabel("默认值 DEFAULT（文本要带引号，如 'abc'）"))
        val defaultEdit = EditText(this).apply {
            setText(col.defaultValue ?: "")
            hint = "留空表示无默认值"
            textSize = 14f
        }
        root.addView(defaultEdit)

        // 数据来源：仅修改已有表时需要
        var sourceSpinner: Spinner? = null
        if (!isNew) {
            root.addView(sectionLabel("数据来源"))
            sourceSpinner = Spinner(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            root.addView(sourceSpinner)
        }

        val row = ColumnRow(
            root = root,
            name = nameEdit,
            typeSpinner = typeSpinner,
            typeEdit = typeEdit,
            pkBox = pkBox,
            notNullBox = notNullBox,
            autoIncBox = autoIncBox,
            defaultEdit = defaultEdit,
            sourceSpinner = sourceSpinner,
            deleteBtn = deleteBtn
        )
        rows.add(row)
        container.addView(root)
        renumber()
        refreshSourceSpinners()
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(0xFF666666.toInt())
        setPadding(0, dp(6f), 0, 0)
    }

    private fun renumber() {
        rows.forEachIndexed { i, r ->
            (r.root.getChildAt(0) as LinearLayout).getChildAt(0).let {
                (it as TextView).text = "第 ${i + 1} 列"
            }
        }
        val canDelete = rows.size > 1
        rows.forEach { it.deleteBtn.isEnabled = canDelete }
    }

    /** 重建「数据来源」下拉内容，尽量保留当前选择 */
    private fun refreshSourceSpinners() {
        val options = listOf(NEW_COLUMN_LABEL) + originalColumnNames()
        for (row in rows) {
            val spinner = row.sourceSpinner ?: continue
            val previous = currentSource(row)
            spinner.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item, options
            )
            val idx = if (previous == null) 0 else options.indexOf(previous).coerceAtLeast(0)
            spinner.setSelection(idx)
        }
    }

    /** 当前选中的数据来源列（null 表示这是新列） */
    private fun currentSource(row: ColumnRow): String? {
        if (row.sourceSpinner == null) return null
        val label = row.sourceSpinner.selectedItem as? String
        return if (label == null || label == NEW_COLUMN_LABEL) null else label
    }

    // ---------------- 应用 ----------------

    private fun onApplyClicked() {
        val tableName = if (isNew) tableNameEdit.text.toString().trim() else table
        if (isNew && tableName.isEmpty()) {
            toast("请填写表名")
            return
        }

        val built = buildColumns() ?: return

        if (!isNew && built.none { it.isPk }) {
            // AlertDialog 是异步的，所以在回调里继续
            AlertDialog.Builder(this)
                .setTitle("没有主键")
                .setMessage("这张表将没有主键，之后无法精确按某一列定位行（只能用 rowid）。确定继续？")
                .setPositiveButton("继续") { _, _ -> doApply(tableName, built) }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        doApply(tableName, built)
    }

    /** 收集界面上的列定义；校验失败返回 null 并提示 */
    /** 收集界面上的原始输入 */
    private fun collectRows(): List<SchemaFormLogic.RowInput> = rows.map { row ->
        SchemaFormLogic.RowInput(
            name = row.name.text.toString(),
            type = row.typeEdit.text.toString(),
            notNull = row.notNullBox.isChecked,
            defaultValue = row.defaultEdit.text.toString(),
            isPk = row.pkBox.isChecked,
            autoIncrement = row.autoIncBox.isChecked,
            source = currentSource(row)
        )
    }

    /**
     * 把界面输入整理成可执行的列定义。
     * 具体规则（主键编号、自增校正、NOT NULL 校验）在 [SchemaFormLogic] 里，可被单测覆盖。
     */
    private fun buildColumns(): List<DdlColumn>? =
        when (val r = SchemaFormLogic.build(collectRows(), isNewTable = isNew)) {
            is SchemaFormLogic.Result.Invalid -> {
                toast(r.message)
                null
            }

            is SchemaFormLogic.Result.Ok -> {
                r.notes.forEach { toast(it) }
                r.columns
            }
        }

    /** 不再直接应用，改为跳到预览页确认（搬错列很难察觉，所以强制看一眼） */
    private fun doApply(tableName: String, columns: List<DdlColumn>) {
        previewLauncher.launch(
            android.content.Intent(this, SchemaPreviewActivity::class.java)
                .putExtra(SchemaPreviewActivity.EXTRA_TABLE, tableName)
                .putExtra(SchemaPreviewActivity.EXTRA_IS_NEW, isNew)
                .putExtra(
                    SchemaPreviewActivity.EXTRA_COLUMNS,
                    SchemaPreviewActivity.encode(columns)
                )
        )
    }

    /**
     * 预览页的结果：执行成功才关掉本页；用户选「返回修改」则留在表单上，
     * 这样不会把已填好的一堆列白费掉。
     */
    private val previewLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()
}
