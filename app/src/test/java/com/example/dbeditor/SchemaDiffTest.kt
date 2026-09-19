package com.example.dbeditor

import com.example.dbeditor.SqlUtil.DdlColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 结构变更比对（应用前预览）的纯逻辑测试。
 *
 * 预览存在的理由：列的对应关系靠「数据来源」，一旦错位，数据会静静地搬进错误的列，
 * 不报错也不丢行数，极难发现。所以这里要确保摘要能把「什么变了」说准。
 */
class SchemaDiffTest {

    private fun col(
        name: String,
        type: String = "TEXT",
        notNull: Boolean = false,
        default: String? = null,
        pk: Int = 0,
        autoInc: Boolean = false,
        from: String? = name
    ) = DdlColumn(name, type, notNull, default, pk, autoInc, from)

    /** 一张典型的三列旧表 */
    private val old = listOf(
        col("id", "INTEGER", pk = 1, autoInc = true),
        col("name", "TEXT", notNull = true),
        col("city", "TEXT")
    )

    @Test
    fun noChangesWhenIdentical() {
        val diff = SqlUtil.diffSchema(old, old)
        assertFalse(diff.hasChanges)
        assertTrue(diff.added.isEmpty())
        assertTrue(diff.removed.isEmpty())
        assertEquals("没有检测到结构变更。", SqlUtil.renderDiff(diff, "t"))
    }

    @Test
    fun detectsAddedColumn() {
        val new = old + col("level", "INTEGER", default = "1", from = null)
        val diff = SqlUtil.diffSchema(old, new)
        assertTrue(diff.hasChanges)
        assertEquals(listOf("level"), diff.added.map { it.name })
        assertTrue(diff.removed.isEmpty())
        assertTrue(SqlUtil.renderDiff(diff, "t").contains("＋ 新增列"))
    }

    @Test
    fun detectsRemovedColumnAndWarnsAboutDataLoss() {
        val new = old.filter { it.name != "city" }
        val diff = SqlUtil.diffSchema(old, new)
        assertEquals(listOf("city"), diff.removed.map { it.name })
        assertTrue("删列必须有丢数据警告", diff.warnings.any { it.contains("删除列会丢数据") })
        assertTrue(diff.warnings.any { it.contains("city") })
    }

    @Test
    fun detectsRenameViaOriginalName() {
        // name 改名为 full_name：新列的 originalName 仍指向 name
        val new = listOf(
            col("id", "INTEGER", pk = 1, autoInc = true),
            col("full_name", "TEXT", notNull = true, from = "name"),
            col("city", "TEXT")
        )
        val diff = SqlUtil.diffSchema(old, new)
        assertEquals(1, diff.renamed.size)
        assertEquals("name", diff.renamed[0].old.name)
        assertEquals("full_name", diff.renamed[0].new.name)
        // 改名不应被当成「删除 + 新增」
        assertTrue(diff.added.isEmpty())
        assertTrue(diff.removed.isEmpty())
        val text = SqlUtil.renderDiff(diff, "t")
        assertTrue(text.contains("改列名"))
        assertTrue(text.contains("name  ⇒  full_name"))
    }

    @Test
    fun columnWithNoSourceIsTreatedAsAdded() {
        // 即使名字相同，只要 originalName 为空就说明是新列（界面里选了「(新列，无数据)」）
        val new = listOf(
            col("id", "INTEGER", pk = 1, autoInc = true),
            col("name", "TEXT", notNull = true, from = null)
        )
        val diff = SqlUtil.diffSchema(old, new)
        // name 失去来源 -> 算新增；旧的 name/city 都算删除
        assertEquals(listOf("name"), diff.added.map { it.name })
        assertEquals(setOf("name", "city"), diff.removed.map { it.name }.toSet())
    }

    @Test
    fun detectsTypeChange() {
        val new = listOf(
            col("id", "INTEGER", pk = 1, autoInc = true),
            col("name", "TEXT", notNull = true),
            col("city", "INTEGER")
        )
        val diff = SqlUtil.diffSchema(old, new)
        assertEquals(listOf("city"), diff.typeChanged.map { it.new.name })
        assertTrue("类型变更必须提示不做数据转换", diff.warnings.any { it.contains("CAST") })
        assertTrue(SqlUtil.renderDiff(diff, "t").contains("改类型"))
    }

    @Test
    fun typeChangeIsCaseInsensitiveAndTrimmed() {
        val new = listOf(
            col("id", "INTEGER", pk = 1, autoInc = true),
            col("name", "text", notNull = true),
            col("city", "TEXT")
        )
        val diff = SqlUtil.diffSchema(old, new)
        assertTrue("text 与 TEXT 应视为同一类型", diff.typeChanged.isEmpty())
    }

