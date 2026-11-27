package io.github.ghsssss.ithomeadkill.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** 只检查文章设置声明，不启动 Android/Compose；渲染验收由集成测试负责。 */
class ArticleCommissionUiContractTest {
    @Test
    fun articleSectionAddsExactlyOneIndependentMasterGatedCommissionSwitch() {
        val source = mainFile("java/io/github/ghsssss/ithomeadkill/ui/SettingsScreen.kt").readText()
        val article = source.substringAfter("item(key = \"article\") {").substringBefore("item(key = \"purification\") {")
        val switches = Regex("toggle = Toggle\\.([A-Z_]+)").findAll(article).map { it.groupValues[1] }.toList()
        assertEquals(listOf("AI_SUMMARY", "ARTICLE_RATING", "ARTICLE_REWARDS", "ARTICLE_COMMISSION", "COMMENT_TREE"), switches)
        val commission = Regex(
            """SettingSwitch\(\s*settings = settings,\s*toggle = Toggle\.ARTICLE_COMMISSION,\s*titleResource = R\.string\.settings_article_commission_title,\s*enabled = childControlsEnabled,\s*onToggleChange = onToggleChange,\s*\)""",
        )
        assertEquals(1, commission.findAll(article).count())
        assertEquals(1, commission.findAll(source).count())
        assertTrue(source.contains("val childControlsEnabled = settings.raw(Toggle.MASTER)"))
        assertTrue(source.contains("checked = settings.raw(toggle)"))
        assertTrue(source.contains("onCheckedChange = { checked -> onToggleChange(toggle, checked) }"))
        assertFalse(source.contains("toggle = Toggle.ARTICLE_RECOMMENDATIONS"))
    }

    @Test
    fun commissionHasExactChineseTitleAndNoGraySummaryResourceOrArgument() {
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(mainFile("res/values/strings.xml")).getElementsByTagName("string")
        val commission = (0 until strings.length).map { strings.item(it) as Element }
            .filter { it.getAttribute("name").contains("commission") }
        assertEquals(listOf("settings_article_commission_title"), commission.map { it.getAttribute("name") })
        assertEquals("关闭佣金广告", commission.single().textContent)
        val source = mainFile("java/io/github/ghsssss/ithomeadkill/ui/SettingsScreen.kt").readText()
        assertFalse(source.contains("summaryResource"))
        assertFalse(source.contains("summary ="))
        assertFalse(source.contains("settings_usage_note"))
        assertFalse(source.contains("onSurfaceVariantSummary"))
    }

    private fun mainFile(path: String): File = File("src/main/$path").takeIf { it.isFile }
        ?: File("app/src/main/$path")
}
