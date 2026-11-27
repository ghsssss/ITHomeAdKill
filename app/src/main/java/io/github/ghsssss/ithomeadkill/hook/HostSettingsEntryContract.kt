package io.github.ghsssss.ithomeadkill.hook

internal object HostSettingsEntryContract {
    const val HOST_PACKAGE = "com.ruanmei.ithome"
    const val HOST_ACTIVITY = "com.ruanmei.ithome.ui.SettingsActivity"
    const val MODULE_PACKAGE = "io.github.ghsssss.ithomeadkill"
    const val MODULE_ACTIVITY = "io.github.ghsssss.ithomeadkill.ui.SettingsActivity"
    const val MARKER = "io.github.ghsssss.ithomeadkill.settings-entry"

    fun matches(packageName: String, activityName: String): Boolean =
        packageName == HOST_PACKAGE && activityName == HOST_ACTIVITY

    fun insertionIndex(profileIndex: Int, entryPresent: Boolean): Int? =
        if (!entryPresent && profileIndex == 1) 1 else null

    fun launch(start: () -> Unit, onFailure: () -> Unit) {
        try {
            start()
        } catch (_: RuntimeException) {
            try { onFailure() } catch (_: RuntimeException) { }
        }
    }
}
