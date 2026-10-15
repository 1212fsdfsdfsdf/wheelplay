package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.robolectric.Shadows.shadowOf

internal object SettingsNavigation {
    fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    fun open(activity: DiPlayActivity, category: String) {
        views(activity.window.decorView).filterIsInstance<BottomNavigationView>().single().selectedItemId = 3
        views(activity.window.decorView).filterIsInstance<Button>()
            .singleOrNull { it.text.toString() == "返回设置" }?.performClick()
        views(activity.window.decorView).single {
            it.contentDescription?.toString()?.startsWith("$category，") == true
        }.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }
}
