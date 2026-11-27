package io.github.ghsssss.ithomeadkill.settings

enum class Toggle(val key: String, val defaultValue: Boolean) {
    MASTER("master_enabled", true),
    AD_CARDS("hide_ad_cards", true),
    PINNED_CARDS("hide_pinned_cards", true),
    WEB_PROMOS("hide_web_promos", true),
    HOME_CAROUSEL("hide_home_carousel", false),
    AI_SUMMARY("hide_article_ai_summary", false),
    ARTICLE_RATING("hide_article_rating", false),
    ARTICLE_REWARDS("hide_article_rewards", false),
    ARTICLE_RECOMMENDATIONS("hide_article_recommendations", false),
    ARTICLE_COMMISSION("hide_article_commission", false),
    COMMENT_TREE("comment_tree_lines", false),
}

object SettingsContract {
    const val MODULE_PACKAGE = "io.github.ghsssss.ithomeadkill"
    const val PREFS_NAME = "ithome_settings"
    const val SCHEMA_KEY = "settings_schema"
    const val REVISION_KEY = "settings_revision"
    const val WRITER_KEY = "settings_writer_id"
    const val REFRESH_KEY = "auto_refresh_minutes"
    val REFRESH_MINUTES = listOf(0, 1, 5, 10)
}

data class ModuleSettings(
    val writerId: String,
    val revision: Long,
    val autoRefreshMinutes: Int,
    val toggles: Map<Toggle, Boolean>,
) {
    fun raw(toggle: Toggle): Boolean = toggles[toggle] == true

    fun enabled(toggle: Toggle): Boolean = raw(Toggle.MASTER) && raw(toggle)

    fun toPreferences(): Map<String, Any> = buildMap {
        put(SettingsContract.SCHEMA_KEY, 5)
        put(SettingsContract.REVISION_KEY, revision)
        put(SettingsContract.WRITER_KEY, writerId)
        put(SettingsContract.REFRESH_KEY, autoRefreshMinutes)
        Toggle.values().forEach { put(it.key, raw(it)) }
    }

    companion object {
        private val writerPattern = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
        )

        fun fromPreferences(values: Map<String, *>): ModuleSettings? {
            val schema = values[SettingsContract.SCHEMA_KEY] as? Int ?: return null
            if (schema !in 1..5) return null
            val revision = values[SettingsContract.REVISION_KEY] as? Long ?: return null
            if (revision <= 0L) return null
            val writerId = values[SettingsContract.WRITER_KEY] as? String ?: return null
            if (!writerPattern.matches(writerId)) return null
            val minutes = values[SettingsContract.REFRESH_KEY] as? Int ?: return null
            if (minutes !in SettingsContract.REFRESH_MINUTES) return null
            // 只兼容完整的旧快照；schema 1 的废弃日历键仍须通过类型校验。
            if (schema == 1 && values["hide_event_calendar"] !is Boolean) return null
            val toggles = Toggle.values().associateWith {
                when {
                    schema == 1 && it == Toggle.ARTICLE_REWARDS -> false
                    schema < 4 && it == Toggle.ARTICLE_COMMISSION -> false
                    schema < 5 && it == Toggle.COMMENT_TREE -> false
                    else -> values[it.key] as? Boolean ?: return null
                }
            }
            return ModuleSettings(writerId, revision, minutes, toggles)
        }

        fun defaults(writerId: String): ModuleSettings = ModuleSettings(
            writerId = writerId,
            revision = 1L,
            autoRefreshMinutes = 0,
            toggles = Toggle.values().associateWith { it.defaultValue },
        )
    }
}
