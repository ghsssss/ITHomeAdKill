package io.github.ghsssss.ithomeadkill.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMessageStateTest {
    @Test
    fun normalLoadingIsNotAnError() {
        assertFalse(SettingsState().error)
    }

    @Test
    fun successfulReadWriteAndUnchangedValuesAreNotErrors() {
        var values: Map<String, *> = ModuleSettings.defaults("550e8400-e29b-41d4-a716-446655440000").toPreferences()
        val store = SettingsStore({ values }, { values = it; true }, SettingsStoreState())

        assertFalse(store.reload().error)
        assertFalse(store.setToggle(Toggle.MASTER, false).error)
        assertFalse(store.setToggle(Toggle.MASTER, false).error)
    }

    @Test
    fun failedSaveStillShowsAnErrorWithPreviouslyLoadedSettings() {
        val values = ModuleSettings.defaults("550e8400-e29b-41d4-a716-446655440000").toPreferences()
        val store = SettingsStore({ values }, { false }, SettingsStoreState())
        store.reload()

        val failed = store.setToggle(Toggle.MASTER, false)

        assertTrue(failed.error)
        assertNotNull(failed.settings)
    }

    @Test
    fun invalidIntervalRemainsAnErrorInsteadOfDisappearingWithTheSummary() {
        val values = ModuleSettings.defaults("550e8400-e29b-41d4-a716-446655440000").toPreferences()
        val store = SettingsStore({ values }, { true }, SettingsStoreState())
        store.reload()

        assertTrue(store.setRefreshMinutes(7).error)
    }

    @Test
    fun readFailureIsAnError() {
        val store = SettingsStore({ throw IllegalStateException("unavailable") }, { true }, SettingsStoreState())

        assertTrue(store.reload().error)
    }
}
