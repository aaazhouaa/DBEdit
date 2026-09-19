package com.example.dbedit

import com.example.dbedit.SchemaFormLogic.Result
import com.example.dbedit.SchemaFormLogic.RowInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 列编辑表单的逻辑测试。
 *
 * 这块最值得测：主键编号写错会让复合主键定义失效；AUTOINCREMENT 校正写错会让自增静默失效；
 * NOT NULL 校验漏了会让结构修改在搬数据阶段才失败。
 */
class SchemaFormLogicTest {

    private fun row(
        name: String,
        type: String = "TEXT",
        notNull: Boolean = false,
        default: String? = null,
        pk: Boolean = false,
        autoInc: Boolean = false,
        source: String? = name
    ) = RowInput(name, type, notNull, default, pk, autoInc, source)

    private fun ok(rows: List<RowInput>, isNewTable: Boolean = false): List<SqlUtil.DdlColumn> {
        val r = SchemaFormLogic.build(rows, isNewTable)
        assertTrue("预期校验通过，实际：$r", r is Result.Ok)
        return (r as Result.Ok).columns
    }

    private fun invalid(rows: List<RowInput>): String {
        val r = SchemaFormLogic.build(rows)
        assertTrue("预期校验失败，实际：$r", r is Result.Invalid)
        return (r as Result.Invalid).message
    }

    // ---------------- 基本校验 ----------------

    @Test
    fun emptyRowsRejected() {
        assertEquals("至少需要一列", invalid(emptyList()))
    }

    @Test
    fun blankNameRejected() {
        assertTrue(invalid(listOf(row("  "))).contains("不能为空"))
    }

    @Test
    fun duplicateNameRejected() {
        val msg = invalid(listOf(row("a"), row("a")))
        assertTrue(msg.contains("重复"))
    }

    @Test
    fun duplicateNameDetectedAfterTrim() {
        // 界面上输入 "a" 和 "a " 应该算重复
        assertTrue(invalid(listOf(row("a"), row(" a "))).contains("重复"))
    }

    @Test
    fun namesAndTypesAreTrimmed() {
        val cols = ok(listOf(row("  name  ", "  TEXT  ")))
        assertEquals("name", cols[0].name)
        assertEquals("TEXT", cols[0].type)
    }

    @Test
    fun blankDefaultBecomesNull() {
        val cols = ok(listOf(row("a", default = "   ")))
        assertNull(cols[0].defaultValue)
    }

    @Test
    fun blankSourceBecomesNull() {
        val cols = ok(listOf(row("a", source = "  ")))
        assertNull("空白的数据来源应视为新列", cols[0].originalName)
    }

    // ---------------- 主键编号 ----------------

    @Test
    fun primaryKeysAreNumberedInUiOrder() {
        val cols = ok(
            listOf(
                row("id", "INTEGER", pk = true, autoInc = true),
                row("tenant", "TEXT", pk = true),
                row("note")
            )
        )
        assertEquals(1, cols[0].pkPosition)
        assertEquals(2, cols[1].pkPosition)
        assertEquals(0, cols[2].pkPosition)
        assertTrue(cols[0].isPk)
        assertTrue(cols[1].isPk)
    }

    @Test
    fun compositePkNumberingIsUnique() {
        // 回归：原先用 rows.indexOf() 求编号，重复数据项会算出同一个位置号，
        // 导致复合主键的定义错乱
        val cols = ok(
            listOf(
                row("a", "TEXT", pk = true),
                row("b", "TEXT", pk = true),
                row("c", "TEXT", pk = true)
            )
        )
        assertEquals(listOf(1, 2, 3), cols.map { it.pkPosition })
        assertEquals(listOf(1, 2, 3).toSet().size, cols.map { it.pkPosition }.toSet().size)
    }

    @Test
    fun noPkGivesZeroPositions() {
        val cols = ok(listOf(row("a"), row("b")))
        assertTrue(cols.all { it.pkPosition == 0 && !it.isPk })
    }

    // ---------------- AUTOINCREMENT 校正 ----------------

    @Test
    fun autoIncrementKeptForSingleIntegerPk() {
        val cols = ok(listOf(row("id", "INTEGER", pk = true, autoInc = true), row("name")))
        assertTrue("单个 INTEGER 主键应保留自增", cols[0].autoIncrement)
    }

    @Test
    fun autoIncrementDroppedForCompositePk() {
        val r = SchemaFormLogic.build(
            listOf(
                row("a", "INTEGER", pk = true, autoInc = true),
                row("b", "TEXT", pk = true)
            )
        )
        assertTrue(r is Result.Ok)
        val cols = (r as Result.Ok).columns
        assertTrue("复合主键下 AUTOINCREMENT 无效，应被取消", cols.none { it.autoIncrement })
        assertTrue("应告知用户已取消", r.notes.any { it.contains("自增") })
    }

