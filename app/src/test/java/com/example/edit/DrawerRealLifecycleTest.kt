package com.example.edit

import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 侧栏留白必须按**真实生命周期**验证。
 *
 * 之前那批测试是「先手动 dispatch inset，再反射调用 applyDrawerInsets」——这个顺序
 * 真机上不会出现，所以测试全绿但侧栏留白实际是 0（真机截图：第一行被状态栏压住）。
 * 这里刻意不做任何手动调用，只走 setup() + 派发 inset，模拟真实时序。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class DrawerRealLifecycleTest {

    private fun statusBar() = 72
    private fun actionBarSize(activity: MainActivity): Int {
        val tv = android.util.TypedValue()
        activity.theme.resolveAttribute(android.R.attr.actionBarSize, tv, true)
        return android.util.TypedValue.complexToDimensionPixelSize(tv.data, activity.resources.displayMetrics)
    }

    private fun dispatchInsets(activity: MainActivity, top: Int) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        ViewCompat.dispatchApplyWindowInsets(
            root,
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, top, 0, 144))
                .build()
        )
    }

    /** 只派发 inset，不手动调用任何内部方法——这就是真机上发生的事 */
    @Test
    fun spacerGetsStatusBarPlusActionBarWithoutManualCall() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        dispatchInsets(activity, statusBar())

        val spacer = activity.findViewById<View>(R.id.navTopSpacer)
        assertEquals(
            "侧栏顶部留白应为「状态栏 + 顶栏基础高」（真实时序，不手动调用内部方法）",
            statusBar() + actionBarSize(activity),
            spacer.layoutParams.height
        )
    }

    /** inset 迟到（先建界面、后派发）也必须补上，不能被一次性 latch 卡死 */
    @Test
    fun spacerUpdatesWhenInsetsArriveLate() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val spacer = activity.findViewById<View>(R.id.navTopSpacer)
        val before = spacer.layoutParams.height

        dispatchInsets(activity, 100)
        assertEquals(
            "inset 后到时也必须把留白补上（当前 latch 会导致永久为 $before）",
            100 + actionBarSize(activity),
            spacer.layoutParams.height
        )
    }

    /** 旋转/横屏导致状态栏高度变化时，留白要跟着变 */
    @Test
    fun spacerFollowsInsetsChange() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        dispatchInsets(activity, 72)
        dispatchInsets(activity, 0)   // 横屏无状态栏
        val spacer = activity.findViewById<View>(R.id.navTopSpacer)
        assertEquals(
            "状态栏消失后留白应只剩顶栏高度",
            actionBarSize(activity),
            spacer.layoutParams.height
        )
    }

    /**
     * API < 30 不启用 edge-to-edge（系统自己处理 inset，内容整体已在状态栏下方）。
     * 此时侧栏仍要与顶栏对齐，留白 = actionBarSize；不能是 0。
     */
    @Test
    @Config(sdk = [29])
    fun legacyApiStillAlignsDrawerWithToolbar() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val spacer = activity.findViewById<View>(R.id.navTopSpacer)
        assertEquals(
            "API<30 时留白应等于顶栏基础高（状态栏部分记 0），不能是 0",
            actionBarSize(activity),
            spacer.layoutParams.height
        )
    }
}
