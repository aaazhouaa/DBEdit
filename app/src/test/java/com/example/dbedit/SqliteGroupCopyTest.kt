package com.example.dbedit

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.DriverManager

/**
 * 验证 DbManager.copySqliteGroup 会把主文件与同目录 -wal/-shm 一起复制，
 * 避免只复制主文件时丢掉 WAL 里尚未 checkpoint 的提交。
 */
class SqliteGroupCopyTest {

    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = File.createTempFile("dbgroup", ".d").apply {
            delete()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    @Test
    fun `copies wal and shm when present`() {
        val src = File(tmp, "a.db")
        val destDir = File(tmp, "out").apply { mkdirs() }
        val dest = File(destDir, "a.db")

        // 建 WAL 库，保持连接打开、不做 checkpoint，让数据留在 -wal 里。
        // autoCommit 下每条语句自动提交，WAL 模式会写入 -wal 而不是立即合并回主文件。
        val conn = DriverManager.getConnection("jdbc:sqlite:${src.absolutePath}")
        conn.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
        conn.createStatement().use { it.execute("CREATE TABLE t(x TEXT)") }
        conn.createStatement().use { it.execute("INSERT INTO t VALUES('wal-only')") }

        val wal = File(src.absolutePath + "-wal")
        assertTrue(wal.exists() && wal.length() > 0)

        // 源连接还开着，-wal 尚未合并；此时复制应连同 -wal/-shm 一起带过去。
        DbManager.copySqliteGroup(src, dest)
        conn.close()

        // 副本应能读到留在 WAL 里的数据。
        DriverManager.getConnection("jdbc:sqlite:${dest.absolutePath}").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT x FROM t").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("wal-only", rs.getString(1))
                }
            }
        }
    }
}
