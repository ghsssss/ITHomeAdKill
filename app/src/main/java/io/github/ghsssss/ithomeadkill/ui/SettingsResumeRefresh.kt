package io.github.ghsssss.ithomeadkill.ui

internal class SettingsResumeRefresh(private val reload: () -> Unit) {
    private var resumed = false

    fun onResume() {
        // Repository construction already requested the first read.
        if (resumed) reload() else resumed = true
    }
}
