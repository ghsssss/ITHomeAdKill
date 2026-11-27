package io.github.ghsssss.ithomeadkill.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleSettingsTest {
    @Test
    fun sharedContractKeepsPackageFileNamesAndTechnicalKeysStable() {
        assertEquals("io.github.ghsssss.ithomeadkill", SettingsContract.MODULE_PACKAGE)
        assertEquals("ithome_settings", SettingsContract.PREFS_NAME)
        assertEquals("settings_schema", SettingsContract.SCHEMA_KEY)
        assertEquals("settings_revision", SettingsContract.REVISION_KEY)
        assertEquals("settings_writer_id", SettingsContract.WRITER_KEY)
    }

    @Test
    fun togglesHaveExactlyTheAgreedNamesKeysAndDefaults() {
        val expected = listOf(
            Triple("MASTER", "master_enabled", true),
            Triple("AD_CARDS", "hide_ad_cards", true),
            Triple("PINNED_CARDS", "hide_pinned_cards", true),
            Triple("WEB_PROMOS", "hide_web_promos", true),
            Triple("HOME_CAROUSEL", "hide_home_carousel", false),
            Triple("AI_SUMMARY", "hide_article_ai_summary", false),
            Triple("ARTICLE_RATING", "hide_article_rating", false),
            Triple("ARTICLE_REWARDS", "hide_article_rewards", false),
            Triple("ARTICLE_RECOMMENDATIONS", "hide_article_recommendations", false),
            Triple("ARTICLE_COMMISSION", "hide_article_commission", false),
            Triple("COMMENT_TREE", "comment_tree_lines", false),
        )

        assertEquals(
            expected,
            Toggle.values().map { Triple(it.name, it.key, it.defaultValue) },
        )
        assertEquals(Toggle.values().size, Toggle.values().map { it.key }.toSet().size)
    }

    @Test
    fun defaultsStartAtRevisionOneWithRefreshOffAndEveryTogglePresent() {
        val settings = ModuleSettings.defaults(WRITER_ID)

        assertEquals(WRITER_ID, settings.writerId)
        assertEquals(1L, settings.revision)
        assertEquals(0, settings.autoRefreshMinutes)
        assertEquals(Toggle.values().toSet(), settings.toggles.keys)
        Toggle.values().forEach { toggle ->
            assertEquals(toggle.name, toggle.defaultValue, settings.raw(toggle))
            assertEquals(toggle.name, toggle.defaultValue, settings.enabled(toggle))
        }
    }

    @Test
    fun defaultsSerializeAsOneCompleteTypedSnapshot() {
        val preferences: Map<String, Any> = ModuleSettings.defaults(WRITER_ID).toPreferences()

        assertEquals(validPreferences(), preferences)
        assertTrue(preferences["settings_schema"] is Int)
        assertTrue(preferences["settings_revision"] is Long)
        assertTrue(preferences["settings_writer_id"] is String)
        assertTrue(preferences["auto_refresh_minutes"] is Int)
        Toggle.values().forEach { assertTrue(preferences[it.key] is Boolean) }
    }

    @Test
    fun validCompletePreferencesDecodeToDefaults() {
        val decoded = ModuleSettings.fromPreferences(validPreferences())

        assertNotNull(decoded)
        assertEquals(ModuleSettings.defaults(WRITER_ID), decoded)
    }

    @Test
    fun nonDefaultSnapshotRoundTripPreservesAllValues() {
        val original = ModuleSettings(
            writerId = OTHER_WRITER_ID,
            revision = 42L,
            autoRefreshMinutes = 10,
            toggles = Toggle.values().associateWith { !it.defaultValue },
        )

        assertEquals(original, ModuleSettings.fromPreferences(original.toPreferences()))
    }

    @Test
    fun disablingMasterGatesEveryToggleWithoutErasingRawChoices() {
        val settings = ModuleSettings.defaults(WRITER_ID).copy(
            toggles = Toggle.values().associateWith { it != Toggle.MASTER },
        )

        assertFalse(settings.raw(Toggle.MASTER))
        Toggle.values().forEach { toggle ->
            assertFalse(toggle.name, settings.enabled(toggle))
            if (toggle != Toggle.MASTER) {
                assertTrue(toggle.name, settings.raw(toggle))
            }
        }
    }

    @Test
    fun enablingMasterAgainRestoresPreviouslySelectedChildren() {
        val off = ModuleSettings.defaults(WRITER_ID).copy(
            toggles = Toggle.values().associateWith { it != Toggle.MASTER },
        )
        val on = off.copy(toggles = off.toggles + (Toggle.MASTER to true))

        Toggle.values().forEach { assertTrue(it.name, on.enabled(it)) }
        Toggle.values().filter { it != Toggle.MASTER }.forEach {
            assertEquals(it.name, off.raw(it), on.raw(it))
        }
    }

    @Test
    fun eachChildToggleIsIndependentlyEffectiveWhenMasterIsOn() {
        Toggle.values().filter { it != Toggle.MASTER }.forEach { selected ->
            val settings = ModuleSettings.defaults(WRITER_ID).copy(
                toggles = Toggle.values().associateWith {
                    it == Toggle.MASTER || it == selected
                },
            )

            assertTrue(settings.enabled(Toggle.MASTER))
            Toggle.values().filter { it != Toggle.MASTER }.forEach { candidate ->
                assertEquals(
                    "selected=${selected.name}, candidate=${candidate.name}",
                    candidate == selected,
                    settings.enabled(candidate),
                )
            }
        }
    }

    @Test
    fun refreshOptionsMapUiIndicesToMinutesRatherThanPersistingIndices() {
        val expectedMinutes = listOf(0, 1, 5, 10)
        assertEquals(expectedMinutes, SettingsContract.REFRESH_MINUTES)

        expectedMinutes.forEachIndexed { index, minutes ->
            val settings = ModuleSettings.defaults(WRITER_ID).copy(
                autoRefreshMinutes = SettingsContract.REFRESH_MINUTES[index],
            )
            val serialized = settings.toPreferences()
            val decoded = ModuleSettings.fromPreferences(serialized)

            assertEquals(minutes, serialized["auto_refresh_minutes"])
            assertNotNull(decoded)
            assertEquals(minutes, decoded!!.autoRefreshMinutes)
            assertEquals(index, SettingsContract.REFRESH_MINUTES.indexOf(decoded.autoRefreshMinutes))
        }
    }

    @Test
    fun refreshMinutesRejectEveryTestedNonOptionIncludingDropdownIndexTwoAndThree() {
        listOf(-1, 2, 3, 4, 6, 9, 11, 60, Int.MAX_VALUE).forEach { minutes ->
            assertRejected("auto_refresh_minutes", minutes)
        }
    }

    @Test
    fun refreshMinutesRejectWrongTypesInsteadOfCoercingThem() {
        listOf<Any?>(null, "5", 5L, 5.0, 5.0f, true).forEach { value ->
            assertRejected("auto_refresh_minutes", value)
        }
    }

    @Test
    fun schemaMustBeIntOneTwoThreeFourOrFiveNotAnUnknownVersionOrCoercibleType() {
        listOf<Any?>(null, 0, -1, 6, Int.MAX_VALUE, 1L, 2L, 3L, 4L, 5L, "1", "2", "3", "4", "5", 5.0, 5.0f, true)
            .forEach { value -> assertRejected("settings_schema", value) }
    }

    @Test
    fun schemaFiveIsCurrentWhileSchemaFourIsHistoricalAndSchemaSixIsUnknown() {
        val current = validPreferences()
        assertEquals(5, current["settings_schema"])
        assertNotNull(ModuleSettings.fromPreferences(current))
        val schemaFour = schemaFourPreferences()
        assertEquals(4, schemaFour["settings_schema"])
        assertFalse(schemaFour.containsKey("comment_tree_lines"))
        assertNotNull(ModuleSettings.fromPreferences(schemaFour))
        assertNotNull(ModuleSettings.fromPreferences(current + ("settings_schema" to 3) - "hide_article_commission"))
        assertNull(ModuleSettings.fromPreferences(current + ("settings_schema" to 6)))
    }

    @Test
    fun bothLegacyEventValuesDecodeReadOnlyWithRewardsOffAndAllOtherValuesPreserved() {
        listOf(false, true).forEach { eventCalendar ->
            val legacy = legacyPreferences(eventCalendar)
            val before = legacy.toMap()
            val decoded = ModuleSettings.fromPreferences(legacy)
            assertNotNull("legacy event=$eventCalendar", decoded)
            val serialized = decoded!!.toPreferences()

            assertEquals(5, serialized["settings_schema"])
            assertFalse(serialized.containsKey("hide_event_calendar"))
            assertEquals(false, serialized["hide_article_rewards"])
            assertFalse(serialized.containsKey("comment_thread_lines"))
            before.filterKeys { it != "settings_schema" && it != "hide_event_calendar" }
                .forEach { (key, value) -> assertEquals("key=$key", value, serialized[key]) }
            assertFalse(decoded.raw(Toggle.MASTER))
            assertEquals(10, decoded.autoRefreshMinutes)
            assertEquals(before, legacy)
        }
    }

    @Test
    fun schemaTwoDecodesReadOnlyWithAllOldValuesPreserved() {
        val legacy = schemaTwoPreferences()
        val before = legacy.toMap()
        val decoded = ModuleSettings.fromPreferences(legacy)

        assertNotNull(decoded)
        val serialized = decoded!!.toPreferences()
        assertEquals(5, serialized["settings_schema"])
        assertFalse(serialized.containsKey("comment_thread_lines"))
        before.filterKeys { it != "settings_schema" }.forEach { (key, value) ->
            assertEquals("key=$key", value, serialized[key])
        }
        assertEquals(before, legacy)
    }

    @Test
    fun legacySchemasIgnoreRetiredCommentKeyWithoutExportingIt() {
        legacySettingsSnapshots().forEach { legacy ->
            val decoded = ModuleSettings.fromPreferences(legacy + ("comment_thread_lines" to true))

            assertNotNull(decoded)
            assertFalse(decoded!!.toPreferences().containsKey("comment_thread_lines"))
        }
    }

    @Test
    fun frozenLegacyFixturesKeepExactlyTheirOriginalSchemaKeys() {
        val commonKeys = setOf(
            "settings_schema", "settings_revision", "settings_writer_id", "auto_refresh_minutes",
            "master_enabled", "hide_ad_cards", "hide_pinned_cards", "hide_web_promos",
            "hide_home_carousel", "hide_article_ai_summary", "hide_article_rating",
            "hide_article_recommendations",
        )

        listOf(false, true).forEach { eventCalendar ->
            val schemaOne = legacyPreferences(eventCalendar)
            assertEquals(commonKeys + "hide_event_calendar", schemaOne.keys)
            assertEquals(1, schemaOne["settings_schema"])
            assertEquals(eventCalendar, schemaOne["hide_event_calendar"])
        }
        val schemaTwo = schemaTwoPreferences()
        assertEquals(commonKeys + "hide_article_rewards", schemaTwo.keys)
        assertEquals(2, schemaTwo["settings_schema"])
        assertEquals(true, schemaTwo["hide_article_rewards"])
        val schemaThree = schemaThreePreferences()
        assertEquals(commonKeys + "hide_article_rewards", schemaThree.keys)
        assertEquals(3, schemaThree["settings_schema"])
        assertEquals(true, schemaThree["hide_article_recommendations"])
    }

    @Test
    fun schemaThreeRequiresAnExplicitAiBooleanAndRetainsItsChoiceBehindMaster() {
        val preferences = schemaThreePreferences()
        assertNull(ModuleSettings.fromPreferences(preferences - "hide_article_ai_summary"))
        listOf<Any?>(null, "false", 0, 1L, 1.0).forEach { invalid ->
            assertNull(ModuleSettings.fromPreferences(preferences + ("hide_article_ai_summary" to invalid)))
        }
        val selected = ModuleSettings.fromPreferences(
            preferences + mapOf("hide_article_ai_summary" to true, "master_enabled" to false),
        )!!

        assertTrue(selected.raw(Toggle.AI_SUMMARY))
        assertFalse(selected.enabled(Toggle.AI_SUMMARY))
        assertEquals(true, selected.toPreferences()["hide_article_ai_summary"])
        assertEquals(selected, ModuleSettings.fromPreferences(selected.toPreferences()))
        val masterOn = selected.copy(toggles = selected.toggles + (Toggle.MASTER to true))
        assertTrue(masterOn.enabled(Toggle.AI_SUMMARY))
    }

    @Test
    fun legacySnapshotsRequireEveryOldKeyEvenTheDiscardedCalendarBoolean() {
        legacySettingsSnapshots().forEach { legacy ->
            legacy.keys.forEach { missing ->
                assertNull("schema=${legacy["settings_schema"]} missing=$missing", ModuleSettings.fromPreferences(legacy - missing))
            }
            legacy.filterValues { it is Boolean }.keys.forEach { key ->
                listOf<Any?>(null, "true", 0, 1L).forEach { invalid ->
                    assertNull("key=$key value=$invalid", ModuleSettings.fromPreferences(legacy + (key to invalid)))
                }
            }
        }
    }

    @Test
    fun legacyMetadataIsAsStrictAsCurrentMetadata() {
        mapOf(
            "settings_revision" to listOf<Any?>(0L, -1L, 41, "41", null),
            "settings_writer_id" to listOf<Any?>("not-a-uuid", null, 1L),
            "auto_refresh_minutes" to listOf<Any?>(2, 3, 10L, "10", null),
            "settings_schema" to listOf<Any?>(1L, "1", 6, null),
        ).forEach { (key, invalidValues) ->
            legacySettingsSnapshots().forEach { legacy ->
                invalidValues.forEach { invalid ->
                    assertNull("key=$key value=$invalid", ModuleSettings.fromPreferences(legacy + (key to invalid)))
                }
            }
        }
    }

    @Test
    fun revisionMustBeStrictlyPositiveLong() {
        listOf<Any?>(null, 0L, -1L, Long.MIN_VALUE, 1, "1", 1.0, 1.0f, true)
            .forEach { value -> assertRejected("settings_revision", value) }
    }

    @Test
    fun maximumPositiveLongRevisionIsStillAValidSnapshot() {
        val decoded = ModuleSettings.fromPreferences(
            validPreferences().apply { put("settings_revision", Long.MAX_VALUE) },
        )

        assertNotNull(decoded)
        assertEquals(Long.MAX_VALUE, decoded!!.revision)
    }

    @Test
    fun writerMustBeACanonicalUuidStringNotAnArbitraryIdentifier() {
        listOf<Any?>(
            null,
            "",
            "not-a-uuid",
            "1-1-1-1-1",
            "550e8400e29b41d4a716446655440000",
            "550e8400-e29b-41d4-a716-44665544000",
            "550e8400-e29b-41d4-a716-4466554400000",
            "550e8400-e29b-41d4-a716-44665544000g",
            " $WRITER_ID",
            "$WRITER_ID ",
            "{$WRITER_ID}",
            1L,
            true,
        ).forEach { value -> assertRejected("settings_writer_id", value) }
    }

    @Test
    fun everyToggleRejectsNonBooleanValues() {
        Toggle.values().forEach { toggle ->
            listOf<Any?>(null, "true", "false", 0, 1, 1L).forEach { value ->
                assertRejected(toggle.key, value)
            }
        }
    }

    @Test
    fun missingAnyRequiredKeyRejectsTheWholeSnapshot() {
        val complete = validPreferences()
        complete.keys.forEach { missingKey ->
            val partial = complete.toMutableMap().apply { remove(missingKey) }

            assertNull("missing=$missingKey", ModuleSettings.fromPreferences(partial))
        }
    }

    @Test
    fun emptyPreferencesAreUnavailableRatherThanImplicitlyEnablingDefaults() {
        assertNull(ModuleSettings.fromPreferences(emptyMap<String, Any>()))
    }

    @Test
    fun decodedSnapshotDoesNotChangeWhenSourcePreferencesAreModified() {
        val values = validPreferences()
        val decoded = ModuleSettings.fromPreferences(values)
        assertNotNull(decoded)

        values["master_enabled"] = false
        values["hide_ad_cards"] = false
        values["settings_revision"] = 9L
        values["auto_refresh_minutes"] = 10

        assertTrue(decoded!!.raw(Toggle.MASTER))
        assertTrue(decoded.raw(Toggle.AD_CARDS))
        assertEquals(1L, decoded.revision)
        assertEquals(0, decoded.autoRefreshMinutes)
    }

    private fun assertRejected(key: String, value: Any?) {
        val values: MutableMap<String, Any?> = validPreferences().toMutableMap()
        values[key] = value

        assertNull(
            "key=$key, value=$value, type=${value?.javaClass?.simpleName}",
            ModuleSettings.fromPreferences(values),
        )
    }

    private fun validPreferences(): MutableMap<String, Any> = linkedMapOf(
        "settings_schema" to 5,
        "settings_revision" to 1L,
        "settings_writer_id" to WRITER_ID,
        "auto_refresh_minutes" to 0,
        "master_enabled" to true,
        "hide_ad_cards" to true,
        "hide_pinned_cards" to true,
        "hide_web_promos" to true,
        "hide_home_carousel" to false,
        "hide_article_ai_summary" to false,
        "hide_article_rating" to false,
        "hide_article_rewards" to false,
        "hide_article_recommendations" to false,
        "hide_article_commission" to false,
        "comment_tree_lines" to false,
    )

    private companion object {
        const val WRITER_ID = "550e8400-e29b-41d4-a716-446655440000"
        const val OTHER_WRITER_ID = "6ba7b810-9dad-41d1-80b4-00c04fd430c8"
    }
}
