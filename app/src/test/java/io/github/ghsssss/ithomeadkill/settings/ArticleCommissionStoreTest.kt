package io.github.ghsssss.ithomeadkill.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleCommissionStoreTest {
    @Test
    fun everyLegacySchemaMigratesOncePreservingWriterMinutesAndAllOldPersistedChoices() {
        legacySettingsSnapshots().forEach { base ->
            SettingsContract.REFRESH_MINUTES.forEach { minutes ->
                val legacy = base + mapOf("auto_refresh_minutes" to minutes, COMMISSION_KEY to "true")
                val storage = MemoryStorage(legacy)
                val expected = migratedPreferences(legacy)
                val store = storage.store()

                val state = store.reload()

                assertNotNull(state.settings)
                assertFalse(state.error)
                assertFalse(state.busy)
                assertEquals(expected, storage.values)
                assertEquals(ModuleSettings.fromPreferences(expected), state.settings)
                assertEquals(legacy["settings_writer_id"], state.settings!!.writerId)
                assertEquals(minutes, state.settings.autoRefreshMinutes)
                assertTrue(state.settings.raw(Toggle.ARTICLE_RECOMMENDATIONS))
                assertFalse(state.settings.raw(Toggle.ARTICLE_COMMISSION))
                repeat(3) { assertEquals(state.settings, storage.store().reload().settings) }
                assertEquals(state.settings, store.setToggle(Toggle.ARTICLE_COMMISSION, false).settings)
                assertEquals(state.settings, store.setRefreshMinutes(minutes).settings)
                assertEquals(listOf(expected), storage.writes)
                assertEquals(0, storage.writerCreations)
            }
        }
    }

    @Test
    fun everyLegacyNoOpEntryPointStillMigratesBeforeReloadWithoutAnExtraRevision() {
        val actions: List<(SettingsStore) -> SettingsState> = listOf(
            { it.setToggle(Toggle.ARTICLE_COMMISSION, false) },
            { it.setToggle(Toggle.MASTER, false) },
            { it.setRefreshMinutes(10) },
        )
        legacySettingsSnapshots().forEach { legacy ->
            actions.forEach { action ->
                val storage = MemoryStorage(legacy)
                val expected = migratedPreferences(legacy)

                val state = action(storage.store())

                assertNotNull(state.settings)
                assertFalse(state.error)
                assertEquals(expected, storage.values)
                assertEquals(42L, state.settings!!.revision)
                assertEquals(listOf(expected), storage.writes)
                assertEquals(0, storage.writerCreations)
            }
        }
    }

    @Test
    fun selectingCommissionBeforeLegacyReloadCombinesMigrationAndChoiceInOneRevision() {
        legacySettingsSnapshots().forEach { legacy ->
            val storage = MemoryStorage(legacy)
            val expected = migratedPreferences(legacy, commission = true)
            val store = storage.store()

            val state = store.setToggle(Toggle.ARTICLE_COMMISSION, true)

            assertNotNull(state.settings)
            assertFalse(state.error)
            assertEquals(expected, storage.values)
            assertTrue(state.settings!!.raw(Toggle.ARTICLE_COMMISSION))
            assertFalse(state.settings.enabled(Toggle.ARTICLE_COMMISSION))
            assertEquals(state.settings, storage.store().reload().settings)
            assertEquals(state.settings, store.setToggle(Toggle.ARTICLE_COMMISSION, true).settings)
            assertEquals(listOf(expected), storage.writes)
            assertEquals(0, storage.writerCreations)
        }
    }

    @Test
    fun currentCommissionChoicePersistsIndependentlyAndMasterOnlyGatesItsEffectiveValue() {
        val original = schemaFivePreferences()
        val storage = MemoryStorage(original)
        val store = storage.store()
        val initial = store.reload().settings!!
        assertTrue(storage.writes.isEmpty())

        val selected = store.setToggle(Toggle.ARTICLE_COMMISSION, true).settings!!
        assertEquals(initial.toggles - Toggle.ARTICLE_COMMISSION, selected.toggles - Toggle.ARTICLE_COMMISSION)
        assertTrue(selected.raw(Toggle.ARTICLE_COMMISSION))
        assertFalse(selected.enabled(Toggle.ARTICLE_COMMISSION))
        val active = store.setToggle(Toggle.MASTER, true).settings!!
        assertTrue(active.enabled(Toggle.ARTICLE_COMMISSION))
        val paused = store.setToggle(Toggle.MASTER, false).settings!!
        assertTrue(paused.raw(Toggle.ARTICLE_COMMISSION))
        assertFalse(paused.enabled(Toggle.ARTICLE_COMMISSION))
        repeat(3) { assertEquals(paused, storage.store().setToggle(Toggle.ARTICLE_COMMISSION, true).settings) }
        val restored = storage.store().setToggle(Toggle.MASTER, true).settings!!
        assertTrue(restored.enabled(Toggle.ARTICLE_COMMISSION))
        val off = store.setToggle(Toggle.ARTICLE_COMMISSION, false).settings!!
        assertFalse(off.enabled(Toggle.ARTICLE_COMMISSION))
        assertEquals(original + mapOf(
            "settings_revision" to 46L,
            "master_enabled" to true,
        ), storage.values)
        assertEquals(listOf(42L, 43L, 44L, 45L, 46L), storage.writes.map { it["settings_revision"] })
        assertEquals(0, storage.writerCreations)
    }

    @Test
    fun failedMigrationCommitNeverPublishesMemoryAndRebuiltInstancesRetryIdenticalRevision() {
        legacySettingsSnapshots().forEach { legacy ->
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
                assertEquals(listOf(expected, expected, expected), storage.writes)
                assertEquals(expected, storage.values)
                assertEquals(0, storage.writerCreations)
            }
        }
    }

    @Test
    fun failedCommissionMigrationRetainsSelectedValueAcrossRecreationAndOnlyIncrementsOnce() {
        legacySettingsSnapshots().forEach { legacy ->
            listOf(false, true).forEach { keepOldValues ->
                val storage = MemoryStorage(legacy).apply {
                    commitSucceeds = false
                    this.keepOldValues = keepOldValues
                }
                val expected = migratedPreferences(legacy, commission = true)
                val failed = storage.store().setToggle(Toggle.ARTICLE_COMMISSION, true)
                assertNull(failed.settings)
                assertTrue(failed.error)
                assertNull(storage.store().reload().settings)
                storage.commitSucceeds = true
                storage.keepOldValues = false

                val recovered = storage.store().reload()

                assertFalse(recovered.error)
                assertEquals(ModuleSettings.fromPreferences(expected), recovered.settings)
                assertTrue(recovered.settings!!.raw(Toggle.ARTICLE_COMMISSION))
                assertFalse(recovered.settings.enabled(Toggle.ARTICLE_COMMISSION))
                assertEquals(listOf(expected, expected, expected), storage.writes)
                assertEquals(expected, storage.values)
                assertEquals(0, storage.writerCreations)
            }
        }
    }

    @Test
    fun pendingRetryAgainstLegacyRollbackClearsPreviouslyConfirmedSettingsUntilCommitAndReadback() {
        legacySettingsSnapshots().forEach { legacy ->
            val original = migratedPreferences(legacy) + ("settings_revision" to 41L)
            val storage = MemoryStorage(original)
            val first = storage.store()
            val confirmed = first.reload().settings
            assertNotNull(confirmed)
            storage.commitSucceeds = false
            storage.keepOldValues = true
            assertEquals(confirmed, first.setToggle(Toggle.ARTICLE_COMMISSION, true).settings)
            storage.values = legacy

            val failed = storage.store().reload()

            assertNull(failed.settings)
            assertNull(first.settings)
            assertTrue(failed.error)
            storage.commitSucceeds = true
            storage.keepOldValues = false
            val expected = migratedPreferences(legacy, commission = true)
            val recovered = storage.store().reload()
            assertEquals(ModuleSettings.fromPreferences(expected), recovered.settings)
            assertEquals(listOf(expected, expected, expected), storage.writes)
            assertEquals(expected, storage.values)
            assertEquals(0, storage.writerCreations)
        }
    }

    @Test
    fun successfulCommitWithWrongReadbackIsNotMigrationSuccessAndRetryKeepsSameRevision() {
        val transforms: List<(Map<String, Any>) -> Map<String, *>> = listOf(
            { it - COMMISSION_KEY },
            { it + (COMMISSION_KEY to "false") },
            { it + (COMMISSION_KEY to true) },
            { (it - COMMISSION_KEY) + ("settings_schema" to 3) },
            { it + ("settings_writer_id" to NEW_WRITER) },
            { it + ("auto_refresh_minutes" to 5) },
            { it + ("hide_article_recommendations" to false) },
        )
        legacySettingsSnapshots().forEach { legacy ->
            transforms.forEach { transform ->
                val storage = MemoryStorage(legacy).apply { writeTransform = transform }
                val expected = migratedPreferences(legacy)
                val failed = storage.store().reload()
                assertNull(failed.settings)
                assertTrue(failed.error)
                assertTrue(failed.message.contains("校验失败"))
                assertEquals(listOf(expected), storage.writes)
                assertEquals(transform(expected), storage.values)
                // 恢复仍属于本次 pending 的旧基线；不能从错误回读直接确认提交。
                storage.values = legacy
                storage.writeTransform = { it }

                val recovered = storage.store().reload()

                assertFalse(recovered.error)
                assertEquals(ModuleSettings.fromPreferences(expected), recovered.settings)
                assertEquals(listOf(expected, expected), storage.writes)
                assertEquals(expected, storage.values)
                assertEquals(0, storage.writerCreations)
            }
        }
    }

    @Test
    fun migrationReadbackExceptionAndSubsequentFalseCommitCannotConfirmThePendingMemorySnapshot() {
        legacySettingsSnapshots().forEach { legacy ->
            val storage = MemoryStorage(legacy).apply { failReadback = true }
            val expected = migratedPreferences(legacy)
            val failed = storage.store().reload()
            assertNull(failed.settings)
            assertTrue(failed.error)
            assertEquals(expected, storage.values)
            storage.failReadback = false
            storage.commitSucceeds = false
            val failedAgain = storage.store().reload()
            assertNull(failedAgain.settings)
            assertTrue(failedAgain.error)
            storage.commitSucceeds = true

            val recovered = storage.store().reload()

            assertFalse(recovered.error)
            assertEquals(ModuleSettings.fromPreferences(expected), recovered.settings)
            assertEquals(listOf(expected, expected, expected), storage.writes)
            assertEquals(expected, storage.values)
            assertEquals(0, storage.writerCreations)
        }
    }

    @Test
    fun pendingSchemaThreeMigrationCannotOverwriteMalformedOldFieldsNewCommissionOrSchemaSix() {
        val legacy = schemaThreePreferences()
        val current = migratedPreferences(legacy)
        val corruptSnapshots = listOf(
            legacy - "hide_article_recommendations",
            legacy + ("hide_article_ai_summary" to "true"),
            legacy + ("auto_refresh_minutes" to 10L),
            current - COMMISSION_KEY,
            current + (COMMISSION_KEY to "false"),
            current + ("settings_schema" to 6),
        )
        corruptSnapshots.forEach { corrupt ->
            val storage = MemoryStorage(legacy).apply { commitSucceeds = false }
            assertNull(storage.store().reload().settings)
            storage.values = corrupt
            storage.commitSucceeds = true
            val rebuilt = storage.store()

            assertNull(rebuilt.reload().settings)
            assertNull(rebuilt.setToggle(Toggle.ARTICLE_COMMISSION, true).settings)
            assertNull(rebuilt.setRefreshMinutes(5).settings)
            assertEquals(1, storage.writes.size)
            assertEquals(corrupt, storage.values)
            assertEquals(0, storage.writerCreations)
        }
    }

    @Test
    fun migrationRevisionBoundsApplyToSchemaThreeTooWithoutOverflowOrRepeatedUpgrade() {
        val actions: List<(SettingsStore) -> SettingsState> = listOf(
            { it.reload() },
            { it.setToggle(Toggle.ARTICLE_COMMISSION, false) },
            { it.setToggle(Toggle.ARTICLE_COMMISSION, true) },
            { it.setRefreshMinutes(10) },
            { it.setRefreshMinutes(5) },
        )
        legacySettingsSnapshots(Long.MAX_VALUE).forEach { legacy ->
            actions.forEach { action ->
                val storage = MemoryStorage(legacy)
                val state = action(storage.store())
                assertNull(state.settings)
                assertTrue(state.error)
                assertEquals(legacy, storage.values)
                assertTrue(storage.writes.isEmpty())
                assertEquals(0, storage.writerCreations)
            }
        }
        legacySettingsSnapshots(Long.MAX_VALUE - 1L).forEach { legacy ->
            val storage = MemoryStorage(legacy)
            val store = storage.store()
            val expected = migratedPreferences(legacy)
            val state = store.reload()
            assertNotNull(state.settings)
            assertEquals(Long.MAX_VALUE, state.settings!!.revision)
            assertEquals(state.settings, storage.store().reload().settings)
            assertEquals(state.settings, store.setToggle(Toggle.ARTICLE_COMMISSION, false).settings)
            val failed = store.setToggle(Toggle.ARTICLE_COMMISSION, true)
            assertEquals(state.settings, failed.settings)
            assertTrue(failed.error)
            assertEquals(listOf(expected), storage.writes)
            assertEquals(expected, storage.values)
            assertEquals(0, storage.writerCreations)
        }
    }

    private fun migratedPreferences(legacy: Map<String, Any>, commission: Boolean = false): Map<String, Any> =
        (legacy - "hide_event_calendar" - COMMISSION_KEY) + mapOf(
            "settings_schema" to 5,
            "comment_tree_lines" to false,
            "settings_revision" to (legacy["settings_revision"] as Long) + 1L,
            "hide_article_rewards" to (legacy["hide_article_rewards"] ?: false),
            COMMISSION_KEY to commission,
        )

    private class MemoryStorage(initial: Map<String, *>) {
        var values: Map<String, *> = initial.toMap()
        val writes = mutableListOf<Map<String, Any>>()
        var commitSucceeds = true
        var keepOldValues = false
        var writerCreations = 0
        var failReadback = false
        private var nextReadFails = false
        var writeTransform: (Map<String, Any>) -> Map<String, *> = { it }
        private val state = SettingsStoreState()

        fun store(): SettingsStore = SettingsStore(
            state = state,
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
                if (failReadback) nextReadFails = true
                commitSucceeds
            },
            newWriterId = {
                writerCreations += 1
                NEW_WRITER
            },
        )
    }

    private companion object {
        const val COMMISSION_KEY = "hide_article_commission"
        const val NEW_WRITER = "550e8400-e29b-41d4-a716-446655440000"
    }
}
