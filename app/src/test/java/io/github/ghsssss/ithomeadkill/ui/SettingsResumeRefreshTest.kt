package io.github.ghsssss.ithomeadkill.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsResumeRefreshTest {
    @Test
    fun firstResumeUsesTheRepositoryInitialReadAndLaterResumesRefresh() {
        var reloads = 0
        val refresh = SettingsResumeRefresh { reloads++ }

        refresh.onResume()
        assertEquals(0, reloads)
        refresh.onResume()
        assertEquals(1, reloads)
        refresh.onResume()
        assertEquals(2, reloads)
    }

    @Test
    fun recreatingThePageSkipsOnlyItsOwnFirstResume() {
        var reloads = 0
        val first = SettingsResumeRefresh { reloads++ }
        first.onResume()
        first.onResume()
        val second = SettingsResumeRefresh { reloads++ }

        second.onResume()
        assertEquals(1, reloads)
        second.onResume()
        assertEquals(2, reloads)
    }
}
