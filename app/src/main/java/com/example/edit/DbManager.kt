package com.example.edit

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/** 一列的信息 */
data class ColumnInfo(
    val name: String,
    val declType: String,
    val notNull: Boolean,
    val defaultValue: String?,
    val pkPosition: Int
) {
    val isPk: Boolean get() = pkPosition > 0
}

/** 行的定位方式 */
sealed class RowKey {
    /** rowid 或 INTEGER PRIMARY KEY，用单个整数定位 */
    data class Rowid(val id: Long) : RowKey() {
        override fun toString() = "rowid=$id"
    }

    /** 非整型主键（可能是复合主键），用主键值定位 */
    data class Pks(val values: List<String?>) : RowKey() {
        override fun toString() = "pk=${values.joinToString(",")}"
    }
}

/** 一行的数据 */
data class RowData(val values: List<String?>, val nulls: List<Boolean>)

/** 一页查询结果 */
data class PageData(
    val columns: List<String>,
    val rows: List<RowData>,
    val keys: List<RowKey>,
    val total: Int
)

/** 搜索结果里的一行 */
data class SearchHit(
    val table: String,
    val column: String,
    val value: String,
    val key: RowKey,
    val index: Int
)

/** 数据库里的表 */
data class TableInfo(val name: String, val rowCount: Int, val type: String = "table") {
    val isView: Boolean get() = type == "view"
}

/** 任意 SQL 的执行结果 */
data class SqlResult(
    val columns: List<String>,
    val rows: List<List<String?>>,
    val nulls: List<List<Boolean>>,
    val message: String,
    val isQuery: Boolean
)

/** 表的主键描述 */
data class KeySpec(
    val isRowid: Boolean,
    val pkColumns: List<String>
) {
    val label: String get() = if (isRowid) "rowid" else pkColumns.joinToString("+")
}

/**
 * SQLite 数据库操作封装。
 * 工作模式：把外部 .db 文件复制到应用私有目录后打开，编辑后写回原文件或另存为。
 */
class DbManager(private val context: Context) {

    companion object {
        private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        const val PAGE_SIZE = 200
        private const val KEY_ALIAS = "__dbedit_key__"
    }

    var db: SQLiteDatabase? = null
        private set

    var workingFile: File? = null
        private set

    var sourceUri: Uri? = null
        private set

    var sourceName: String = ""
        private set

    var dirty: Boolean = false
        private set

    val isOpen: Boolean get() = db?.isOpen == true
    val canSaveInPlace: Boolean get() = sourceUri != null

    /**
     * 丢弃所有未保存的修改：从原文件重新导入并重开。
     *
     * 工作副本是从原文件复制的，所以只要还没「保存」，原文件就未被修改，
     * 重新导入即可完全回滚（包括改表结构这种大操作）。
     */
    fun discardChanges(): Boolean {
        if (!dirty) return true
        val uri = sourceUri ?: return false
        return try {
            close()
            openFromUri(uri)
            true
        } catch (_: Exception) {
            false
        }
    }

    private val keyCache = HashMap<String, KeySpec>()
    private val viewCache = HashMap<String, Boolean>()

    /** 是否是视图（视图没有 rowid，不能按行增删改） */
    fun isView(name: String): Boolean = viewCache.getOrPut(name) {
        masterSql(name)?.type == "view"
    }

    // ---------------- 打开 / 关闭 ----------------

    fun close() {
        try {
            db?.close()
        } catch (_: Exception) {
        }
        db = null
        workingFile = null
        keyCache.clear()
        viewCache.clear()
    }

    private fun looksLikeSqlite(file: File): Boolean {
        if (file.length() < 16) return false
        return try {
            val head = ByteArray(16)
            file.inputStream().use { it.read(head) }
            head.contentEquals(SQLITE_MAGIC)
        } catch (e: Exception) {
            false
        }
    }

