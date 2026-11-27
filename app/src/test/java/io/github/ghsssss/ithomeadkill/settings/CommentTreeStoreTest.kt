package io.github.ghsssss.ithomeadkill.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentTreeStoreTest {
    @Test
    fun everyLegacySchemaMigratesOnceWithWriterMinutesRevisionAndEveryOldChoicePreserved() {
        legacySnapshots().forEach { base ->
            SettingsContract.REFRESH_MINUTES.forEach { minutes ->
                val legacy = base + mapOf("auto_refresh_minutes" to minutes, RETIRED_KEY to true)
                val storage = MemoryStorage(legacy)
                val expected = migratedPreferences(legacy)
                val store = storage.store()

                val migrated = store.reload()

                assertNotNull(migrated.settings)
                assertFalse(migrated.error)
                assertFalse(migrated.busy)
                assertEquals(expected, storage.values)
                assertEquals(ModuleSettings.fromPreferences(expected), migrated.settings)
                assertEquals(base["settings_writer_id"], migrated.settings!!.writerId)
                assertEquals(minutes, migrated.settings.autoRefreshMinutes)
                assertEquals(42L, migrated.settings.revision)
                assertFalse(migrated.settings.raw(Toggle.COMMENT_TREE))
                assertEquals(listOf(expected), storage.writes)
                repeat(3) { assertEquals(migrated.settings, storage.store().reload().settings) }
                assertEquals(listOf(expected), storage.writes)
                assertEquals(0, storage.writerCreations)
            }
        }
    }

    @Test
    fun schemaFourMigrationPreservesEachBooleanChoiceIncludingCommissionOnAndMasterOn() {
        schemaFourPreferences().filterValues { it is Boolean }.keys.forEach { key ->
            listOf(false, true).forEach { value ->
                val legacy = schemaFourPreferences() + (key to value)
                val storage = MemoryStorage(legacy)
                val migrated = storage.store().reload()
                assertEquals(migratedPreferences(legacy), storage.values)
                assertEquals(value, migrated.settings!!.toPreferences()[key])
                assertEquals(42L, migrated.settings.revision)
                assertEquals(1, storage.writes.size)
                assertEquals(0, storage.writerCreations)
            }
        }
    }

    @Test
    fun noOpEntryPointsStillMigrateAllFourLegacySchemasWithJustOneRevision() {
        val actions: List<(SettingsStore) -> SettingsState> = listOf(
            { it.setToggle(Toggle.COMMENT_TREE, false) },
            { it.setToggle(Toggle.MASTER, false) },
            { it.setRefreshMinutes(10) },
        )
        legacySnapshots().forEach { legacy ->
            actions.forEach { action ->
                val storage = MemoryStorage(legacy)
                val migrated = action(storage.store())
                assertFalse(migrated.error)
                assertEquals(ModuleSettings.fromPreferences(migratedPreferences(legacy)), migrated.settings)
                assertEquals(listOf(migratedPreferences(legacy)), storage.writes)
                assertEquals(0, storage.writerCreations)
            }
        }
    }

    @Test
    fun selectingTreeBeforeLegacyReloadCombinesMigrationAndNewChoiceInOneRevision() {
        legacySnapshots().forEach { legacy ->
            val storage = MemoryStorage(legacy)
            val expected = migratedPreferences(legacy, tree = true)
            val selected = storage.store().setToggle(Toggle.COMMENT_TREE, true)
            assertFalse(selected.error)
            assertEquals(ModuleSettings.fromPreferences(expected), selected.settings)
            assertTrue(selected.settings!!.raw(Toggle.COMMENT_TREE))
            assertFalse(selected.settings.enabled(Toggle.COMMENT_TREE))
            assertEquals(expected, storage.values)
            assertEquals(selected.settings, storage.store().reload().settings)
            assertEquals(listOf(expected), storage.writes)
            assertEquals(0, storage.writerCreations)
        }
    }

    @Test
    fun currentTreeChoicePersistsIndependentlyAndMasterOnlyGatesEffectiveValue() {
        val original = schemaFivePreferences() + ("hide_article_commission" to true)
        val storage = MemoryStorage(original)
        val initial = storage.store().reload().settings!!
        assertTrue(storage.writes.isEmpty())
        val selected = storage.store().setToggle(Toggle.COMMENT_TREE, true).settings!!
        assertTrue(selected.raw(Toggle.COMMENT_TREE))
        assertFalse(selected.enabled(Toggle.COMMENT_TREE))
        assertEquals(initial.toggles - Toggle.COMMENT_TREE, selected.toggles - Toggle.COMMENT_TREE)
        val active = storage.store().setToggle(Toggle.MASTER, true).settings!!
        assertTrue(active.enabled(Toggle.COMMENT_TREE))
        val paused = storage.store().setToggle(Toggle.MASTER, false).settings!!
        assertTrue(paused.raw(Toggle.COMMENT_TREE))
        assertFalse(paused.enabled(Toggle.COMMENT_TREE))
        repeat(3) { assertEquals(paused, storage.store().setToggle(Toggle.COMMENT_TREE, true).settings) }
        val restored = storage.store().setToggle(Toggle.MASTER, true).settings!!
        assertTrue(restored.enabled(Toggle.COMMENT_TREE))
        val off = storage.store().setToggle(Toggle.COMMENT_TREE, false).settings!!
        assertFalse(off.enabled(Toggle.COMMENT_TREE))
        assertEquals(original + mapOf("settings_revision" to 46L, "master_enabled" to true), storage.values)
        assertEquals(listOf(42L, 43L, 44L, 45L, 46L), storage.writes.map { it["settings_revision"] })
        assertEquals(0, storage.writerCreations)
    }

    @Test
    fun missingOrBadNewKeyAndUnknownSchemaSixAreRejectedWithoutCommitEvenWithRetiredKey() {
        val current = schemaFivePreferences() + (RETIRED_KEY to true)
        val invalidSnapshots = listOf(current - TREE_KEY, current + ("settings_schema" to 6)) +
            listOf<Any?>(null, "true", "false", 0, 1L, 1.0f, setOf("true"), listOf(true)).map {
                current + (TREE_KEY to it)
            }
        invalidSnapshots.forEach { invalid ->
            val storage = MemoryStorage(invalid)
            val states = listOf(
                storage.store().reload(),
                storage.store().setToggle(Toggle.COMMENT_TREE, true),
                storage.store().setRefreshMinutes(5),
            )
            states.forEach { assertNull(it.settings); assertTrue(it.error) }
            assertEquals(invalid, storage.values)
            assertTrue(storage.writes.isEmpty())
            assertEquals(0, storage.writerCreations)
        }
    }

    @Test
    fun failedMigrationCommitStaysUnpublishedAndRebuiltInstancesRetryIdenticalRevision() {
        legacySnapshots().forEach { legacy ->
            listOf(false, true).forEach { keepOldValues ->
                val storage = MemoryStorage(legacy).apply {
                    commitSucceeds = false
                    this.keepOldValues = keepOldValues
                }
                val expected = migratedPreferences(legacy)
                repeat(2) {
                    val rebuilt = storage.store()
                    val failed = rebuilt.reload()
                    assertNull(failed.settings)
                    assertNull(rebuilt.settings)
                    assertTrue(failed.error)
                    assertFalse(failed.busy)
                    assertEquals(if (keepOldValues) legacy else expected, storage.values)
                }
                storage.commitSucceeds = true
                storage.keepOldValues = false
                val recovered = storage.store().reload()
                assertFalse(recovered.error)
                assertEquals(ModuleSettings.fromPreferences(expected), recovered.settings)
                assertEquals(expected, storage.values)
                assertEquals(listOf(expected, expected, expected), storage.writes)
                assertEquals(0, storage.writerCreations)
            }
        }
    }

    @Test
    fun failedSelectedTreeMigrationRetainsPendingChoiceAcrossInstancesWithoutDoubleIncrement() {
        legacySnapshots().forEach { legacy ->
            listOf(false, true).forEach { keepOldValues ->
                val storage = MemoryStorage(legacy).apply {
                    commitSucceeds = false
                    this.keepOldValues = keepOldValues
                }
                val expected = migratedPreferences(legacy, tree = true)
                val failed = storage.store().setToggle(Toggle.COMMENT_TREE, true)
                assertNull(failed.settings)
                assertTrue(failed.error)
                assertNull(storage.store().reload().settings)
                storage.commitSucceeds = true
                storage.keepOldValues = false
                val recovered = storage.store().reload()
                assertEquals(ModuleSettings.fromPreferences(expected), recovered.settings)
                assertEquals(expected, storage.values)
                assertEquals(listOf(expected, expected, expected), storage.writes)
                assertEquals(0, storage.writerCreations)
            }
        }
    }

    @Test
    fun successfulCommitWithNonExactReadbackNeverConfirmsMigrationAndRetryReusesRevision() {
        val transforms: List<(Map<String, Any>) -> Map<String, *>> = listOf(
            { it - TREE_KEY },
            { it + (TREE_KEY to "false") },
            { it + (TREE_KEY to true) },
            { (it - TREE_KEY) + ("settings_schema" to 4) },
            { it + (RETIRED_KEY to true) },
            { it + ("settings_writer_id" to NEW_WRITER) },
            { it + ("auto_refresh_minutes" to 5) },
            { it + ("hide_article_recommendations" to false) },
        )
        legacySnapshots().forEach { legacy ->
            transforms.forEach { transform ->
                val storage = MemoryStorage(legacy).apply { writeTransform = transform }
                val expected = migratedPreferences(legacy)
                val failed = storage.store().reload()
                assertNull(failed.settings)
                assertTrue(failed.error)
                assertTrue(failed.message.contains("校验失败"))
                assertEquals(listOf(expected), storage.writes)
                assertEquals(transform(expected), storage.values)
                // 回到仍属于此 pending 的旧基线，不能把别人的不同成功快照强行改回。
                storage.values = legacy
                storage.writeTransform = { it }
                val recovered = storage.store().reload()
                assertEquals(ModuleSettings.fromPreferences(expected), recovered.settings)
                assertEquals(listOf(expected, expected), storage.writes)
                assertEquals(expected, storage.values)
                assertEquals(0, storage.writerCreations)
            }
        }
    }

    @Test
    fun readbackExceptionAndThenFalseCommitCannotPublishPendingSchemaFiveMemory() {
        legacySnapshots().forEach { legacy ->
            val storage = MemoryStorage(legacy).apply { failReadback = true }
            val expected = migratedPreferences(legacy)
            val failed = storage.store().reload()
            assertNull(failed.settings)
            assertTrue(failed.error)
            assertEquals(expected, storage.values)
            storage.failReadback = false
            storage.commitSucceeds = false
            assertNull(storage.store().reload().settings)
            storage.commitSucceeds = true
            val recovered = storage.store().reload()
            assertEquals(ModuleSettings.fromPreferences(expected), recovered.settings)
            assertEquals(listOf(expected, expected, expected), storage.writes)
            assertEquals(0, storage.writerCreations)
        }
    }

    @Test
    fun commitExceptionsBeforeOrAfterMemoryMutationKeepPendingChoiceForAnotherInstance() {
        legacySnapshots().forEach { legacy ->
            listOf(false, true).forEach { keepOldValues ->
                val storage = MemoryStorage(legacy).apply {
                    throwOnCommit = true
                    this.keepOldValues = keepOldValues
                }
                val expected = migratedPreferences(legacy, tree = true)
                val failed = storage.store().setToggle(Toggle.COMMENT_TREE, true)
                assertNull(failed.settings)
                assertTrue(failed.error)
                assertNull(storage.store().reload().settings)
                storage.throwOnCommit = false
                storage.keepOldValues = false
                val recovered = storage.store().reload()
                assertEquals(ModuleSettings.fromPreferences(expected), recovered.settings)
                assertEquals(expected, storage.values)
                assertEquals(listOf(expected, expected, expected), storage.writes)
                assertEquals(0, storage.writerCreations)
            }
        }
    }

    @Test
    fun pendingMigrationCannotOverwriteMalformedLegacyOrCurrentTreeOrFutureSchema() {
        val legacy = schemaFourPreferences() + ("hide_article_commission" to true)
        val current = migratedPreferences(legacy)
        val corruptSnapshots = listOf(
            legacy - "hide_article_recommendations",
            legacy + ("hide_article_commission" to "true"),
            legacy + ("auto_refresh_minutes" to 10L),
            current - TREE_KEY,
            current + (TREE_KEY to "false"),
            current + ("settings_schema" to 6),
        )
        corruptSnapshots.forEach { corrupt ->
            val storage = MemoryStorage(legacy).apply { commitSucceeds = false; keepOldValues = true }
            assertNull(storage.store().reload().settings)
            assertEquals(1, storage.writes.size)
            storage.values = corrupt
            storage.commitSucceeds = true
            storage.keepOldValues = false
            val states = listOf(
                storage.store().reload(),
                storage.store().setToggle(Toggle.COMMENT_TREE, true),
                storage.store().setRefreshMinutes(5),
            )
            states.forEach { assertNull(it.settings); assertTrue(it.error) }
            assertEquals(1, storage.writes.size)
            assertEquals(corrupt, storage.values)
            assertEquals(0, storage.writerCreations)
        }
    }

    @Test
    fun anotherInstanceSuccessfulCommitBecomesNewBaseInsteadOfBeingOverwrittenByOldPending() {
        legacySnapshots().forEach { legacy ->
            val storage = MemoryStorage(legacy).apply { commitSucceeds = false; keepOldValues = true }
            val first = storage.store()
            assertNull(first.setToggle(Toggle.COMMENT_TREE, true).settings)
            storage.commitSucceeds = true
            storage.keepOldValues = false
            val other = storage.store(SettingsStoreState())
            assertNotNull(other.reload().settings)
            val confirmed = other.setToggle(Toggle.MASTER, true).settings!!
            assertFalse(confirmed.raw(Toggle.COMMENT_TREE))
            val writesBeforeRetry = storage.writes.toList()

            assertEquals(confirmed, first.reload().settings)
            assertEquals(writesBeforeRetry, storage.writes)
            assertEquals(confirmed.toPreferences(), storage.values)
            val edited = first.setRefreshMinutes(5).settings!!
            assertTrue(edited.raw(Toggle.MASTER))
            assertFalse(edited.raw(Toggle.COMMENT_TREE))
            assertEquals(confirmed.revision + 1L, edited.revision)
            assertEquals(0, storage.writerCreations)
        }
    }

    @Test
    fun differentWriterAtSameRevisionCannotBeOverwrittenByAnOldPendingMigration() {
        val legacy = schemaFourPreferences()
        val storage = MemoryStorage(legacy).apply { commitSucceeds = false; keepOldValues = true }
        assertNull(storage.store().setToggle(Toggle.COMMENT_TREE, true).settings)
        val external = schemaFivePreferences(42L) + mapOf(
            "settings_writer_id" to NEW_WRITER,
            "master_enabled" to true,
        )
        storage.values = external
        storage.commitSucceeds = true
        storage.keepOldValues = false
        val loaded = storage.store().reload()
        assertEquals(ModuleSettings.fromPreferences(external), loaded.settings)
        assertEquals(external, storage.values)
        assertEquals(1, storage.writes.size)
        assertEquals(0, storage.writerCreations)
    }

    @Test
    fun legacyRollbackClearsPreviouslyConfirmedSettingsUntilPendingCommitAndReadbackSucceed() {
        legacySnapshots().forEach { legacy ->
            val original = migratedPreferences(legacy) + ("settings_revision" to 41L)
            val storage = MemoryStorage(original)
            val first = storage.store()
            val confirmed = first.reload().settings!!
            storage.commitSucceeds = false
            storage.keepOldValues = true
            assertEquals(confirmed, first.setToggle(Toggle.COMMENT_TREE, true).settings)
            storage.values = legacy
            val failed = storage.store().reload()
            assertNull(failed.settings)
            assertNull(first.settings)
            assertTrue(failed.error)
            storage.commitSucceeds = true
            storage.keepOldValues = false
            val expected = migratedPreferences(legacy, tree = true)
            assertEquals(ModuleSettings.fromPreferences(expected), storage.store().reload().settings)
            assertEquals(listOf(expected, expected, expected), storage.writes)
            assertEquals(expected, storage.values)
            assertEquals(0, storage.writerCreations)
        }
    }

    @Test
    fun migrationAtRevisionLimitNeverOverflowsAndLastPossibleMigrationIsNotRepeated() {
        val actions: List<(SettingsStore) -> SettingsState> = listOf(
            { it.reload() },
            { it.setToggle(Toggle.COMMENT_TREE, false) },
            { it.setToggle(Toggle.COMMENT_TREE, true) },
            { it.setRefreshMinutes(10) },
            { it.setRefreshMinutes(5) },
        )
        legacySnapshots(Long.MAX_VALUE).forEach { legacy ->
            actions.forEach { action ->
                val storage = MemoryStorage(legacy)
                val failed = action(storage.store())
                assertNull(failed.settings)
                assertTrue(failed.error)
                assertEquals(legacy, storage.values)
                assertTrue(storage.writes.isEmpty())
                assertEquals(0, storage.writerCreations)
            }
        }
        legacySnapshots(Long.MAX_VALUE - 1L).forEach { legacy ->
            val storage = MemoryStorage(legacy)
            val migrated = storage.store().reload()
            assertEquals(Long.MAX_VALUE, migrated.settings!!.revision)
            assertEquals(migrated.settings, storage.store().reload().settings)
            assertEquals(migrated.settings, storage.store().setToggle(Toggle.COMMENT_TREE, false).settings)
            val failed = storage.store().setToggle(Toggle.COMMENT_TREE, true)
            assertEquals(migrated.settings, failed.settings)
            assertTrue(failed.error)
            assertEquals(listOf(migratedPreferences(legacy)), storage.writes)
            assertEquals(0, storage.writerCreations)
        }
    }

    private fun legacySnapshots(revision: Long = 41L): List<Map<String, Any>> =
        legacySettingsSnapshots(revision) + listOf(
            schemaFourPreferences(revision),
            schemaFourPreferences(revision) + ("hide_article_commission" to true),
        )

    private fun migratedPreferences(legacy: Map<String, *>, tree: Boolean = false): Map<String, Any> =
        legacy.filterKeys { it != "hide_event_calendar" && it != RETIRED_KEY && it != TREE_KEY }
            .mapValues { it.value!! } + mapOf(
            "settings_schema" to 5,
            "settings_revision" to (legacy["settings_revision"] as Long) + 1L,
            "hide_article_rewards" to (legacy["hide_article_rewards"] ?: false),
            "hide_article_commission" to if (legacy["settings_schema"] == 4) legacy["hide_article_commission"]!! else false,
            TREE_KEY to tree,
        )

    private class MemoryStorage(initial: Map<String, *>) {
        var values: Map<String, *> = initial.toMap()
        val writes = mutableListOf<Map<String, Any>>()
        var commitSucceeds = true
        var keepOldValues = false
        var throwOnCommit = false
        var failReadback = false
        private var nextReadFails = false
        var writerCreations = 0
        var writeTransform: (Map<String, Any>) -> Map<String, *> = { it }
        private val state = SettingsStoreState()

        fun store(sharedState: SettingsStoreState = state): SettingsStore = SettingsStore(
            state = sharedState,
            read = {
                if (nextReadFails) {
                    nextReadFails = false
                    throw IllegalStateException("readback unavailable")
                }
                values.toMap()
            },
            commit = { next ->
                writes += next.toMap()
                if (!keepOldValues) values = writeTransform(next.toMap())
                if (throwOnCommit) throw IllegalStateException("commit unavailable")
                if (failReadback) nextReadFails = true
                commitSucceeds
            },
            newWriterId = { writerCreations += 1; NEW_WRITER },
        )
    }

    private companion object {
        const val TREE_KEY = "comment_tree_lines"
        const val RETIRED_KEY = "comment_thread_lines"
        const val NEW_WRITER = "550e8400-e29b-41d4-a716-446655440000"
    }
}
