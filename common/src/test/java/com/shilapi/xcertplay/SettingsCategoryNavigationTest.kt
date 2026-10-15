package com.shilapi.xcertplay

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w360dp-h800dp")
class SettingsCategoryNavigationTest {
    private fun views(activity: DiPlayActivity) = SettingsNavigation.views(activity.window.decorView)
    private fun back(activity: DiPlayActivity) = views(activity).filterIsInstance<Button>()
        .single { it.text.toString() == "返回设置" }.performClick()
    private fun hasRow(activity: DiPlayActivity, title: String) = views(activity).any {
        it.contentDescription?.toString()?.startsWith("$title，") == true
    }
    private fun tabs(activity: DiPlayActivity) = views(activity).filterIsInstance<BottomNavigationView>().single()
    private fun scroll(activity: DiPlayActivity) = views(activity).filterIsInstance<ScrollView>()
        .single { it.visibility == View.VISIBLE }
    private fun layout(activity: DiPlayActivity) {
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 360, 800)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test fun indexShowsCategoriesAndOnlySelectedCategoryContainsControls() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            SettingsNavigation.open(activity, "画面与串流")
            assertTrue(hasRow(activity, "CarPlay 昼夜模式"))
            assertFalse(hasRow(activity, "麦克风来源"))
            assertFalse(hasRow(activity, "HTTP 端口"))
            back(activity)
            assertFalse(hasRow(activity, "CarPlay 昼夜模式"))
            assertEquals(9, SettingsNavigation.views(scroll(activity)).count {
                it.contentDescription?.toString()?.contains("，") == true
            })
            SettingsNavigation.open(activity, "音频与麦克风")
            assertTrue(hasRow(activity, "音频输出"))
            assertTrue(hasRow(activity, "麦克风来源"))
            assertFalse(hasRow(activity, "画面安全区域"))
            activity.onBackPressedDispatcher.onBackPressed()
            assertTrue(hasRow(activity, "画面与串流"))
            assertEquals(3, tabs(activity).selectedItemId)
            activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(1, tabs(activity).selectedItemId)
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun returningRestoresIndexScrollAndEachCategoryKeepsItsOwnScroll() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            SettingsNavigation.open(activity, "画面与串流")
            back(activity); layout(activity)
            scroll(activity).scrollTo(0, 160)
            views(activity).single { it.contentDescription?.toString()?.startsWith("画面与串流，") == true }.performClick()
            layout(activity)
            assertEquals(0, scroll(activity).scrollY)
            scroll(activity).scrollTo(0, 220)
            back(activity); layout(activity)
            assertEquals(160, scroll(activity).scrollY)
            SettingsNavigation.open(activity, "音频与麦克风")
            layout(activity)
            assertEquals(0, scroll(activity).scrollY)
            SettingsNavigation.open(activity, "画面与串流")
            layout(activity)
            assertEquals(220, scroll(activity).scrollY)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun categoriesKeepExistingSettingsAndUpstreamProjectLinkAccessible() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            // First launch without authentication resources must still lead directly to import.
            assertTrue(views(activity).filterIsInstance<Button>().any { it.text == "从 DiPlay APK 导入" })
            for ((category, control) in listOf(
                "iPhone 连接" to "iPhone",
                "画面与串流" to "协商帧率",
                "音频与麦克风" to "麦克风来源",
                "车厂与定位" to "定位来源",
                "浏览器设备" to "连接过的设备",
                "Web 服务" to "HTTP 端口",
                "Tesla 兼容（实验）" to "连接方案",
                "CarPlay 认证资源" to "DiPlay 官网下载",
                "帮助与关于" to "源码地址"
            )) {
                SettingsNavigation.open(activity, category)
                assertTrue("Missing $control in $category", hasRow(activity, control))
            }
            back(activity)
            views(activity).single {
                it.contentDescription == "本项目基于 DiPlay 修改。点击打开 DiPlay GitHub 项目"
            }.performClick()
            assertEquals("https://github.com/shihabal3amri/DiPlay",
                shadowOf(activity).nextStartedActivity.dataString)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun secondaryPageSurvivesTabsRotationAndActivityRecreation() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        var activity = controller.get()
        try {
            SettingsNavigation.open(activity, "Web 服务")
            layout(activity); scroll(activity).scrollTo(0, 120)
            tabs(activity).selectedItemId = 2
            tabs(activity).selectedItemId = 3
            assertTrue(hasRow(activity, "HTTP 端口"))
            assertEquals(120, scroll(activity).scrollY)
            activity.onConfigurationChanged(Configuration(activity.resources.configuration))
            layout(activity)
            assertTrue(hasRow(activity, "HTTP 端口"))
            assertEquals(120, scroll(activity).scrollY)
            controller.recreate()
            activity = controller.get(); layout(activity)
            assertTrue(hasRow(activity, "HTTP 端口"))
            assertEquals(3, tabs(activity).selectedItemId)
            assertEquals(120, scroll(activity).scrollY)
            back(activity)
            assertTrue(hasRow(activity, "CarPlay 认证资源"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun renderIndexAndAudioCategory() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            SettingsNavigation.open(activity, "音频与麦克风")
            for (name in listOf("audio", "index")) {
                if (name == "index") back(activity)
                layout(activity)
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val bitmap = android.graphics.Bitmap.createBitmap(360, 800, android.graphics.Bitmap.Config.ARGB_8888)
                root.draw(android.graphics.Canvas(bitmap))
                val file = java.io.File("build/reports/material-ui/settings-categories/$name.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        } finally { controller.pause().stop().destroy() }
    }
}
