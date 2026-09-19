package com.example.edit

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar

/**
 * 结构变更预览页。
 *
 * 存在的理由：搬数据时靠「数据来源」下拉决定对应关系，一旦对应错位，
 * 数据会被静静地搬到错误的列里（不报错、不丢行数），很难发现。
 * 所以应用前强制看一眼「什么列、变成什么、数据从哪来」。
 */
class SchemaPreviewActivity : BaseActivity() {

    companion object {
        const val EXTRA_TABLE = "table"
        /** 待应用的列定义（Parcelable 序列化后的数组，这里用简单编码传递） */
        const val EXTRA_COLUMNS = "columns"
        const val EXTRA_IS_NEW = "is_new"

        /**
         * 列定义在 Intent 里的行编码格式：字段用 \u0001 分隔（与 DdlColumn 一一对应）。
         * 避免引入 Parcelable 样板代码。
         */
        private const val SEP = "\u0001"

        fun encode(columns: List<SqlUtil.DdlColumn>): Array<String> = columns.map { c ->
            listOf(
                c.name,
                c.type,
                if (c.notNull) "1" else "0",
                c.defaultValue ?: "",
                c.pkPosition.toString(),
                if (c.autoIncrement) "1" else "0",
                c.originalName ?: ""
            ).joinToString(SEP)
        }.toTypedArray()

        fun decode(rows: Array<String>): List<SqlUtil.DdlColumn> = rows.map { row ->
            val f = row.split(SEP)
            SqlUtil.DdlColumn(
                name = f.getOrElse(0) { "" },
                type = f.getOrElse(1) { "" },
                notNull = f.getOrElse(2) { "0" } == "1",
                defaultValue = f.getOrElse(3) { "" }.ifEmpty { null },
                pkPosition = f.getOrElse(4) { "0" }.toIntOrNull() ?: 0,
                autoIncrement = f.getOrElse(5) { "0" } == "1",
                originalName = f.getOrElse(6) { "" }.ifEmpty { null }
            )
        }
    }

    private var table = ""
    private var isNew = false
    private var newColumns: List<SqlUtil.DdlColumn> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schema_preview)

        table = intent.getStringExtra(EXTRA_TABLE) ?: ""
        isNew = intent.getBooleanExtra(EXTRA_IS_NEW, false)
        @Suppress("UNCHECKED_CAST")
        val raw = intent.getStringArrayExtra(EXTRA_COLUMNS) as? Array<String> ?: emptyArray()
        newColumns = decode(raw)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        setBarTitle(if (isNew) "确认建表" else "确认结构变更")
        toolbar.setNavigationOnClickListener { finish() }

        val oldColumns = if (isNew) emptyList() else
            runCatching { manager.tableSchema(table).columns }.getOrDefault(emptyList())

        val diff = SqlUtil.diffSchema(oldColumns, newColumns)
        val summary = findViewById<TextView>(R.id.tvSummary)
        summary.text = if (isNew) {
            "即将创建表「$table」，包含 ${newColumns.size} 列：\n\n" +
                    newColumns.joinToString("\n") { "    · ${describe(it)}" }
        } else {
            SqlUtil.renderDiff(diff, table)
        }
        val detail = findViewById<TextView>(R.id.tvDetail)
        detail.text = buildString {
            append("完整列定义\n")
            newColumns.forEachIndexed { i, c ->
                append("  ${i + 1}. ").append(describe(c))
                c.originalName?.let { if (!isNew) append("        ← 数据来自 ").append(it) }
                append('\n')
            }
        }

        findViewById<Button>(R.id.btnApply).setOnClickListener { doApply() }
        findViewById<Button>(R.id.btnCancel).setOnClickListener { finish() }
    }

    private fun describe(c: SqlUtil.DdlColumn): String = buildString {
        append(c.name)
        if (c.isPk) append(" [主键]")
        if (c.autoIncrement) append(" [自增]")
        append("  ")
        append(c.type.ifBlank { "无类型" })
        if (c.notNull) append("  NOT NULL")
        c.defaultValue?.let { append("  DEFAULT ").append(it) }
    }

    private fun doApply() {
        try {
            if (isNew) {
                manager.createTable(table, newColumns)
                toast("已创建表「$table」（记得保存）")
            } else {
                manager.applySchema(table, newColumns)
                toast("结构已修改（记得保存）。若结果不对，可在菜单里「丢弃未保存的改动」恢复")
            }
            setResult(Activity.RESULT_OK)
            finish()
        } catch (e: Exception) {
            reportFailure(e)
        }
    }

    /** 应用失败时，给出「为什么会失败」+「怎么恢复」的完整交代 */
    private fun reportFailure(e: Exception) {
        val rollbackNote = if (!isNew && manager.dirty) {
            "\n\n注意：数据库当前有未保存的改动（可能是之前的操作留下的）。\n" +
                    "本次失败已整体回滚，这张表没被改动。\n" +
                    "如需彻底恢复，可在菜单里选「丢弃未保存的改动」。"
        } else {
            "\n\n本次操作已整体回滚，数据没有变化（你还没保存，原文件也未被动过）。"
        }
        AlertDialog.Builder(this)
            .setTitle("操作失败")
            .setMessage((e.message ?: e.javaClass.simpleName) + rollbackNote)
            .setPositiveButton("知道了", null)
            .show()
    }
}
