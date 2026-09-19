package com.example.dbeditor

/**
 * 系统栏 inset 的计算规则（纯逻辑，便于单测）。
 *
 * 界面结构是「Toolbar + 内容」，所以我们希望：
 *  - 顶栏的那块颜色延伸到状态栏下面，而不是被状态栏盖住；
 *  - 标题仍在可见区域（状态栏下方）垂直居中。
 *
 * 因此不去给整个根布局加顶部内边距（那样状态栏区域会露出窗口底色，
 * 顶栏颜色会断开），而是把状态栏高度加进 Toolbar 自身的高度和内边距，
 * 让它变成一整块更高的颜色区。
 */
object InsetMath {

    /** 需要施加到视图上的量 */
    data class Plan(
        /** 根布局顶部内边距。只有在没有 Toolbar 时才需要（此时内容直接顶到状态栏） */
        val rootPaddingTop: Int,
        /** 根布局底部内边距（导航栏与输入法的较大者，避免键盘遮住底部按钮） */
        val rootPaddingBottom: Int,
        /** Toolbar 需要增加的高度 */
        val toolbarExtraHeight: Int,
        /** Toolbar 需要增加的顶部内边距 */
        val toolbarExtraPaddingTop: Int
    )

    fun plan(
        systemBarTop: Int,
        systemBarBottom: Int,
        imeBottom: Int,
        hasToolbar: Boolean
    ): Plan {
        val top = systemBarTop.coerceAtLeast(0)
        val bottom = systemBarBottom.coerceAtLeast(0)
        val ime = imeBottom.coerceAtLeast(0)
        return Plan(
            rootPaddingTop = if (hasToolbar) 0 else top,
            // 取较大者而不是相加：键盘弹出时导航栏会被键盘盖住，两个 inset 会同时上报
            rootPaddingBottom = maxOf(bottom, ime),
            toolbarExtraHeight = if (hasToolbar) top else 0,
            toolbarExtraPaddingTop = if (hasToolbar) top else 0
        )
    }
}
