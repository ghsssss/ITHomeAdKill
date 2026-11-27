package io.github.ghsssss.ithomeadkill.settings

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStoreConcurrencyTest {
    @Test
    fun twoStoresKeepMasterDisabledWhenTheStaleStoreEnablesAi() {
        val storage = SharedStorage()
        val first = storage.store()
        val second = storage.store()
        first.reload()
        second.reload()

        assertEquals(2L, first.setToggle(Toggle.MASTER, false).settings!!.revision)
        val latest = second.setToggle(Toggle.AI_SUMMARY, true).settings!!

        assertFalse(latest.raw(Toggle.MASTER))
        assertTrue(latest.raw(Toggle.AI_SUMMARY))
        assertEquals(3L, latest.revision)
        assertEquals(latest, storage.snapshot())
        assertEquals(listOf(2L, 3L), storage.writes.map { it[SettingsContract.REVISION_KEY] })
    }

    @Test
    fun staleNoOpIsComparedAgainstTheLatestSnapshotNotTheStoreCache() {
        val storage = SharedStorage()
        val first = storage.store()
        val second = storage.store()
        first.reload()
        second.reload()
        first.setToggle(Toggle.MASTER, false)

        val latest = second.setToggle(Toggle.MASTER, true).settings!!

        assertTrue(latest.raw(Toggle.MASTER))
        assertEquals(3L, latest.revision)
        assertEquals(latest, storage.snapshot())
    }

    @Test
    fun staleRefreshChangePreservesTheOtherStoresToggle() {
        val storage = SharedStorage()
        val first = storage.store()
        val second = storage.store()
        first.reload()
        second.reload()
        first.setToggle(Toggle.MASTER, false)

        val latest = second.setRefreshMinutes(5).settings!!

        assertFalse(latest.raw(Toggle.MASTER))
        assertEquals(5, latest.autoRefreshMinutes)
        assertEquals(3L, latest.revision)
    }

    @Test
    fun mutationReadFailureDoesNotWriteTheCachedSnapshot() {
        val storage = SharedStorage()
        val store = storage.store()
        store.reload()
        storage.readFails = true

        val state = store.setToggle(Toggle.MASTER, false)

        assertNull(state.settings)
        assertTrue(state.message.contains("失败"))
        assertTrue(storage.writes.isEmpty())
    }

    @Test
    fun mutationWithDamagedCurrentDataDoesNotOverwriteItWithCachedOrDefaultValues() {
        val storage = SharedStorage()
        val store = storage.store()
        store.reload()
        storage.values = mapOf("master_enabled" to false)

        val state = store.setRefreshMinutes(5)

        assertNull(state.settings)
        assertTrue(storage.writes.isEmpty())
        assertEquals(mapOf("master_enabled" to false), storage.values)
    }

    @Test
    fun mutationWithMissingCurrentDataDoesNotInitializeDefaults() {
        val storage = SharedStorage()
        val store = storage.store()
        store.reload()
        storage.values = emptyMap<String, Any>()

        assertNull(store.setToggle(Toggle.AI_SUMMARY, true).settings)
        assertTrue(storage.writes.isEmpty())
        assertTrue(storage.values.isEmpty())
    }

    @Test
    fun stalePendingRetryCannotOverwriteAnotherStoresSuccessfulCommit() {
        val storage = SharedStorage()
        val first = storage.store()
        val second = storage.store()
        first.reload()
        second.reload()
        storage.commitSucceeds = false
        first.setToggle(Toggle.AD_CARDS, false)
        storage.commitSucceeds = true
        val newer = second.setToggle(Toggle.MASTER, false).settings!!
        val writesBeforeRetry = storage.writes.size

        val reloaded = first.reload().settings!!

        assertEquals(newer, reloaded)
        assertEquals(newer, storage.snapshot())
        assertEquals(writesBeforeRetry, storage.writes.size)
    }

    @Test
    fun mutationAfterAFailedWriteStillPreservesANewerSuccessfulForeignChange() {
        val storage = SharedStorage()
        val first = storage.store()
        val second = storage.store()
        first.reload()
        second.reload()
        storage.commitSucceeds = false
        first.setToggle(Toggle.AD_CARDS, false)
        storage.commitSucceeds = true
        val newer = second.setToggle(Toggle.MASTER, false).settings!!

        val latest = first.setToggle(Toggle.AI_SUMMARY, true).settings!!

        assertFalse(latest.raw(Toggle.MASTER))
        assertTrue(latest.raw(Toggle.AI_SUMMARY))
        assertEquals(newer.revision + 1L, latest.revision)
        assertEquals(latest, storage.snapshot())
    }

    @Test
    fun concurrentStoresSerializeTheWholeReadModifyCommitReadBackTransaction() {
        repeat(20) {
            val storage = SharedStorage().apply { readDelayMillis = 2L }
            val first = storage.store()
            val second = storage.store()
            first.reload()
            second.reload()

            runTogether(
                { first.setToggle(Toggle.MASTER, false) },
                { second.setToggle(Toggle.AI_SUMMARY, true) },
            )

            val latest = storage.snapshot()!!
            assertFalse("iteration=$it", latest.raw(Toggle.MASTER))
            assertTrue("iteration=$it", latest.raw(Toggle.AI_SUMMARY))
            assertEquals("iteration=$it", 3L, latest.revision)
            assertEquals(listOf(2L, 3L), storage.writes.map { it[SettingsContract.REVISION_KEY] })
        }
    }

    @Test
    fun concurrentFirstOpenCreatesOnlyOneWriterAndOneInitialCommit() {
        val storage = SharedStorage(emptyMap<String, Any>()).apply { readDelayMillis = 10L }
        val first = storage.store()
        val second = storage.store()

        runTogether({ first.reload() }, { second.reload() })

        assertEquals(1, storage.writerCreations.get())
        assertEquals(1, storage.writes.size)
        assertEquals(1L, storage.snapshot()!!.revision)
        assertEquals(first.settings, second.settings)
    }

    @Test
    fun failedInitializationRetryDoesNotOverwriteNewlyCorruptedData() {
        val storage = SharedStorage(emptyMap<String, Any>()).apply { commitSucceeds = false }
        val store = storage.store()
        store.reload()
        storage.commitSucceeds = true
        storage.values = mapOf("master_enabled" to false)
        val writesBeforeRetry = storage.writes.size

        assertNull(store.reload().settings)
        assertEquals(writesBeforeRetry, storage.writes.size)
        assertEquals(mapOf("master_enabled" to false), storage.values)
    }

    @Test
    fun missingDataAfterASuccessfulLoadIsNotTreatedAsFirstOpen() {
        val storage = SharedStorage()
        val store = storage.store()
        store.reload()
        storage.values = emptyMap<String, Any>()

        assertNull(store.reload().settings)
        assertTrue(storage.writes.isEmpty())
        assertEquals(0, storage.writerCreations.get())
    }

    @Test
    fun pendingMutationRetryDoesNotRepublishAnOldSnapshotOverMissingData() {
        val storage = SharedStorage()
        val store = storage.store()
        store.reload()
        storage.commitSucceeds = false
        store.setToggle(Toggle.MASTER, false)
        storage.commitSucceeds = true
        storage.values = emptyMap<String, Any>()
        val writesBeforeRetry = storage.writes.size

        assertNull(store.reload().settings)
        assertEquals(writesBeforeRetry, storage.writes.size)
        assertTrue(storage.values.isEmpty())
    }

    @Test
    fun concurrentLegacyReloadsPerformOnlyOneMigrationWithoutCreatingAWriter() {
        legacySettingsSnapshots().forEach { legacy ->
            val storage = SharedStorage(legacy).apply { readDelayMillis = 10L }
            val first = storage.store()
            val second = storage.store()

            runTogether({ first.reload() }, { second.reload() })

            assertEquals(0, storage.writerCreations.get())
            assertEquals(1, storage.writes.size)
            assertEquals(42L, storage.snapshot()!!.revision)
            assertEquals(5, storage.values["settings_schema"])
            assertFalse(storage.values.containsKey("hide_event_calendar"))
            assertEquals(first.settings, second.settings)
        }
    }

    @Test
    fun concurrentLegacyMigrationAndRewardMutationPreserveEveryOtherRawValue() {
        repeat(10) { iteration ->
            val snapshots = legacySettingsSnapshots()
            val legacy = snapshots[iteration % snapshots.size]
            val storage = SharedStorage(legacy).apply { readDelayMillis = 2L }
            val first = storage.store()
            val second = storage.store()

            runTogether({ first.reload() }, { second.setToggle(Toggle.ARTICLE_REWARDS, true) })

            val expected = (legacy - "hide_event_calendar") + mapOf(
                "settings_schema" to 5,
                "hide_article_commission" to false,
                "comment_tree_lines" to false,
                "settings_revision" to 41L + storage.writes.size,
                "hide_article_rewards" to true,
            )
            assertEquals("iteration=$iteration", expected, storage.values)
            assertTrue(storage.writes.size in 1..2)
            assertTrue(storage.writes.all {
                it["settings_schema"] == 5 && !it.containsKey("hide_event_calendar")
            })
            assertEquals(0, storage.writerCreations.get())
        }
    }

    @Test
    fun failedPendingMigrationCannotOverwriteAnotherStoresNewerRewardChoice() {
        legacySettingsSnapshots().forEach { legacy ->
            val storage = SharedStorage(legacy).apply { commitSucceeds = false }
            val first = storage.store()
            assertNull(first.reload().settings)
            storage.commitSucceeds = true
            val second = storage.store()
            second.reload()
            val newer = second.setToggle(Toggle.ARTICLE_REWARDS, legacy["hide_article_rewards"] != true).settings!!
            val writesBeforeRetry = storage.writes.size

            assertEquals(newer, first.reload().settings)
            assertEquals(newer, storage.snapshot())
            assertEquals(writesBeforeRetry, storage.writes.size)
            assertEquals(43L, newer.revision)
        }
    }

    @Test
    fun anotherStoreMustConfirmAFailedMigrationBeforePublishingItsMemorySnapshot() {
        legacySettingsSnapshots().forEach { legacy ->
            val storage = SharedStorage(legacy).apply { commitSucceeds = false }
            val first = storage.store()
            assertNull(first.reload().settings)
            val second = storage.store()

            val failedAgain = second.reload()

            assertNull(failedAgain.settings)
            assertTrue(failedAgain.message.contains("失败"))
            assertEquals(2, storage.writes.size)
            assertEquals(storage.writes[0], storage.writes[1])
            storage.commitSucceeds = true
            val rebuilt = storage.store()
            val recovered = rebuilt.reload()
            assertEquals(3, storage.writes.size)
            assertEquals(42L, recovered.settings!!.revision)
            assertEquals(storage.writes[0], storage.writes[2])
            assertEquals(recovered.settings, storage.snapshot())
        }
    }

    @Test
    fun newStoresAlsoRequireConfirmationAfterFailedInitializationOrCurrentSchemaWrite() {
        listOf(false, true).forEach { initialized ->
            val storage = SharedStorage(if (initialized) {
                ModuleSettings.defaults(WRITER).toPreferences()
            } else {
                emptyMap<String, Any>()
            })
            val first = storage.store()
            val confirmed = if (initialized) first.reload().settings else null
            storage.commitSucceeds = false
            if (initialized) first.setToggle(Toggle.MASTER, false) else first.reload()
            val attemptsBeforeRebuild = storage.writes.size

            val failedAgain = storage.store().reload()

            assertEquals(confirmed, failedAgain.settings)
            assertTrue(failedAgain.message.contains("失败"))
            assertEquals(attemptsBeforeRebuild + 1, storage.writes.size)
            storage.commitSucceeds = true
            val recovered = storage.store().reload().settings!!
            assertEquals(if (initialized) 2L else 1L, recovered.revision)
            assertEquals(recovered, storage.snapshot())
            if (initialized) assertFalse(recovered.raw(Toggle.MASTER))
            assertEquals(if (initialized) 0 else 1, storage.writerCreations.get())
        }
    }

    @Test
    fun failedConfirmationDoesNotLeakIntoAnIndependentPreferenceBackend() {
        val failed = SharedStorage(legacyPreferences()).apply { commitSucceeds = false }
        assertNull(failed.store().reload().settings)
        val healthy = SharedStorage(legacyPreferences(false))

        val migrated = healthy.store().reload().settings!!

        assertEquals(42L, migrated.revision)
        assertEquals(1, healthy.writes.size)
        assertEquals(migrated, healthy.snapshot())
        assertNull(failed.store().reload().settings)
        assertEquals(2, failed.writes.size)
    }

    @Test
    fun concurrentLegacyMigrationAndAiChoicePreserveOtherValuesAndSequentialRevisions() {
        legacySettingsSnapshots().forEach { legacy ->
            repeat(10) { iteration ->
                val storage = SharedStorage(legacy).apply { readDelayMillis = 2L }
                val first = storage.store()
                val second = storage.store()

                runTogether({ first.reload() }, { second.setToggle(Toggle.AI_SUMMARY, false) })

                val expected = (legacy - "hide_event_calendar") + mapOf(
                    "settings_schema" to 5,
                    "hide_article_commission" to false,
                    "comment_tree_lines" to false,
                    "settings_revision" to 41L + storage.writes.size,
                    "hide_article_rewards" to (legacy["hide_article_rewards"] ?: false),
                    "hide_article_ai_summary" to false,
                )
                assertEquals("iteration=$iteration schema=${legacy["settings_schema"]}", expected, storage.values)
                assertTrue(storage.writes.size in 1..2)
                assertEquals((0 until storage.writes.size).map { 42L + it }, storage.writes.map { it["settings_revision"] })
                assertTrue(storage.writes.all { it["settings_schema"] == 5 && !it.containsKey("hide_event_calendar") })
                assertEquals(0, storage.writerCreations.get())
                assertFalse(storage.snapshot()!!.raw(Toggle.AI_SUMMARY))
                assertFalse(storage.snapshot()!!.enabled(Toggle.AI_SUMMARY))
            }
        }
    }

    @Test
    fun concurrentSchemaFourReloadsConfirmOneMigrationWithoutLosingCommissionOrCreatingWriter() {
        repeat(30) { iteration ->
            val legacy = schemaFourPreferences() + ("hide_article_commission" to true)
            val storage = SharedStorage(legacy).apply { readDelayMillis = 1L }
            val first = storage.store()
            val second = storage.store()

            runTogether({ first.reload() }, { second.reload() })

            val expected = legacy + mapOf("settings_schema" to 5, "settings_revision" to 42L, "comment_tree_lines" to false)
            assertEquals("iteration=$iteration", expected, storage.values)
            assertEquals(listOf(expected), storage.writes.toList())
            assertEquals(first.settings, second.settings)
            assertEquals(first.settings, storage.store().reload().settings)
            assertEquals(0, storage.writerCreations.get())
        }
    }

    @Test
    fun concurrentSchemaFourReloadAndNewTreeSelectionPreserveEveryOldChoiceAndMonotonicRevision() {
        repeat(30) { iteration ->
            val legacy = schemaFourPreferences() + ("hide_article_commission" to true)
            val storage = SharedStorage(legacy).apply { readDelayMillis = 1L }
            val first = storage.store()
            val second = storage.store()

            runTogether({ first.reload() }, { second.setToggle(Toggle.COMMENT_TREE, true) })

            val expected = legacy + mapOf(
                "settings_schema" to 5,
                "settings_revision" to 41L + storage.writes.size,
                "comment_tree_lines" to true,
            )
            assertEquals("iteration=$iteration", expected, storage.values)
            assertTrue(storage.writes.size in 1..2)
            assertEquals((0 until storage.writes.size).map { 42L + it }, storage.writes.map { it["settings_revision"] })
            assertTrue(storage.writes.all { it["settings_schema"] == 5 && it["hide_article_commission"] == true })
            assertTrue(storage.snapshot()!!.raw(Toggle.COMMENT_TREE))
            assertFalse(storage.snapshot()!!.enabled(Toggle.COMMENT_TREE))
            assertEquals(0, storage.writerCreations.get())
        }
    }

    @Test
    fun concurrentMasterAndTreeEditsRetainBothChoicesAndMasterGating() {
        repeat(30) { iteration ->
            val initial = schemaFivePreferences() + ("master_enabled" to true)
            val storage = SharedStorage(initial).apply { readDelayMillis = 1L }
            val first = storage.store()
            val second = storage.store()
            first.reload()
            second.reload()

            runTogether({ first.setToggle(Toggle.MASTER, false) }, { second.setToggle(Toggle.COMMENT_TREE, true) })

            val expected = initial + mapOf(
                "settings_revision" to 43L,
                "master_enabled" to false,
                "comment_tree_lines" to true,
            )
            assertEquals("iteration=$iteration", expected, storage.values)
            assertEquals(listOf(42L, 43L), storage.writes.map { it["settings_revision"] })
            assertTrue(storage.snapshot()!!.raw(Toggle.COMMENT_TREE))
            assertFalse(storage.snapshot()!!.enabled(Toggle.COMMENT_TREE))
            assertEquals(0, storage.writerCreations.get())
        }
    }

    private fun runTogether(first: () -> Unit, second: () -> Unit) {
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val futures = listOf(first, second).map { action ->
                executor.submit {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    action()
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    private class SharedStorage(
        initial: Map<String, *> = ModuleSettings.defaults(WRITER).toPreferences(),
    ) {
        @Volatile var values: Map<String, *> = initial.toMap()
        @Volatile var readFails = false
        @Volatile var commitSucceeds = true
        var readDelayMillis = 0L
        val writes = CopyOnWriteArrayList<Map<String, Any>>()
        val writerCreations = AtomicInteger()
        private val state = SettingsStoreState()

        fun snapshot(): ModuleSettings? = ModuleSettings.fromPreferences(values)

        fun store(): SettingsStore = SettingsStore(
            state = state,
            read = {
                check(!readFails) { "模拟读取失败" }
                val captured = values.toMap()
                if (readDelayMillis > 0L) Thread.sleep(readDelayMillis)
                captured
            },
            commit = { next ->
                writes += next.toMap()
                values = next.toMap()
                commitSucceeds
            },
            newWriterId = {
                if (writerCreations.incrementAndGet() == 1) WRITER else OTHER_WRITER
            },
        )
    }

    private companion object {
        const val WRITER = "550e8400-e29b-41d4-a716-446655440000"
        const val OTHER_WRITER = "6ba7b810-9dad-41d1-80b4-00c04fd430c8"
    }
}
