package io.github.ghsssss.ithomeadkill.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaThreeSettingsStoreTest {
    @Test
    fun schemaTwoReloadMigratesExactlyOnceWithoutCreatingWriterOrLosingChoices() {
        val legacy = schemaTwoPreferences()
        val storage = MemoryStorage(legacy)
        val store = storage.store()
        val expected = legacy + mapOf<String, Any>(
            "settings_schema" to 5,
            "hide_article_commission" to false,
            "comment_tree_lines" to false,
            "settings_revision" to 42L,
        )

        val migrated = store.reload().settings

        assertNotNull(migrated)
        assertEquals(expected, storage.values)
        assertEquals(listOf(expected), storage.writes)
        assertEquals(ModuleSettings.fromPreferences(expected), migrated)
        assertTrue(migrated!!.raw(Toggle.AI_SUMMARY))
        repeat(3) { assertEquals(migrated, store.reload().settings) }
        assertEquals(migrated, storage.store().reload().settings)
        assertEquals(migrated, store.setToggle(Toggle.AI_SUMMARY, true).settings)
        assertEquals(migrated, store.setRefreshMinutes(10).settings)
        assertEquals(1, storage.writes.size)
        assertEquals(0, storage.writerCreations)
    }

    @Test
    fun bothNoOpEntryPointsMigrateSchemaTwoBeforeReload() {
        val actions: List<(SettingsStore) -> SettingsState> = listOf(
            { it.setToggle(Toggle.MASTER, false) },
            { it.setRefreshMinutes(10) },
        )
        actions.forEach { action ->
            val legacy = schemaTwoPreferences()
            val storage = MemoryStorage(legacy)
            val expected = legacy + mapOf<String, Any>(
                "settings_schema" to 5,
                "hide_article_commission" to false,
                "comment_tree_lines" to false,
                "settings_revision" to 42L,
            )

            val state = action(storage.store())

            assertEquals(ModuleSettings.fromPreferences(expected), state.settings)
            assertEquals(expected, storage.values)
            assertEquals(listOf(expected), storage.writes)
            assertEquals(0, storage.writerCreations)
        }
    }

    @Test
    fun legacyAiMutationCombinesMigrationAndChangeIntoOneRevision() {
        legacySettingsSnapshots().forEach { legacy ->
            val storage = MemoryStorage(legacy)
            val expected = (legacy - "hide_event_calendar") + mapOf(
                "settings_schema" to 5,
                "hide_article_commission" to false,
                "comment_tree_lines" to false,
                "settings_revision" to 42L,
                "hide_article_rewards" to (legacy["hide_article_rewards"] ?: false),
                "hide_article_ai_summary" to false,
            )

            val state = storage.store().setToggle(Toggle.AI_SUMMARY, false)

            assertEquals(expected, storage.values)
            assertEquals(listOf(expected), storage.writes)
            assertEquals(ModuleSettings.fromPreferences(expected), state.settings)
            assertFalse(state.settings!!.raw(Toggle.AI_SUMMARY))
            assertFalse(state.settings.enabled(Toggle.AI_SUMMARY))
            assertEquals(state.settings, storage.store().reload().settings)
            assertEquals(1, storage.writes.size)
            assertEquals(0, storage.writerCreations)
        }
    }

    @Test
    fun schemaTwoRevisionLimitBlocksReloadNoOpAndActualChanges() {
        val actions: List<(SettingsStore) -> SettingsState> = listOf(
            { it.reload() },
            { it.setToggle(Toggle.MASTER, false) },
            { it.setToggle(Toggle.AI_SUMMARY, false) },
            { it.setRefreshMinutes(10) },
            { it.setRefreshMinutes(5) },
        )
        actions.forEach { action ->
            val legacy = schemaTwoPreferences(revision = Long.MAX_VALUE)
            val storage = MemoryStorage(legacy)

            val state = action(storage.store())

            assertNull(state.settings)
            assertTrue(state.message.contains("失败"))
            assertFalse(state.busy)
            assertTrue(storage.writes.isEmpty())
            assertEquals(legacy, storage.values)
            assertEquals(0, storage.writerCreations)
        }
    }

    @Test
    fun schemaTwoPenultimateRevisionMigratesToMaxWithoutWrappingOrRepeating() {
        val legacy = schemaTwoPreferences(revision = Long.MAX_VALUE - 1L)
        val storage = MemoryStorage(legacy)
        val store = storage.store()
        val expected = legacy + mapOf<String, Any>(
            "settings_schema" to 5,
            "hide_article_commission" to false,
            "comment_tree_lines" to false,
            "settings_revision" to Long.MAX_VALUE,
        )

        val migrated = store.reload().settings

        assertNotNull(migrated)
        assertEquals(Long.MAX_VALUE, migrated!!.revision)
        assertEquals(expected, storage.values)
        assertEquals(migrated, store.reload().settings)
        assertEquals(migrated, store.setToggle(Toggle.AI_SUMMARY, true).settings)
        val failed = store.setToggle(Toggle.AI_SUMMARY, false)
        assertEquals(migrated, failed.settings)
        assertTrue(failed.message.contains("失败"))
        assertEquals(expected, storage.values)
        assertEquals(listOf(expected), storage.writes)
        assertEquals(0, storage.writerCreations)
    }

    @Test
    fun failedSchemaTwoCommitRequiresConfirmationAndRetriesSameSnapshotAcrossStores() {
        listOf(false, true).forEach { keepOldValues ->
            val legacy = schemaTwoPreferences()
            val storage = MemoryStorage(legacy).apply {
                commitSucceeds = false
                this.keepOldValues = keepOldValues
            }
            val expected = legacy + mapOf<String, Any>(
                "settings_schema" to 5,
                "hide_article_commission" to false,
                "comment_tree_lines" to false,
                "settings_revision" to 42L,
            )

            repeat(2) {
                val failed = storage.store().reload()
                assertNull(failed.settings)
                assertTrue(failed.message.contains("失败"))
                assertFalse(failed.busy)
            }
            storage.commitSucceeds = true
            storage.keepOldValues = false
            val recovered = storage.store().reload()

            assertEquals(ModuleSettings.fromPreferences(expected), recovered.settings)
            assertEquals(expected, storage.values)
            assertEquals(listOf(expected, expected, expected), storage.writes)
            assertEquals(0, storage.writerCreations)
        }
    }

    @Test
    fun pendingRetryAgainstSchemaTwoDoesNotRepublishPreviousConfirmedSettings() {
        val legacy = schemaTwoPreferences()
        val original = schemaFivePreferences()
        val storage = MemoryStorage(original)
        val store = storage.store()
        assertNotNull(store.reload().settings)
        storage.commitSucceeds = false
        storage.keepOldValues = true
        store.setToggle(Toggle.AI_SUMMARY, false)
        storage.values = legacy

        val failed = storage.store().reload()

        assertNull(failed.settings)
        assertNull(store.settings)
        assertTrue(failed.message.contains("失败"))
        storage.commitSucceeds = true
        storage.keepOldValues = false
        val recovered = storage.store().reload().settings!!
        assertFalse(recovered.raw(Toggle.AI_SUMMARY))
        assertEquals(42L, recovered.revision)
        assertEquals(original + mapOf("settings_revision" to 42L, "hide_article_ai_summary" to false), storage.values)
        assertEquals(3, storage.writes.size)
        assertTrue(storage.writes.all { it == storage.writes.first() })
        assertEquals(0, storage.writerCreations)
    }

    @Test
    fun failedSchemaTwoNoOpMigrationNeverPublishesOldOrSpeculativeSnapshot() {
        listOf(false, true).forEach { keepOldValues ->
            val storage = MemoryStorage(schemaTwoPreferences()).apply {
                commitSucceeds = false
                this.keepOldValues = keepOldValues
            }
            val store = storage.store()

            val failed = store.setRefreshMinutes(10)

            assertNull(failed.settings)
            assertTrue(failed.message.contains("失败"))
            assertNull(storage.store().reload().settings)
            storage.commitSucceeds = true
            storage.keepOldValues = false
            val recovered = storage.store().reload().settings!!
            assertEquals(42L, recovered.revision)
            assertTrue(recovered.raw(Toggle.AI_SUMMARY))
            assertEquals(3, storage.writes.size)
            assertTrue(storage.writes.all { it == storage.writes.first() })
            assertEquals(0, storage.writerCreations)
        }
    }

    @Test
    fun legacyReadBackMustMatchSchemaKeysAndEveryValueNotJustDecodedModel() {
        legacySettingsSnapshots().forEach { legacy ->
            val transforms: List<(Map<String, Any>) -> Map<String, *>> = listOf(
                { next -> legacy + ("settings_revision" to next["settings_revision"]!!) },
                { next -> next + ("hide_event_calendar" to true) },
                { next -> next - "hide_article_ai_summary" },
                { next -> next - "hide_article_commission" },
                { next -> next + ("hide_article_commission" to "false") },
                { next -> next + ("hide_article_commission" to true) },
                { next -> next + ("hide_article_ai_summary" to false) },
                { next -> next + ("hide_article_ai_summary" to "true") },
                { next -> next + ("comment_thread_lines" to true) },
                { next -> next + ("comment_thread_lines" to "false") },
                { next -> next + ("settings_writer_id" to NEW_WRITER) },
                { next -> next + ("auto_refresh_minutes" to 5) },
                { next -> next + ("hide_ad_cards" to true) },
            )
            transforms.forEach { transform ->
                val storage = MemoryStorage(legacy).apply { writeTransform = transform }

                val state = storage.store().reload()

                assertNull(state.settings)
                assertTrue(state.message.contains("失败"))
                assertFalse(state.busy)
                assertEquals(1, storage.writes.size)
                assertEquals(5, storage.writes.single()["settings_schema"])
                assertEquals(42L, storage.writes.single()["settings_revision"])
                assertEquals(transform(storage.writes.single()), storage.values)
                assertEquals(0, storage.writerCreations)
            }
        }
    }

    @Test
    fun malformedLegacyCurrentAndFutureSnapshotsAreNeverDefaultedOrOverwritten() {
        val schemaFour = schemaFourPreferences()
        assertEquals(4, schemaFour["settings_schema"])
        assertFalse(schemaFour.containsKey("comment_tree_lines"))
        val snapshots = legacySettingsSnapshots() + listOf(schemaFour, schemaFivePreferences())
        val invalid = snapshots.flatMap { snapshot ->
            snapshot.keys.map { key -> snapshot - key } +
                snapshot.filterValues { it is Boolean }.keys.flatMap { key ->
                    listOf<Any?>(null, "false", 0, 1L).map { value -> snapshot + (key to value) }
                } + listOf(
                    snapshot + ("settings_revision" to 0L),
                    snapshot + ("settings_revision" to -1L),
                    snapshot + ("settings_revision" to 41),
                    snapshot + ("settings_writer_id" to "invalid"),
                    snapshot + ("auto_refresh_minutes" to 3),
                    snapshot + ("auto_refresh_minutes" to 10L),
                    snapshot + ("settings_schema" to 6),
                    snapshot + ("settings_schema" to Int.MAX_VALUE),
                    snapshot + ("settings_schema" to "3"),
                    snapshot + ("settings_schema" to 3L),
                )
        }
        invalid.forEach { snapshot ->
            val storage = MemoryStorage(snapshot)
            val store = storage.store()

            assertNull("snapshot=$snapshot", store.reload().settings)
            assertNull(store.setToggle(Toggle.AI_SUMMARY, false).settings)
            assertNull(store.setRefreshMinutes(5).settings)
            assertTrue(storage.writes.isEmpty())
            assertEquals(snapshot, storage.values)
            assertEquals(0, storage.writerCreations)
        }
    }

    @Test
    fun pendingSchemaTwoMigrationCannotOverwriteNewlyCorruptOrFutureData() {
        val schemaTwo = schemaTwoPreferences()
        val schemaThree = schemaTwo + ("settings_schema" to 3)
        val corruptSnapshots = listOf(
            schemaTwo - "hide_article_rewards",
            schemaTwo + ("hide_article_rewards" to "true"),
            schemaThree - "hide_article_ai_summary",
            schemaThree + ("hide_article_ai_summary" to "false"),
            schemaThree + ("settings_schema" to 6),
        )
        corruptSnapshots.forEach { corrupt ->
            val storage = MemoryStorage(schemaTwo).apply { commitSucceeds = false }
            assertNull(storage.store().reload().settings)
            storage.values = corrupt
            storage.commitSucceeds = true
            val writesBeforeRetry = storage.writes.size
            val rebuilt = storage.store()

            assertNull(rebuilt.reload().settings)
            assertNull(rebuilt.setToggle(Toggle.AI_SUMMARY, false).settings)
            assertNull(rebuilt.setRefreshMinutes(5).settings)
            assertEquals(writesBeforeRetry, storage.writes.size)
            assertEquals(corrupt, storage.values)
            assertEquals(0, storage.writerCreations)
        }
    }

    @Test
    fun pendingSchemaTwoMigrationCannotOverwriteNewerConfirmedAiChoice() {
        val legacy = schemaTwoPreferences()
        val storage = MemoryStorage(legacy).apply { commitSucceeds = false }
        val first = storage.store()
        assertNull(first.reload().settings)
        storage.commitSucceeds = true
        val second = storage.store()
        assertEquals(42L, second.reload().settings!!.revision)
        val newer = second.setToggle(Toggle.AI_SUMMARY, false).settings!!
        val writesBeforeRetry = storage.writes.size

        assertEquals(newer, first.reload().settings)
        assertEquals(newer, ModuleSettings.fromPreferences(storage.values))
        assertEquals(writesBeforeRetry, storage.writes.size)
        assertEquals(43L, newer.revision)
        val refreshed = first.setRefreshMinutes(5).settings!!
        assertEquals(44L, refreshed.revision)
        assertFalse(refreshed.raw(Toggle.AI_SUMMARY))
        assertFalse(refreshed.raw(Toggle.MASTER))
        assertEquals(legacy["settings_writer_id"], refreshed.writerId)
        assertEquals(listOf(42L, 42L, 43L, 44L), storage.writes.map { it["settings_revision"] })
        assertEquals(0, storage.writerCreations)
    }

    @Test
    fun currentAiChoicePersistsIndependentlyAndNoOpsDoNotAddRevisions() {
        val original = schemaFivePreferences()
        val storage = MemoryStorage(original)
        val store = storage.store()
        val initial = store.reload().settings!!
        assertTrue(initial.raw(Toggle.AI_SUMMARY))
        assertFalse(initial.enabled(Toggle.AI_SUMMARY))
        assertTrue(storage.writes.isEmpty())

        val rewardsOff = store.setToggle(Toggle.ARTICLE_REWARDS, false).settings!!
        assertTrue(rewardsOff.raw(Toggle.AI_SUMMARY))
        val masterOn = store.setToggle(Toggle.MASTER, true).settings!!
        assertTrue(masterOn.enabled(Toggle.AI_SUMMARY))
        val aiOff = store.setToggle(Toggle.AI_SUMMARY, false).settings!!
        assertTrue(aiOff.raw(Toggle.MASTER))
        assertFalse(aiOff.raw(Toggle.ARTICLE_REWARDS))
        assertFalse(aiOff.enabled(Toggle.AI_SUMMARY))
        repeat(3) { assertEquals(aiOff, store.setToggle(Toggle.AI_SUMMARY, false).settings) }
        assertEquals(aiOff, storage.store().reload().settings)
        assertEquals(original + mapOf(
            "settings_revision" to 44L,
            "hide_article_rewards" to false,
            "master_enabled" to true,
            "hide_article_ai_summary" to false,
        ), storage.values)
        assertEquals(listOf(42L, 43L, 44L), storage.writes.map { it["settings_revision"] })
        assertEquals(0, storage.writerCreations)
    }

    @Test
    fun failedCurrentAiWriteIsNotConfirmedFromSharedMemoryAcrossInstances() {
        val original = schemaFivePreferences() + ("hide_article_ai_summary" to false)
        val storage = MemoryStorage(original)
        val first = storage.store()
        val confirmed = first.reload().settings
        storage.commitSucceeds = false

        assertEquals(confirmed, first.setToggle(Toggle.AI_SUMMARY, true).settings)
        val failedAgain = storage.store().reload()
        assertEquals(confirmed, failedAgain.settings)
        assertTrue(failedAgain.message.contains("失败"))
        assertFalse(failedAgain.settings!!.raw(Toggle.AI_SUMMARY))
        assertEquals(true, storage.values["hide_article_ai_summary"])
        storage.commitSucceeds = true
        val recovered = storage.store().reload().settings!!
        assertTrue(recovered.raw(Toggle.AI_SUMMARY))
        assertEquals(42L, recovered.revision)
        assertEquals(3, storage.writes.size)
        assertTrue(storage.writes.all { it == storage.writes.first() })
        assertEquals(0, storage.writerCreations)
    }

    private class MemoryStorage(initial: Map<String, *>) {
        var values: Map<String, *> = initial.toMap()
        val writes = mutableListOf<Map<String, Any>>()
        var commitSucceeds = true
        var keepOldValues = false
        var writerCreations = 0
        var writeTransform: (Map<String, Any>) -> Map<String, *> = { it }
        private val state = SettingsStoreState()

        fun store(): SettingsStore = SettingsStore(
            state = state,
            read = { values.toMap() },
            commit = { next ->
                writes += next.toMap()
                // commit=false 的共享内存污染与仍保留旧值两种情况都必须重试确认。
                if (!keepOldValues) values = writeTransform(next.toMap())
                commitSucceeds
            },
            newWriterId = {
                writerCreations += 1
                NEW_WRITER
            },
        )
    }

    private companion object {
        const val NEW_WRITER = "550e8400-e29b-41d4-a716-446655440000"
    }
}
