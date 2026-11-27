package io.github.ghsssss.ithomeadkill.ui

import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIconControllerTest {
    @Test
    fun defaultAndExplicitlyEnabledLauncherAreVisible() {
        listOf(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED).forEach { state ->
            assertFalse(LauncherIconController({ state }, {}).isHidden())
        }
    }

    @Test
    fun everyExplicitDisabledStateIsHidden() {
        listOf(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED).forEach { state ->
            assertTrue(LauncherIconController({ state }, {}).isHidden())
        }
    }

    @Test
    fun hidingOnlyDisablesTheLauncherAndReadsBackTheResult() {
        var state = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        val writes = mutableListOf<Int>()
        val controller = LauncherIconController({ state }) { writes += it; state = it }

        controller.setHidden(true)

        assertTrue(controller.isHidden())
        assertEquals(listOf(PackageManager.COMPONENT_ENABLED_STATE_DISABLED), writes)
    }

    @Test
    fun showingExplicitlyEnablesTheLauncher() {
        var state = PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val controller = LauncherIconController({ state }) { state = it }

        controller.setHidden(false)

        assertFalse(controller.isHidden())
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state)
    }

    @Test
    fun repeatedRequestDoesNotWriteOrRestartAnything() {
        val writes = mutableListOf<Int>()
        val controller = LauncherIconController({ PackageManager.COMPONENT_ENABLED_STATE_DEFAULT }, writes::add)

        controller.setHidden(false)

        assertTrue(writes.isEmpty())
    }

    @Test
    fun recreatedControllerUsesThePersistedComponentState() {
        var state = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        LauncherIconController({ state }) { state = it }.setHidden(true)

        assertTrue(LauncherIconController({ state }, {}).isHidden())
    }

    @Test
    fun deniedWriteNeverCreatesAnOptimisticHiddenState() {
        val controller = LauncherIconController({ PackageManager.COMPONENT_ENABLED_STATE_DEFAULT }) {
            throw SecurityException("denied")
        }

        assertThrows(SecurityException::class.java) { controller.setHidden(true) }
        assertFalse(controller.isHidden())
    }

    @Test
    fun ignoredWriteIsNotReportedAsSuccess() {
        val controller = LauncherIconController({ PackageManager.COMPONENT_ENABLED_STATE_DEFAULT }, {})

        assertThrows(IllegalStateException::class.java) { controller.setHidden(true) }
        assertFalse(controller.isHidden())
    }

    @Test
    fun readFailureIsNotReportedAsVisible() {
        val controller = LauncherIconController({ throw IllegalStateException("unavailable") }, {})

        assertThrows(IllegalStateException::class.java) { controller.isHidden() }
    }
}
