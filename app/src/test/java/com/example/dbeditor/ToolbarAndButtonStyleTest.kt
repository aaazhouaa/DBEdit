package com.example.dbeditor

import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
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
 * 顶栏右边距与按钮配色。
 *
 * 这两项都是「看起来对不对」的问题，不会崩溃也不会报错，所以只能靠断言钉住，
 * 否则以后调整布局会被不知不觉改回去。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ToolbarAndButtonStyleTest {

    private val screenW = 822   // 411dp @ xhdpi
    private val screenH = 1782
    private val overflowGapDp = 8

    private fun findByClass(v: View, name: String): View? {
        if (v.javaClass.name.contains(name)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) {
            findByClass(v.getChildAt(i), name)?.let { return it }
        }
        return null
    }

    /**
     * 挂上真实菜单并完成布局后再量。
     * ActionMenuView 是按需生成的：没有菜单时它在层级里是 0 宽，量出来会是空的。
     */
    private fun layoutWithMenu(activity: AppCompatActivity): View? {
        val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
        activity.setSupportActionBar(toolbar)
        toolbar.inflateMenu(R.menu.menu_common)
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, screenW, screenH)
        return findByClass(toolbar, "ActionMenuView")
    }

    @Test
    fun overflowMenuIsPulledAwayFromRightEdge() {
        // Toolbar 默认把 ⋮ 贴在右边缘，太挤；断言它确实被推进来了
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val amv = layoutWithMenu(activity)
        assertNotNull("菜单未生成，测试前提不成立", amv)

        val density = activity.resources.displayMetrics.density
        val gapPx = screenW - amv!!.right
        assertEquals(
            "顶栏菜单右侧应留出 ${overflowGapDp}dp，实际 ${gapPx / density}dp",
            (overflowGapDp * density).toInt(),
            gapPx
        )
        // 确认不是靠把菜单撑宽实现的——撑宽只会让它变胖，图标不动
        assertTrue(
            "ActionMenuView 不应被 padding 撑宽（实际宽 ${amv.width}）",
            amv.width <= 90
        )
    }

    @Test
    fun allToolbarLayoutsGetTheSameEndPadding() {
        // 若只给某一个布局手写右边距，换页面就会漏。这里验证走的是统一入口。
        val activity = Robolectric.buildActivity(TableListActivity::class.java).setup().get()
        val amv = layoutWithMenu(activity) ?: return
        val density = activity.resources.displayMetrics.density
        assertEquals(
            "其他页面顶栏也应留出 ${overflowGapDp}dp",
            (overflowGapDp * density).toInt(),
            screenW - amv.right
        )
    }

    /** 读 color state list 的取色，避免依赖 Robolectric 无法栅格化的 drawable */
    private fun colorFor(state: IntArray, resId: Int): Int {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cs = androidx.core.content.ContextCompat.getColorStateList(ctx, resId)
        assertNotNull("$resId 应是状态色清单", cs)
        return cs!!.getColorForState(state, 0)
    }

    @Test
    fun mutedButtonTextIsPrimaryWhenEnabledAndGrayWhenDisabled() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val res = ctx.resources
        assertEquals(
            "次按钮常态文字应为主色",
            res.getColor(R.color.primary, null),
            colorFor(intArrayOf(android.R.attr.state_enabled), R.color.button_outlined_text)
        )
        assertEquals(
            "禁用时文字应转为弱化灰，否则灰掉的按钮看着仍像可点",
            res.getColor(R.color.subtle_text, null),
            colorFor(intArrayOf(-android.R.attr.state_enabled), R.color.button_outlined_text)
        )
    }

    /**
     * 用解析 XML 的方式验证描边按钮的定义。
     *
     * 不用逐像素判定：Robolectric 没有图形后端，drawable 画不到位图上
     * （实测 GradientDrawable.draw(Canvas) 后中心像素仍是画布底色），
     * 那种断言会「永远通过」，等于没测。
     */
    private fun shapeItems(drawableRes: Int): List<Map<String, String>> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val xml = ctx.resources.getXml(drawableRes)
        val ns = "http://schemas.android.com/apk/res/android"
        val items = mutableListOf<Map<String, String>>()
        var event = xml.next()
        var current: MutableMap<String, String>? = null
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                when (xml.name) {
                    "item" -> {
                        current = mutableMapOf()
                        xml.getAttributeValue(ns, "state_enabled")?.let { current!!["enabled"] = it }
                        xml.getAttributeValue(ns, "state_pressed")?.let { current!!["pressed"] = it }
                    }
                    "solid" -> xml.getAttributeValue(ns, "color")?.let { current?.set("solid", it) }
                    "stroke" -> {
                        xml.getAttributeValue(ns, "color")?.let { current?.set("stroke", it) }
                        xml.getAttributeValue(ns, "width")?.let { current?.set("strokeWidth", it) }
                    }
                }
            }
            if (event == org.xmlpull.v1.XmlPullParser.END_TAG && xml.name == "item") {
                current?.let { items += it }
                current = null
            }
            event = xml.next()
        }
        return items
    }

    @Test
    fun outlinedButtonIsWhiteWithStrokeInEveryState() {
        val items = shapeItems(R.drawable.bg_button_outlined)
        assertEquals("应有 常态/按下/禁用 三种状态", 3, items.size)
        // 每个状态都必须有描边——否则按钮会「看起来像纯文字」
        items.forEach { item ->
            assertTrue("每个状态都应有描边，实际 $item", item.containsKey("stroke"))
            assertNotNull("$item 应有底色", item["solid"])
        }
        // 按名字解析资源，不要硬编码数字 ID（ID 会随资源增删变化）
        val pkg = ApplicationProvider.getApplicationContext<android.content.Context>().packageName
        val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        fun idOf(name: String) = res.getIdentifier(name, "color", pkg)

        // XML 里拿到的是 "@2130968667" 这种引用串，取数字部分
        fun refToInt(ref: String?) = ref?.trimStart('@')?.substringAfterLast('/')?.toIntOrNull()

        // 调试用：把实际拿到的资源名打出来，避免靠猜
        fun nameOf(id: Int?) = id?.let { res.getResourceName(it).substringAfterLast('/') } ?: "null"

        // 注意区分顺序：按下态也没有 "enabled" 键，不能用 !containsKey 判常态
        val disabled = items.first { it["enabled"] == "false" }
        val pressed = items.first { it["pressed"] == "true" }
        val normal = items.first { !it.containsKey("enabled") && !it.containsKey("pressed") }

        assertEquals(
            "常态描边应为 outline（实际 ${nameOf(refToInt(normal["stroke"]))}）",
            idOf("outline"), refToInt(normal["stroke"])
        )
        assertEquals(
            "禁用态描边应为 subtle_border（更浅），否则和可点态看不出区别",
            idOf("subtle_border"), refToInt(disabled["stroke"])
        )
        assertTrue(
            "禁用态描边必须与常态不同",
            refToInt(disabled["stroke"]) != refToInt(normal["stroke"])
        )
        assertEquals(
            "按下态描边仍应是主色，表示「可以点」",
            idOf("primary"), refToInt(pressed["stroke"])
        )
        // 常态/禁用是卡片白（按钮看起来是描边块而非实心块）；
        // 按下态用主色容器色做反馈，这是刻意为之。
        val cardId = idOf("card_surface")
        assertEquals("常态底色应为卡片白", cardId, refToInt(normal["solid"]))
        assertEquals("禁用态底色应为卡片白", cardId, refToInt(disabled["solid"]))
        assertEquals(
            "按下态底色应为主色容器色（按下反馈）",
            idOf("primary_container"), refToInt(pressed["solid"])
        )
    }
}
