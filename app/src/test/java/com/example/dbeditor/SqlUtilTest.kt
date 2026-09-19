package com.example.dbeditor

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types

/**
 * 用真实 SQLite 引擎（JDBC）逐条执行 SqlUtil 构造的 SQL。
 *
 * 本环境是 aarch64 容器、无 KVM，跑不了 Android 模拟器，Robolectric 的原生库
 * 也只有 x86_64，无法用真实 android.database.sqlite。因此把「SQL 语句构造」
 * 全部收敛到无 Android 依赖的 SqlUtil，在这里用同版本 SQLite 引擎验证其正确性。
 */
class SqlUtilTest {

    private lateinit var conn: Connection

    private fun buildDemo() {
        conn.createStatement().use { st ->
            st.execute(
                "CREATE TABLE users(id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "name TEXT NOT NULL,email TEXT,age INTEGER,city TEXT,note TEXT)"
            )
            st.execute("INSERT INTO users(name,email,age,city,note) VALUES('张三','z@e.com',28,'北京','研发')")
            st.execute("INSERT INTO users(name,email,age,city,note) VALUES('李四','l@e.com',34,'上海','产品')")
            st.execute("INSERT INTO users(name,email,age,city,note) VALUES('Alice',NULL,25,'杭州','爱咖啡和猫')")
            st.execute("INSERT INTO users(name,email,age,city,note) VALUES('Bob','b@e.com',30,'成都',NULL)")
            st.execute("INSERT INTO users(name,email,age,city,note) VALUES('100%好人','p@e.com',40,'重庆','含百分号')")
            st.execute("CREATE TABLE products(sku TEXT PRIMARY KEY,title TEXT,price REAL,stock INTEGER)")
            st.execute("INSERT INTO products VALUES('A-001','键盘',499.0,32)")
            st.execute("INSERT INTO products VALUES('A-002','鼠标',129.0,120)")
        }
    }

    @Before
    fun setUp() {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
        buildDemo()
    }

    @After
    fun tearDown() {
        conn.close()
    }

    // ---------------- 工具 ----------------

    private fun count(sql: String, vararg args: String?): Int =
        conn.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> if (a == null) ps.setNull(i + 1, Types.VARCHAR) else ps.setString(i + 1, a) }
            ps.executeQuery().use { rs ->
                rs.next(); rs.getInt(1)
            }
        }

    /** 构造与 DbManager 完全一致的：SELECT 前缀 + 表 + WHERE + ORDER + LIMIT/OFFSET */
    private fun page(
        table: String,
        kind: SqlUtil.KeyKind,
        pkColumns: List<String>,
        offset: Int,
        limit: Int,
        columns: List<String>,
        keyword: String
    ): List<List<String?>> {
        val where = SqlUtil.buildSearchWhere(columns, keyword)
        val args = if (where != null) SqlUtil.searchArgs(columns, keyword) else emptyArray()
        val sql = "SELECT " + SqlUtil.selectPrefix(kind, "__key__") +
                "* FROM ${SqlUtil.quoteIdent(table)}" +
                (where?.let { " WHERE $it" } ?: "") +
                " ORDER BY ${SqlUtil.orderSql(kind, pkColumns)} LIMIT ? OFFSET ?"
        val out = mutableListOf<List<String?>>()
        conn.prepareStatement(sql).use { ps ->
            var i = 1
            args.forEach { ps.setString(i++, it) }
            ps.setInt(i++, limit)
            ps.setInt(i, offset)
            ps.executeQuery().use { rs ->
                val n = rs.metaData.columnCount
                while (rs.next()) {
                    out.add((1..n).map { rs.getString(it) })
                }
            }
        }
        return out
    }

    // ---------------- 标识符 / 转义 ----------------

    @Test
    fun quoteIdent_escapesDoubleQuotes() {
        assertEquals("\"users\"", SqlUtil.quoteIdent("users"))
        assertEquals("\"we\"\"ird\"", SqlUtil.quoteIdent("we\"ird"))
    }

    @Test
    fun likePattern_escapesWildcards() {
        assertEquals("%100\\%%", SqlUtil.likePattern("100%"))
        assertEquals("%a\\_b%", SqlUtil.likePattern("a_b"))
        assertEquals("%x\\\\y%", SqlUtil.likePattern("x\\y"))
    }

    @Test
    fun searchWhere_nullWhenBlank() {
        assertNull(SqlUtil.buildSearchWhere(listOf("name"), ""))
        assertNull(SqlUtil.buildSearchWhere(listOf("name"), "   "))
        assertNull(SqlUtil.buildSearchWhere(emptyList(), "abc"))
    }

    @Test
    fun searchWhere_buildsOrOverColumns() {
        val where = SqlUtil.buildSearchWhere(listOf("name", "note"), "abc")
        assertEquals(
            "CAST(\"name\" AS TEXT) LIKE ? ESCAPE '\\' OR CAST(\"note\" AS TEXT) LIKE ? ESCAPE '\\'",
            where
        )
    }

    @Test
    fun displayCell_nullAndEmpty() {
        assertEquals("NULL", SqlUtil.displayCell(null, true))
        assertEquals("", SqlUtil.displayCell(null, false))
        assertEquals("abc", SqlUtil.displayCell("abc", false))
    }

    // ---------------- 分页 / 排序 ----------------

    @Test
    fun paging_orderByRowid_noOverlap() {
        val cols = listOf("id", "name", "email", "age", "city", "note")
        val p0 = page("users", SqlUtil.KeyKind.ROWID, listOf("id"), 0, 2, cols, "")
        val p1 = page("users", SqlUtil.KeyKind.ROWID, listOf("id"), 2, 2, cols, "")
        val p2 = page("users", SqlUtil.KeyKind.ROWID, listOf("id"), 4, 2, cols, "")
        assertEquals(2, p0.size)
        assertEquals(2, p1.size)
        assertEquals(1, p2.size)
        // 第一列是取出的 rowid（__key__），分页之间不重叠
        val ids = (p0 + p1 + p2).map { it[0] }
        assertEquals(ids.size, ids.toSet().size)
        // ORDER BY rowid 应当是递增的
        assertEquals(ids.sortedBy { it!!.toLong() }, ids)
    }

    @Test
    fun paging_countMatchesTotal() {
        val cols = listOf("name", "email", "note")
        val where = SqlUtil.buildSearchWhere(cols, "e.com")
        val total = count(
            "SELECT COUNT(*) FROM \"users\" WHERE $where",
            *SqlUtil.searchArgs(cols, "e.com")
        )
        assertEquals(4, total)
        val rows = page("users", SqlUtil.KeyKind.ROWID, listOf("id"), 0, 200, cols, "e.com")
        assertEquals(total, rows.size)
    }

    @Test
    fun orderByTextPrimaryKey() {
        val rows = page("products", SqlUtil.KeyKind.PK, listOf("sku"), 0, 10, listOf("sku", "title"), "")
        assertEquals("A-001", rows[0][0])
        assertEquals("A-002", rows[1][0])
    }

    // ---------------- 搜索转义（真实引擎验证） ----------------

    @Test
    fun likeEscaping_percentIsLiteral() {
        val cols = listOf("name", "note")
        val where = SqlUtil.buildSearchWhere(cols, "100%")!!
        val n = count("SELECT COUNT(*) FROM \"users\" WHERE $where", *SqlUtil.searchArgs(cols, "100%"))
        assertEquals("字面 % 应命中含 '100%' 的那一行", 1, n)
    }

    @Test
    fun likeEscaping_barePercentMatchesNothing() {
        val cols = listOf("name", "note")
        val where = SqlUtil.buildSearchWhere(cols, "%")!!
        val n = count("SELECT COUNT(*) FROM \"users\" WHERE $where", *SqlUtil.searchArgs(cols, "%"))
        val all = count("SELECT COUNT(*) FROM users")
        assertEquals("字面 % 只应命中含 % 的那 1 行", 1, n)
        assertTrue("若无转义则 % 会全表命中（$all 行）", n < all)
    }

    @Test
    fun multiColumnSearchEscapesEveryLike() {
        // 回归测试：ESCAPE 只能作用于紧邻的 LIKE，多列时必须每列都带
        conn.createStatement().use { it.execute("INSERT INTO users(name,note) VALUES('50%off','折扣') ") }
        val cols = listOf("name", "note")
        val where = SqlUtil.buildSearchWhere(cols, "50%")!!
        val hit = count("SELECT COUNT(*) FROM \"users\" WHERE $where", *SqlUtil.searchArgs(cols, "50%"))
        assertEquals(1, hit)
        // 第二列也要能正确转义（若只在末尾加一次 ESCAPE，这里会因 % 通配而误命中）
        val miss = count("SELECT COUNT(*) FROM \"users\" WHERE $where", *SqlUtil.searchArgs(cols, "50X"))
        assertEquals(0, miss)
    }

    @Test
    fun likeEscaping_underscoreIsLiteral() {
        conn.createStatement().use { it.execute("INSERT INTO users(name,note) VALUES('x','stay_hungry')") }
        val cols = listOf("note")
        val where = SqlUtil.buildSearchWhere(cols, "stay_hungry")!!
        val hit = count("SELECT COUNT(*) FROM \"users\" WHERE $where", *SqlUtil.searchArgs(cols, "stay_hungry"))
        val miss = count("SELECT COUNT(*) FROM \"users\" WHERE $where", *SqlUtil.searchArgs(cols, "stayXhungry"))
        assertEquals(1, hit)
        assertEquals("_ 不应匹配任意单字符", 0, miss)
    }

    @Test
    fun search_chineseAndNullColumn() {
        val cols = listOf("name", "note")
        val where = SqlUtil.buildSearchWhere(cols, "咖啡")!!
        val n = count("SELECT COUNT(*) FROM \"users\" WHERE $where", *SqlUtil.searchArgs(cols, "咖啡"))
        assertEquals(1, n)
    }

    // ---------------- 主键定位 / 更新 / 插入 / 删除 ----------------

    @Test
    fun updateByRowid_setsValueAndNull() {
        val where = SqlUtil.whereSql(SqlUtil.KeyKind.ROWID, emptyList())
        val sql = SqlUtil.updateSql("users", listOf("city", "note"), where)
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, "南京")
            ps.setNull(2, Types.VARCHAR)
            ps.setString(3, "1")
            assertEquals(1, ps.executeUpdate())
        }
        conn.createStatement().use { st ->
            st.executeQuery("SELECT city, note FROM users WHERE rowid = 1").use { rs ->
                rs.next()
                assertEquals("南京", rs.getString(1))
                assertNull(rs.getString(2))
            }
        }
    }

    @Test
    fun updateByTextPk() {
        val where = SqlUtil.whereSql(SqlUtil.KeyKind.PK, listOf("sku"))
        val sql = SqlUtil.updateSql("products", listOf("stock"), where)
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, "999")
            ps.setString(2, "A-001")
            assertEquals(1, ps.executeUpdate())
        }
        conn.createStatement().use { st ->
            st.executeQuery("SELECT stock FROM products WHERE sku='A-001'").use { rs ->
                rs.next(); assertEquals("999", rs.getString(1))
            }
        }
    }

    @Test
    fun compositePk_whereUsesIsOperator_nullsMatch() {
        conn.createStatement().use {
            it.execute("CREATE TABLE cp(a TEXT, b TEXT, v TEXT, PRIMARY KEY(a,b))")
            it.execute("INSERT INTO cp VALUES(NULL, NULL, 'x')")
            it.execute("INSERT INTO cp VALUES('p', 'q', 'y')")
        }
        val where = SqlUtil.whereSql(SqlUtil.KeyKind.PK, listOf("a", "b"))
        assertEquals("\"a\" IS ? AND \"b\" IS ?", where)
        val sql = SqlUtil.updateSql("cp", listOf("v"), where)

        // NULL 主键行：IS NULL 可命中
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, "updated-null")
            ps.setNull(2, Types.VARCHAR)
            ps.setNull(3, Types.VARCHAR)
            assertEquals(1, ps.executeUpdate())
        }
        // 普通主键行
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, "updated-pq")
            ps.setString(2, "p")
            ps.setString(3, "q")
            assertEquals(1, ps.executeUpdate())
        }
        assertEquals(1, count("SELECT COUNT(*) FROM cp WHERE v='updated-null'"))
        assertEquals(1, count("SELECT COUNT(*) FROM cp WHERE v='updated-pq'"))
    }

    @Test
    fun insert_withColumnsAndNull() {
        val names = listOf("name", "email", "age", "city", "note")
        val sql = SqlUtil.insertSql("users", names)
        assertEquals(
            "INSERT INTO \"users\" (\"name\", \"email\", \"age\", \"city\", \"note\") VALUES (?, ?, ?, ?, ?)",
            sql
        )
        val before = count("SELECT COUNT(*) FROM users")
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, "新用户")
            ps.setNull(2, Types.VARCHAR)
            ps.setString(3, "18")
            ps.setString(4, "武汉")
            ps.setNull(5, Types.VARCHAR)
            ps.executeUpdate()
        }
        assertEquals(before + 1, count("SELECT COUNT(*) FROM users"))
        assertEquals(1, count("SELECT COUNT(*) FROM users WHERE name='新用户' AND email IS NULL"))
    }

    @Test
    fun insert_autoPkLeftToSqlite() {
        // 自增主键留空 -> 用不含 id 的列集合
        val sql = SqlUtil.insertSql("users", listOf("name"))
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, "只有名字")
            ps.executeUpdate()
        }
        conn.createStatement().use { st ->
            st.executeQuery("SELECT MAX(id) FROM users").use { rs ->
                rs.next()
                assertTrue(rs.getLong(1) > 0)
            }
        }
    }

    @Test
    fun insert_defaultValues_whenNoColumns() {
        conn.createStatement().use { it.execute("CREATE TABLE d(x INTEGER)") }
        val sql = SqlUtil.insertSql("d", emptyList())
        assertEquals("INSERT INTO \"d\" DEFAULT VALUES", sql)
        conn.createStatement().use { it.execute(sql) }
        assertEquals(1, count("SELECT COUNT(*) FROM d"))
    }

    @Test
    fun deleteByRowidAndPk() {
        val delRowid = SqlUtil.deleteSql("users", SqlUtil.whereSql(SqlUtil.KeyKind.ROWID, emptyList()))
        assertEquals("DELETE FROM \"users\" WHERE rowid = ?", delRowid)
        val before = count("SELECT COUNT(*) FROM users")
        conn.prepareStatement(delRowid).use { ps ->
            ps.setString(1, "2")
            assertEquals(1, ps.executeUpdate())
        }
        assertEquals(before - 1, count("SELECT COUNT(*) FROM users"))

        val delPk = SqlUtil.deleteSql("products", SqlUtil.whereSql(SqlUtil.KeyKind.PK, listOf("sku")))
        conn.prepareStatement(delPk).use { ps ->
            ps.setString(1, "A-002")
            assertEquals(1, ps.executeUpdate())
        }
        assertEquals(1, count("SELECT COUNT(*) FROM products"))
    }

    @Test
    fun updateNonExistentRowAffectsZero() {
        val sql = SqlUtil.updateSql("users", listOf("city"), SqlUtil.whereSql(SqlUtil.KeyKind.ROWID, emptyList()))
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, "X")
            ps.setString(2, "999999")
            assertEquals(0, ps.executeUpdate())
        }
    }

    @Test
    fun notNullConstraintRejectsNullInsert() {
        val sql = SqlUtil.insertSql("users", listOf("name"))
        val ex = runCatching {
            conn.prepareStatement(sql).use { ps ->
                ps.setNull(1, Types.VARCHAR)
                ps.executeUpdate()
            }
        }.exceptionOrNull()
        assertTrue("name 是 NOT NULL，插入 NULL 应报错", ex != null)
    }

    // ---------------- 全库搜索的拼接表达式 ----------------

    @Test
    fun searchAllConcatExpression_identifiesColumn() {
        val textCols = listOf("name", "note")
        val concat = textCols.joinToString(" || '\u0001' || ") { SqlUtil.quoteIdent(it) }
        val sql = "SELECT rowid, $concat FROM \"users\" WHERE " +
                textCols.joinToString(" OR ") { "${SqlUtil.quoteIdent(it)} LIKE ? ESCAPE '\\'" } +
                " ORDER BY rowid"
        conn.prepareStatement(sql).use { ps ->
            val pattern = SqlUtil.likePattern("咖啡")
            ps.setString(1, pattern)
            ps.setString(2, pattern)
            ps.executeQuery().use { rs ->
                assertTrue(rs.next())
                val parts = rs.getString(2).split('\u0001')
                assertEquals("Alice", parts[0])
                assertEquals("爱咖啡和猫", parts[1])
            }
        }
    }

    // ---------------- CSV ----------------

    @Test
    fun csvEscape_quotesWhenNeeded() {
        assertEquals("abc", SqlUtil.csvEscape("abc"))
        assertEquals("\"a,b\"", SqlUtil.csvEscape("a,b"))
        assertEquals("\"he said \"\"hi\"\"\"", SqlUtil.csvEscape("he said \"hi\""))
        assertEquals("\"line1\nline2\"", SqlUtil.csvEscape("line1\nline2"))
    }

    @Test
    fun csvLine_rendersNullAsEmpty() {
        assertEquals("a,,c", SqlUtil.csvLine(listOf("a", null, "c")))
        assertEquals("\"x,y\",z", SqlUtil.csvLine(listOf("x,y", "z")))
    }

    // ---------------- 元数据相关（PRAGMA / sqlite_master） ----------------

    @Test
    fun tableInfoReflectsPkAndNotNull() {
        val cols = mutableListOf<Triple<String, String, Int>>()
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(\"users\")").use { rs ->
                while (rs.next()) {
                    cols.add(Triple(rs.getString("name"), rs.getString("type"), rs.getInt("pk")))
                }
            }
        }
        assertEquals(listOf("id", "name", "email", "age", "city", "note"), cols.map { it.first })
        assertEquals("INTEGER", cols[0].second)
        assertEquals(1, cols[0].third)

        val productPk = conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(\"products\")").use { rs ->
                rs.next(); rs.getString("name") to rs.getString("type")
            }
        }
        assertEquals("sku" to "TEXT", productPk)
    }

    @Test
    fun listTablesExcludesInternal() {
        conn.createStatement().use { it.execute("CREATE INDEX idx_note ON users(note)") }
        val names = mutableListOf<String>()
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
            ).use { rs -> while (rs.next()) names.add(rs.getString(1)) }
        }
        assertTrue(names.contains("users"))
        assertTrue(names.contains("products"))
        assertTrue("索引不应被列为表", !names.contains("idx_note"))
    }

    @Test
    fun integrityCheckOnFreshDb() {
        val v = conn.createStatement().use { st ->
            st.executeQuery("PRAGMA integrity_check").use { rs -> rs.next(); rs.getString(1) }
        }
        assertEquals("ok", v)
    }

    @Test
    fun saveRoundTrip_persistsToFile() {
        // 模拟「保存回写」：把内存库序列化到文件，再打开确认数据还在
        val tmp = java.io.File.createTempFile("dbedit", ".db")
        tmp.deleteOnExit()
        val fileConn = DriverManager.getConnection("jdbc:sqlite:${tmp.absolutePath}")
        conn.createStatement().use { src ->
            val target = fileConn.createStatement()
            val ddl = src.executeQuery(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='users'"
            )
            ddl.next(); target.execute(ddl.getString(1))
            target.close()
        }
        // 复制数据
        val insertSql = SqlUtil.insertSql("users", listOf("id", "name", "email", "age", "city", "note"))
        conn.createStatement().use { st ->
            st.executeQuery("SELECT id,name,email,age,city,note FROM users").use { rs ->
                fileConn.prepareStatement(insertSql).use { ps ->
                    while (rs.next()) {
                        ps.setString(1, rs.getString(1))
                        ps.setString(2, rs.getString(2))
                        ps.setString(3, rs.getString(3))
                        ps.setString(4, rs.getString(4))
                        ps.setString(5, rs.getString(5))
                        ps.setString(6, rs.getString(6))
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
        }
        fileConn.close()

        val reopened = DriverManager.getConnection("jdbc:sqlite:${tmp.absolutePath}")
        reopened.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM users").use { rs ->
                rs.next(); assertEquals(5, rs.getInt(1))
            }
        }
        reopened.close()
    }
}
