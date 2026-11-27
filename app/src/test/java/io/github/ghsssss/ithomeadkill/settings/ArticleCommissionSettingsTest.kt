package io.github.ghsssss.ithomeadkill.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleCommissionSettingsTest {
    @Test
    fun commissionToggleHasStableKeyFalseDefaultAndObeysMasterWithoutLosingItsChoice() {
        val commission = Toggle.values().find { it.name == "ARTICLE_COMMISSION" }
        assertNotNull("independent commission toggle must exist", commission)
        val toggle = commission!!
        assertEquals(COMMISSION_KEY, toggle.key)
        assertFalse(toggle.defaultValue)
        val defaults = ModuleSettings.defaults(WRITER)
        assertFalse(defaults.raw(toggle))
        assertFalse(defaults.enabled(toggle))
        val selected = defaults.copy(toggles = defaults.toggles + (toggle to true))
        assertTrue(selected.enabled(toggle))
        val paused = selected.copy(toggles = selected.toggles + (Toggle.MASTER to false))
        assertTrue(paused.raw(toggle))
        assertFalse(paused.enabled(toggle))
        assertEquals(selected.toggles - Toggle.MASTER, paused.toggles - Toggle.MASTER)
        assertEquals(paused, ModuleSettings.fromPreferences(paused.toPreferences()))
        assertTrue(paused.copy(toggles = paused.toggles + (Toggle.MASTER to true)).enabled(toggle))
    }

    @Test
    fun schemaFourRequiresAnExplicitBooleanCommissionValue() {
        val complete = schemaFourPreferences()
        assertEquals(4, complete["settings_schema"])
        assertFalse(complete.containsKey("comment_tree_lines"))
        listOf(false, true).forEach { selected ->
            val values = complete + (COMMISSION_KEY to selected)
            val decoded = ModuleSettings.fromPreferences(values)
            assertNotNull("commission=$selected", decoded)
            assertEquals(values + mapOf("settings_schema" to 5, "comment_tree_lines" to false), decoded!!.toPreferences())
        }
        assertNull(ModuleSettings.fromPreferences(complete - COMMISSION_KEY))
        unexpectedValues().forEach { invalid ->
            assertNull("commission=$invalid", ModuleSettings.fromPreferences(complete + (COMMISSION_KEY to invalid)))
        }
    }

    @Test
    fun legacySchemasIgnoreCommissionKeyRegardlessOfItsPresenceValueOrType() {
        legacySettingsSnapshots().forEach { legacy ->
            val expected = (legacy - "hide_event_calendar") + mapOf(
                "settings_schema" to 5,
                "comment_tree_lines" to false,
                "hide_article_rewards" to (legacy["hide_article_rewards"] ?: false),
                COMMISSION_KEY to false,
            )
            val inputs = listOf(legacy) + (listOf(false, true) + unexpectedValues()).map {
                legacy + (COMMISSION_KEY to it)
            }
            inputs.forEach { values ->
                val before = values.toMap()
                val decoded = ModuleSettings.fromPreferences(values)
                assertNotNull("legacy=$values", decoded)
                assertEquals(expected, decoded!!.toPreferences())
                assertEquals(before, values)
            }
        }
    }

    @Test
    fun schemaThreeDecodePreservesWriterRevisionEveryOldToggleAndEveryRefreshOption() {
        SettingsContract.REFRESH_MINUTES.forEach { minutes ->
            val legacy = schemaThreePreferences(73L) + ("auto_refresh_minutes" to minutes)
            val decoded = ModuleSettings.fromPreferences(legacy)
            assertNotNull(decoded)
            assertEquals(legacy["settings_writer_id"], decoded!!.writerId)
            assertEquals(73L, decoded.revision)
            assertEquals(minutes, decoded.autoRefreshMinutes)
            assertTrue(decoded.raw(Toggle.ARTICLE_RECOMMENDATIONS))
            assertFalse(decoded.enabled(Toggle.ARTICLE_RECOMMENDATIONS))
            legacy.filterKeys { it != "settings_schema" }.forEach { (key, value) ->
                assertEquals("key=$key", value, decoded.toPreferences()[key])
            }
            assertEquals(false, decoded.toPreferences()[COMMISSION_KEY])
            assertEquals(5, decoded.toPreferences()["settings_schema"])
        }
    }

    @Test
    fun injectedCommissionKeyCannotRelaxAnySchemaThreeRetainedFieldValidation() {
        val legacy = schemaThreePreferences() + (COMMISSION_KEY to true)
        schemaThreePreferences().keys.forEach { missing ->
            assertNull("missing=$missing", ModuleSettings.fromPreferences(legacy - missing))
        }
        schemaThreePreferences().filterValues { it is Boolean }.keys.forEach { key ->
            unexpectedValues().forEach { invalid ->
                assertNull("key=$key value=$invalid", ModuleSettings.fromPreferences(legacy + (key to invalid)))
            }
        }
        mapOf(
            "settings_schema" to listOf<Any?>(null, 6, 3L, "3", 3.0),
            "settings_revision" to listOf<Any?>(null, 0L, -1L, 41, "41"),
            "settings_writer_id" to listOf<Any?>(null, "invalid", 1L),
            "auto_refresh_minutes" to listOf<Any?>(null, 2, 3, 10L, "10"),
        ).forEach { (key, values) ->
            values.forEach { invalid ->
                assertNull("key=$key value=$invalid", ModuleSettings.fromPreferences(legacy + (key to invalid)))
            }
        }
    }

    private fun unexpectedValues(): List<Any?> = listOf(
        null, "true", "false", 0, 1, 1L, 1.0f, 1.0, setOf("true"), listOf(true), mapOf("enabled" to true),
    )

    private companion object {
        const val COMMISSION_KEY = "hide_article_commission"
        const val WRITER = "550e8400-e29b-41d4-a716-446655440000"
    }
}
