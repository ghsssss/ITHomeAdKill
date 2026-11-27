package io.github.ghsssss.ithomeadkill.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetiredCommentSettingsTest {
    @Test
    fun retiredCommentToggleIsAbsentFromTheModelAndDefaultSnapshot() {
        assertFalse(Toggle.values().any { it.key == RETIRED_KEY })
        val defaults = ModuleSettings.defaults(WRITER)
        assertFalse(defaults.toggles.keys.any { it.key == RETIRED_KEY })
        assertFalse(defaults.toPreferences().containsKey(RETIRED_KEY))
        assertEquals(5, defaults.toPreferences()["settings_schema"])
    }

    @Test
    fun enabledHistoricalSchemaThreeCommentKeyIsIgnoredAndNotExported() {
        val expected = schemaFivePreferences()
        val historical = schemaThreePreferences() + (RETIRED_KEY to true)
        val before = historical.toMap()

        val decoded = ModuleSettings.fromPreferences(historical)

        assertNotNull(decoded)
        assertEquals(expected, decoded!!.toPreferences())
        assertFalse(decoded.toggles.keys.any { it.key == RETIRED_KEY })
        assertEquals(before, historical)
    }

    @Test
    fun missingFalseAndMalformedHistoricalValuesDecodeToTheSameRetainedSettings() {
        val expected = schemaFivePreferences()
        val expectedSettings = ModuleSettings.fromPreferences(expected)
        assertNotNull(expectedSettings)
        historicalSnapshots().forEach { historical ->
            val before = historical.toMap()
            val decoded = ModuleSettings.fromPreferences(historical)

            assertEquals("historical=$historical", expectedSettings, decoded)
            assertEquals(expected, decoded!!.toPreferences())
            assertFalse(decoded.toggles.keys.any { it.key == RETIRED_KEY })
            assertEquals(before, historical)
        }
    }

    @Test
    fun legacySchemasIgnoreHistoricalCommentValuesWhilePreservingCalendarAndRewardsMigration() {
        legacySettingsSnapshots().forEach { legacy ->
            val expected = (legacy - "hide_event_calendar") + mapOf(
                "settings_schema" to 5,
                "comment_tree_lines" to false,
                "hide_article_commission" to false,
                "hide_article_rewards" to (legacy["hide_article_rewards"] ?: false),
            )
            historicalSnapshots(legacy).forEach { historical ->
                val decoded = ModuleSettings.fromPreferences(historical)

                assertNotNull("historical=$historical", decoded)
                assertEquals(expected, decoded!!.toPreferences())
                assertEquals(legacy["settings_writer_id"], decoded.writerId)
                assertEquals(41L, decoded.revision)
                assertEquals(10, decoded.autoRefreshMinutes)
            }
        }
    }

    @Test
    fun legacyCalendarAndRewardsValidationRemainsSchemaSpecific() {
        listOf(false, true).forEach { eventCalendar ->
            val schemaOne = legacyPreferences(eventCalendar) + (RETIRED_KEY to "obsolete")
            historicalValues().forEach { unexpectedRewards ->
                val decoded = ModuleSettings.fromPreferences(
                    schemaOne + ("hide_article_rewards" to unexpectedRewards),
                )
                assertNotNull(decoded)
                assertFalse(decoded!!.raw(Toggle.ARTICLE_REWARDS))
            }
            assertNull(ModuleSettings.fromPreferences(schemaOne - "hide_event_calendar"))
            listOf<Any?>(null, "true", 0, 1L).forEach { invalid ->
                assertNull(ModuleSettings.fromPreferences(schemaOne + ("hide_event_calendar" to invalid)))
            }
        }
        val schemaTwo = schemaTwoPreferences() + (RETIRED_KEY to "obsolete")
        assertNull(ModuleSettings.fromPreferences(schemaTwo - "hide_article_rewards"))
        listOf<Any?>(null, "true", 0, 1L).forEach { invalid ->
            assertNull(ModuleSettings.fromPreferences(schemaTwo + ("hide_article_rewards" to invalid)))
        }
    }

    @Test
    fun historicalCommentKeyCannotRelaxRetainedFieldValidationOrAdmitFutureSchemas() {
        val required = schemaFivePreferences()
        historicalSnapshots().forEach { historical ->
            val malformed = required.keys.map { historical - it } +
                required.filterValues { it is Boolean }.keys.flatMap { key ->
                    listOf<Any?>(null, "true", 0, 1L).map { historical + (key to it) }
                } + mapOf(
                    "settings_schema" to listOf<Any?>(null, 6, 5L, "5"),
                    "settings_revision" to listOf<Any?>(null, 0L, -1L, 41, "41"),
                    "settings_writer_id" to listOf<Any?>(null, "invalid", 1L),
                    "auto_refresh_minutes" to listOf<Any?>(null, 2, 3, 10L, "10"),
                ).flatMap { (key, values) -> values.map { historical + (key to it) } }
            malformed.forEach { invalid ->
                assertNull("invalid=$invalid", ModuleSettings.fromPreferences(invalid))
                val storage = MemoryStorage(invalid)
                val store = storage.store()

                assertNull(store.reload().settings)
                assertNull(store.setToggle(Toggle.AI_SUMMARY, false).settings)
                assertNull(store.setRefreshMinutes(5).settings)
                assertTrue(storage.writes.isEmpty())
                assertEquals(invalid, storage.values)
                assertEquals(0, storage.writerCreations)
            }
        }
    }

    @Test
    fun reloadPreservesHistoricalSnapshotWithoutMigrationWritesOrNewWriter() {
        val expected = ModuleSettings.fromPreferences(schemaFivePreferences())!!
        historicalSnapshots().forEach { historical ->
            val storage = MemoryStorage(historical)
            val store = storage.store()

            val loaded = store.reload()

            assertEquals(expected, loaded.settings)
            assertFalse(loaded.busy)
            assertFalse(loaded.message.contains("失败"))
            repeat(3) { assertEquals(expected, store.reload().settings) }
            assertEquals(expected, storage.store().reload().settings)
            assertEquals(historical, storage.values)
            assertTrue(storage.writes.isEmpty())
            assertEquals(0, storage.writerCreations)
        }
    }

    @Test
    fun changingOnlyHistoricalKeyAcrossReloadsNeverResetsOtherFields() {
        val expected = ModuleSettings.fromPreferences(schemaFivePreferences())!!
        val storage = MemoryStorage(schemaFivePreferences())
        val store = storage.store()
        assertEquals(expected, store.reload().settings)

        historicalSnapshots().forEach { historical ->
            storage.values = historical

            assertEquals(expected, store.reload().settings)
            assertEquals(expected, storage.store().reload().settings)
            assertEquals(historical, storage.values)
        }
        assertTrue(storage.writes.isEmpty())
        assertEquals(0, storage.writerCreations)
    }

    @Test
    fun normalToggleSaveClearsHistoricalKeyAndPreservesEveryOtherRetainedField() {
        val expected = schemaFivePreferences() + mapOf(
            "settings_revision" to 42L,
            "hide_article_ai_summary" to false,
        )
        historicalSnapshots().forEach { historical ->
            val storage = MemoryStorage(historical)
            val store = storage.store()
            assertNotNull(store.reload().settings)

            val saved = store.setToggle(Toggle.AI_SUMMARY, false)

            assertEquals(ModuleSettings.fromPreferences(expected), saved.settings)
            assertEquals(expected, storage.values)
            assertEquals(listOf(expected), storage.writes)
            assertFalse(storage.values.containsKey(RETIRED_KEY))
            assertEquals(saved.settings, storage.store().reload().settings)
            assertEquals(1, storage.writes.size)
            assertEquals(0, storage.writerCreations)
        }
    }

    @Test
    fun normalRefreshSaveClearsHistoricalKeyWithoutChangingAnyToggleOrIdentity() {
        val expected = schemaFivePreferences() + mapOf<String, Any>(
            "settings_revision" to 42L,
            "auto_refresh_minutes" to 5,
        )
        historicalSnapshots().forEach { historical ->
            val storage = MemoryStorage(historical)
            val store = storage.store()
            assertNotNull(store.reload().settings)

            val saved = store.setRefreshMinutes(5)

            assertEquals(ModuleSettings.fromPreferences(expected), saved.settings)
            assertEquals(expected, storage.values)
            assertEquals(listOf(expected), storage.writes)
            assertFalse(storage.values.containsKey(RETIRED_KEY))
            assertEquals(saved.settings, storage.store().reload().settings)
            assertEquals(1, storage.writes.size)
            assertEquals(0, storage.writerCreations)
        }
    }

    @Test
    fun schemaFiveNoOpsDoNotRewriteHistoricalKeyOrIncrementRevision() {
        historicalSnapshots().forEach { historical ->
            val storage = MemoryStorage(historical)
            val store = storage.store()
            val initial = store.reload().settings
            assertNotNull(initial)

            repeat(3) {
                assertEquals(initial, store.setToggle(Toggle.AI_SUMMARY, true).settings)
                assertEquals(initial, store.setRefreshMinutes(10).settings)
            }

            assertEquals(historical, storage.values)
            assertTrue(storage.writes.isEmpty())
            assertEquals(41L, initial!!.revision)
            assertEquals(0, storage.writerCreations)
        }
    }

    @Test
    fun revisionLimitDoesNotForceHistoricalKeyMigrationButStillBlocksChanges() {
        historicalSnapshots(schemaFivePreferences(Long.MAX_VALUE)).forEach { historical ->
            val storage = MemoryStorage(historical)
            val store = storage.store()
            val initial = store.reload().settings
            assertNotNull(initial)
            assertEquals(Long.MAX_VALUE, initial!!.revision)
            assertEquals(initial, store.setToggle(Toggle.AI_SUMMARY, true).settings)
            assertEquals(initial, store.setRefreshMinutes(10).settings)

            val failures = listOf(
                store.setToggle(Toggle.AI_SUMMARY, false),
                store.setRefreshMinutes(5),
            )

            failures.forEach { failed ->
                assertEquals(initial, failed.settings)
                assertTrue(failed.message.contains("失败"))
            }
            assertEquals(historical, storage.values)
            assertTrue(storage.writes.isEmpty())
            assertEquals(0, storage.writerCreations)
        }
    }

    @Test
    fun readbackThatReintroducesHistoricalKeyCannotConfirmAWriteAndRetriesSameRevision() {
        val expected = schemaFivePreferences() + mapOf(
            "settings_revision" to 42L,
            "hide_article_ai_summary" to false,
        )
        historicalValues().forEach { historicalValue ->
            val storage = MemoryStorage(schemaFivePreferences() + (RETIRED_KEY to historicalValue)).apply {
                writeTransform = { next -> next + (RETIRED_KEY to historicalValue) }
            }
            val store = storage.store()
            val confirmed = store.reload().settings
            assertNotNull(confirmed)

            val failed = store.setToggle(Toggle.AI_SUMMARY, false)

            assertEquals(confirmed, failed.settings)
            assertTrue(failed.message.contains("校验失败"))
            assertFalse(failed.busy)
            assertEquals(expected, storage.writes.single())
            assertEquals(expected + (RETIRED_KEY to historicalValue), storage.values)
            assertEquals(ModuleSettings.fromPreferences(expected), ModuleSettings.fromPreferences(storage.values))
            val failedAgain = storage.store().reload()
            assertEquals(confirmed, failedAgain.settings)
            assertTrue(failedAgain.message.contains("校验失败"))
            storage.writeTransform = { it }

            val recovered = storage.store().reload()

            assertEquals(ModuleSettings.fromPreferences(expected), recovered.settings)
            assertEquals(expected, storage.values)
            assertEquals(listOf(expected, expected, expected), storage.writes)
            assertEquals(0, storage.writerCreations)
        }
    }

    private fun historicalValues(): List<Any?> = listOf(
        true, false, null, "true", "false", 0, 1L, 1.0f, 1.0, setOf("true"), listOf(true),
        mapOf("enabled" to true),
    )

    private fun historicalSnapshots(
        base: Map<String, Any> = schemaFivePreferences(),
    ): List<Map<String, *>> = listOf(base) + historicalValues().map { base + (RETIRED_KEY to it) }

    private class MemoryStorage(initial: Map<String, *>) {
        var values: Map<String, *> = initial.toMap()
        val writes = mutableListOf<Map<String, Any>>()
        var writerCreations = 0
        var writeTransform: (Map<String, Any>) -> Map<String, *> = { it }
        private val state = SettingsStoreState()

        fun store(): SettingsStore = SettingsStore(
            state = state,
            read = { values.toMap() },
            commit = { next ->
                writes += next.toMap()
                values = writeTransform(next.toMap())
                true
            },
            newWriterId = {
                writerCreations += 1
                WRITER
            },
        )
    }

    private companion object {
        const val RETIRED_KEY = "comment_thread_lines"
        const val WRITER = "550e8400-e29b-41d4-a716-446655440000"
    }
}
