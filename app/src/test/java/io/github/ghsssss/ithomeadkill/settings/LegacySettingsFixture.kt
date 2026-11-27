package io.github.ghsssss.ithomeadkill.settings

/** 显式旧格式夹具，不能由当前序列化器生成，以免把迁移缺陷一起复制进测试。 */
internal fun legacyPreferences(
    eventCalendar: Boolean = true,
    revision: Long = 41L,
): Map<String, Any> = linkedMapOf(
    "settings_schema" to 1,
    "settings_revision" to revision,
    "settings_writer_id" to "6ba7b810-9dad-41d1-80b4-00c04fd430c8",
    "auto_refresh_minutes" to 10,
    "master_enabled" to false,
    "hide_ad_cards" to false,
    "hide_pinned_cards" to true,
    "hide_event_calendar" to eventCalendar,
    "hide_web_promos" to false,
    "hide_home_carousel" to true,
    "hide_article_ai_summary" to true,
    "hide_article_rating" to true,
    "hide_article_recommendations" to true,
)

/** schema 2 的键和值显式冻结；新增 Toggle 不能悄悄扩展旧快照。 */
internal fun schemaTwoPreferences(revision: Long = 41L): Map<String, Any> = linkedMapOf(
    "settings_schema" to 2,
    "settings_revision" to revision,
    "settings_writer_id" to "6ba7b810-9dad-41d1-80b4-00c04fd430c8",
    "auto_refresh_minutes" to 10,
    "master_enabled" to false,
    "hide_ad_cards" to false,
    "hide_pinned_cards" to true,
    "hide_web_promos" to false,
    "hide_home_carousel" to true,
    "hide_article_ai_summary" to true,
    "hide_article_rating" to true,
    "hide_article_rewards" to true,
    "hide_article_recommendations" to true,
)

/** schema 3 保留 AI 和推荐等所有持久键，但没有佣金开关。 */
internal fun schemaThreePreferences(revision: Long = 41L): Map<String, Any> = linkedMapOf(
    "settings_schema" to 3,
    "settings_revision" to revision,
    "settings_writer_id" to "6ba7b810-9dad-41d1-80b4-00c04fd430c8",
    "auto_refresh_minutes" to 10,
    "master_enabled" to false,
    "hide_ad_cards" to false,
    "hide_pinned_cards" to true,
    "hide_web_promos" to false,
    "hide_home_carousel" to true,
    "hide_article_ai_summary" to true,
    "hide_article_rating" to true,
    "hide_article_rewards" to true,
    "hide_article_recommendations" to true,
)

/** schema 4 的旧格式显式冻结；佣金是必填 Boolean，没有新的评论层级键。 */
internal fun schemaFourPreferences(revision: Long = 41L): Map<String, Any> = linkedMapOf(
    "settings_schema" to 4,
    "settings_revision" to revision,
    "settings_writer_id" to "6ba7b810-9dad-41d1-80b4-00c04fd430c8",
    "auto_refresh_minutes" to 10,
    "master_enabled" to false,
    "hide_ad_cards" to false,
    "hide_pinned_cards" to true,
    "hide_web_promos" to false,
    "hide_home_carousel" to true,
    "hide_article_ai_summary" to true,
    "hide_article_rating" to true,
    "hide_article_rewards" to true,
    "hide_article_recommendations" to true,
    "hide_article_commission" to false,
)

/** 当前 fixture 不依赖生产序列化器，旧 schema 4 fixture 不随新增键变化。 */
internal fun schemaFivePreferences(revision: Long = 41L): Map<String, Any> =
    schemaFourPreferences(revision) + mapOf("settings_schema" to 5, "comment_tree_lines" to false)

internal fun legacySettingsSnapshots(revision: Long = 41L): List<Map<String, Any>> = listOf(
    legacyPreferences(eventCalendar = false, revision = revision),
    legacyPreferences(eventCalendar = true, revision = revision),
    schemaTwoPreferences(revision = revision),
    schemaThreePreferences(revision = revision),
)