    @Test
    fun detectsConstraintChanges() {
        val new = listOf(
            col("id", "INTEGER", pk = 1, autoInc = true),
            col("name", "TEXT", notNull = false), // 去掉 NOT NULL
            col("city", "TEXT", default = "'未知'") // 加默认值
        )
        val diff = SqlUtil.diffSchema(old, new)
        val names = diff.constraintsChanged.map { it.new.name }.toSet()
        assertEquals(setOf("name", "city"), names)
        assertTrue(SqlUtil.renderDiff(diff, "t").contains("约束变化"))
    }

    @Test
    fun detectsColumnReorder() {
        val new = listOf(
            col("id", "INTEGER", pk = 1, autoInc = true),
            col("city", "TEXT"),
            col("name", "TEXT", notNull = true)
        )
        val diff = SqlUtil.diffSchema(old, new)
        assertEquals(listOf("id", "city", "name"), diff.moved)
        assertTrue("列顺序变化要提示核对数据来源", diff.warnings.any { it.contains("列顺序") })
    }

    @Test
    fun noReorderWarningWhenOrderUnchanged() {
        val new = old + col("extra", "TEXT", from = null)
        val diff = SqlUtil.diffSchema(old, new)
        assertTrue(diff.moved.isEmpty())
    }

    @Test
    fun newNotNullColumnWithoutDefaultIsRejected() {
        val new = old + col("required", "TEXT", notNull = true, from = null)
        val diff = SqlUtil.diffSchema(old, new)
        assertTrue(
            "NOT NULL 且无默认值的新列会让已有行插入失败",
            diff.warnings.any { it.contains("required") && it.contains("NOT NULL") }
        )
    }

    @Test
    fun newNotNullColumnWithDefaultIsFine() {
        val new = old + col("required", "TEXT", notNull = true, default = "'x'", from = null)
        val diff = SqlUtil.diffSchema(old, new)
        assertTrue(diff.warnings.none { it.contains("required") })
    }

    @Test
    fun warnsWhenPrimaryKeyChanges() {
        val new = listOf(
            col("id", "INTEGER", autoInc = false), // 不再是主键
            col("name", "TEXT", notNull = true, pk = 1),
            col("city", "TEXT")
        )
        val diff = SqlUtil.diffSchema(old, new)
        assertTrue(diff.warnings.any { it.contains("主键") })
    }

    @Test
    fun invalidSourceIsTreatedAsAddedNotSilentlyIgnored() {
        // 来源列在旧表里不存在时，不能掉进「既不是新增也不是对应」的灰色地带，
        // 否则预览会漏报（应用时 DbManager 会报「找不到来源列」）
        val new = listOf(col("id", "INTEGER", pk = 1, autoInc = true), col("ghost", "TEXT", from = "nope"))
        val diff = SqlUtil.diffSchema(old, new)
        assertEquals(listOf("ghost"), diff.added.map { it.name })
        assertTrue(diff.renamed.isEmpty())
        assertTrue(diff.typeChanged.isEmpty())
    }

    @Test
    fun emptyToColumnsReportsOnlyAdded() {
        val diff = SqlUtil.diffSchema(emptyList(), old)
        assertEquals(3, diff.added.size)
        assertTrue(diff.removed.isEmpty())
        assertTrue(diff.renamed.isEmpty())
    }

    @Test
    fun renderDiffListsEverySection() {
        val new = listOf(
            col("id", "INTEGER", pk = 1, autoInc = true),
            col("full_name", "TEXT", notNull = true, from = "name"),
            col("city", "INTEGER"),
            col("level", "INTEGER", default = "1", from = null)
        )
        val diff = SqlUtil.diffSchema(old, new)
        val text = SqlUtil.renderDiff(diff, "users")
        assertTrue(text.contains("表：users"))
        assertTrue("新增段", text.contains("新增列"))
        assertTrue("改名段", text.contains("改列名"))
        assertTrue("类型段", text.contains("改类型"))
        assertTrue("注意段", text.contains("注意"))
        assertTrue(text.contains("full_name"))
        assertTrue(text.contains("level"))
    }

    @Test
    fun renameAndTypeChangeTogetherBothReported() {
        val new = listOf(
            col("id", "INTEGER", pk = 1, autoInc = true),
            col("full_name", "INTEGER", from = "name"), // 既改名又改类型
            col("city", "TEXT")
        )
        val diff = SqlUtil.diffSchema(old, new)
        assertEquals(1, diff.renamed.size)
        assertEquals(1, diff.typeChanged.size)
        assertEquals("full_name", diff.typeChanged[0].new.name)
    }

    @Test
    fun autoIncrementChangeIsReportedAsConstraintChange() {
        val new = listOf(
            col("id", "INTEGER", pk = 1, autoInc = false), // 去掉自增
            col("name", "TEXT", notNull = true),
            col("city", "TEXT")
        )
        val diff = SqlUtil.diffSchema(old, new)
        assertTrue(diff.constraintsChanged.any { it.new.name == "id" })
    }
}
