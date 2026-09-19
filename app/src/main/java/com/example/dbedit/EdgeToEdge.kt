package com.example.dbedit

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 处理系统栏（状态栏 / 导航栏 / 输入法）对界面的遮挡。
 *
 * 背景：Android 15（API 35）起强制 edge-to-edge，窗口延伸到状态栏与导航栏之下，
 * 不处理 inset 的话顶栏会被状态栏盖住、底部按钮会被导航栏压住。
 */
object EdgeToEdge {

    /**
     * 让窗口进入 edge-to-edge。**必须在 super.onCreate 之前调用**：
     * decor 一旦生成，再改这个开关就不会影响布局了。
     *
     * @return 是否已启用 edge-to-edge。返回 false 表示沿用系统默认排布，
     *         此时 [applyInsetsTo] 不应再做任何补偿，否则会重复留白。
     */
    fun setup(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // API 30 以下：setDecorFitsSystemWindows 会去访问 decorView，
            // 从而在 AppCompat 建立自己的 subdecor 之前就把 decor 装好，导致
            // requestWindowFeature 之类抛异常。这些版本系统默认就是「内容不延伸到
            // 系统栏下」，本来就没有遮挡问题，直接沿用默认即可。
            return false
        }
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)

        // 顶栏是浅灰底 + 深色文字（对齐 AiCode 的扁平 TopAppBar），
        // 所以状态栏图标必须用深色，否则白底上看不见。
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // 让页面底色透到状态栏区域。API 35 起该属性已废弃（状态栏本就透明）
            activity.window.statusBarColor = android.graphics.Color.TRANSPARENT
        }
        return true
    }

    /**
     * 顶栏右侧留白（dp）。
     *
     * Toolbar 默认把菜单贴在右边缘（右边距为 0），⋮ 会紧挨屏幕边，视觉上太挤。
     * 这里统一给 Toolbar 加 paddingEnd：实测 ActionMenuView 会随之整体左移
     * （加 8dp → 右边缘从 822px 移到 806px），且对展开/收起的菜单项都生效。
     * 注意不能给 ActionMenuView 自己加 padding——那样只会把它撑宽，图标不动。
     */
    private const val TOOLBAR_END_PADDING_DP = 8

    /**
     * 把 inset 应用到视图树上。必须在 setContentView 之后调用（需要视图树已存在）。
     *
     * 用「基础值 + 增量」的方式反复计算：inset 可能被多次分发，
     * 不能在已经加过值的属性上继续累加。
     */
    fun applyInsetsTo(activity: Activity, root: View) {
        val toolbar = activity.findViewById<View>(R.id.toolbar)

        val baseRootPaddingTop = root.paddingTop
        val baseRootPaddingBottom = root.paddingBottom
        val baseToolbarHeight = toolbar?.layoutParams?.height ?: 0
        val baseToolbarPaddingTop = toolbar?.paddingTop ?: 0
        // 顶栏右侧统一留白（dp 在运行时换算，避免资源限定符差异）
        val toolbarEndPaddingPx =
            (TOOLBAR_END_PADDING_DP * activity.resources.displayMetrics.density).toInt()
        val baseToolbarPaddingEnd = (toolbar?.paddingEnd ?: 0) + toolbarEndPaddingPx

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val hasToolbar = toolbar != null && baseToolbarHeight > 0

            val plan = InsetMath.plan(bars.top, bars.bottom, ime.bottom, hasToolbar)

            v.setPadding(
                bars.left,
                baseRootPaddingTop + plan.rootPaddingTop,
                bars.right,
                baseRootPaddingBottom + plan.rootPaddingBottom
            )

            if (hasToolbar) {
                toolbar!!.layoutParams = toolbar.layoutParams.apply {
                    height = baseToolbarHeight + plan.toolbarExtraHeight
                }
                toolbar.setPadding(
                    toolbar.paddingLeft,
                    baseToolbarPaddingTop + plan.toolbarExtraPaddingTop,
                    baseToolbarPaddingEnd,
                    toolbar.paddingBottom
                )
            }
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
    }

    /** 从 content 容器取根视图后应用 inset（供 setContentView(int) 使用） */
    fun applyInsetsFromContent(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.childCount == 0) return
        applyInsetsTo(activity, content.getChildAt(0))
    }
}
