package com.example.edit

import com.example.edit.SqlUtil.DdlColumn

/**
 * 列编辑表单的纯逻辑：把界面上收集到的原始输入整理成可执行的列定义。
 *
 * 为什么要单独抽出来：这里集中了主键编号、AUTOINCREMENT 校正、NOT NULL 校验等规则，
 * 一旦出错的表现是「建表失败」或「数据搬进错列」，但原先把它们写在 Activity 里，
 * 无法单测。抽成纯函数后可以穷举验证。
 */
object SchemaFormLogic {

    /** 一行表单的原始输入（对应界面上的一行列定义） */
    data class RowInput(
        val name: String,
        val type: String,
        val notNull: Boolean = false,
        val defaultValue: String? = null,
        val isPk: Boolean = false,
        val autoIncrement: Boolean = false,
        /** 数据来源列名；null 表示新列（没有数据来源） */
        val source: String? = null
    )

    sealed class Result {
        /** 校验通过。notes 是非致命提醒，应展示给用户 */
        data class Ok(val columns: List<DdlColumn>, val notes: List<String> = emptyList()) : Result()

        /** 校验失败，message 可直接展示 */
        data class Invalid(val message: String) : Result()
    }

    fun build(rows: List<RowInput>, isNewTable: Boolean = false): Result {
        if (rows.isEmpty()) return Result.Invalid("至少需要一列")

        // 1. 列名非空且不重复
        val seen = mutableSetOf<String>()
        for (r in rows) {
            val name = r.name.trim()
            if (name.isEmpty()) return Result.Invalid("列名不能为空")
            if (!seen.add(name)) return Result.Invalid("列名重复：$name")
        }

        // 2. 主键按界面顺序连续编号（原来用 rows.indexOf(row) + 1，重复项会算出同一个号）
        var pkPos = 0
        val numbered = rows.map { r ->
            DdlColumn(
                name = r.name.trim(),
                type = r.type.trim(),
                notNull = r.notNull,
                defaultValue = r.defaultValue?.trim()?.ifEmpty { null },
                pkPosition = if (r.isPk) ++pkPos else 0,
                autoIncrement = r.autoIncrement,
                originalName = r.source?.trim()?.ifEmpty { null }
            )
        }

        // 3. AUTOINCREMENT 只对「唯一一个 INTEGER 主键」成立，否则静默失效（SQLite 会忽略）
        val pks = numbered.filter { it.isPk }
        val autoIncAllowed = pks.size == 1 && pks[0].isIntegerPk && pks[0].autoIncrement
        val askedAutoInc = numbered.any { it.autoIncrement }
        val notes = mutableListOf<String>()
        if (askedAutoInc && !autoIncAllowed) {
            notes.add("已取消自增：AUTOINCREMENT 只能用于单个 INTEGER 主键")
        }
        val fixed = numbered.map { it.copy(autoIncrement = autoIncAllowed && it.isPk) }

        // 4. 新列若 NOT NULL 又没有默认值，已有行搬数据时必然失败。
        //    但这条只适用于「重建已有表」：新建表时根本没有已有行，
        //    `name TEXT NOT NULL` 是完全合法的建表语句，不能拦。
        if (!isNewTable) {
            fixed.firstOrNull { it.originalName == null && it.notNull && it.defaultValue == null }
                ?.let {
                    return Result.Invalid(
                        "新列「${it.name}」设了 NOT NULL 又没有默认值，已有行会插入失败。" +
                                "请允许为空，或给它一个默认值。"
                    )
                }
        }

        // 5. 没有主键属于策略提醒，由界面层用确认弹窗处理（不阻断）
        return Result.Ok(fixed, notes)
    }
}
