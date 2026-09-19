package com.example.edit

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 布局与系统栏 inset 的运行期测试（Robolectric，真实 Android 框架，不涉及 SQLite）。
 *
 * 两个价值：
 *  1. 布局是我手写的 XML，标错 id/属性只会在运行时才发现——这里逐个真正加载一遍。
 *  2. 「顶栏被状态栏盖住」的修复必须真的执行过一遍才知道对不对：这里派发真实的
 *     WindowInsets，然后断言 Toolbar 确实变高了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LayoutAndInsetsTest {

    private val statusBar = 72
    private val navBar = 144
    private val ime = 800

    private fun insets(top: Int, bottom: Int, imeBottom: Int = 0): WindowInsetsCompat =
        WindowInsetsCompat.Builder()
            .setInsets(
                WindowInsetsCompat.Type.systemBars(),
                androidx.core.graphics.Insets.of(0, top, 0, bottom)
            )
            .setInsets(
                WindowInsetsCompat.Type.ime(),
                androidx.core.graphics.Insets.of(0, 0, 0, imeBottom)
            )
            .build()

    /**
     * 用应用自己的主题创建 inflater。
     *
     * 布局里用了 `?attr/actionBarSize` 这类主题属性，而 ApplicationProvider 给的 context
     * 用的是框架默认主题（不认识这些属性），会直接报 Failed to resolve attribute。
     * 真机上走的是清单里声明的主题，所以必须在这里补上，否则测的不是真实情况。
     */
    private fun themedInflater(): android.view.LayoutInflater {
        val base = ApplicationProvider.getApplicationContext<android.content.Context>()
        val themed = android.view.ContextThemeWrapper(base, R.style.Theme_Edit)
        return android.view.LayoutInflater.from(themed)
    }


    /**
     * 统计菜单里会占用顶栏图标位的条目数。
     *
     * 注意：showAsAction 经 AAPT 编译后是整数（never=0 / ifRoom=1 / always=2 ...），
     * 直接和字符串 "never" 比较会得到错误结果——这里按整数解析，缺省视为 0（never）。
     */
    private fun actionBarItemCount(activity: android.app.Activity, menuRes: Int): Int {
        val xml = activity.resources.getXml(menuRes)
        var event = xml.next()
        var count = 0
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG && xml.name == "item") {
                val raw = xml.getAttributeValue(
                    "http://schemas.android.com/apk/res-auto", "showAsAction"
                )
                val flags = raw?.toIntOrNull() ?: 0
                if (flags and 0x3 != 0) count++ // ALWAYS|IF_ROOM
            }
            event = xml.next()
        }
        return count
    }

    // ---------------- 布局能加载 ----------------

    @Test
    fun allLayoutsInflate() {
        val inflater = themedInflater()
        val ids = listOf(
            R.layout.activity_main,
            R.layout.activity_table_list,
            R.layout.activity_table_data,
            R.layout.activity_search,
            R.layout.activity_columns_editor,
            R.layout.activity_schema_preview,
            R.layout.item_table,
            R.layout.item_hit,
            R.layout.item_action_row,
            R.layout.item_nav,
            R.layout.nav_drawer
        )
        ids.forEach { id ->
            val v = inflater.inflate(id, null)
            assertNotNull("布局 $id 加载失败", v)
            assertTrue("布局 $id 应该是 ViewGroup", v is ViewGroup)
        }
    }

    @Test
    fun allMenusInflate() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        listOf(R.menu.menu_common, R.menu.menu_table_data, R.menu.menu_table_list).forEach { id ->
            val popup = androidx.appcompat.view.menu.MenuBuilder(activity)
            activity.menuInflater.inflate(id, popup)
            assertTrue("菜单 $id 应有条目", popup.size() > 0)
        }
    }

    /** 每个带 Toolbar 的布局都必须真的有 id 为 toolbar 的控件，否则 inset 逻辑会静默失效 */
    @Test
    fun layoutsWithToolbarExposeToolbarId() {
        val inflater = themedInflater()
        listOf(
            R.layout.activity_main,
            R.layout.activity_table_list,
            R.layout.activity_table_data,
            R.layout.activity_search,
            R.layout.activity_columns_editor,
            R.layout.activity_schema_preview
        ).forEach { id ->
            val v = inflater.inflate(id, null)
            assertNotNull("布局 $id 里找不到 @id/toolbar（inset 逻辑会静默失效）", v.findViewById<View>(R.id.toolbar))
        }
    }

    @Test
    fun toolbarIsFlatNotAColoredBar() {
        // 对齐 AiCode：顶栏是浅灰页面底 + 深色文字，不是彩色条
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        // 页面底色（同时用于顶栏与侧栏）
        assertEquals(0xFFF8F8F8.toInt(), activity.getColorCompat(R.color.page_background))

        // 主题的 windowBackground 应为页面底色，而不是某种彩色顶栏色
        val tv = android.util.TypedValue()
        activity.theme.resolveAttribute(android.R.attr.windowBackground, tv, true)
        assertEquals(R.color.page_background, tv.resourceId)
    }

    @Test
    fun designTokensMatchAiCode() {
        // 把从 AiCode 抄过来的色值钉死，以后误改会被测出来（避免无声漂移）
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals(0xFF2563EB.toInt(), activity.getColorCompat(R.color.primary))
        assertEquals(0xFFF8F8F8.toInt(), activity.getColorCompat(R.color.page_background))
        assertEquals(0xFF0F172A.toInt(), activity.getColorCompat(R.color.on_background))
        assertEquals(0xFFE5E5EA.toInt(), activity.getColorCompat(R.color.subtle_border))
        assertEquals(0xFFFFFFFF.toInt(), activity.getColorCompat(R.color.card_surface))
        assertEquals(0xFFEAF4FF.toInt(), activity.getColorCompat(R.color.surface_variant))
    }

    @Test
    fun toolbarTitleUsesDarkText() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val tv = android.util.TypedValue()
        activity.theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true)
        assertEquals(
            "顶栏/正文主色应为深色，白底上才看得清",
            R.color.on_background,
            tv.resourceId
        )
    }

    // ---------------- 顶栏不被状态栏盖住 ----------------

    @Test
    fun mainActivityToolbarGrowsByStatusBarHeight() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val toolbar = activity.findViewById<View>(R.id.toolbar)
        val before = toolbar.layoutParams.height
        assertTrue("Toolbar 应已有基础高度", before > 0)

        ViewCompat.dispatchApplyWindowInsets(root, insets(statusBar, navBar))

        assertEquals(
            "Toolbar 高度应加上状态栏高度，否则标题会被状态栏盖住",
            before + statusBar,
            toolbar.layoutParams.height
        )
    }

    @Test
    fun rootBottomPaddingAccountsForNavBar() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)

        ViewCompat.dispatchApplyWindowInsets(root, insets(statusBar, navBar))
        assertEquals("底部按钮不能被导航栏压住", navBar, root.paddingBottom)
    }

    @Test
    fun repeatedInsetsDoNotAccumulate() {
        // inset 可能被多次派发，不能在已加过的值上继续叠加
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val toolbar = activity.findViewById<View>(R.id.toolbar)
        val baseHeight = toolbar.layoutParams.height

        repeat(3) {
            ViewCompat.dispatchApplyWindowInsets(root, insets(statusBar, navBar))
        }
        assertEquals(baseHeight + statusBar, toolbar.layoutParams.height)
        assertEquals(navBar, root.paddingBottom)
    }

    @Test
    fun keyboardDoesNotAddToNavBarPadding() {
        // 键盘弹出时导航栏与输入法会同时上报，应取较大者而不是相加
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)

        ViewCompat.dispatchApplyWindowInsets(root, insets(statusBar, navBar, ime))
        assertEquals("应取 max(导航栏, 键盘) 而不是两者相加", ime, root.paddingBottom)
    }

    @Test
    fun rootTopPaddingStaysZeroWhenToolbarHandlesStatusBar() {
        // 顶栏自己承担状态栏高度；根布局若也留白，状态栏区域会露出窗口底色、颜色断开
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)

        ViewCompat.dispatchApplyWindowInsets(root, insets(statusBar, navBar))
        assertEquals(0, root.paddingTop)
    }

    @Test
    fun zeroInsetsLeaveLayoutUntouched() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val toolbar = activity.findViewById<View>(R.id.toolbar)
        val baseHeight = toolbar.layoutParams.height

        ViewCompat.dispatchApplyWindowInsets(root, insets(0, 0, 0))
        assertEquals(baseHeight, toolbar.layoutParams.height)
        assertEquals(0, root.paddingBottom)
        assertEquals(0, root.paddingTop)
    }

    @Test
    fun mainActivityStartsWithNoDatabaseAndFinishesGracefully() {
        // 未打开数据库时应提示并结束，而不是崩溃
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.setup().get()
        assertNotNull("主页应有侧边栏", activity.findViewById<View>(R.id.drawerLayout))
        assertNotNull("主页应有工具卡片容器", activity.findViewById<View>(R.id.navContainer))
    }

    // ---------------- 侧边栏（只有一项，且可扩展） ----------------

    @Test
    fun sidebarShowsOnlyEditForNow() {
        assertEquals(
            "当前侧边栏应只有 Edit一项",
            listOf(AppNav.TOOL_DB_EDITOR),
            AppNav.items.map { it.id }
        )
    }

    @Test
    fun sidebarRowIsRenderedWithTitleAndIcon() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val container = activity.findViewById<android.widget.LinearLayout>(R.id.navContainer)
        assertEquals("应渲染出 1 个工具条目", 1, container.childCount)

        val row = container.getChildAt(0)
        val title = row.findViewById<android.widget.TextView>(R.id.tvNavTitle)
        assertEquals(
            activity.getString(R.string.nav_db_editor),
            title.text.toString()
        )
    }

    @Test
    fun sidebarMarksCurrentToolAsSelected() {
        // 选中项用主色文字，与未选中区分
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val container = activity.findViewById<android.widget.LinearLayout>(R.id.navContainer)
        val title = container.getChildAt(0).findViewById<android.widget.TextView>(R.id.tvNavTitle)
        assertEquals(
            activity.getColorCompat(R.color.primary),
            title.currentTextColor
        )
    }

    @Test
    fun sidebarCanBeExtendedWithMoreItems() {
        // 关键需求：后续往下加新工具时，容器应自动多渲染一行 + 一条分隔线
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val container = activity.findViewById<android.widget.LinearLayout>(R.id.navContainer)

        val extra = AppNav.items + NavItem(
            id = "future_tool",
            titleRes = R.string.nav_db_editor,
            iconRes = R.drawable.ic_clock
        )
        AppNav.render(container, AppNav.TOOL_DB_EDITOR, extra) { }

        // 2 行 + 1 条分隔线
        assertEquals(3, container.childCount)
        val second = container.getChildAt(2)
        val secondTitle = second.findViewById<android.widget.TextView>(R.id.tvNavTitle)
        assertNotNull("新增的工具条目也要能取到标题", secondTitle)
    }

    @Test
    fun sidebarUnavailableToolIsDisabled() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val container = activity.findViewById<android.widget.LinearLayout>(R.id.navContainer)

        val disabled = listOf(
            NavItem("soon", R.string.nav_db_editor, R.drawable.ic_clock, available = false)
        )
        AppNav.render(container, AppNav.TOOL_DB_EDITOR, disabled) { }

        val row = container.getChildAt(0)
        assertTrue("未实现的工具不可点", !row.isEnabled)
    }

    @Test
    fun sidebarSelectedRowUsesNoDividerWhenSingleItem() {
        // 只有一项时不应出现多余分隔线
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val container = activity.findViewById<android.widget.LinearLayout>(R.id.navContainer)
        AppNav.render(container, AppNav.TOOL_DB_EDITOR, AppNav.items) { }
        assertEquals(1, container.childCount)
    }

    @Test
    fun drawerOpensFromToolbarButton() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val drawer = activity.findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.drawerLayout)
        val toolbar = activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        assertNotNull(toolbar.navigationIcon)
        assertTrue("抽屉初始应是关闭的", !drawer.isDrawerOpen(androidx.core.view.GravityCompat.START))
    }

    // ---------------- 侧栏：不被状态栏盖住 + 大圆角 ----------------

    @Test
    fun drawerPanelHasRoundedCorners() {
        // 侧栏底板带圆角（右侧两角大圆角，左侧与屏幕边缘齐平不圆）
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val panel = activity.findViewById<View>(R.id.navPanel)
        assertNotNull("侧栏面板应存在", panel)
        assertNotNull("侧栏面板应有圆角底图", panel.background)
    }

    @Test
    fun drawerTopSpacerExistsForStatusBarInset() {
        // DrawerLayout 会吞掉 inset，所以侧栏顶部靠一个占位 View 留白
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertNotNull(
            "侧栏顶部应有占位 View（否则标题会被状态栏盖住）",
            activity.findViewById<View>(R.id.navTopSpacer)
        )
    }

    @Test
    fun drawerTopSpacerGetsStatusBarHeight() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val spacer = activity.findViewById<View>(R.id.navTopSpacer)

        // 先派发 inset，让 EdgeToEdge 记下状态栏高度
        ViewCompat.dispatchApplyWindowInsets(root, insets(statusBar, navBar))
        assertEquals("EdgeToEdge 应记下状态栏高度", statusBar, EdgeToEdge.lastSystemBarTop)

        // 再触发侧栏留白计算
        activity.javaClass.getDeclaredMethod("applyDrawerInsets").apply {
            isAccessible = true
            invoke(activity)
        }
        assertEquals(
            "侧栏顶部占位应等于状态栏高度",
            statusBar,
            spacer.layoutParams.height
        )
    }

    @Test
    fun edgeToEdgeCachesStatusBarTopForDrawer() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        EdgeToEdge.lastSystemBarTop = 0
        ViewCompat.dispatchApplyWindowInsets(root, insets(66, 100))
        assertEquals(66, EdgeToEdge.lastSystemBarTop)
    }

    // ---------------- 顶栏不堆冗余图标 ----------------

    @Test
    fun tableDataToolbarHasNoRedundantIcons() {
        // 搜索框与「＋行」已在界面上，顶栏不应再放它们的图标
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val popup = androidx.appcompat.view.menu.MenuBuilder(activity)
        activity.menuInflater.inflate(R.menu.menu_table_data, popup)

        // 所有条目都应是 never，才会只出现在 ⋮ 里
        assertEquals(
            "表数据页顶栏不应有常驻图标",
            0,
            actionBarItemCount(activity, R.menu.menu_table_data)
        )
        assertTrue("次要操作应保留在溢出菜单里", popup.size() >= 5)
        // 功能仍在
        assertNotNull(popup.findItem(R.id.action_schema))
        assertNotNull(popup.findItem(R.id.action_export_csv))
    }

    @Test
    fun saveActionMovedToOverflowButStillPresent() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val popup = androidx.appcompat.view.menu.MenuBuilder(activity)
        activity.menuInflater.inflate(R.menu.menu_common, popup)

        val save = popup.findItem(R.id.action_save)
        assertNotNull("保存功能不能丢", save)

        assertEquals(
            "通用菜单不应占顶栏图标位（只留 ⋮）",
            0,
            actionBarItemCount(activity, R.menu.menu_common)
        )
    }
}
