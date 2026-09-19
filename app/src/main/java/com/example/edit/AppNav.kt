package com.example.edit

import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 侧边栏里的一项工具。
 *
 * 新增工具时只要往 [AppNav.items] 里加一个实例，
 * 侧边栏会自动生成对应的条目并处理选中态，不需要改布局。
 */
data class NavItem(
    val id: String,
    val titleRes: Int,
    val iconRes: Int,
    /** 该工具是否已经可用；未实现的先展示为「即将推出」并不可点 */
    val available: Boolean = true
)

/**
 * 侧边导航栏。
 *
 * 目前只有「Edit」一项，后续工具在 [items] 里追加即可。
 */
object AppNav {

    const val TOOL_DB_EDITOR = "db_editor"

    /** 侧边栏工具清单（顺序即展示顺序） */
    val items: List<NavItem> = listOf(
        NavItem(
            id = TOOL_DB_EDITOR,
            titleRes = R.string.nav_db_editor,
            iconRes = R.drawable.ic_database
        )
        // 以后追加，例如：
        // NavItem("log_viewer", R.string.nav_log_viewer, R.drawable.ic_clock),
    )

    /**
     * 把工具列表渲染进侧边栏容器。
     *
     * @param selectedId 当前选中的工具 id，用于高亮
     * @param items 要渲染的工具（默认 [AppNav.items]，测试时可传入自定义列表）
     */
    fun render(
        container: LinearLayout,
        selectedId: String,
        items: List<NavItem> = AppNav.items,
        onSelect: (NavItem) -> Unit
    ) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(container.context)
        items.forEachIndexed { index, item ->
            val row = inflater.inflate(R.layout.item_nav, container, false)
            bindRow(row, item, item.id == selectedId)

            if (item.available) {
                row.setOnClickListener { onSelect(item) }
            } else {
                row.isEnabled = false
                row.alpha = 0.5f
            }

            container.addView(row)
            if (index != items.lastIndex) {
                container.addView(buildDivider(container))
            }
        }
    }

    /** 条目之间的细分隔线，左右与文字对齐内缩 16dp */
    private fun buildDivider(container: LinearLayout): View {
        val dp = container.resources.displayMetrics.density
        val divider = View(container.context)
        divider.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).apply {
            marginStart = (16 * dp).toInt()
            marginEnd = (16 * dp).toInt()
        }
        divider.setBackgroundResource(R.color.subtle_border)
        return divider
    }

    private fun bindRow(row: View, item: NavItem, selected: Boolean) {
        val icon = row.findViewById<ImageView>(R.id.ivNavIcon)
        val title = row.findViewById<TextView>(R.id.tvNavTitle)

        title.setText(item.titleRes)
        icon.setImageResource(item.iconRes)

        if (selected) {
            // 选中态：主色文字 + 浅蓝底，图标也着主色
            title.setTextColor(row.context.getColorCompat(R.color.primary))
            title.setTypeface(null, android.graphics.Typeface.BOLD)
            row.setBackgroundResource(R.drawable.bg_chip_primary)
            icon.setColorFilter(row.context.getColorCompat(R.color.primary))
        } else {
            title.setTextColor(row.context.getColorCompat(R.color.on_background))
            title.setTypeface(null, android.graphics.Typeface.NORMAL)
            row.setBackgroundResource(R.drawable.bg_row)
            icon.clearColorFilter()
        }
    }
}

internal fun android.content.Context.getColorCompat(id: Int): Int =
    androidx.core.content.ContextCompat.getColor(this, id)