    fun queryDisplayName(uri: Uri): String {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "database.db"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    name = c.getString(idx) ?: name
                }
            }
        } catch (_: Exception) {
        }
        return name
    }

    /** 从 SAF Uri 打开数据库（先复制到私有目录）。 */
    fun openFromUri(uri: Uri) {
        val name = queryDisplayName(uri)
        val dir = File(context.filesDir, "dbs").apply { mkdirs() }
        val dest = File(dir, sanitizeFileName(name))

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        } ?: throw IllegalArgumentException("无法读取所选文件")

        openFromPath(dest, uri, name)
    }

    /** 打开本地路径的库（演示库 / 新建库用）。 */
    fun openFromPath(path: File, uri: Uri?, name: String) {
        // 0 字节文件按空数据库处理（SQLite 首次写入前不写文件头）
        if (path.length() > 0 && !looksLikeSqlite(path)) {
            throw IllegalArgumentException("所选文件不是有效的 SQLite 数据库（.db）")
        }
        val opened = SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            ?: throw IllegalArgumentException("数据库打开失败")
        db?.close()
        db = opened
        // 固定为回滚日志模式：避免保存后残留没有配对 -wal 的库，其他程序也能正常打开
        try {
            opened.rawQuery("PRAGMA journal_mode=DELETE", null)?.close()
        } catch (_: Exception) {
        }
        workingFile = path
        sourceUri = uri
        sourceName = name
        dirty = false
        keyCache.clear()
        viewCache.clear()
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]"), "_")
        return if (cleaned.endsWith(".db")) cleaned else "$cleaned.db"
    }

    private fun copyTo(uri: Uri) {
        val file = workingFile ?: throw IllegalStateException("数据库未打开")
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            file.inputStream().use { input -> input.copyTo(out) }
        } ?: throw IllegalStateException("无法写入目标文件")
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
    }

    /** 关闭连接以便文件完整落盘 */
    private fun closeForWrite() {
        checkpoint()
        db?.let { if (it.isOpen) it.close() }
        db = null
    }

    private fun reopen() {
        val file = workingFile ?: return
        db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db?.rawQuery("PRAGMA journal_mode=DELETE", null)?.close()
        } catch (_: Exception) {
        }
        keyCache.clear()
        viewCache.clear()
    }

    /** WAL 模式下确保内容写入主文件 */
    private fun checkpoint() {
        try {
            db?.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)?.close()
        } catch (_: Exception) {
        }
    }

    /** 覆盖写回原文件 */
    fun saveToSource() {
        val uri = sourceUri ?: throw IllegalStateException("没有可写回的原文件")
        closeForWrite()
        try {
            copyTo(uri)
        } finally {
            reopen()
        }
        dirty = false
    }

    /** 另存为新的 Uri */
    fun saveAs(uri: Uri) {
        closeForWrite()
        try {
            copyTo(uri)
        } finally {
            reopen()
        }
        sourceUri = uri
        sourceName = queryDisplayName(uri)
        dirty = false
    }

    // ---------------- 元信息 ----------------

    fun listTables(includeInternal: Boolean = false, includeViews: Boolean = true): List<TableInfo> {
        val result = mutableListOf<TableInfo>()
        val d = db ?: return result
        val types = if (includeViews) "('table','view')" else "('table',)"
        val sql = "SELECT name, type FROM sqlite_master WHERE type IN $types " +
                (if (includeInternal) "" else "AND name NOT LIKE 'sqlite_%' ") +
                "ORDER BY type DESC, name COLLATE NOCASE"
        d.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val type = c.getString(1) ?: "table"
                result.add(TableInfo(name, countRows(name), type))
            }
        }
        return result
    }

    fun countRows(table: String): Int = try {
        db?.rawQuery("SELECT COUNT(*) FROM ${SqlUtil.quoteIdent(table)}", null)?.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        } ?: 0
    } catch (_: Exception) {
        -1
    }

    fun columns(table: String): List<ColumnInfo> {
        val list = mutableListOf<ColumnInfo>()
        val d = db ?: return list
        try {
            d.rawQuery("PRAGMA table_info(${SqlUtil.quoteIdent(table)})", null).use { c ->
                val iName = c.getColumnIndex("name")
                val iType = c.getColumnIndex("type")
                val iNotNull = c.getColumnIndex("notnull")
                val iDflt = c.getColumnIndex("dflt_value")
                val iPk = c.getColumnIndex("pk")
                while (c.moveToNext()) {
                    list.add(
                        ColumnInfo(
                            name = c.getString(iName),
                            declType = if (iType >= 0) c.getString(iType) ?: "" else "",
                            notNull = iNotNull >= 0 && c.getInt(iNotNull) != 0,
                            defaultValue = if (iDflt >= 0 && !c.isNull(iDflt)) c.getString(iDflt) else null,
                            pkPosition = if (iPk >= 0) c.getInt(iPk) else 0
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return list
    }

    /** 判断行的定位方式 */
    fun keySpec(table: String): KeySpec {
        keyCache[table]?.let { return it }
        val cols = columns(table)
        val pks = cols.filter { it.isPk }.sortedBy { it.pkPosition }
        val spec = when {
            pks.isEmpty() -> KeySpec(true, emptyList())
            pks.size == 1 && pks[0].declType.equals("INTEGER", ignoreCase = true) ->
                KeySpec(true, listOf(pks[0].name))
            else -> KeySpec(false, pks.map { it.name })
        }
        keyCache[table] = spec
        return spec
    }

    /** 该表是否是「无主键的表」以外需要额外处理的虚拟/内部表 */
    private fun isQueryable(table: String): Boolean = columns(table).isNotEmpty()

    // ---------------- 查询 ----------------

    fun queryPage(
        table: String,
        offset: Int,
        limit: Int = PAGE_SIZE,
        keyword: String = "",
        columns: List<String>? = null
    ): PageData {
        val d = db ?: throw IllegalStateException("数据库未打开")
        val cols = columns ?: columns(table).map { it.name }
        val where = buildSearchWhere(table, cols, keyword)
        val args = if (where != null) SqlUtil.searchArgs(cols, keyword) else emptyArray()

        val total = if (where == null) countRows(table) else try {
            d.rawQuery(
                "SELECT COUNT(*) FROM ${SqlUtil.quoteIdent(table)} WHERE $where", args
            ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } catch (_: Exception) {
            0
        }

        val spec = keySpec(table)
        // 视图没有 rowid，也不能排序：只做只读分页
        val isView = isView(table)
        val kind = if (spec.isRowid) SqlUtil.KeyKind.ROWID else SqlUtil.KeyKind.PK
        val keySelect = if (isView) "" else SqlUtil.selectPrefix(kind, KEY_ALIAS)
        val order = if (isView) null else SqlUtil.orderSql(kind, spec.pkColumns)

        val rows = mutableListOf<RowData>()
        val keys = mutableListOf<RowKey>()
        d.rawQuery(
            "SELECT $keySelect* FROM ${SqlUtil.quoteIdent(table)}" +
                    (where?.let { " WHERE $it" } ?: "") +
                    (order?.let { " ORDER BY $it" } ?: "") +
                    " LIMIT ? OFFSET ?",
            args + arrayOf(limit.toString(), offset.toString())
        ).use { c ->
            val keyIdx = c.getColumnIndex(KEY_ALIAS)
            val pkIdx = spec.pkColumns.map { c.getColumnIndex(it) }
            while (c.moveToNext()) {
                val values = ArrayList<String?>(cols.size)
                val nulls = ArrayList<Boolean>(cols.size)
                for (i in 0 until c.columnCount) {
                    if (spec.isRowid && i == keyIdx) continue
                    nulls.add(c.isNull(i))
                    values.add(if (c.isNull(i)) null else c.getString(i))
                }
                rows.add(RowData(values, nulls))
                keys.add(
                    if (isView) RowKey.Rowid(-1)
                    else if (spec.isRowid) RowKey.Rowid(c.getLong(keyIdx))
                    else RowKey.Pks(pkIdx.map { idx -> if (c.isNull(idx)) null else c.getString(idx) })
                )
            }
        }
        return PageData(cols, rows, keys, total)
    }

    private fun buildSearchWhere(table: String, cols: List<String>, keyword: String): String? =
        SqlUtil.buildSearchWhere(cols, keyword)

    /** 读取一行（用于编辑） */
    fun fetchRow(table: String, key: RowKey): RowData? {
        val d = db ?: return null
        val spec = keySpec(table)
        d.rawQuery(
            "SELECT * FROM ${SqlUtil.quoteIdent(table)} WHERE ${whereFor(spec, key)} LIMIT 1",
            argsFor(key)
        ).use { c ->
            if (!c.moveToFirst()) return null
            val values = ArrayList<String?>(c.columnCount)
            val nulls = ArrayList<Boolean>(c.columnCount)
            for (i in 0 until c.columnCount) {
                nulls.add(c.isNull(i))
                values.add(if (c.isNull(i)) null else c.getString(i))
            }
            return RowData(values, nulls)
        }
    }

    private fun whereFor(spec: KeySpec, key: RowKey): String =
        SqlUtil.whereSql(
            if (spec.isRowid) SqlUtil.KeyKind.ROWID else SqlUtil.KeyKind.PK,
            spec.pkColumns
        )

    private fun argsFor(key: RowKey): Array<String?> = when (key) {
        is RowKey.Rowid -> arrayOf(key.id.toString())
        is RowKey.Pks -> key.values.toTypedArray()
    }

    // ---------------- 修改 ----------------

    /** 更新一行中的若干列。changes: 列名 -> 新值（null 表示设为 NULL）。 */
    fun updateRow(table: String, key: RowKey, changes: Map<String, String?>): Int {
        if (changes.isEmpty()) return 0
        if (isView(table)) throw IllegalArgumentException("视图是只读的，不能修改数据")
        val d = db ?: throw IllegalStateException("数据库未打开")
        val spec = keySpec(table)
        val kind = if (spec.isRowid) SqlUtil.KeyKind.ROWID else SqlUtil.KeyKind.PK
        // 主键列一律不可修改
        val pkNames = spec.pkColumns
        val body = changes.filterKeys { it !in pkNames }
        if (body.isEmpty()) throw IllegalArgumentException("主键列不可修改")

        val sql = SqlUtil.updateSql(table, body.keys.toList(), whereFor(spec, key))
        val stmt = d.compileStatement(sql)
        var i = 1
        body.values.forEach { v ->
            if (v == null) stmt.bindNull(i) else stmt.bindString(i, v)
            i++
        }
        when (key) {
            is RowKey.Rowid -> stmt.bindString(i, key.id.toString())
            is RowKey.Pks -> key.values.forEach { v ->
                if (v == null) stmt.bindNull(i) else stmt.bindString(i, v)
                i++
            }
        }
        val affected = stmt.executeUpdateDelete()
        if (affected > 0) dirty = true
        return affected
    }

    /** 插入新行 */
    fun insertRow(table: String, values: Map<String, String?>): Long {
        if (isView(table)) throw IllegalArgumentException("视图是只读的，不能插入数据")
        val d = db ?: throw IllegalStateException("数据库未打开")
        val cols = columns(table)
        val effective = values.filter { (k, v) ->
            val col = cols.find { it.name == k }
            val autoPk = col != null && col.isPk && col.declType.equals("INTEGER", ignoreCase = true)
            !(autoPk && v.isNullOrEmpty())
        }
        val names = effective.keys.toList()
        val sql = SqlUtil.insertSql(table, names)
        val stmt = d.compileStatement(sql)
        effective.values.forEachIndexed { i, v ->
            if (v == null) stmt.bindNull(i + 1) else stmt.bindString(i + 1, v)
        }
        val id = stmt.executeInsert()
        if (id >= 0) dirty = true
        return id
    }

    /** 删除一行 */
    fun deleteRow(table: String, key: RowKey): Int {
        if (isView(table)) throw IllegalArgumentException("视图是只读的，不能删除数据")
        val d = db ?: throw IllegalStateException("数据库未打开")
        val spec = keySpec(table)
        val stmt = d.compileStatement(SqlUtil.deleteSql(table, whereFor(spec, key)))
        var i = 1
        when (key) {
            is RowKey.Rowid -> stmt.bindString(i, key.id.toString())
            is RowKey.Pks -> key.values.forEach { v ->
                if (v == null) stmt.bindNull(i) else stmt.bindString(i, v)
                i++
            }
        }
        val n = stmt.executeUpdateDelete()
        if (n > 0) dirty = true
        return n
    }

    // ---------------- 全库搜索 ----------------

    fun searchAll(keyword: String, limit: Int = 300): List<SearchHit> {
        val d = db ?: return emptyList()
        if (keyword.isBlank()) return emptyList()
        val hits = mutableListOf<SearchHit>()
        val pattern = SqlUtil.likePattern(keyword)

        for (t in listTables()) {
            if (hits.size >= limit) break
            val cols = columns(t.name)
            val textCols = cols.filter {
                val ty = it.declType
                ty.isEmpty() || ty.contains("TEXT", true) || ty.contains("CHAR", true) ||
                        ty.contains("CLOB", true)
            }
            if (textCols.isEmpty()) continue

            val spec = keySpec(t.name)
            val where = SqlUtil.buildSearchWhere(textCols.map { it.name }, keyword)
                ?: continue
            val args = Array<String>(textCols.size) { pattern }

            val concat = textCols.joinToString(" || '\u0001' || ") { SqlUtil.quoteIdent(it.name) }
            val keyPart = if (spec.isRowid) "rowid" else
                spec.pkColumns.joinToString(" || '\u0002' || ") { SqlUtil.quoteIdent(it) }
            val sql = "SELECT $keyPart, $concat FROM ${SqlUtil.quoteIdent(t.name)} " +
                    "WHERE $where LIMIT 300"
            try {
                d.rawQuery(sql, args).use { c ->
                    while (c.moveToNext() && hits.size < limit) {
                        val blob = c.getString(1) ?: ""
                        val parts = blob.split("\u0001")
                        var matchedCol = textCols.first().name
                        var matchedVal = blob
                        for ((i, col) in textCols.withIndex()) {
                            val v = parts.getOrNull(i) ?: continue
                            if (v.contains(keyword, ignoreCase = true)) {
                                matchedCol = col.name
                                matchedVal = v
                                break
                            }
                        }
                        val key = if (spec.isRowid) RowKey.Rowid(c.getLong(0))
                        else RowKey.Pks((c.getString(0) ?: "").split("\u0002"))
                        hits.add(SearchHit(t.name, matchedCol, matchedVal, key, hits.size + 1))
                    }
                }
            } catch (_: Exception) {
                // 个别表（虚拟表等）查询失败则跳过
            }
        }
        return hits
    }

    // ---------------- 任意 SQL ----------------

    fun runSql(sql: String): SqlResult {
        val d = db ?: throw IllegalStateException("数据库未打开")
        val trimmed = sql.trim().trimEnd(';').trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("SQL 不能为空")

        val isQuery = Regex("^(select|pragma|with|explain)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(trimmed)

        if (!isQuery) {
            d.execSQL(trimmed)
            dirty = true
            val n = d.rawQuery("SELECT changes()", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            return SqlResult(emptyList(), emptyList(), emptyList(), "执行成功，影响 $n 行", false)
        }

        d.rawQuery(trimmed, null).use { c ->
            val cols = c.columnNames.toList()
            val rows = mutableListOf<List<String?>>()
            val nulls = mutableListOf<List<Boolean>>()
            while (c.moveToNext() && rows.size < 1000) {
                val vals = ArrayList<String?>(c.columnCount)
                val ns = ArrayList<Boolean>(c.columnCount)
                for (i in 0 until c.columnCount) {
                    ns.add(c.isNull(i))
                    vals.add(if (c.isNull(i)) null else c.getString(i))
                }
                rows.add(vals)
                nulls.add(ns)
            }
            return SqlResult(cols, rows, nulls, "返回 ${rows.size} 行", true)
        }
    }

    // ---------------- 导出 ----------------

    /** 把整张表导出为 CSV 文本 */
    fun exportCsv(table: String, out: java.io.OutputStream): Int {
        val d = db ?: throw IllegalStateException("数据库未打开")
        val cols = columns(table).map { it.name }
        val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(out, Charsets.UTF_8))
        writer.write('\uFEFF'.toString()) // BOM，Excel 打开中文不乱码
        writer.write(cols.joinToString(",") { SqlUtil.csvEscape(it) })
        writer.newLine()
        var n = 0
        d.rawQuery("SELECT * FROM ${SqlUtil.quoteIdent(table)}", null).use { c ->
            while (c.moveToNext()) {
                val fields = ArrayList<String?>(c.columnCount)
                for (i in 0 until c.columnCount) {
                    fields.add(if (c.isNull(i)) null else c.getString(i))
                }
                writer.write(SqlUtil.csvLine(fields))
                writer.newLine()
                n++
            }
        }
        writer.flush()
        return n
    }

    // ---------------- 表结构编辑 ----------------

    /** 表的结构描述（用于结构编辑界面） */
    data class TableSchema(
        val table: String,
        val columns: List<SqlUtil.DdlColumn>,
        val foreignKeys: List<String>,
        val indexes: List<Pair<String, String>>,
        val triggers: List<Pair<String, String>>,
        val createSql: String?,
        val isVirtual: Boolean,
        val isView: Boolean
    )

    /** 读取完整表结构 */
    fun tableSchema(table: String): TableSchema {
        val cols = columns(table).map { c ->
            SqlUtil.DdlColumn(
                name = c.name,
                type = c.declType,
                notNull = c.notNull,
                defaultValue = c.defaultValue,
                pkPosition = c.pkPosition,
                autoIncrement = c.isPk && c.declType.equals("INTEGER", true) &&
                        (createSqlOf(table)?.contains("AUTOINCREMENT", ignoreCase = true) == true),
                originalName = c.name
            )
        }
        val master = masterSql(table)
        return TableSchema(
            table = table,
            columns = cols,
            foreignKeys = foreignKeysOf(table),
            indexes = sqliteMasterObjects(table, "index")
                .filter { it.sql != null && it.name.isNotBlank() && !it.name.startsWith("sqlite_") }
                .map { it.name to it.sql!! },
            triggers = sqliteMasterObjects(table, "trigger").mapNotNull { o ->
                o.sql?.let { o.name to it }
            },
            createSql = master?.sql,
            isVirtual = SqlUtil.isVirtualTable(master?.sql),
            isView = master?.type == "view"
        )
    }

    private data class MasterRow(val name: String, val type: String, val sql: String?)

    private fun masterSql(name: String): MasterRow? = try {
        db?.rawQuery(
            "SELECT name, type, sql FROM sqlite_master WHERE name = ?", arrayOf(name)
        )?.use { c -> if (c.moveToFirst()) MasterRow(c.getString(0), c.getString(1), c.getString(2)) else null }
    } catch (_: Exception) {
        null
    }

    private fun createSqlOf(table: String): String? = masterSql(table)?.sql

    private fun sqliteMasterObjects(table: String, type: String): List<MasterRow> = try {
        db?.rawQuery(
            "SELECT name, type, sql FROM sqlite_master WHERE tbl_name = ? AND type = ?",
            arrayOf(table, type)
        )?.use { c ->
            val out = mutableListOf<MasterRow>()
            while (c.moveToNext()) out.add(MasterRow(c.getString(0), c.getString(1), c.getString(2)))
            out
        } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun foreignKeysOf(table: String): List<String> {
        val rows = mutableListOf<SqlUtil.ForeignKeyRow>()
        try {
            db?.rawQuery("PRAGMA foreign_key_list(${SqlUtil.quoteIdent(table)})", null)?.use { c ->
                val iId = c.getColumnIndex("id")
                val iSeq = c.getColumnIndex("seq")
                val iTable = c.getColumnIndex("table")
                val iFrom = c.getColumnIndex("from")
                val iTo = c.getColumnIndex("to")
                val iOnU = c.getColumnIndex("on_update")
                val iOnD = c.getColumnIndex("on_delete")
                val iMatch = c.getColumnIndex("match")
                while (c.moveToNext()) {
                    rows.add(
                        SqlUtil.ForeignKeyRow(
                            id = c.getInt(iId),
                            seq = c.getInt(iSeq),
                            targetTable = if (iTable >= 0) c.getString(iTable) else null,
                            fromColumn = c.getString(iFrom),
                            toColumn = if (iTo >= 0) c.getString(iTo) else null,
                            onUpdate = if (iOnU >= 0) c.getString(iOnU) else null,
                            onDelete = if (iOnD >= 0) c.getString(iOnD) else null,
                            match = if (iMatch >= 0) c.getString(iMatch) else null
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return SqlUtil.foreignKeyClause(rows)
    }

    /** 自增计数器当前值（无则 null） */
    private fun sequenceValue(table: String): Long? = try {
        db?.rawQuery(
            "SELECT seq FROM sqlite_sequence WHERE name = ?", arrayOf(table)
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
    } catch (_: Exception) {
        null
    }

    /**
     * 应用新的表结构。
     *
     * @param newColumns 编辑后的列定义；`originalName` 非空表示数据来源列
     * @throws IllegalArgumentException 参数非法或不支持的表
     */
    fun applySchema(table: String, newColumns: List<SqlUtil.DdlColumn>) {
        val d = db ?: throw IllegalStateException("数据库未打开")
        require(newColumns.isNotEmpty()) { "表必须至少保留一列" }

        val schema = tableSchema(table)
        if (schema.isView) throw IllegalArgumentException("视图不支持修改结构")
        if (schema.isVirtual) throw IllegalArgumentException("虚拟表（FTS 等）不支持修改结构")
        if (createSqlOf(table)?.let { SqlUtil.isWithoutRowid(it) } == true) {
            throw IllegalArgumentException("WITHOUT ROWID 表暂不支持修改结构")
        }
        // 新加的列必须能接住数据：要么有默认值、要么允许 NULL
        newColumns.filter { it.originalName == null }.forEach {
            if (it.notNull && it.defaultValue == null) {
                throw IllegalArgumentException("新增列「${it.name}」设了 NOT NULL，必须同时给默认值")
            }
        }

        val oldColumns = columns(table)
        val oldNames = oldColumns.map { it.name }
        val newNames = newColumns.map { it.name }
        if (newNames.toSet().size != newNames.size) throw IllegalArgumentException("列名不能重复")
        // 数据来源列必须真实存在
        newColumns.mapNotNull { it.originalName }.forEach {
            if (it !in oldNames) throw IllegalArgumentException("找不到来源列「$it」")
        }

        // 前置检查：索引/触发器引用了将不再存在的列时，重建会报一句难懂的 SQL 错
        // （如 no such column: city），这里提前给出可操作的提示
        val gone = oldNames.filter { it !in newNames.toSet() }
        if (gone.isNotEmpty()) {
            val badIndexes = schema.indexes.filter { (_, sql) ->
                gone.any { SqlUtil.referencesColumn(sql, it) }
            }.map { it.first }
            val badTriggers = schema.triggers.filter { (_, sql) ->
                gone.any { SqlUtil.referencesColumn(sql, it) }
            }.map { it.first }
            if (badIndexes.isNotEmpty() || badTriggers.isNotEmpty()) {
                val which = buildList {
                    if (badIndexes.isNotEmpty()) add("索引 ${badIndexes.joinToString("、")}")
                    if (badTriggers.isNotEmpty()) add("触发器 ${badTriggers.joinToString("、")}")
                }.joinToString("；")
                throw IllegalArgumentException(
                    "这些列被删除或改名，但有对象还在引用它们，请先删除对应对象或在「执行 SQL」里处理：$which"
                )
            }
        }

        val preserveRowid = !newColumns.any { it.isIntegerPk } && !oldColumns.any {
            it.isPk && it.declType.equals("INTEGER", true) && oldColumns.count { c -> c.isPk } == 1
        }

        // 收集所有视图与触发器（跨表），找出「引用本表、需先摘除再恢复」的外部对象。
        // 注意：挂在本表自身的触发器由 selfTriggers 负责，dependentObjects 会排除它们。
        val allObjects = allTriggersAndViews()
        val deps = SqlUtil.restoreOrder(SqlUtil.dependentObjects(allObjects, table))

        val stmts = SqlUtil.rebuildTableStatements(
            table = table,
            columns = newColumns,
            fkSqls = schema.foreignKeys,
            indexes = schema.indexes,
            selfTriggers = schema.triggers,
            deps = deps,
            preserveRowid = preserveRowid,
            sequenceValue = sequenceValue(table)
        )
        executeSchemaStatements(stmts, deps)
        invalidateKeyCache(table)
        dirty = true
    }

    /** 所有视图与触发器（跨表收集，用于找出外部依赖） */
    private fun allTriggersAndViews(): List<SqlUtil.SchemaObject> = try {
        db?.rawQuery(
            "SELECT name, type, tbl_name, sql FROM sqlite_master " +
                    "WHERE type IN ('view','trigger') AND sql IS NOT NULL", null
        )?.use { c ->
            val out = mutableListOf<SqlUtil.SchemaObject>()
            while (c.moveToNext()) {
                out.add(SqlUtil.SchemaObject(c.getString(0), c.getString(1), c.getString(2), c.getString(3)))
            }
            out
        } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /** 在事务里执行重建语句（先关外键约束，失败则整体回滚） */
    private fun executeSchemaStatements(stmts: List<String>, deps: List<SqlUtil.SchemaObject>) {
        val d = db ?: throw IllegalStateException("数据库未打开")
        // 先把依赖对象摘掉（逆序），失败或回滚时重新装回由事务保证
        d.beginTransaction()
        try {
            d.execSQL("PRAGMA foreign_keys=OFF")
            deps.reversed().forEach { d.execSQL("DROP ${it.type.uppercase()} IF EXISTS ${SqlUtil.quoteIdent(it.name)}") }
            stmts.forEach { d.execSQL(it) }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
            try {
                d.execSQL("PRAGMA foreign_keys=ON")
            } catch (_: Exception) {
            }
        }
    }

    /** 新建表 */
    fun createTable(name: String, newColumns: List<SqlUtil.DdlColumn>) {
        val d = db ?: throw IllegalStateException("数据库未打开")
        require(name.isNotBlank()) { "表名不能为空" }
        if (listTables(includeInternal = true).any { it.name == name }) {
            throw IllegalArgumentException("已存在同名表「$name」")
        }
        if (newColumns.isEmpty()) throw IllegalArgumentException("至少要有一列")
        if (newColumns.map { it.name }.toSet().size != newColumns.size) {
            throw IllegalArgumentException("列名不能重复")
        }
        d.execSQL(SqlUtil.createTableSql(name, newColumns, emptyList()))
        dirty = true
    }

    /** 删除视图 */
    fun dropView(view: String) {
        db?.execSQL("DROP VIEW IF EXISTS ${SqlUtil.quoteIdent(view)}")
        dirty = true
    }

    // ---------------- 危险操作 ----------------

    fun dropTable(table: String) {
        db?.execSQL("DROP TABLE IF EXISTS ${SqlUtil.quoteIdent(table)}")
        keyCache.remove(table)
        dirty = true
    }

    fun renameTable(oldName: String, newName: String) {
        db?.execSQL(
            "ALTER TABLE ${SqlUtil.quoteIdent(oldName)} RENAME TO ${SqlUtil.quoteIdent(newName)}"
        )
        keyCache.remove(oldName)
        dirty = true
    }

    fun clearTable(table: String) {
        if (isView(table)) throw IllegalArgumentException("视图是只读的")
        db?.execSQL("DELETE FROM ${SqlUtil.quoteIdent(table)}")
        dirty = true
    }

    fun vacuum() {
        db?.execSQL("VACUUM")
    }

    /** 供结构重建时清理缓存 */
    fun invalidateKeyCache(table: String) {
        keyCache.remove(table)
        viewCache.remove(table)
    }

    /** 统计信息：页大小、页数、编码等 */
    fun databaseStats(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val d = db ?: return out
        listOf("page_size", "page_count", "freelist_count", "encoding", "user_version", "schema_version")
            .forEach { key ->
                try {
                    d.rawQuery("PRAGMA $key", null).use {
                        if (it.moveToFirst()) out.add(key to (it.getString(0) ?: ""))
                    }
                } catch (_: Exception) {
                }
            }
        out.add("tables" to listTables().size.toString())
        out.add("integrity" to if (integrityCheck()) "ok" else "有问题")
        return out
    }

    fun integrityCheck(): Boolean = try {
        db?.rawQuery("PRAGMA integrity_check", null)?.use {
            it.moveToFirst() && it.getString(0).equals("ok", ignoreCase = true)
        } ?: false
    } catch (_: Exception) {
        false
    }
}
