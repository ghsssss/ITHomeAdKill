package io.github.ghsssss.ithomeadkill.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentTreeSettingsTest {
    @Test
    fun independentCommentTreeToggleHasNewStableKeyAndStartsDisabled() {
        val toggle = commentTreeToggle()
        assertEquals(TREE_KEY, toggle.key)
        assertFalse(toggle.defaultValue)
        assertFalse(Toggle.values().any { it.key == RETIRED_KEY })
        val defaults = ModuleSettings.defaults(WRITER)
        assertFalse(defaults.raw(toggle))
        assertFalse(defaults.enabled(toggle))
        assertEquals(5, defaults.toPreferences()["settings_schema"])
        assertEquals(false, defaults.toPreferences()[TREE_KEY])
        assertFalse(defaults.toPreferences().containsKey(RETIRED_KEY))
    }

    @Test
    fun commentTreeChoiceSurvivesMasterGatingAndRoundTripWithoutChangingOtherChoices() {
        val toggle = commentTreeToggle()
        val defaults = ModuleSettings.defaults(WRITER)
        val selected = defaults.copy(toggles = defaults.toggles + (toggle to true))
        assertTrue(selected.enabled(toggle))
        assertEquals(defaults.toggles - toggle, selected.toggles - toggle)
        val paused = selected.copy(toggles = selected.toggles + (Toggle.MASTER to false))
        assertTrue(paused.raw(toggle))
        assertFalse(paused.enabled(toggle))
        assertEquals(paused, ModuleSettings.fromPreferences(paused.toPreferences()))
        assertTrue(paused.copy(toggles = paused.toggles + (Toggle.MASTER to true)).enabled(toggle))
    }

    @Test
    fun schemaFiveRequiresNewBooleanEvenWhenRetiredKeyIsTrue() {
        val complete = schemaFivePreferences() + (RETIRED_KEY to true)
        listOf(false, true).forEach { selected ->
            val decoded = ModuleSettings.fromPreferences(complete + (TREE_KEY to selected))
            assertNotNull("new tree=$selected", decoded)
            assertEquals(selected, decoded!!.toPreferences()[TREE_KEY])
            assertFalse(decoded.toPreferences().containsKey(RETIRED_KEY))
        }
        assertNull(ModuleSettings.fromPreferences(complete - TREE_KEY))
        unexpectedValues().forEach { invalid ->
            assertNull("new tree=$invalid", ModuleSettings.fromPreferences(complete + (TREE_KEY to invalid)))
        }
    }

    @Test
    fun everyLegacySchemaDefaultsTreeOffRegardlessOfInjectedNewOrRetiredKeyValues() {
        (legacySettingsSnapshots() + schemaFourPreferences()).forEach { legacy ->
            val decoded = ModuleSettings.fromPreferences(legacy)
            assertNotNull(decoded)
            val expected = decoded!!.toPreferences() + mapOf("settings_schema" to 5, TREE_KEY to false)
            (listOf<Any?>(false, true) + unexpectedValues()).forEach { value ->
                val values = legacy + mapOf(TREE_KEY to value, RETIRED_KEY to value)
                val before = values.toMap()
                val injected = ModuleSettings.fromPreferences(values)
                assertNotNull("schema=${legacy["settings_schema"]} injected=$value", injected)
                assertEquals(expected, injected!!.toPreferences())
                assertFalse(injected.raw(commentTreeToggle()))
                assertEquals(before, values)
            }
        }
    }

    @Test
    fun schemaFourDecodesReadOnlyPreservingAllOldChoicesAndEveryMinute() {
        listOf(false, true).forEach { commission ->
            SettingsContract.REFRESH_MINUTES.forEach { minutes ->
                val legacy = schemaFourPreferences(73L) + mapOf(
                    "auto_refresh_minutes" to minutes,
                    "hide_article_commission" to commission,
                )
                val before = legacy.toMap()
                val decoded = ModuleSettings.fromPreferences(legacy)
                assertNotNull(decoded)
                assertEquals(legacy + mapOf("settings_schema" to 5, TREE_KEY to false), decoded!!.toPreferences())
                assertEquals(legacy["settings_writer_id"], decoded.writerId)
                assertEquals(73L, decoded.revision)
                assertEquals(minutes, decoded.autoRefreshMinutes)
                assertEquals(commission, decoded.raw(Toggle.ARTICLE_COMMISSION))
                assertEquals(before, legacy)
            }
        }
    }

    @Test
    fun treeKeysCannotRelaxAnyRequiredLegacyFieldOrCommissionSchemaFourRule() {
        (legacySettingsSnapshots() + schemaFourPreferences()).forEach { legacy ->
            val injected = legacy + mapOf(TREE_KEY to true, RETIRED_KEY to true)
            legacy.keys.forEach { key ->
                assertNull("schema=${legacy["settings_schema"]} missing=$key", ModuleSettings.fromPreferences(injected - key))
            }
            legacy.filterValues { it is Boolean }.keys.forEach { key ->
                unexpectedValues().forEach { invalid ->
                    assertNull("schema=${legacy["settings_schema"]} $key=$invalid", ModuleSettings.fromPreferences(injected + (key to invalid)))
                }
            }
        }
    }

    @Test
    fun retiredKeyCannotEnableOrDisableAnExplicitSchemaFiveTreeChoice() {
        listOf(false, true).forEach { selected ->
            val current = schemaFivePreferences() + (TREE_KEY to selected)
            val expected = ModuleSettings.fromPreferences(current)
            assertNotNull(expected)
            (listOf<Any?>(false, true) + unexpectedValues()).forEach { retired ->
                val decoded = ModuleSettings.fromPreferences(current + (RETIRED_KEY to retired))
                assertEquals(expected, decoded)
                assertEquals(current, decoded!!.toPreferences())
            }
        }
    }

    @Test
    fun schemaSixAndCoercibleSchemaFiveVersionsAreRejectedEvenWithBothTreeKeys() {
        val current = schemaFivePreferences() + (RETIRED_KEY to true)
        listOf<Any?>(6, Int.MAX_VALUE, 5L, "5", 5.0, 5.0f, null).forEach { invalid ->
            assertNull("schema=$invalid", ModuleSettings.fromPreferences(current + ("settings_schema" to invalid)))
        }
    }

    @Test
    fun schemaFourFixtureRemainsFrozenWithoutNewOrRetiredCommentKeys() {
        val legacy = schemaFourPreferences()
        assertEquals(4, legacy["settings_schema"])
        assertEquals(schemaThreePreferences().keys + "hide_article_commission", legacy.keys)
        assertFalse(legacy.containsKey(TREE_KEY))
        assertFalse(legacy.containsKey(RETIRED_KEY))
        assertEquals(false, legacy["hide_article_commission"])
    }

    private fun commentTreeToggle(): Toggle {
        val toggle = Toggle.values().find { it.name == "COMMENT_TREE" }
        assertNotNull("independent COMMENT_TREE toggle must exist", toggle)
        return toggle!!
    }

    private fun unexpectedValues(): List<Any?> = listOf(
        null, "true", "false", 0, 1, 1L, 1.0f, 1.0, setOf("true"), listOf(true), mapOf("enabled" to true),
    )

    private companion object {
        const val TREE_KEY = "comment_tree_lines"
        const val RETIRED_KEY = "comment_thread_lines"
        const val WRITER = "550e8400-e29b-41d4-a716-446655440000"
    }
}
