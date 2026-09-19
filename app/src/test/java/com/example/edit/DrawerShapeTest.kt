package com.example.edit

import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 侧栏几何：圆角定义与状态栏留白。
 *
 * 为什么不去栅格化后数像素：Robolectric 没有真实图形后端，drawable 画不到位图上
 * （实测直接调 GradientDrawable.draw(Canvas) 中心像素仍是画布底色）。
 * 所以这里改为解析 drawable 的 XML 定义，验证圆角半径本身；布局位置则用真实测量结果断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class DrawerShapeTest {

    /** 读取 shape drawable 的 corners 半径，键为 topLeft/topRight/bottomLeft/bottomRight */
    private fun cornerRadii(activity: android.app.Activity, drawableRes: Int): Map<String, Float> {
        val xml = activity.resources.getXml(drawableRes)
        val ns = "http://schemas.android.com/apk/res/android"
        val out = mutableMapOf<String, Float>()
        var event = xml.next()
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG && xml.name == "corners") {
                listOf("topLeftRadius", "topRightRadius", "bottomLeftRadius", "bottomRightRadius")
                    .forEach { attr ->
                        val raw = xml.getAttributeValue(ns, attr)
                        out[attr] = raw?.let { Regex("[-0-9.]+").find(it)?.value?.toFloatOrNull() } ?: 0f
                    }
            }
            event = xml.next()
        }
        return out
    }

    private fun density(activity: android.app.Activity) = activity.resources.displayMetrics.density

    @Test
    fun drawerPanelHasLargeRoundedCornersOnRightSideOnly() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val radii = cornerRadii(activity, R.drawable.bg_drawer)

        // 注意：AAPT 返回的是 dp 数值（不是 px），所以直接和 16dp 比较
        assertEquals("共解析到 4 个圆角", 4, radii.size)
        assertTrue(
            "右上角应有大圆角（>=16dp），实际 ${radii["topRightRadius"]}dp",
            radii["topRightRadius"]!! >= 16f
        )
        assertTrue(
            "右下角应有大圆角（>=16dp），实际 ${radii["bottomRightRadius"]}dp",
            radii["bottomRightRadius"]!! >= 16f
        )
        assertEquals("左上角与屏幕边缘齐平，应为直角", 0f, radii["topLeftRadius"]!!, 0.01f)
        assertEquals("左下角与屏幕边缘齐平，应为直角", 0f, radii["bottomLeftRadius"]!!, 0.01f)
    }

    @Test
    fun panelUsesDrawerBackground() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val panel = activity.findViewById<ViewGroup>(R.id.navPanel)
        assertNotNull("侧栏面板应存在", panel)
        assertNotNull("侧栏面板应设置底板（含圆角）", panel.background)
    }

    @Test
    fun drawerHasNoTitleOrGroupHeader() {
        // 抽屉里只有一个工具，再写「Edit」「工具」是重复信息
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val panel = activity.findViewById<ViewGroup>(R.id.navPanel)

        val texts = mutableListOf<String>()
        fun collect(v: View) {
            if (v is android.widget.TextView) texts += v.text.toString()
            if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i))
        }
        collect(panel)

        // 顶部标题是重复信息，已删除；「工具」分组小字一并去掉。
        // 注意工具项本身仍叫「Edit」，它是要保留的菜单文字——所以这里要求
        // 恰好只剩 1 处，而不是 0 处。
        // 顶部标题的 id 已从布局里删除：这里不能再 findViewById(R.id.tvDrawerTitle)，
        // 否则连编译都过不去——这本身就是「标题已移除」的编译期保证。
        assertEquals("侧栏里「Edit」应只作为工具项出现一次", 1, texts.count { it == "Edit" })
        assertTrue("分组小字「工具」应已移除", texts.none { it == "工具" })
        assertTrue("侧栏仍应有工具项文字", texts.any { it.isNotBlank() })
    }

    @Test
    fun toolListIsFirstContentInDrawer() {
        // 去掉标题后，工具卡片应紧跟在状态栏占位之后，而不是被留出一大段空白
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val panel = activity.findViewById<ViewGroup>(R.id.navPanel)
        val spacerIndex = (0 until panel.childCount).indexOfFirst { it == 0 || panel.getChildAt(it).id == R.id.navTopSpacer }
        val containerIndex = (0 until panel.childCount).indexOfFirst { panel.getChildAt(it).id == R.id.navContainer }
        assertTrue("应找到状态栏占位与工具容器", spacerIndex >= 0 && containerIndex >= 0)
        assertEquals("工具卡片应紧跟在占位之后，中间不再插标题", spacerIndex + 1, containerIndex)
    }

    @Test
    fun panelIsWideByThreeHundredDp() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val panel = activity.findViewById<ViewGroup>(R.id.navPanel)
        assertEquals(
            "侧栏宽度应为 300dp",
            (300 * density(activity)).toInt(),
            panel.layoutParams.width
        )
    }

    @Test
    fun statusBarInsetPushesContentDown() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)

        ViewCompat.dispatchApplyWindowInsets(
            root,
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 72, 0, 144))
                .build()
        )
        activity.javaClass.getDeclaredMethod("applyDrawerInsets").apply {
            isAccessible = true
            invoke(activity)
        }

        val spacer = activity.findViewById<View>(R.id.navTopSpacer)
        // 与主页对齐：状态栏(72px) + actionBarSize(112px)
        assertEquals(
            "侧栏顶部占位应为「状态栏 + 顶栏基础高」，才与主页内容对齐",
            72 + 112,
            spacer.layoutParams.height
        )
    }

    @Test
    fun noStatusBarInsetMeansNoSpacer() {
        // 全屏/无状态栏设备不应白白留一条空白
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        EdgeToEdge.lastSystemBarTop = 0
        ViewCompat.dispatchApplyWindowInsets(
            root,
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 0, 0, 0))
                .build()
        )
        activity.javaClass.getDeclaredMethod("applyDrawerInsets").apply {
            isAccessible = true
            invoke(activity)
        }
        val spacer = activity.findViewById<View>(R.id.navTopSpacer)
        assertEquals(0, spacer.layoutParams.height)
    }
}
