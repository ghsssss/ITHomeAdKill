package io.github.ghsssss.ithomeadkill.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStoreTest {
    @Test
    fun emptyStoreInitializesOneCompleteFrameworkPreferenceSnapshot() {
        val storage = MemoryStorage()
        val store = storage.store()

        val state = store.reload()

        assertEquals(ModuleSettings.defaults(WRITER), state.settings)
        assertEquals(state.settings, ModuleSettings.fromPreferences(storage.values))
        assertEquals(listOf(state.settings!!.toPreferences()), storage.writes)
        assertFalse(state.busy)
        assertTrue(state.message.contains("框架"))
        assertFalse(state.message.contains("本机"))
        assertFalse(state.message.contains("已激活"))
        assertFalse(state.message.contains("已生效"))
    }

    @Test
    fun validExistingSnapshotLoadsWithoutOverwritingIt() {
        val original = ModuleSettings.defaults(WRITER).copy(
            revision = 7L,
            toggles = ModuleSettings.defaults(WRITER).toggles + (Toggle.MASTER to false),
        )
        val storage = MemoryStorage(original.toPreferences())

        assertEquals(original, storage.store().reload().settings)
        assertTrue(storage.writes.isEmpty())
    }

    @Test
    fun invalidNonemptySnapshotIsNotReplacedWithEnabledDefaults() {
        val storage = MemoryStorage(mapOf("master_enabled" to false))

        val state = storage.store().reload()

        assertNull(state.settings)
        assertFalse(state.busy)
        assertTrue(storage.writes.isEmpty())
        assertEquals(mapOf("master_enabled" to false), storage.values)
    }

    @Test
    fun legacyReloadMigratesBothEventValuesExactlyOnceWithoutLosingRawChoices() {
        listOf(false, true).forEach { eventCalendar ->
            val legacy = legacyPreferences(eventCalendar)
            val storage = MemoryStorage(legacy)
            val store = storage.store()
            val expected = (legacy - "hide_event_calendar") + mapOf(
                "settings_schema" to 5,
                "hide_article_commission" to false,
                "comment_tree_lines" to false,
                "settings_revision" to 42L,
                "hide_article_rewards" to false,
            )

            val migrated = store.reload()

            assertEquals(expected, storage.values)
            assertEquals(listOf(expected), storage.writes)
            assertEquals(ModuleSettings.fromPreferences(expected), migrated.settings)
            assertFalse(migrated.settings!!.raw(Toggle.MASTER))
            assertEquals(10, migrated.settings!!.autoRefreshMinutes)
            repeat(3) { assertEquals(migrated.settings, store.reload().settings) }
            assertEquals(migrated.settings, storage.store().reload().settings)
            assertEquals(1, storage.writes.size)
        }
    }

    @Test
    fun legacyMaximumRevisionIsUnavailableAndNeverWritten() {
        val legacy = legacyPreferences(revision = Long.MAX_VALUE)
        val storage = MemoryStorage(legacy)

        val state = storage.store().reload()

        assertNull(state.settings)
        assertTrue(state.message.contains("失败"))
        assertFalse(state.busy)
        assertTrue(storage.writes.isEmpty())
        assertEquals(legacy, storage.values)
    }

    @Test
    fun failedLegacyCommitStaysUnavailableAndRetriesTheSameSnapshotAndRevision() {
        listOf(false, true).forEach { keepOldValues ->
            val storage = MemoryStorage(legacyPreferences()).apply {
                commitSucceeds = false
                this.keepOldValues = keepOldValues
            }
            val store = storage.store()

            repeat(2) {
                val failed = store.reload()
                assertNull(failed.settings)
                assertTrue(failed.message.contains("失败"))
                assertFalse(failed.busy)
            }
            storage.commitSucceeds = true
            storage.keepOldValues = false
            val recovered = store.reload()

            assertNotNull(recovered.settings)
            assertEquals(42L, recovered.settings!!.revision)
            assertEquals(3, storage.writes.size)
            assertTrue(storage.writes.all { it == storage.writes.first() })
            assertEquals(storage.writes.last(), storage.values)
        }
    }

    @Test
    fun failedLegacyMigrationDoesNotPublishAnOlderPreviouslyLoadedState() {
        val storage = MemoryStorage(ModuleSettings.defaults(WRITER).toPreferences())
        val store = storage.store()
        assertNotNull(store.reload().settings)
        storage.values = legacyPreferences()
        storage.commitFailure = true

        val state = store.reload()

        assertNull(state.settings)
        assertNull(store.settings)
        assertTrue(state.message.contains("失败"))
    }

    @Test
    fun pendingRetryAgainstLegacyIsUnavailableUntilMigrationIsConfirmed() {
        val legacy = legacyPreferences() + ("hide_ad_cards" to true)
        val original = ModuleSettings.fromPreferences(legacy)!!.toPreferences()
        val storage = MemoryStorage(original)
        val store = storage.store()
        store.reload()
        storage.commitSucceeds = false
        storage.keepOldValues = true
        store.setToggle(Toggle.AD_CARDS, false)
        storage.values = legacy

        val failed = store.reload()

        assertNull(failed.settings)
        assertTrue(failed.message.contains("失败"))
        storage.commitSucceeds = true
        storage.keepOldValues = false
        val recovered = store.reload().settings!!
        assertFalse(recovered.raw(Toggle.AD_CARDS))
        assertEquals(42L, recovered.revision)
        assertEquals(storage.writes.first(), storage.writes.last())
        assertFalse(storage.values.containsKey("hide_event_calendar"))
    }

    @Test
    fun migrationReadBackCannotAcceptLegacySchemaWithTheNewRevision() {
        val storage = MemoryStorage(legacyPreferences()).apply {
            writeTransform = { next -> legacyPreferences(revision = next["settings_revision"] as Long) }
        }

        val state = storage.store().reload()

        assertNull(state.settings)
        assertTrue(state.message.contains("失败"))
        assertEquals(1, storage.values["settings_schema"])
    }

    @Test
    fun migrationReadBackCannotAcceptAnUnclearedLegacyKey() {
        val storage = MemoryStorage(legacyPreferences()).apply {
            writeTransform = { next -> next + ("hide_event_calendar" to true) }
        }

        val state = storage.store().reload()

        assertNull(state.settings)
        assertTrue(state.message.contains("失败"))
        assertTrue(storage.values.containsKey("hide_event_calendar"))
    }

    @Test
    fun migrationReadBackWithChangedRawFlagsIsNotReportedAsSaved() {
        val storage = MemoryStorage(legacyPreferences()).apply {
            writeTransform = { next -> next + ("hide_article_rating" to false) }
        }

        val state = storage.store().reload()

        assertNull(state.settings)
        assertTrue(state.message.contains("失败"))
    }

    @Test
    fun pendingMigrationDoesNotOverwriteANewlyMalformedLegacySnapshot() {
        val storage = MemoryStorage(legacyPreferences()).apply { commitSucceeds = false }
        val store = storage.store()
        assertNull(store.reload().settings)
        val corrupt = legacyPreferences() + ("hide_event_calendar" to "true")
        storage.values = corrupt
        storage.commitSucceeds = true
        val writesBeforeRetry = storage.writes.size

        assertNull(store.reload().settings)
        assertNull(store.setToggle(Toggle.ARTICLE_REWARDS, true).settings)
        assertEquals(writesBeforeRetry, storage.writes.size)
        assertEquals(corrupt, storage.values)
    }

    @Test
    fun malformedLegacyOrCurrentAndUnknownSchemasAreNeverResetOrWritten() {
        val legacy = legacyPreferences()
        val current = ModuleSettings.defaults(WRITER).toPreferences()
        val invalid = listOf(
            legacy - "hide_event_calendar",
            legacy - "hide_article_rating",
            legacy + ("hide_event_calendar" to 1),
            legacy + ("settings_revision" to 41),
            legacy + ("settings_writer_id" to "invalid"),
            legacy + ("auto_refresh_minutes" to 3),
            legacy + ("settings_schema" to 6),
            current - "hide_article_rewards",
            current + ("hide_article_rewards" to "false"),
            current + ("settings_schema" to 6),
        )
        invalid.forEach { snapshot ->
            val storage = MemoryStorage(snapshot)
            val store = storage.store()

            assertNull("snapshot=$snapshot", store.reload().settings)
            assertNull(store.setToggle(Toggle.ARTICLE_REWARDS, true).settings)
            assertNull(store.setRefreshMinutes(1).settings)
            assertTrue(storage.writes.isEmpty())
            assertEquals(snapshot, storage.values)
        }
    }

    @Test
    fun noOpMutationFreshReadsAndMigratesLegacyInsteadOfLeavingItOnSchemaOne() {
        val storage = MemoryStorage(ModuleSettings.defaults(WRITER).toPreferences())
        val store = storage.store()
        store.reload()
        storage.values = legacyPreferences()

        val state = store.setToggle(Toggle.MASTER, false)

        assertEquals(42L, state.settings!!.revision)
        assertFalse(state.settings!!.raw(Toggle.MASTER))
        assertEquals(10, state.settings!!.autoRefreshMinutes)
        assertEquals(5, storage.values["settings_schema"])
        assertFalse(storage.values.containsKey("hide_event_calendar"))
        assertEquals(1, storage.writes.size)
    }

    @Test
    fun rewardMutationBeforeReloadCombinesMigrationAndChangeIntoOneRevision() {
        val legacy = legacyPreferences(false)
        val storage = MemoryStorage(legacy)

        val state = storage.store().setToggle(Toggle.ARTICLE_REWARDS, true)

        val expected = (legacy - "hide_event_calendar") + mapOf(
            "settings_schema" to 5,
            "hide_article_commission" to false,
            "comment_tree_lines" to false,
            "settings_revision" to 42L,
            "hide_article_rewards" to true,
        )
        assertEquals(expected, storage.values)
        assertEquals(ModuleSettings.fromPreferences(expected), state.settings)
        assertEquals(listOf(expected), storage.writes)
    }

    @Test
    fun failedLegacyMutationCannotPublishAnUnmigratedUsableSnapshot() {
        val storage = MemoryStorage(legacyPreferences()).apply { commitSucceeds = false }

        val state = storage.store().setToggle(Toggle.ARTICLE_REWARDS, true)

        assertNull(state.settings)
        assertTrue(state.message.contains("失败"))
    }

    @Test
    fun legacyMaximumRevisionNoOpMutationIsUnavailableAndDoesNotWrite() {
        val legacy = legacyPreferences(revision = Long.MAX_VALUE)
        val storage = MemoryStorage(legacy)

        val state = storage.store().setToggle(Toggle.MASTER, false)

        assertNull(state.settings)
        assertTrue(state.message.contains("失败"))
        assertTrue(storage.writes.isEmpty())
        assertEquals(legacy, storage.values)
    }

    @Test
    fun currentRewardsChoicePersistsIndependentlyWithoutRepeatedMigration() {
        val original = ModuleSettings.defaults(WRITER).copy(
            revision = 8L,
            autoRefreshMinutes = 10,
            toggles = Toggle.values().associateWith { it != Toggle.MASTER },
        )
        val storage = MemoryStorage(original.toPreferences())
        val store = storage.store()
        assertEquals(original, store.reload().settings)

        val changed = store.setToggle(Toggle.ARTICLE_REWARDS, false).settings!!

        assertEquals(original.copy(
            revision = 9L,
            toggles = original.toggles + (Toggle.ARTICLE_REWARDS to false),
        ), changed)
        assertEquals(changed, storage.store().reload().settings)
        assertEquals(1, storage.writes.size)
    }

    @Test
    fun readFailureIsReportedWithoutInitializingOrThrowing() {
        val storage = MemoryStorage().apply { readFailure = true }

        val state = storage.store().reload()

        assertNull(state.settings)
        assertTrue(state.message.contains("失败"))
        assertFalse(state.busy)
        assertTrue(storage.writes.isEmpty())
    }

    @Test
    fun queuedStyleUpdatesUseLatestConfirmedSnapshotInsteadOfAnOldUiCopy() {
        val storage = MemoryStorage()
        val store = storage.store()
        store.reload()

        store.setToggle(Toggle.AD_CARDS, false)
        store.setToggle(Toggle.AI_SUMMARY, true)
        val state = store.setRefreshMinutes(5)

        assertEquals(4L, state.settings!!.revision)
        assertFalse(state.settings!!.raw(Toggle.AD_CARDS))
        assertTrue(state.settings!!.raw(Toggle.AI_SUMMARY))
        assertEquals(5, state.settings!!.autoRefreshMinutes)
        assertEquals(state.settings, ModuleSettings.fromPreferences(storage.values))
    }

    @Test
    fun masterChangeRetainsTheRawChildChoices() {
        val storage = MemoryStorage()
        val store = storage.store()
        store.reload()
        store.setToggle(Toggle.AI_SUMMARY, true)

        val state = store.setToggle(Toggle.MASTER, false)

        assertTrue(state.settings!!.raw(Toggle.AI_SUMMARY))
        Toggle.values().forEach { assertFalse(state.settings!!.enabled(it)) }
    }

    @Test
    fun noOpDoesNotRewriteOrIncrementRevision() {
        val storage = MemoryStorage()
        val store = storage.store()
        val initial = store.reload().settings

        assertEquals(initial, store.setToggle(Toggle.MASTER, true).settings)
        assertEquals(initial, store.setRefreshMinutes(0).settings)
        assertEquals(1, storage.writes.size)
    }

    @Test
    fun invalidMinutesAreRejectedWithoutChangingStateOrWriting() {
        val storage = MemoryStorage()
        val store = storage.store()
        val initial = store.reload().settings

        listOf(-1, 2, 3, 60).forEach { minutes ->
            val state = store.setRefreshMinutes(minutes)
            assertEquals(initial, state.settings)
            assertTrue(state.message.contains("无效"))
        }
        assertEquals(1, storage.writes.size)
    }

    @Test
    fun failedCommitDoesNotPromoteChangedSharedPreferencesMemoryToConfirmedState() {
        val storage = MemoryStorage()
        val store = storage.store()
        val initial = store.reload().settings
        storage.commitSucceeds = false

        val failed = store.setToggle(Toggle.AD_CARDS, false)

        assertEquals(initial, failed.settings)
        assertTrue(failed.message.contains("失败"))
        assertFalse(ModuleSettings.fromPreferences(storage.values)!!.raw(Toggle.AD_CARDS))
        storage.commitSucceeds = true
        val next = store.setToggle(Toggle.AI_SUMMARY, true)
        assertTrue(next.settings!!.raw(Toggle.AD_CARDS))
        assertTrue(next.settings!!.raw(Toggle.AI_SUMMARY))
        assertEquals(2L, next.settings!!.revision)
    }

    @Test
    fun reloadAfterFailedCommitRetriesCommitRatherThanAcceptingItsMemoryCache() {
        val storage = MemoryStorage()
        val store = storage.store()
        store.reload()
        storage.commitSucceeds = false
        store.setToggle(Toggle.AD_CARDS, false)

        val retryFailure = store.reload()
        assertTrue(retryFailure.message.contains("失败"))
        assertTrue(retryFailure.settings!!.raw(Toggle.AD_CARDS))
        storage.commitSucceeds = true
        val recovered = store.reload()
        assertFalse(recovered.settings!!.raw(Toggle.AD_CARDS))
        assertEquals(2L, recovered.settings!!.revision)
        assertEquals(4, storage.writes.size)
    }

    @Test
    fun failedInitializationCanBeRetriedWithoutLosingItsWriterIdentity() {
        val storage = MemoryStorage().apply { commitSucceeds = false }
        val store = storage.store()

        assertNull(store.reload().settings)
        storage.commitSucceeds = true
        val retry = store.reload()

        assertNotNull(retry.settings)
        assertEquals(WRITER, retry.settings!!.writerId)
        assertEquals(1L, retry.settings!!.revision)
        assertEquals(storage.writes[0], storage.writes[1])
    }

    @Test
    fun commitExceptionIsAnHonestFailureNotAnUncaughtWorkerException() {
        val storage = MemoryStorage()
        val store = storage.store()
        val initial = store.reload().settings
        storage.commitFailure = true

        val state = store.setRefreshMinutes(10)

        assertEquals(initial, state.settings)
        assertTrue(state.message.contains("失败"))
        assertFalse(state.busy)
    }

    @Test
    fun successfulCommitWithDifferentReadBackIsNotReportedAsSaved() {
        val storage = MemoryStorage()
        val store = storage.store()
        val initial = store.reload().settings
        storage.keepOldValues = true

        val state = store.setToggle(Toggle.AD_CARDS, false)

        assertEquals(initial, state.settings)
        assertTrue(state.message.contains("失败"))
    }

    @Test
    fun reloadOfUnreadableDataDropsTheEffectiveSnapshotRatherThanKeepingMasterOn() {
        val storage = MemoryStorage()
        val store = storage.store()
        store.reload()
        storage.readFailure = true

        val state = store.reload()

        assertNull(state.settings)
        assertNull(store.settings)
        assertTrue(state.message.contains("失败"))
    }

    @Test
    fun updatesWithoutAValidLoadedSnapshotDoNotInventDefaults() {
        val storage = MemoryStorage(mapOf("master_enabled" to false))
        val store = storage.store()
        store.reload()

        assertNull(store.setToggle(Toggle.AI_SUMMARY, true).settings)
        assertNull(store.setRefreshMinutes(1).settings)
        assertTrue(storage.writes.isEmpty())
    }

    @Test
    fun maximumRevisionCannotWrapOrWriteANonPositiveRevision() {
        val original = ModuleSettings.defaults(WRITER).copy(revision = Long.MAX_VALUE)
        val storage = MemoryStorage(original.toPreferences())
        val store = storage.store()
        store.reload()

        val state = store.setToggle(Toggle.AD_CARDS, false)

        assertEquals(original, state.settings)
        assertTrue(state.message.contains("失败"))
        assertTrue(storage.writes.isEmpty())
    }

    private class MemoryStorage(initial: Map<String, *> = emptyMap<String, Any>()) {
        var values: Map<String, *> = initial.toMap()
        val writes = mutableListOf<Map<String, Any>>()
        var commitSucceeds = true
        var readFailure = false
        var commitFailure = false
        var keepOldValues = false
        var writeTransform: (Map<String, Any>) -> Map<String, *> = { it }
        private val state = SettingsStoreState()

        fun store(): SettingsStore = SettingsStore(
            state = state,
            read = {
                check(!readFailure) { "模拟读取失败" }
                values.toMap()
            },
            commit = { next ->
                check(!commitFailure) { "模拟写入异常" }
                writes += next.toMap()
                // Android commit=false 也可能已经改变同进程内存，故意模拟这个边界。
                if (!keepOldValues) values = writeTransform(next.toMap())
                commitSucceeds
            },
            newWriterId = { WRITER },
        )
    }

    private companion object {
        const val WRITER = "550e8400-e29b-41d4-a716-446655440000"
    }
}
