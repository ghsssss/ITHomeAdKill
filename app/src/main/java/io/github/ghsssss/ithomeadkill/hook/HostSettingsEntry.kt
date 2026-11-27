package io.github.ghsssss.ithomeadkill.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Uses only the verified host settings layout, independently of purification preferences. */
internal object HostSettingsEntry {
    @SuppressLint("DiscouragedApi")
    fun attach(activity: Activity, root: View) {
        if (!HostSettingsEntryContract.matches(activity.packageName, activity.javaClass.name) ||
            activity.isFinishing || activity.isDestroyed) return
        fun resource(name: String, type: String = "id") =
            activity.resources.getIdentifier(name, type, HostSettingsEntryContract.HOST_PACKAGE)

        val container = root.findViewById<LinearLayout>(resource("ll_settings_container")) ?: return
        val scroll = container.parent as? ViewGroup ?: return
        if (container.orientation != LinearLayout.VERTICAL || entryName(scroll) != "nsv_list" ||
            scroll.childCount != 1 || scroll.getChildAt(0) !== container ||
            (scroll.parent as? View)?.let(::entryName) != "rl_settings_main") return
        val profile = container.findViewById<ViewGroup>(resource("rl_user_profile")) ?: return
        if (profile.parent !== container) return
        val nativeTitle = profile.findViewWithTag<TextView>("subTitle") ?: return
        val nativeArrow = profile.findViewWithTag<ImageView>("arrow") ?: return
        val profileIndex = container.indexOfChild(profile)
        val nativeLine = container.getChildAt(profileIndex + 1)
        val existing = (0 until container.childCount).map(container::getChildAt)
            .firstOrNull { it.tag == HostSettingsEntryContract.MARKER }
        if (existing != null) {
            syncTheme(existing, nativeTitle, nativeArrow, nativeLine)
            return
        }
        val index = HostSettingsEntryContract.insertionIndex(profileIndex, entryPresent = false) ?: return
        // A changed host layout must not move or replace any native settings row.
        if (container.getChildAt(0)?.javaClass != View::class.java) return
        val layout = resource("list_item_account_setting_item", "layout")
        if (layout == 0) return
        val row = activity.layoutInflater.inflate(layout, container, false)
        val title = row.findViewById<TextView>(resource("tv_name")) ?: return
        val description = row.findViewById<TextView>(resource("tv_des")) ?: return
        val arrow = row.findViewById<ImageView>(resource("iv_arrow")) ?: return
        val line = row.findViewById<View>(resource("view_divider")) ?: return
        row.tag = HostSettingsEntryContract.MARKER
        title.text = "ITHomeAdKill"
        description.visibility = View.GONE
        arrow.tag = "arrow"
        line.tag = "line"
        nativeArrow.drawable?.constantState?.newDrawable(activity.resources)?.mutate()?.let(arrow::setImageDrawable)
        row.background = profile.background?.constantState?.newDrawable(activity.resources)?.mutate() ?: row.background
        syncTheme(row, nativeTitle, nativeArrow, nativeLine)
        row.isFocusable = true
        row.setOnClickListener {
            HostSettingsEntryContract.launch({
                activity.startActivity(Intent().apply {
                    component = ComponentName(HostSettingsEntryContract.MODULE_PACKAGE, HostSettingsEntryContract.MODULE_ACTIVITY)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
            }, {
                Toast.makeText(activity, "无法打开模块设置，请确认模块已安装并启用", Toast.LENGTH_SHORT).show()
            })
        }
        container.addView(row, index)
    }

    private fun syncTheme(row: View, source: TextView, sourceArrow: ImageView, sourceLine: View?) {
        val title = row.findViewWithTag<TextView>("subTitle") ?: return
        if (title.textColors != source.textColors) title.setTextColor(source.textColors)
        if (title.textSize != source.textSize) title.setTextSize(TypedValue.COMPLEX_UNIT_PX, source.textSize)
        if (title.typeface !== source.typeface) title.typeface = source.typeface
        val arrow = row.findViewWithTag<ImageView>("arrow")
        if (arrow != null) {
            if (arrow.imageTintList != sourceArrow.imageTintList) arrow.imageTintList = sourceArrow.imageTintList
            if (arrow.colorFilter !== sourceArrow.colorFilter) arrow.colorFilter = sourceArrow.colorFilter
        }
        val line = row.findViewWithTag<View>("line")
        val color = (sourceLine?.background as? ColorDrawable)?.color
        if (line != null && color != null && (line.background as? ColorDrawable)?.color != color) {
            line.setBackgroundColor(color)
        }
    }
}
