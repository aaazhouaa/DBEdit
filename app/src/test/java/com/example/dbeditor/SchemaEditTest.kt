package com.example.dbeditor

import com.example.dbeditor.SqlUtil.DdlColumn
import com.example.dbeditor.SqlUtil.SchemaObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * 表结构编辑（加列 / 删列 / 改名 / 改类型 / 复合主键）的 DDL 生成，用真实 SQLite 验证。
 *
 * 重点验证重建表不会弄坏索引、触发器、视图、外键、自增序列——
 * 这些依赖对象最容易出错，也最容易被单测忽略。
 */
class SchemaEditTest {

    private lateinit var conn: Connection

    @Before
    fun setUp() {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { st ->
            st.execute("PRAGMA foreign_keys=ON")
            st.execute(
                "CREATE TABLE users(id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "name TEXT NOT NULL,email TEXT,age INTEGER,city TEXT,note TEXT)"
            )
            st.execute("INSERT INTO users(name,email,age,city,note) VALUES('张三','z@e.com',28,'北京','研发')")
            st.execute("INSERT INTO users(name,email,age,city,note) VALUES('李四','l@e.com',34,'上海','产品')")
            st.execute("INSERT INTO users(name,email,age,city,note) VALUES('Bob',NULL,30,'成都',NULL)")
        }
    }

    @After
    fun tearDown() {
        conn.close()
    }

    // ---------------- 辅助 ----------------

    private fun scalar(sql: String): Any? = conn.createStatement().use { st ->
        st.executeQuery(sql).use { rs -> if (rs.next()) rs.getObject(1) else null }
    }

    private fun count(sql: String): Int = (scalar(sql) as Number).toInt()

    private fun exec(vararg sqls: String) {
        conn.createStatement().use { st -> sqls.forEach { st.execute(it) } }
    }

    private fun columnNames(table: String): List<String> {
        val out = mutableListOf<String>()
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(${SqlUtil.quoteIdent(table)})").use { rs ->
                while (rs.next()) out.add(rs.getString("name"))
            }
        }
        return out
    }

    private fun columnType(table: String, column: String): String =
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(${SqlUtil.quoteIdent(table)})").use { rs ->
                while (rs.next()) if (rs.getString("name") == column) return@use rs.getString("type")
                ""
            }
        }

    private fun createSqlFor(name: String): String? =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT sql FROM sqlite_master WHERE name='$name'").use { rs ->
                if (rs.next()) rs.getString(1) else null
            }
        }

    private fun collectIndexes(table: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT name, sql FROM sqlite_master WHERE type='index' AND tbl_name='$table' " +
                        "AND sql IS NOT NULL"
            ).use { rs -> while (rs.next()) out.add(rs.getString(1) to rs.getString(2)) }
        }
        return out
    }

    private fun collectSelfTriggers(table: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT name, sql FROM sqlite_master WHERE type='trigger' AND tbl_name='$table'")
                .use { rs -> while (rs.next()) out.add(rs.getString(1) to rs.getString(2)) }
        }
        return out
    }

    private fun collectAllDeps(): List<SchemaObject> {
        val out = mutableListOf<SchemaObject>()
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT name, type, tbl_name, sql FROM sqlite_master " +
                        "WHERE type IN ('view','trigger') AND sql IS NOT NULL"
            ).use { rs ->
                while (rs.next()) {
                    out.add(SchemaObject(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                }
            }
        }
        return out
    }

    private fun collectFks(table: String): List<String> {
        val rows = mutableListOf<SqlUtil.ForeignKeyRow>()
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA foreign_key_list(${SqlUtil.quoteIdent(table)})").use { rs ->
                while (rs.next()) {
                    rows.add(
                        SqlUtil.ForeignKeyRow(
                            id = rs.getInt("id"),
                            seq = rs.getInt("seq"),
                            targetTable = rs.getString("table"),
                            fromColumn = rs.getString("from"),
                            toColumn = rs.getString("to"),
                            onUpdate = rs.getString("on_update"),
                            onDelete = rs.getString("on_delete"),
                            match = rs.getString("match")
                        )
                    )
                }
            }
        }
        return SqlUtil.foreignKeyClause(rows)
    }

    private fun sequenceValue(table: String): Long? =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT seq FROM sqlite_sequence WHERE name='$table'").use { rs ->
                if (rs.next()) rs.getLong(1) else null
            }
        }

    /** 完全复刻 DbManager.applySchema 的执行姿势 */
    private fun rebuild(table: String, columns: List<DdlColumn>) {
        val deps = SqlUtil.restoreOrder(SqlUtil.dependentObjects(collectAllDeps(), table))
        val stmts = SqlUtil.rebuildTableStatements(
            table = table,
            columns = columns,
            fkSqls = collectFks(table),
            indexes = collectIndexes(table),
            selfTriggers = collectSelfTriggers(table),
            deps = deps,
            preserveRowid = true,
            sequenceValue = sequenceValue(table)
        )
        conn.autoCommit = false
        try {
            exec("PRAGMA foreign_keys=OFF")
            deps.reversed().forEach { exec("DROP ${it.type.uppercase()} IF EXISTS ${SqlUtil.quoteIdent(it.name)}") }
            stmts.forEach { exec(it) }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            exec("PRAGMA foreign_keys=ON")
            conn.autoCommit = true
        }
    }

    private fun cols(vararg c: DdlColumn) = c.toList()

    // ---------------- 基本语句生成 ----------------

    @Test
    fun quoteIdent_escapesQuotes() {
        assertEquals("\"a\"\"b\"", SqlUtil.quoteIdent("a\"b"))
    }

    @Test
    fun createTableSql_singleIntegerPkAutoincrement() {
        val sql = SqlUtil.createTableSql(
            "t",
            cols(
                DdlColumn("id", "INTEGER", pkPosition = 1, autoIncrement = true),
                DdlColumn("name", "TEXT", notNull = true)
            ),
            emptyList()
        )
        assertTrue(sql.contains("\"id\" INTEGER PRIMARY KEY AUTOINCREMENT"))
        assertTrue(sql.contains("\"name\" TEXT NOT NULL"))
        assertFalse("AUTOINCREMENT 时不应再生成表级主键约束", sql.contains("PRIMARY KEY(\"id\")"))
    }

    @Test
    fun createTableSql_compositePkUsesTableConstraint() {
        val sql = SqlUtil.createTableSql(
            "t",
            cols(
                DdlColumn("a", "TEXT", pkPosition = 1, notNull = true),
                DdlColumn("b", "TEXT", pkPosition = 2, notNull = true),
                DdlColumn("v", "TEXT")
            ),
            emptyList()
        )
        assertTrue(sql.contains("PRIMARY KEY(\"a\", \"b\")"))
    }

    @Test
    fun ddlColumnDefinition_defaultAndEscaping() {
        val def = SqlUtil.ddlColumnDefinition(
            DdlColumn("we\"ird", "TEXT", notNull = true, defaultValue = "'hi'"),
            singlePkAutoIncrement = false
        )
        assertEquals("\"we\"\"ird\" TEXT NOT NULL DEFAULT 'hi'", def)
    }

    @Test
    fun createTableSql_rejectsDuplicateColumns() {
        val ex = runCatching {
            SqlUtil.createTableSql("t", cols(DdlColumn("a"), DdlColumn("a")), emptyList())
        }.exceptionOrNull()
        assertNotNull(ex)
    }

    @Test
    fun createTableSql_rejectsEmptyColumns() {
        val ex = runCatching { SqlUtil.createTableSql("t", emptyList(), emptyList()) }.exceptionOrNull()
        assertNotNull(ex)
    }

    @Test
    fun stringLiteral_escapesQuotes() {
        assertEquals("'a''b'", SqlUtil.stringLiteral("a'b"))
    }

    // ---------------- 加列 ----------------

    @Test
    fun addColumn_preservesDataAndExistingDefs() {
        rebuild(
            "users", cols(
                DdlColumn("id", "INTEGER", pkPosition = 1, autoIncrement = true, originalName = "id"),
                DdlColumn("name", "TEXT", notNull = true, originalName = "name"),
                DdlColumn("email", "TEXT", originalName = "email"),
                DdlColumn("age", "INTEGER", originalName = "age"),
                DdlColumn("city", "TEXT", originalName = "city"),
                DdlColumn("note", "TEXT", originalName = "note"),
                DdlColumn("level", "INTEGER", defaultValue = "1") // 新列
            )
        )

        assertEquals(
            listOf("id", "name", "email", "age", "city", "note", "level"),
            columnNames("users")
        )
        assertEquals(3, count("SELECT COUNT(*) FROM users"))
        assertEquals("张三", scalar("SELECT name FROM users WHERE id=1"))
        assertEquals("北京", scalar("SELECT city FROM users WHERE id=1"))
        assertEquals(3, count("SELECT COUNT(*) FROM users WHERE level=1"))
        assertTrue(createSqlFor("users")!!.contains("AUTOINCREMENT"))
    }

    @Test
    fun autoincrementSequenceIsPreserved() {
        // DROP TABLE 会清掉 sqlite_sequence，不恢复的话新插入会从 1 开始
        exec("UPDATE sqlite_sequence SET seq=100 WHERE name='users'")
        rebuild(
            "users", cols(
                DdlColumn("id", "INTEGER", pkPosition = 1, autoIncrement = true, originalName = "id"),
                DdlColumn("name", "TEXT", notNull = true, originalName = "name")
            )
        )
        exec("INSERT INTO users(name) VALUES('after')")
        assertTrue(
            "自增序列应延续，实际 MAX(id)=" + scalar("SELECT MAX(id) FROM users"),
            (scalar("SELECT MAX(id) FROM users") as Number).toLong() > 100
        )
    }

    // ---------------- 删列 ----------------

    @Test
    fun dropColumn_removesDataAndColumn() {
        rebuild(
            "users", cols(
                DdlColumn("id", "INTEGER", pkPosition = 1, autoIncrement = true, originalName = "id"),
                DdlColumn("name", "TEXT", notNull = true, originalName = "name"),
                DdlColumn("city", "TEXT", originalName = "city")
            )
        )
        assertEquals(listOf("id", "name", "city"), columnNames("users"))
        assertEquals(3, count("SELECT COUNT(*) FROM users"))
        assertEquals("上海", scalar("SELECT city FROM users WHERE name='李四'"))
    }

    // ---------------- 改列名 ----------------

    @Test
    fun renameColumn_movesData() {
        rebuild(
            "users", cols(
                DdlColumn("id", "INTEGER", pkPosition = 1, autoIncrement = true, originalName = "id"),
                DdlColumn("full_name", "TEXT", notNull = true, originalName = "name"),
                DdlColumn("email", "TEXT", originalName = "email")
            )
        )
        assertEquals(listOf("id", "full_name", "email"), columnNames("users"))
        assertEquals("张三", scalar("SELECT full_name FROM users WHERE id=1"))
        assertEquals(3, count("SELECT COUNT(*) FROM users WHERE full_name IS NOT NULL"))
    }

    // ---------------- 改类型 ----------------

    @Test
    fun changeType_updatesDeclaredTypeAndKeepsData() {
        rebuild(
            "users", cols(
                DdlColumn("id", "INTEGER", pkPosition = 1, autoIncrement = true, originalName = "id"),
                DdlColumn("name", "TEXT", notNull = true, originalName = "name"),
                DdlColumn("age", "TEXT", originalName = "age") // INTEGER -> TEXT
            )
        )
        assertEquals("TEXT", columnType("users", "age"))
        assertEquals(3, count("SELECT COUNT(*) FROM users"))
        assertEquals("28", scalar("SELECT age FROM users WHERE name='张三'").toString())
    }

    @Test
    fun changeType_textToInteger_doesNotZeroOutNonNumeric() {
        // 关键回归：搬数据时若用 CAST，非数字文本会被 CAST 成 0 而丢内容
        rebuild(
            "users", cols(
                DdlColumn("id", "INTEGER", pkPosition = 1, autoIncrement = true, originalName = "id"),
                DdlColumn("name", "INTEGER", originalName = "name"), // 故意把名字改成 INTEGER
                DdlColumn("age", "INTEGER", originalName = "age")
            )
        )
        assertEquals("张三", scalar("SELECT name FROM users WHERE id=1"))
    }

    // ---------------- 约束 ----------------

    @Test
    fun notNullConstraintIsEnforcedAfterRebuild() {
        rebuild(
            "users", cols(
                DdlColumn("id", "INTEGER", pkPosition = 1, autoIncrement = true, originalName = "id"),
                DdlColumn("name", "TEXT", notNull = true, originalName = "name")
            )
        )
        val ex = runCatching { exec("INSERT INTO users(name) VALUES(NULL)") }.exceptionOrNull()
        assertNotNull("NOT NULL 应在重建后依然生效", ex)
    }

    @Test
    fun nullsSurviveRebuild() {
        rebuild(
            "users", cols(
                DdlColumn("id", "INTEGER", pkPosition = 1, autoIncrement = true, originalName = "id"),
                DdlColumn("name", "TEXT", notNull = true, originalName = "name"),
                DdlColumn("note", "TEXT", originalName = "note"),
                DdlColumn("email", "TEXT", originalName = "email")
            )
        )
        assertEquals("Bob", scalar("SELECT name FROM users WHERE note IS NULL"))
        assertEquals(1, count("SELECT COUNT(*) FROM users WHERE email IS NULL"))
        assertNull(scalar("SELECT email FROM users WHERE name='Bob'"))
    }

    @Test
    fun defaultValueSurvivesRebuild() {
        exec("ALTER TABLE users ADD COLUMN flag INTEGER DEFAULT 7")
        rebuild(
            "users", cols(
                DdlColumn("id", "INTEGER", pkPosition = 1, autoIncrement = true, originalName = "id"),
                DdlColumn("flag", "INTEGER", defaultValue = "7", originalName = "flag")
            )
        )
        exec("INSERT INTO users(id) VALUES(100)")
        assertEquals(7L, (scalar("SELECT flag FROM users WHERE id=100") as Number).toLong())
    }

    // ---------------- 依赖对象：索引 / 触发器 / 视图 / 外键 ----------------

    @Test
    fun indexesAreRebuiltAndUsable() {
        exec("CREATE INDEX idx_users_city ON users(city)")
        exec("CREATE UNIQUE INDEX idx_users_email ON users(email)")
        rebuild(
            "users", cols(
                DdlColumn("id", "INTEGER", pkPosition = 1, autoIncrement = true, originalName = "id"),
                DdlColumn("name", "TEXT", notNull = true, originalName = "name"),
                DdlColumn("email", "TEXT", originalName = "email"),
                DdlColumn("age", "INTEGER", originalName = "age"),
                DdlColumn("city", "TEXT", originalName = "city"),
                DdlColumn("note", "TEXT", originalName = "note")
            )
        )
        val idxNames = collectIndexes("users").map { it.first }
        assertTrue("索引应被重建：$idxNames", idxNames.contains("idx_users_city"))
        assertTrue("唯一索引应被重建：$idxNames", idxNames.contains("idx_users_email"))

        val plan = conn.createStatement().use { st ->
            st.executeQuery("EXPLAIN QUERY PLAN SELECT * FROM users WHERE city='北京'").use { rs ->
                if (rs.next()) rs.getString(4) else ""
            }
        }
        assertTrue("应走索引，实际计划：$plan", plan.contains("idx_users_city", ignoreCase = true))

        val ex = runCatching { exec("INSERT INTO users(name,email) VALUES('dup','z@e.com')") }.exceptionOrNull()
        assertNotNull("唯一约束应保持", ex)
    }

    @Test
    fun selfTriggersAreRebuiltAndStillFire() {
        exec("CREATE TABLE audit(msg TEXT)")
        exec(
            "CREATE TRIGGER trg_users_ins AFTER INSERT ON users " +
                    "BEGIN INSERT INTO audit(msg) VALUES('inserted:' || NEW.name); END"
        )
        rebuild(
            "users", cols(
                DdlColumn("id", "INTEGER", pkPosition = 1, autoIncrement = true, originalName = "id"),
                DdlColumn("name", "TEXT", notNull = true, originalName = "name"),
                DdlColumn("city", "TEXT", originalName = "city")
            )
        )
        assertTrue(collectSelfTriggers("users").any { it.first == "trg_users_ins" })
        exec("INSERT INTO users(name,city) VALUES('触发测试','西安')")
        assertEquals(1, count("SELECT COUNT(*) FROM audit WHERE msg='inserted:触发测试'"))
    }

    @Test
    fun externalTriggerReferencingTableIsRestored() {
        // 其他表上的触发器引用了目标表：DROP TABLE 会直接报错，必须先摘掉再装回
        exec("CREATE TABLE audit(msg TEXT)")
        exec(
            "CREATE TRIGGER trg_audit_upd AFTER INSERT ON audit " +
                    "BEGIN UPDATE users SET note='touched' WHERE city=NEW.msg; END"
        )
        rebuild(
            "users", cols(
                DdlColumn("id", "INTEGER", pkPosition = 1, autoIncrement = true, originalName = "id"),
                DdlColumn("name", "TEXT", notNull = true, originalName = "name"),
                DdlColumn("city", "TEXT", originalName = "city"),
                DdlColumn("note", "TEXT", originalName = "note")
            )
        )
        assertNotNull("外部触发器应被恢复", createSqlFor("trg_audit_upd"))
        exec("INSERT INTO audit(msg) VALUES('北京')")
        assertEquals(1, count("SELECT COUNT(*) FROM users WHERE note='touched' AND city='北京'"))
    }

    @Test
    fun dependentViewsIncludingChainedOnesAreRestored() {
        exec("CREATE VIEW v_users AS SELECT city FROM users")
        exec("CREATE VIEW v_cities AS SELECT city FROM v_users WHERE city IS NOT NULL")
        exec("CREATE VIEW v_unrelated AS SELECT 1 AS one")
        rebuild(
            "users", cols(
                DdlColumn("id", "INTEGER", pkPosition = 1, autoIncrement = true, originalName = "id"),
                DdlColumn("name", "TEXT", notNull = true, originalName = "name"),
                DdlColumn("city", "TEXT", originalName = "city")
            )
        )
        assertEquals(3, count("SELECT COUNT(*) FROM v_users"))
        assertEquals(3, count("SELECT COUNT(*) FROM v_cities"))
        assertEquals("链式视图不能丢", 1, count("SELECT COUNT(*) FROM v_unrelated"))
    }

    @Test
    fun foreignKeysAreRebuiltAndEnforced() {
        exec(
            "CREATE TABLE orders(id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "user_id INTEGER REFERENCES users(id) ON DELETE CASCADE,amount REAL)"
        )
        exec("INSERT INTO orders(user_id,amount) VALUES(1, 9.9)")
        rebuild(
            "orders", cols(
                DdlColumn("id", "INTEGER", pkPosition = 1, autoIncrement = true, originalName = "id"),
                DdlColumn("user_id", "INTEGER", originalName = "user_id"),
                DdlColumn("amount", "REAL", originalName = "amount")
            )
        )
        val fkSql = collectFks("orders")
        assertEquals(1, fkSql.size)
        assertTrue("外键应带 ON DELETE CASCADE，实际：${fkSql[0]}", fkSql[0].contains("ON DELETE CASCADE"))

        exec("DELETE FROM users WHERE id=1")
        assertEquals(0, count("SELECT COUNT(*) FROM orders WHERE user_id=1"))
    }

    @Test
    fun foreignKeyAutoIndexFollowsConstraint() {
        exec("CREATE TABLE orders2(id INTEGER PRIMARY KEY, uid INTEGER REFERENCES users(id))")
        exec("INSERT INTO orders2 VALUES(1, 1)")
        rebuild(
            "orders2", cols(
                DdlColumn("id", "INTEGER", pkPosition = 1, originalName = "id"),
                DdlColumn("uid", "INTEGER", originalName = "uid")
            )
        )
        assertEquals(1, count("SELECT COUNT(*) FROM orders2"))
        assertTrue(collectFks("orders2").isNotEmpty())
    }

    // ---------------- 主键结构变化 ----------------

    @Test
    fun compositePkAddedToExistingTable() {
        val columns = cols(
            DdlColumn("id", "INTEGER", pkPosition = 1, originalName = "id"),
            DdlColumn("name", "TEXT", pkPosition = 2, notNull = true, originalName = "name"),
            DdlColumn("city", "TEXT", originalName = "city")
        )
        rebuild("users", columns)
        assertEquals(3, count("SELECT COUNT(*) FROM users"))
        exec("INSERT INTO users(id,name,city) VALUES(99,'张三','X')")
        val ex = runCatching { exec("INSERT INTO users(id,name,city) VALUES(99,'张三','Y')") }.exceptionOrNull()
        assertNotNull("复合主键唯一性应生效", ex)
    }

    @Test
    fun newTableWithCompositePkIsUsable() {
        exec(
            SqlUtil.createTableSql(
                "cp", cols(
                    DdlColumn("a", "TEXT", pkPosition = 1, notNull = true),
                    DdlColumn("b", "INTEGER", pkPosition = 2, notNull = true),
                    DdlColumn("v", "TEXT")
                ), emptyList()
            )
        )
        exec("INSERT INTO cp VALUES('x',1,'v1')")
        val ex = runCatching { exec("INSERT INTO cp VALUES('x',1,'v2')") }.exceptionOrNull()
        assertNotNull(ex)
        assertEquals(1, count("SELECT COUNT(*) FROM cp"))
    }

    // ---------------- rowid 搬迁 ----------------

    @Test
    fun preserveRowid_keepsRowidsStable() {
        exec("CREATE TABLE logs(a TEXT, b TEXT)")
        exec("INSERT INTO logs(rowid,a,b) VALUES(10,'x','y')")
        exec("INSERT INTO logs(rowid,a,b) VALUES(50,'p','q')")
        rebuild(
            "logs", cols(
                DdlColumn("a", "TEXT", originalName = "a"),
                DdlColumn("b", "TEXT", originalName = "b")
            )
        )
        val after = conn.createStatement().use { st ->
            st.executeQuery("SELECT rowid FROM logs ORDER BY rowid").use { rs ->
                val l = mutableListOf<Long>(); while (rs.next()) l.add(rs.getLong(1)); l
            }
        }
        assertEquals(listOf(10L, 50L), after)
    }

    // ---------------- 依赖分析（纯逻辑） ----------------

    @Test
    fun referencesToken_matchesWholeIdentifiersOnly() {
        assertTrue(SqlUtil.referencesToken("SELECT * FROM users", "users"))
        assertTrue(SqlUtil.referencesToken("SELECT * FROM \"users\"", "users"))
        assertTrue(SqlUtil.referencesToken("SELECT * FROM [users]", "users"))
        assertTrue(SqlUtil.referencesToken("SELECT * FROM `users`", "users"))
        assertTrue(SqlUtil.referencesToken("SELECT * FROM main.users", "users"))
        // 子串不应误命中
        assertFalse(SqlUtil.referencesToken("SELECT * FROM users_archive", "users"))
        assertFalse(SqlUtil.referencesToken("SELECT * FROM my_users", "users"))
        assertFalse(SqlUtil.referencesToken("SELECT * FROM users2", "users"))
    }

    @Test
    fun referencesColumn_skipsSqlKeywordsToAvoidFalsePositives() {
        // 列名恰好叫 index / on / table 时，不能把语句里的关键字当成列引用，
        // 否则合法的结构修改会被误拦
        val idxSql = "CREATE INDEX idx_a ON t(\"index\")"
        assertFalse("index 是关键字，应跳过", SqlUtil.referencesColumn(idxSql, "index"))
        assertFalse(SqlUtil.referencesColumn(idxSql, "on"))
        assertFalse(SqlUtil.referencesColumn("CREATE TABLE t(a TEXT)", "table"))
        // 非关键字列名仍然正常判定
        assertTrue(SqlUtil.referencesColumn(idxSql, "t"))
        assertTrue(SqlUtil.referencesToken(idxSql, "index"))
    }

    @Test
    fun guardCatchesDroppingColumnUsedByIndex() {
        // 应用结构前的前置检查：删掉被索引引用的列应报错，给出可操作提示
        exec("CREATE INDEX idx_city ON users(city)")
        val gone = listOf("city")
        val bad = collectIndexes("users").filter { (_, sql) ->
            gone.any { SqlUtil.referencesColumn(sql, it) }
        }.map { it.first }
        assertEquals(listOf("idx_city"), bad)
    }

    @Test
    fun guardCatchesRenamingColumnUsedByIndex() {
        exec("CREATE INDEX idx_city ON users(city)")
        // city 改名为 town 后，city 不再存在，但索引还引用着它
        val newNames = setOf("id", "name", "email", "age", "town", "note")
        val gone = listOf("id", "name", "email", "age", "city", "note").filter { it !in newNames }
        assertEquals(listOf("city"), gone)
        val bad = collectIndexes("users").filter { (_, sql) ->
            gone.any { SqlUtil.referencesColumn(sql, it) }
        }.map { it.first }
        assertEquals(listOf("idx_city"), bad)
    }

    @Test
    fun droppingColumnWithoutIndexPassesGuard() {
        val gone = listOf("note")
        val bad = collectIndexes("users").filter { (_, sql) ->
            gone.any { SqlUtil.referencesColumn(sql, it) }
        }
        assertTrue("note 上没索引，不应拦截", bad.isEmpty())
    }

    @Test
    fun dependentObjects_excludesSelfTriggersAndClosesTransitively() {
        val objs = listOf(
            SchemaObject("trg_self", "trigger", "users", "AFTER INSERT ON users BEGIN SELECT 1; END"),
            SchemaObject("trg_ext", "trigger", "audit", "AFTER INSERT ON audit BEGIN UPDATE users SET x=1; END"),
            SchemaObject("v1", "view", "v1", "CREATE VIEW v1 AS SELECT * FROM users"),
            SchemaObject("v2", "view", "v2", "CREATE VIEW v2 AS SELECT * FROM v1"),
            SchemaObject("unrelated", "view", "unrelated", "CREATE VIEW unrelated AS SELECT 1")
        )
        val deps = SqlUtil.dependentObjects(objs, "users").map { it.name }.toSet()
        assertEquals(setOf("trg_ext", "v1", "v2"), deps)
    }

    @Test
    fun restoreOrder_putsDependenciesFirst() {
        val objs = listOf(
            SchemaObject("v1", "view", "v1", "CREATE VIEW v1 AS SELECT * FROM users"),
            SchemaObject("v2", "view", "v2", "CREATE VIEW v2 AS SELECT * FROM v1"),
            SchemaObject("v3", "view", "v3", "CREATE VIEW v3 AS SELECT * FROM v2")
        )
        val order = SqlUtil.restoreOrder(objs).map { it.name }
        assertEquals(listOf("v1", "v2", "v3"), order)
        // 移除顺序应反过来
        assertEquals(listOf("v3", "v2", "v1"), order.reversed())
    }

    @Test
    fun restoreOrder_handlesIndependentObjects() {
        val objs = listOf(
            SchemaObject("a", "view", "a", "CREATE VIEW a AS SELECT 1"),
            SchemaObject("b", "view", "b", "CREATE VIEW b AS SELECT 2")
        )
        assertEquals(setOf("a", "b"), SqlUtil.restoreOrder(objs).map { it.name }.toSet())
    }

    // ---------------- 事务性 ----------------

    @Test
    fun rebuildIsAtomicOnFailure() {
        val columns = cols(
            DdlColumn("id", "INTEGER", pkPosition = 1, autoIncrement = true, originalName = "id"),
            DdlColumn("name", "TEXT", notNull = true, originalName = "name")
        )
        val stmts = SqlUtil.rebuildTableStatements(
            table = "users",
            columns = columns,
            fkSqls = emptyList(),
            indexes = collectIndexes("users"),
            selfTriggers = collectSelfTriggers("users"),
            deps = emptyList(),
            preserveRowid = false
        )
        conn.autoCommit = false
        try {
            exec("PRAGMA foreign_keys=OFF")
            stmts.take(2).forEach { exec(it) } // 建新表 + 搬数据
            exec("SELECT * FROM no_such_table") // 故意失败
            conn.commit()
        } catch (_: Exception) {
            conn.rollback()
        } finally {
            exec("PRAGMA foreign_keys=ON")
            conn.autoCommit = true
        }
        // 回滚后原表（含数据）应保持不变
        assertTrue(columnNames("users").contains("email"))
        assertEquals(3, count("SELECT COUNT(*) FROM users"))
        assertEquals("张三", scalar("SELECT name FROM users WHERE id=1"))
    }

    // ---------------- 解析辅助 ----------------

    @Test
    fun isWithoutRowidDetection() {
        assertTrue(SqlUtil.isWithoutRowid("CREATE TABLE t(a TEXT, PRIMARY KEY(a)) WITHOUT ROWID"))
        assertFalse(SqlUtil.isWithoutRowid("CREATE TABLE t(a TEXT)"))
        assertFalse(SqlUtil.isWithoutRowid(null))
    }

    @Test
    fun isVirtualTableDetection() {
        assertTrue(SqlUtil.isVirtualTable("CREATE VIRTUAL TABLE f USING fts5(a, b)"))
        assertFalse(SqlUtil.isVirtualTable("CREATE TABLE t(a TEXT)"))
        assertFalse(SqlUtil.isVirtualTable(null))
    }

    @Test
    fun foreignKeyClause_handlesCompositeFkAndActions() {
        val rows = listOf(
            SqlUtil.ForeignKeyRow(0, 0, "parent", "a", "x", "NO ACTION", "CASCADE", "NONE"),
            SqlUtil.ForeignKeyRow(0, 1, "parent", "b", "y", "NO ACTION", "CASCADE", "NONE")
        )
        val clause = SqlUtil.foreignKeyClause(rows)
        assertEquals(1, clause.size)
        assertEquals(
            "FOREIGN KEY(\"a\", \"b\") REFERENCES \"parent\"(\"x\", \"y\") ON DELETE CASCADE",
            clause[0]
        )
    }

    @Test
    fun foreignKeyClause_omitsNoAction() {
        val rows = listOf(
            SqlUtil.ForeignKeyRow(0, 0, "p", "a", "x", "NO ACTION", "NO ACTION", "NONE")
        )
        assertEquals("FOREIGN KEY(\"a\") REFERENCES \"p\"(\"x\")", SqlUtil.foreignKeyClause(rows)[0])
    }

    // ---------------- 视图（只读） ----------------

    @Test
    fun viewHasNoRowid_soRowidOrderingWouldFail() {
        // 这是「视图必须只读」的根本原因：视图没有 rowid
        exec("CREATE VIEW v_simple AS SELECT name, city FROM users")
        val ex = runCatching {
            exec("SELECT rowid FROM v_simple")
        }.exceptionOrNull()
        assertNotNull("视图不应有 rowid，实际却能查？", ex)
    }

    @Test
    fun viewPagingWithoutOrderByWorks() {
        // DbManager 对视图不拼 ORDER BY、不加 rowid 列，这样的分页语句必须有效
        exec("CREATE VIEW v_simple AS SELECT name, city FROM users")
        val sql = "SELECT * FROM \"v_simple\" LIMIT ? OFFSET ?"
        conn.prepareStatement(sql).use { ps ->
            ps.setInt(1, 2)
            ps.setInt(2, 0)
            ps.executeQuery().use { rs ->
                var n = 0
                while (rs.next()) n++
                assertEquals(2, n)
            }
        }
        // 视图也支持搜索（WHERE 里做 CAST LIKE）
        val where = SqlUtil.buildSearchWhere(listOf("name", "city"), "北京")!!
        val n = count("SELECT COUNT(*) FROM v_simple WHERE $where")
        assertEquals(0, n) // 参数未绑定，COUNT 应为 0；这里只验证语句合法
    }

    @Test
    fun dropViewDoesNotTouchUnderlyingTable() {
        exec("CREATE VIEW v_simple AS SELECT name FROM users")
        exec("DROP VIEW IF EXISTS \"v_simple\"")
        assertEquals(3, count("SELECT COUNT(*) FROM users"))
        assertNull(createSqlFor("v_simple"))
    }

    @Test
    fun dependentViewBlocksTableRebuildUnlessRemovedFirst() {
        // 实测：DROP TABLE 本身不报错，真正报错的是随后的 RENAME——
        // SQLite 在重命名时会校验所有引用该表的视图，视图失效则直接失败。
        // 这就是「必须先摘掉依赖视图」的原因。
        exec("CREATE VIEW v_simple AS SELECT name FROM users")
        val ex = runCatching {
            conn.autoCommit = false
            exec("CREATE TABLE users__new(id INTEGER, name TEXT)")
            exec("DROP TABLE users")
            exec("ALTER TABLE users__new RENAME TO users") // 此处应因失效视图而失败
            conn.commit()
        }.exceptionOrNull()
        runCatching {
            conn.rollback()
            conn.autoCommit = true
        }
        assertNotNull("存在依赖视图时，重建中的 RENAME 应报错", ex)
        // 回滚后原表应完好
        assertTrue(columnNames("users").contains("email"))
        assertEquals(3, count("SELECT COUNT(*) FROM users"))
    }

    @Test
    fun removingDependentViewFirstAllowsRebuild() {
        // 先摘掉视图、重建后再装回（DbManager 的做法）。rebuild() 内部已自动恢复依赖，
        // 所以这里不再重复创建视图，而是直接验证它已经回来了且可用。
        exec("CREATE VIEW v_simple AS SELECT name FROM users")
        rebuild(
            "users", cols(
                DdlColumn("id", "INTEGER", pkPosition = 1, autoIncrement = true, originalName = "id"),
                DdlColumn("name", "TEXT", notNull = true, originalName = "name")
            )
        )
        assertNotNull("依赖视图应被自动恢复", createSqlFor("v_simple"))
        assertEquals(3, count("SELECT COUNT(*) FROM v_simple"))
    }
}
