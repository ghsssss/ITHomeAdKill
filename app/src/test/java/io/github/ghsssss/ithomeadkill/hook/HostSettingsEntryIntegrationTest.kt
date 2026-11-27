package io.github.ghsssss.ithomeadkill.hook

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Static wiring checks complement the real WSA view/click acceptance cases. */
class HostSettingsEntryIntegrationTest {
    private fun source(name: String): String {
        val relative = "src/main/java/io/github/ghsssss/ithomeadkill/hook/$name.kt"
        return (File(relative).takeIf { it.exists() } ?: File("app/$relative")).readText()
    }

    @Test
    fun settingsEntryRunsBeforePurificationSnapshotAndIsLocallyIsolated() {
        val host = source("HostRuntime")
        val sweep = host.substringAfter("private fun sweep(screen: Screen)").substringBefore("fun enabled(toggle:")
        assertTrue(sweep.contains("runCatching { HostSettingsEntry.attach(activity, root) }"))
        assertTrue(sweep.indexOf("HostSettingsEntry.attach") < sweep.indexOf("val settings = preferences.snapshot"))
    }

    @Test
    fun adapterUsesRealExplicitComponentInsteadOfHideableLauncher() {
        val entry = source("HostSettingsEntry")
        assertTrue(entry.contains("ComponentName(HostSettingsEntryContract.MODULE_PACKAGE, HostSettingsEntryContract.MODULE_ACTIVITY)"))
        assertFalse(entry.contains("getLaunchIntentForPackage"))
        assertFalse(entry.contains("FLAG_ACTIVITY_NEW_TASK"))
        assertFalse(entry.contains("preferences.snapshot"))
        assertFalse(entry.contains("Toggle."))
    }

    @Test
    fun adapterVerifiesNativeParentChainAndOnlyAddsItsOwnRow() {
        val entry = source("HostSettingsEntry")
        listOf("ll_settings_container", "nsv_list", "rl_settings_main", "rl_user_profile", "HostSettingsEntryContract.MARKER")
            .forEach { assertTrue(it, entry.contains(it)) }
        assertTrue(entry.contains("container.addView(row, index)"))
        assertFalse(entry.contains("removeView"))
        assertTrue(entry.contains("description.visibility = View.GONE"))
    }
}
