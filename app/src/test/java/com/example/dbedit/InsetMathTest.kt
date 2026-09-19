package com.example.dbedit

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 系统栏 inset 计算的测试。
 *
 * 为什么值得测：修的是「顶栏被状态栏盖住」，而这套计算里有两个很容易写错的地方——
 * 没有 Toolbar 时忘了给根布局补顶部留白，以及键盘弹出时把导航栏高度和输入法高度相加
 * （它们会同时上报，相加会导致底部多出一大块空白）。
 */
class InsetMathTest {

    // 典型数值：状态栏 24dp(=72px @3x)、导航栏 48dp(=144px)、键盘 800px
    private val statusBar = 72
    private val navBar = 144
    private val ime = 800

    @Test
    fun withToolbar_rootTopPaddingIsZeroAndToolbarGrows() {
        val p = InsetMath.plan(statusBar, navBar, 0, hasToolbar = true)
        // 顶栏自己承担状态栏高度，根布局不再留白，否则颜色会断开
        assertEquals(0, p.rootPaddingTop)
        assertEquals(statusBar, p.toolbarExtraHeight)
        assertEquals(statusBar, p.toolbarExtraPaddingTop)
        // 底部按导航栏留白
        assertEquals(navBar, p.rootPaddingBottom)
    }

    @Test
    fun withoutToolbar_rootGetsTopPadding() {
        val p = InsetMath.plan(statusBar, navBar, 0, hasToolbar = false)
        assertEquals(statusBar, p.rootPaddingTop)
        assertEquals(0, p.toolbarExtraHeight)
        assertEquals(0, p.toolbarExtraPaddingTop)
    }

    @Test
    fun imeUsesMaxNotSum() {
        // 键盘弹出时导航栏与输入法 inset 会同时上报，必须取较大者
        val p = InsetMath.plan(statusBar, navBar, ime, hasToolbar = true)
        assertEquals(ime, p.rootPaddingBottom)
    }

    @Test
    fun imeSmallerThanNavBarKeepsNavBar() {
        val p = InsetMath.plan(statusBar, 200, 50, hasToolbar = true)
        assertEquals(200, p.rootPaddingBottom)
    }

    @Test
    fun zeroInsetsProduceNoChanges() {
        val p = InsetMath.plan(0, 0, 0, hasToolbar = true)
        assertEquals(0, p.rootPaddingTop)
        assertEquals(0, p.rootPaddingBottom)
        assertEquals(0, p.toolbarExtraHeight)
        assertEquals(0, p.toolbarExtraPaddingTop)
    }

    @Test
    fun negativeInsetsAreClampedToZero() {
        // 某些设备/折叠屏可能上报负值，不能让它把视图往外撑
        val p = InsetMath.plan(-10, -20, -30, hasToolbar = true)
        assertEquals(0, p.rootPaddingTop)
        assertEquals(0, p.rootPaddingBottom)
        assertEquals(0, p.toolbarExtraHeight)
        assertEquals(0, p.toolbarExtraPaddingTop)
    }

    @Test
    fun toolbarHeightAlwaysGrowsByStatusBarHeight() {
        // 顶栏够高，标题才不会和状态栏撞在一起
        val heights = listOf(24, 48, 100, 200)
        heights.forEach { top ->
            val p = InsetMath.plan(top, 0, 0, hasToolbar = true)
            assertEquals(top, p.toolbarExtraHeight)
        }
    }

    @Test
    fun noToolbarStillReservesForIme() {
        val p = InsetMath.plan(statusBar, navBar, ime, hasToolbar = false)
        assertEquals(ime, p.rootPaddingBottom)
        assertEquals(statusBar, p.rootPaddingTop)
    }
}