    @Test
    fun autoIncrementDroppedForTextPk() {
        val r = SchemaFormLogic.build(listOf(row("sku", "TEXT", pk = true, autoInc = true)))
        assertTrue(r is Result.Ok)
        val cols = (r as Result.Ok).columns
        assertTrue("TEXT 主键不能自增", cols.none { it.autoIncrement })
        assertTrue(r.notes.any { it.contains("自增") })
    }

    @Test
    fun autoIncrementDroppedWhenNotPk() {
        val cols = ok(listOf(row("a", "INTEGER", pk = false, autoInc = true), row("id", "INTEGER", pk = true)))
        assertTrue("非主键列上的自增应被取消", cols.none { it.autoIncrement })
    }

    @Test
    fun autoIncrementNotOnColumnsWithoutRequest() {
        val r = SchemaFormLogic.build(listOf(row("id", "INTEGER", pk = true)))
        assertTrue(r is Result.Ok)
        assertTrue("没勾自增就不该产生提示", (r as Result.Ok).notes.isEmpty())
        assertTrue(r.columns.none { it.autoIncrement })
    }

    @Test
    fun integerPkWithModifierIsStillIntegerPk() {
        // "INTEGER NOT NULL" 这类写法也应被认成 INTEGER 主键
        val cols = ok(listOf(row("id", "INTEGER NOT NULL", pk = true, autoInc = true)))
        assertTrue(cols[0].autoIncrement)
    }

    // ---------------- NOT NULL 校验 ----------------

    @Test
    fun newNotNullColumnWithoutDefaultIsRejected() {
        val msg = invalid(
            listOf(row("id", "INTEGER", pk = true, autoInc = true), row("req", "TEXT", notNull = true, source = null))
        )
        assertTrue(msg.contains("req"))
        assertTrue(msg.contains("NOT NULL"))
    }

    @Test
    fun newNotNullColumnWithDefaultIsAllowed() {
        val cols = ok(
            listOf(
                row("id", "INTEGER", pk = true, autoInc = true),
                row("req", "TEXT", notNull = true, default = "'x'", source = null)
            )
        )
        assertEquals(2, cols.size)
    }

    @Test
    fun carriedNotNullColumnWithoutDefaultIsAllowed() {
        // 已有列带 NOT NULL 是正常的：它本来就有数据来源，搬过去不会违反约束
        val cols = ok(
            listOf(
                row("id", "INTEGER", pk = true, autoInc = true),
                row("name", "TEXT", notNull = true, source = "name")
            )
        )
        assertTrue(cols[1].notNull)
    }

    // ---------------- 组合场景 ----------------

    @Test
    fun renameKeepsSourcePointingAtOldName() {
        val cols = ok(
            listOf(
                row("id", "INTEGER", pk = true, autoInc = true),
                row("full_name", "TEXT", source = "name")
            )
        )
        assertEquals("name", cols[1].originalName)
        assertEquals("full_name", cols[1].name)
    }

    @Test
    fun typicalAddColumnScenario() {
        val cols = ok(
            listOf(
                row("id", "INTEGER", pk = true, autoInc = true),
                row("name", "TEXT", notNull = true),
                row("city", "TEXT"),
                row("level", "INTEGER", default = "1", source = null)
            )
        )
        assertEquals(4, cols.size)
        assertEquals("1", cols[3].defaultValue)
        assertNull(cols[3].originalName)
        assertTrue(cols.filter { it.originalName != null }.size == 3)
    }

    @Test
    fun newTableScenarioHasNoSources() {
        val cols = ok(
            listOf(
                row("id", "INTEGER", pk = true, autoInc = true, source = null),
                row("name", "TEXT", notNull = true, source = null)
            ),
            isNewTable = true
        )
        assertTrue("新建表不应该有数据来源", cols.all { it.originalName == null })
        assertTrue("新建表时 NOT NULL 列应被接受", cols[1].notNull)
    }

    @Test
    fun newTableAllowsNotNullWithoutDefault() {
        // 回归：这条校验原本不分场景，导致「新建表」连 name TEXT NOT NULL 都建不出来
        val cols = ok(listOf(row("name", "TEXT", notNull = true, source = null)), isNewTable = true)
        assertTrue(cols[0].notNull)
        assertTrue(cols[0].autoIncrement.not())
    }

    @Test
    fun existingTableStillRejectsNotNullWithoutDefault() {
        // 但重建已有表时必须拦住：已有行无法回填
        val msg = invalid(listOf(row("req", "TEXT", notNull = true, source = null)))
        assertTrue(msg.contains("NOT NULL"))
    }

    @Test
    fun invalidMessageIsUserFacing() {
        // 报错信息要能直接展示，不能是内部术语或英文异常
        val messages = listOf(
            invalid(emptyList()),
            invalid(listOf(row(""))),
            invalid(listOf(row("a"), row("a"))),
            invalid(listOf(row("x", notNull = true, source = null)))
        )
        messages.forEach { m ->
            assertTrue("提示不应为空", m.isNotBlank())
            assertTrue("提示应以中文说明为主：$m", m.any { it.code in 0x4E00..0x9FFF })
        }
    }
}
