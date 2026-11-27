package io.github.ghsssss.ithomeadkill.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HostSettingsEntryContractTest {
    @Test
    fun onlyExactHostSettingsActivityIsAccepted() {
        assertTrue(HostSettingsEntryContract.matches("com.ruanmei.ithome", "com.ruanmei.ithome.ui.SettingsActivity"))
        listOf(
            "com.ruanmei.ithome.ui.MainActivity",
            "com.ruanmei.ithome.ui.PrivacyCenterActivity",
            "com.ruanmei.ithome.ui.DisplaySettingsActivity",
            "com.ruanmei.ithome.ui.NightModeSettingsActivity",
            "com.ruanmei.ithome.ui.AdvanceSettingsActivity",
            "com.ruanmei.ithome.ui.SettingsActivityExtra",
            HostSettingsEntryContract.MODULE_ACTIVITY,
        ).forEach { name -> assertFalse(name, HostSettingsEntryContract.matches("com.ruanmei.ithome", name)) }
        assertFalse(HostSettingsEntryContract.matches("other.package", HostSettingsEntryContract.HOST_ACTIVITY))
    }

    @Test
    fun entryGoesAfterOriginalTopSpacerBeforePersonalProfile() {
        assertEquals(1, HostSettingsEntryContract.insertionIndex(profileIndex = 1, entryPresent = false))
    }

    @Test
    fun currentContainerAlreadyHavingEntryIsNeverInjectedAgain() {
        listOf(-1, 0, 1, 2, 3).forEach { index ->
            assertNull(HostSettingsEntryContract.insertionIndex(index, entryPresent = true))
        }
    }

    @Test
    fun missingOrChangedNativeTopStructureFailsOpen() {
        listOf(-1, 0, 2, 3).forEach { index ->
            assertNull(HostSettingsEntryContract.insertionIndex(index, entryPresent = false))
        }
    }

    @Test
    fun launchRunsExactlyOnceWithoutErrorNotice() {
        var starts = 0
        var failures = 0
        HostSettingsEntryContract.launch({ starts++ }, { failures++ })
        assertEquals(1, starts)
        assertEquals(0, failures)
    }

    @Test
    fun launchFailureIsReportedWithoutCrashingOrRetrying() {
        listOf(SecurityException("denied"), IllegalStateException("missing activity")).forEach { failure ->
            var starts = 0
            var notices = 0
            HostSettingsEntryContract.launch({ starts++; throw failure }, { notices++ })
            assertEquals(1, starts)
            assertEquals(1, notices)
        }
    }

    @Test
    fun failureNoticeCannotCrashHost() {
        var notices = 0
        HostSettingsEntryContract.launch({ throw SecurityException() }, { notices++; throw IllegalStateException() })
        assertEquals(1, notices)
    }

    @Test
    fun fatalErrorsAreNotConvertedIntoLaunchFailures() {
        val expected = AssertionError("fatal")
        var notices = 0
        val actual = assertThrows(AssertionError::class.java) {
            HostSettingsEntryContract.launch({ throw expected }, { notices++ })
        }
        assertTrue(actual === expected)
        assertEquals(0, notices)
    }

    @Test
    fun launchTargetIsRealActivityNotHideableAlias() {
        assertEquals("io.github.ghsssss.ithomeadkill", HostSettingsEntryContract.MODULE_PACKAGE)
        assertEquals("io.github.ghsssss.ithomeadkill.ui.SettingsActivity", HostSettingsEntryContract.MODULE_ACTIVITY)
        assertFalse(HostSettingsEntryContract.MODULE_ACTIVITY.endsWith("Launcher"))
    }
}
