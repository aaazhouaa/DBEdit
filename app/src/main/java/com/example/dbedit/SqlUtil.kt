package com.example.dbedit

/**
 * 纯逻辑的 SQL 片段构造，便于单元测试。
 */
object SqlUtil {

    /** 用双引号包裹标识符（表名/列名），内部的 " 转义为 ""。 */
    fun quoteIdent(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    /**
     * 构造搜索的 WHERE 片段：对所有给定列做 LIKE 模糊匹配（OR 连接）。
     * 返回 null 表示不需要过滤。
     *
     * 注意：ESCAPE 只能作用于紧邻的一个 LIKE，所以必须给每个 LIKE 都带上，
     * 否则多列搜索时前面的列会失去转义、 % 退化为通配符。
     */
    fun buildSearchWhere(columns: List<String>, keyword: String): String? {
        if (keyword.isBlank() || columns.isEmpty()) return null
        return columns.joinToString(" OR ") {
            "CAST(${quoteIdent(it)} AS TEXT) LIKE ? ESCAPE '\\'"
        }
    }

    /** LIKE 模式：转义 % _ \ ，配合 ESCAPE '\' 使用。 */
    fun likePattern(keyword: String): String {
        val escaped = keyword
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return "%$escaped%"
    }

    /** 每个搜索列对应一个绑定参数。 */
    fun searchArgs(columns: List<String>, keyword: String): Array<String> =
        Array(columns.size) { likePattern(keyword) }

    /** 单元格文本 -> 可读展示（NULL 单独标记）。 */
    fun displayCell(value: String?, isNull: Boolean): String =
        if (isNull) "NULL" else value ?: ""

    // ---------------- 键值定位 ----------------

    /** 行的定位方式（纯数据描述，供 SQL 构造与测试使用） */
    enum class KeyKind { ROWID, PK }

    /** 由主键描述得到定位方式 */
    fun keyKind(hasPkColumn: Boolean, singlePkIsInteger: Boolean): KeyKind =
        if (!hasPkColumn || singlePkIsInteger) KeyKind.ROWID else KeyKind.PK

    /** WHERE 片段：rowid 用 =，主键用 IS（对 NULL 主键也安全） */
    fun whereSql(kind: KeyKind, pkColumns: List<String>): String =
        if (kind == KeyKind.ROWID) "rowid = ?"
        else pkColumns.joinToString(" AND ") { "${quoteIdent(it)} IS ?" }

    // ---------------- 语句构造 ----------------

    /** 插入语句；names 为空时 d 默认值 */
    fun insertSql(table: String, names: List<String>): String =
        if (names.isEmpty()) "INSERT INTO ${quoteIdent(table)} DEFAULT VALUES"
        else "INSERT INTO ${quoteIdent(table)} (" +
                names.joinToString(", ") { quoteIdent(it) } +
                ") VALUES (" + names.joinToString(", ") { "?" } + ")"

    /** 更新语句：setNames 为要改的列，where 为定位条件 */
    fun updateSql(table: String, setNames: List<String>, where: String): String =
        "UPDATE ${quoteIdent(table)} SET " +
                setNames.joinToString(", ") { "${quoteIdent(it)} = ?" } +
                " WHERE $where"

    /** 删除语句 */
    fun deleteSql(table: String, where: String): String =
        "DELETE FROM ${quoteIdent(table)} WHERE $where"

    /** 分页查询的 ORDER BY：rowid 表按 rowid，否则按主键列 */
    fun orderSql(kind: KeyKind, pkColumns: List<String>): String =
        if (kind == KeyKind.ROWID) "rowid"
        else pkColumns.joinToString(", ") { quoteIdent(it) }

    /** SELECT 前缀：rowid 表额外取出 rowid 作为行键 */
    fun selectPrefix(kind: KeyKind, keyAlias: String): String =
        if (kind == KeyKind.ROWID) "rowid AS $keyAlias, " else ""

    // ---------------- CSV ----------------

    /** CSV 字段转义 */
    fun csvEscape(s: String): String {
        val needsQuote = s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')
        return if (needsQuote) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    fun csvLine(fields: List<String?>): String =
        fields.joinToString(",") { if (it == null) "" else csvEscape(it) }

    // ---------------- 表结构编辑（DDL） ----------------

    /**
     * 表结构编辑用的列定义。与 Android 的 ColumnInfo 解耦，便于纯 JVM 单元测试。
     * @param originalName 非空表示这是从原表同名/改名的列（用于数据搬迁）；
     *                     为 null 表示新建列（无数据来源）
     */
    data class DdlColumn(
        val name: String,
        val type: String = "",
        val notNull: Boolean = false,
        val defaultValue: String? = null,
        val pkPosition: Int = 0,
        val autoIncrement: Boolean = false,
        val originalName: String? = null
    ) {
        val isPk: Boolean get() = pkPosition > 0

        /** 该列是否是 INTEGER 主键（此时它就是 rowid 的别名） */
        val isIntegerPk: Boolean
            get() = isPk && type.trim().substringBefore(' ').equals("INTEGER", ignoreCase = true)
    }

    /** 生成单列的完整定义片段 */
    fun ddlColumnDefinition(c: DdlColumn, singlePkAutoIncrement: Boolean): String {
        val sb = StringBuilder()
        sb.append(quoteIdent(c.name))
        if (c.type.isNotBlank()) sb.append(' ').append(c.type.trim())
        // AUTOINCREMENT 必须是列级 INTEGER PRIMARY KEY AUTOINCREMENT
        if (singlePkAutoIncrement && c.autoIncrement && c.isIntegerPk) {
            sb.append(" PRIMARY KEY AUTOINCREMENT")
        }
        if (c.notNull) {
            sb.append(" NOT NULL")
        }
        c.defaultValue?.let { sb.append(" DEFAULT ").append(it) }
        return sb.toString()
    }

    /**
     * 生成重建表所需的完整语句序列（不含事务控制与 PRAGMA）。
     *
     * 流程（已在真实 SQLite 上验证，见 SchemaEditTest）：
     *  1. 建新表（临时名）
     *  2. 搬数据（同时搬 rowid，若需要）
     *  3. DROP 旧表
     *  4. 临时表改名为原表名
     *  5. 重建索引、本表触发器
     *  6. 恢复 sqlite_sequence（AUTOINCREMENT 计数器）
     *  7. 恢复引用了本表的外部触发器 / 视图（按依赖顺序）
     *
     * 调用方需在事务内执行，并在执行前 `PRAGMA foreign_keys=OFF`（结束后恢复）；
     * deps 必须已通过 [dependentObjects] 找出、并以 [restoreOrder] 排序。
     *
     * 为什么不用「RENAME 旧表」的方案：实测即使开了 legacy_alter_table，
     * SQLite 仍会改写索引/触发器 SQL 里的表名，反而更麻烦。
     */
    fun rebuildTableStatements(
        table: String,
        columns: List<DdlColumn>,
        fkSqls: List<String>,
        indexes: List<Pair<String, String>>,
        selfTriggers: List<Pair<String, String>>,
        deps: List<SchemaObject>,
        preserveRowid: Boolean,
        sequenceValue: Long? = null,
        tempName: String = "${table}__dbedit_new"
    ): List<String> {
        val stmts = mutableListOf<String>()
        stmts.add(createTableSql(tempName, columns, fkSqls))

        // 数据搬迁：只搬运有来源的列；无来源列留空/取默认值
        val copies = columns.filter { it.originalName != null }
        val rowidCopy = preserveRowid && copies.isNotEmpty()
        if (copies.isNotEmpty() || rowidCopy) {
            val targetCols = mutableListOf<String>()
            val sourceCols = mutableListOf<String>()
            if (rowidCopy) {
                targetCols.add("rowid")
                sourceCols.add("rowid")
            }
            copies.forEach {
                targetCols.add(quoteIdent(it.name))
                // 不加 CAST：交给目标列的亲和性处理，避免把非数字文本 CAST 成 0
                sourceCols.add(quoteIdent(it.originalName!!))
            }
            stmts.add(
                "INSERT INTO ${quoteIdent(tempName)} (${targetCols.joinToString(", ")}) " +
                        "SELECT ${sourceCols.joinToString(", ")} FROM ${quoteIdent(table)}"
            )
        }

        stmts.add("DROP TABLE ${quoteIdent(table)}")
        stmts.add("ALTER TABLE ${quoteIdent(tempName)} RENAME TO ${quoteIdent(table)}")

        // 自增计数器：DROP TABLE 会删掉 sqlite_sequence 里的行，不恢复的话
        // 下次插入会从 1 重新开始，可能与已有 id 冲突
        if (sequenceValue != null) {
            stmts.add(
                "UPDATE sqlite_sequence SET seq = MAX(seq, $sequenceValue) " +
                        "WHERE name = ${stringLiteral(table)}"
            )
        }

        indexes.forEach { (name, _) -> stmts.add("DROP INDEX IF EXISTS ${quoteIdent(name)}") }
        selfTriggers.forEach { (name, _) -> stmts.add("DROP TRIGGER IF EXISTS ${quoteIdent(name)}") }
        indexes.forEach { (_, sql) -> stmts.add(sql.trimEnd(';')) }
        selfTriggers.forEach { (_, sql) -> stmts.add(sql.trimEnd(';')) }

        // 外部依赖对象：按拓扑序恢复（被依赖的在前）
        deps.forEach { stmts.add(it.sql.trimEnd(';')) }

        return stmts
    }

    // ---------------- 依赖对象分析 ----------------

    /** sqlite_master 里的一个视图/触发器 */
    data class SchemaObject(
        val name: String,
        val type: String, // "view" | "trigger"
        val attachTable: String,
        val sql: String
    )

    private val TOKEN_CACHE = HashMap<String, Regex>()

    /** SQL 关键字：列名恰好叫这些词时，不能把语句里的关键字误当成列引用 */
    private val SQL_KEYWORDS = setOf(
        "ABORT", "ALL", "ALTER", "AND", "AS", "ASC", "ATTACH", "AUTOINCREMENT",
        "BEGIN", "BETWEEN", "BY", "CASCADE", "CASE", "CAST", "CHECK", "COLLATE",
        "COMMIT", "CONFLICT", "CONSTRAINT", "CREATE", "DEFAULT", "DEFERRABLE", "DELETE",
        "DESC", "DETACH", "DISTINCT", "DROP", "ELSE", "END", "ESCAPE", "EXCEPT",
        "EXCLUSIVE", "EXISTS", "EXPLAIN", "FAIL", "FOR", "FOREIGN", "FROM", "FULL",
        "GLOB", "GROUP", "HAVING", "IF", "IGNORE", "IMMEDIATE", "IN", "INDEX",
        "INNER", "INSERT", "INSTEAD", "INTERSECT", "INTO", "IS", "ISNULL", "JOIN",
        "KEY", "LEFT", "LIKE", "LIMIT", "MATCH", "NATURAL", "NO", "NOT", "NOTNULL",
        "NULL", "OF", "OFFSET", "ON", "OR", "ORDER", "OUTER", "PLAN", "PRAGMA",
        "PRIMARY", "QUERY", "RAISE", "REFERENCES", "REGEXP", "RELEASE", "RENAME",
        "REPLACE", "RESTRICT", "RIGHT", "ROLLBACK", "ROW", "ROWID", "SAVEPOINT",
        "SELECT", "SET", "TABLE", "TEMP", "TEMPORARY", "THEN", "TO", "TRANSACTION",
        "TRIGGER", "UNION", "UNIQUE", "UPDATE", "USING", "VACUUM", "VALUES", "VIEW",
        "VIRTUAL", "WHEN", "WHERE", "WITH", "WITHOUT"
    )

    /** 匹配「SQL 文中作为一个标识符出现的 token」 */
    fun referencesToken(sql: String, token: String): Boolean =
        tokenRegex(token).containsMatchIn(sql)

    /**
     * 判断 SQL 是否引用了名为 name 的列/对象。
     * 与 [referencesToken] 的区别：若 name 本身是 SQL 关键字（如 index、on、table），
     * 直接返回 false，避免把语句里的关键字误判为引用（宁可漏判也不误报）。
     */
    fun referencesColumn(sql: String, name: String): Boolean =
        name.uppercase() !in SQL_KEYWORDS && referencesToken(sql, name)

    private fun tokenRegex(token: String): Regex = TOKEN_CACHE.getOrPut(token) {
        val e = Regex.escape(token)
        Regex(
            "(?i)(?<![A-Za-z0-9_$\"`\\[])" +
                    "(?:\"" + e + "\"|\\[" + e + "\\]|`" + e + "`|" + e + ")" +
                    "(?![A-Za-z0-9_$])"
        )
    }

    /**
     * 找出「引用了目标表、需要先移除再恢复」的外部对象。
     *
     * 必要性：DROP TABLE 在以下情况会直接报错（实测）：
     *  - 其他表上的触发器引用了它（error in trigger xxx: no such table）
     *  - 存在引用它的视图（error in view xxx: no such table）
     *
     * 注意排除「挂在目标表自身上的触发器」——那些由调用方作为 selfTriggers 传参处理。
     * 闭包计算：链式视图（v2 引用 v1，v1 引用本表）也要一并找出。
     */
    fun dependentObjects(objects: List<SchemaObject>, table: String): List<SchemaObject> {
        val candidates = objects.filterNot { it.type == "trigger" && it.attachTable == table }
        val keep = LinkedHashMap<String, SchemaObject>()
        var changed = true
        while (changed) {
            changed = false
            for (o in candidates) {
                if (keep.containsKey(o.name)) continue
                val depends = referencesColumn(o.sql, table) ||
                        keep.keys.any { referencesColumn(o.sql, it) }
                if (depends) {
                    keep[o.name] = o
                    changed = true
                }
            }
        }
        return keep.values.toList()
    }

    /**
     * 给出恢复顺序：被依赖的对象排在前面。
     * 移除时反过来（先用后删）。
     */
    fun restoreOrder(deps: List<SchemaObject>): List<SchemaObject> {
        val names = deps.map { it.name }.toSet()
        val done = HashSet<String>()
        val out = ArrayList<SchemaObject>()
        fun visit(o: SchemaObject) {
            if (o.name in done) return
            done.add(o.name)
            deps.filter { it.name != o.name && it.name in names && referencesColumn(o.sql, it.name) }
                .forEach { visit(it) }
            out.add(o)
        }
        deps.forEach { visit(it) }
        return out
    }

    /** SQL 字符串字面量 */
    fun stringLiteral(s: String): String = "'" + s.replace("'", "''") + "'"

    /** 解析 sqlite_master.sql 判断是否 WITHOUT ROWID 表 */
    fun isWithoutRowid(createSql: String?): Boolean =
        createSql != null && Regex("\\bWITHOUT\\s+ROWID\\b", RegexOption.IGNORE_CASE).containsMatchIn(createSql)

    /** 判断是否是虚拟表（FTS 等影子表不能被安全重建） */
    fun isVirtualTable(createSql: String?): Boolean =
        createSql != null && Regex("\\bUSING\\s+", RegexOption.IGNORE_CASE).containsMatchIn(createSql)

    /** 常用列类型建议 */
    val COMMON_TYPES = listOf("INTEGER", "TEXT", "REAL", "BLOB", "NUMERIC", "BOOLEAN", "DATETIME")

    /** 生成 CREATE TABLE 语句 */
    fun createTableSql(table: String, columns: List<DdlColumn>, fkSqls: List<String>): String {
        require(columns.isNotEmpty()) { "表至少需要一列" }
        require(columns.map { it.name }.toSet().size == columns.size) { "列名不能重复" }

        val pks = columns.filter { it.isPk }.sortedBy { it.pkPosition }
        val autoIncSingle = pks.size == 1 && pks[0].autoIncrement && pks[0].isIntegerPk

        val parts = mutableListOf<String>()
        columns.forEach { parts.add(ddlColumnDefinition(it, autoIncSingle)) }

        // 非「单列 INTEGER PK AUTOINCREMENT」时，主键用表级约束表达，兼容复合主键与任意类型
        if (pks.isNotEmpty() && !autoIncSingle) {
            parts.add("PRIMARY KEY(" + pks.joinToString(", ") { quoteIdent(it.name) } + ")")
        }
        fkSqls.forEach { parts.add(it) }

        return "CREATE TABLE ${quoteIdent(table)} (\n  " + parts.joinToString(",\n  ") + "\n)"
    }

    /**
     * 把 PRAGMA foreign_key_list 的结果组装成 FOREIGN KEY 子句。
     * @param rows 每行：(id, seq, 目标表, from 列, to 列, on_update, on_delete, match)
     */
    fun foreignKeyClause(
        rows: List<ForeignKeyRow>
    ): List<String> = rows
        .groupBy { it.id }
        .toSortedMap()
        .values
        .mapNotNull { group ->
            val sorted = group.sortedBy { it.seq }
            val target = sorted.firstOrNull()?.targetTable ?: return@mapNotNull null
            val fromCols = sorted.map { quoteIdent(it.fromColumn) }
            val toCols = sorted.mapNotNull { it.toColumn }
            val sb = StringBuilder()
            sb.append("FOREIGN KEY(").append(fromCols.joinToString(", ")).append(')')
            sb.append(" REFERENCES ").append(quoteIdent(target))
            if (toCols.size == fromCols.size) {
                sb.append('(').append(toCols.joinToString(", ") { quoteIdent(it) }).append(')')
            }
            val onUpdate = sorted.first().onUpdate
            val onDelete = sorted.first().onDelete
            if (!onUpdate.isNullOrBlank() && !onUpdate.equals("NO ACTION", true)) {
                sb.append(" ON UPDATE ").append(onUpdate)
            }
            if (!onDelete.isNullOrBlank() && !onDelete.equals("NO ACTION", true)) {
                sb.append(" ON DELETE ").append(onDelete)
            }
            val match = sorted.first().match
            if (!match.isNullOrBlank() && !match.equals("NONE", true)) {
                sb.append(" MATCH ").append(match)
            }
            sb.toString()
        }

    /** PRAGMA foreign_key_list 的一行 */
    data class ForeignKeyRow(
        val id: Int,
        val seq: Int,
        val targetTable: String?,
        val fromColumn: String,
        val toColumn: String?,
        val onUpdate: String?,
        val onDelete: String?,
        val match: String?
    )

    /** 列名是否可用 */
    fun isValidColumnName(name: String): Boolean = name.isNotBlank()

    // ---------------- 结构变更比对（应用前的预览） ----------------

    /** 一列表述结构变更比较的两列 */
    data class ColumnPair(val old: DdlColumn, val new: DdlColumn)

    /**
     * 结构变更摘要。应用前给用户看，重点是把「数据怎么搬」说清楚，
     * 因为搬错列（比如顺序错位）是很难察觉的。
     */
    data class SchemaDiff(
        val added: List<DdlColumn>,
        val removed: List<DdlColumn>,
        val renamed: List<ColumnPair>,
        val typeChanged: List<ColumnPair>,
        val constraintsChanged: List<ColumnPair>,
        val moved: List<String>,
        val warnings: List<String>
    ) {
        val hasChanges: Boolean
            get() = added.isNotEmpty() || removed.isNotEmpty() || renamed.isNotEmpty() ||
                    typeChanged.isNotEmpty() || constraintsChanged.isNotEmpty() || moved.isNotEmpty()
    }

    /**
     * 比对旧/新列定义。
     *
     * 列的对应关系靠 `originalName`：新列的 originalName 指向它从哪个旧列取数据。
     */
    fun diffSchema(old: List<DdlColumn>, new: List<DdlColumn>): SchemaDiff {
        val oldByName = old.associateBy { it.name }
        // 「有效来源」必须真实存在于旧表；否则该列应被当作新增
        // （DbManager.applySchema 会拒绝无效来源，这里统一口径，避免预览漏报）
        fun validSource(c: DdlColumn): String? = c.originalName?.takeIf { it in oldByName }

        val newBySource = new.mapNotNull { n -> validSource(n)?.let { it to n } }.toMap()

        val added = new.filter { validSource(it) == null }
        val removed = old.filter { it.name !in newBySource }

        fun paired(): List<ColumnPair> = new.mapNotNull { n ->
            val o = validSource(n)?.let { oldByName[it] } ?: return@mapNotNull null
            ColumnPair(o, n)
        }

        val renamed = paired().filter { (o, n) -> o.name != n.name }
        val typeChanged = paired().filter { (o, n) ->
            o.type.trim().uppercase() != n.type.trim().uppercase()
        }
        val constraintsChanged = paired().filter { (o, n) ->
            o.notNull != n.notNull || o.defaultValue != n.defaultValue ||
                    o.isPk != n.isPk ||
                    (o.autoIncrement != n.autoIncrement && !typeChanged.any { it.new === n })
        }

        // 保留列的相对顺序是否变了
        val oldOrder = old.map { it.name }
        val carried = new.mapNotNull { validSource(it) }
        val keptOldOrder = oldOrder.filter { it in carried }
        val moved = if (keptOldOrder != carried) carried else emptyList()

        val warnings = mutableListOf<String>()
        if (removed.isNotEmpty()) {
            warnings.add(
                "删除列会丢数据：${removed.joinToString("、") { it.name }}"
            )
        }
        added.filter { it.notNull && it.defaultValue == null }.forEach {
            warnings.add("新列「${it.name}」是 NOT NULL 且无默认值，已有行会插入失败")
        }
        if (typeChanged.isNotEmpty()) {
            warnings.add(
                "类型变更不会转换数据（不做 CAST）。如把 TEXT 改成 INTEGER，" +
                        "非数字内容会原样保留在 INTEGER 亲和性列里"
            )
        }
        if (moved.isNotEmpty()) {
            warnings.add("列顺序已调整，请确认「数据来源」对应无误")
        }
        val pkChanged = paired().count { (o, n) -> o.isPk != n.isPk }
        if (pkChanged > 0) {
            warnings.add("主键定义发生变化，行定位方式可能改变")
        }
        return SchemaDiff(
            added = added,
            removed = removed,
            renamed = renamed,
            typeChanged = typeChanged,
            constraintsChanged = constraintsChanged,
            moved = moved,
            warnings = warnings
        )
    }

    /** 把摘要渲染成可直接展示的多行文本 */
    fun renderDiff(diff: SchemaDiff, table: String): String {
        if (!diff.hasChanges) return "没有检测到结构变更。"
        val sb = StringBuilder()
        sb.append("表：").append(table).append('\n')
        if (diff.added.isNotEmpty()) {
            sb.append("\n＋ 新增列（无数据来源，取默认值或 NULL）\n")
            diff.added.forEach { sb.append("    · ").append(describe(it)).append('\n') }
        }
        if (diff.removed.isNotEmpty()) {
            sb.append("\n－ 删除列（这些列的数据会丢失）\n")
            diff.removed.forEach { sb.append("    · ").append(it.name).append('\n') }
        }
        if (diff.renamed.isNotEmpty()) {
            sb.append("\n→ 改列名（数据会搬过去）\n")
            diff.renamed.forEach { (o, n) ->
                sb.append("    · ").append(o.name).append("  ⇒  ").append(n.name).append('\n')
            }
        }
        if (diff.typeChanged.isNotEmpty()) {
            sb.append("\n⇄ 改类型\n")
            diff.typeChanged.forEach { (o, n) ->
                sb.append("    · ").append(n.name).append("：")
                    .append(o.type.ifBlank { "无类型" }).append(" ⇒ ")
                    .append(n.type.ifBlank { "无类型" }).append('\n')
            }
        }
        if (diff.constraintsChanged.isNotEmpty()) {
            sb.append("\n⚙ 约束变化\n")
            diff.constraintsChanged.forEach { (o, n) ->
                sb.append("    · ").append(n.name).append("：")
                    .append(constraintSummary(o)).append(" ⇒ ").append(constraintSummary(n)).append('\n')
            }
        }
        if (diff.moved.isNotEmpty()) {
            sb.append("\n↕ 新的列顺序\n    ")
            sb.append(diff.moved.joinToString(" , "))
            sb.append('\n')
        }
        if (diff.warnings.isNotEmpty()) {
            sb.append("\n⚠ 注意\n")
            diff.warnings.forEach { sb.append("    · ").append(it).append('\n') }
        }
        return sb.toString()
    }

    private fun describe(c: DdlColumn): String =
        c.name + (if (c.type.isBlank()) "" else " ${c.type}") + " " + constraintSummary(c)

    private fun constraintSummary(c: DdlColumn): String {
        val parts = mutableListOf<String>()
        if (c.isPk) parts.add("主键")
        if (c.autoIncrement) parts.add("自增")
        if (c.notNull) parts.add("非空")
        c.defaultValue?.let { parts.add("默认 $it") }
        return if (parts.isEmpty()) "—" else parts.joinToString("/")
    }
}
