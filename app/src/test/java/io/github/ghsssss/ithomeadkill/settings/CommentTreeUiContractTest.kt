package io.github.ghsssss.ithomeadkill.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** 只检查设置声明；Compose/设备渲染留给集成 owner。 */
class CommentTreeUiContractTest {
    @Test
    fun articleSectionDeclaresExactlyOneIndependentMasterGatedCommentTreeSwitch() {
        val source = mainFile("java/io/github/ghsssss/ithomeadkill/ui/SettingsScreen.kt").readText()
        val article = source.substringAfter("item(key = \"article\")").substringBefore("item(key = \"purification\")")
        val blocks = Regex("SettingSwitch\\((.*?)\\n\\s*\\)", RegexOption.DOT_MATCHES_ALL)
            .findAll(article).map { it.groupValues[1] }.toList()
        val matching = blocks.filter { it.contains("toggle = Toggle.COMMENT_TREE,") }
        assertEquals("new tree switch is unique in article section", 1, matching.size)
        assertEquals(1, Regex("toggle = Toggle\\.COMMENT_TREE,").findAll(source).count())
        val tree = matching.single()
        assertTrue(tree.contains("settings = settings,"))
        assertTrue(tree.contains("titleResource = R.string.settings_comment_tree_title,"))
        assertTrue(tree.contains("enabled = childControlsEnabled,"))
        assertTrue(tree.contains("onToggleChange = onToggleChange,"))
        assertFalse(tree.contains("summary"))
        assertTrue(source.contains("val childControlsEnabled = settings.raw(Toggle.MASTER)"))
        assertTrue(source.contains("checked = settings.raw(toggle)"))
        assertFalse(source.contains("comment_thread_lines"))
    }

    @Test
    fun newTitleIsExactAndSettingsStillHaveNoSummaryResources() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(mainFile("res/values/strings.xml"))
        val nodes = document.getElementsByTagName("string")
        val strings = (0 until nodes.length).map { nodes.item(it) as Element }
        val treeTitles = strings.filter { it.getAttribute("name") == "settings_comment_tree_title" }
        assertEquals(1, treeTitles.size)
        assertEquals("评论层级连接线", treeTitles.single().textContent)
        assertFalse(strings.any { it.getAttribute("name").contains("summary") && !it.getAttribute("name").contains("ai_summary") })
        assertFalse(strings.any { it.getAttribute("name").contains("comment_thread") })
        val source = mainFile("java/io/github/ghsssss/ithomeadkill/ui/SettingsScreen.kt").readText()
        assertFalse(source.contains("summary ="))
        assertFalse(source.contains("summaryResource"))
    }

    private fun mainFile(path: String): File = File("src/main/$path").takeIf { it.isFile }
        ?: File("app/src/main/$path")
}
