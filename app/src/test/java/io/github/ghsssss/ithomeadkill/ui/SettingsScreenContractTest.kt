package io.github.ghsssss.ithomeadkill.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class SettingsScreenContractTest {
    private val source get() = File("src/main/java/io/github/ghsssss/ithomeadkill/ui/SettingsScreen.kt").readText()

    @Test
    fun noGrayExplanationsOrPreferenceSummariesRemain() {
        assertFalse(source.contains("summaryResource"))
        assertFalse(source.contains("summary ="))
        assertFalse(source.contains("settings_usage_note"))
        assertFalse(source.contains("onSurfaceVariantSummary"))
        assertFalse(source.contains("settings_unavailable_hint"))
    }

    @Test
    fun necessaryErrorsAndTheIndependentLauncherControlRemain() {
        assertTrue(source.contains("state.error"))
        assertTrue(source.contains("settings_hide_launcher_title"))
        assertTrue(source.contains("onLauncherIconChange"))
    }

    @Test
    fun onlyTheAliasOwnsTheDesktopLauncherWhileTheSettingsActivityStaysEnabled() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val namespace = "http://schemas.android.com/apk/res/android"
        val activity = doc.getElementsByTagName("activity").item(0) as Element
        assertEquals(".ui.SettingsActivity", activity.getAttributeNS(namespace, "name"))
        assertFalse(activity.getAttributeNS(namespace, "enabled") == "false")
        val activityCategories = activity.getElementsByTagName("category")
        val names = (0 until activityCategories.length).map {
            (activityCategories.item(it) as Element).getAttributeNS(namespace, "name")
        }
        assertEquals(listOf("de.robv.android.xposed.category.MODULE_SETTINGS"), names)
        val aliases = doc.getElementsByTagName("activity-alias")
        assertEquals(1, aliases.length)
        val alias = aliases.item(0) as Element
        assertEquals(".Launcher", alias.getAttributeNS(namespace, "name"))
        assertEquals(".ui.SettingsActivity", alias.getAttributeNS(namespace, "targetActivity"))
        assertEquals("true", alias.getAttributeNS(namespace, "enabled"))
        val category = alias.getElementsByTagName("category").item(0) as Element
        assertEquals("android.intent.category.LAUNCHER", category.getAttributeNS(namespace, "name"))
    }
}
