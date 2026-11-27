package io.github.ghsssss.ithomeadkill.hook

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ArticleCommissionStyleTest {
    @Test
    fun disabledCommissionKeepsAllPreviousCssEmpty() {
        val script = ArticleStyle.script(false, false, false, false)
        assertTrue(script.contains("var css = '';"))
        assertTrue(script.contains("commissionRequested:false"))
    }

    @Test
    fun commissionHidesOnlyArticleCommerceCardsAndTheDedicatedDisclosure() {
        val script = ArticleStyle.script(false, false, false, true)
        assertTrue(script.contains("#post_body .card.main-site"))
        assertTrue(script.contains("#post_body .ad-tips"))
        assertTrue(script.contains(".content.news-content .card.main-site"))
        assertTrue(script.contains(".content.news-content .ad-tips"))
        assertTrue(script.contains("commissionRequested:true"))
        assertFalse(script.contains("document.body.innerHTML"))
        assertFalse(script.contains(".remove()"))
        assertFalse(script.contains("MutationObserver"))
    }

    @Test
    fun commissionDoesNotEnableTheOtherArticleRules() {
        val script = ArticleStyle.script(false, false, false, true)
        assertFalse(script.contains(".ai-abstract { display:"))
        assertFalse(script.contains("#userGrade, #editorGrade, .footer > .news_grade { display:"))
        assertFalse(script.contains("body > .reward { display:"))
    }

    @Test
    fun affiliateMatchingDoesNotBlockAllOfficialStoreLinksOrMatchLinkText() {
        val script = ArticleStyle.script(false, false, false, true)
        assertTrue(script.contains("u.jd.com/"))
        assertTrue(script.contains("s.click.taobao.com/"))
        assertTrue(script.contains("union-click.jd.com/"))
        assertTrue(script.contains("data-androidurl"))
        assertTrue(script.contains("data-appurl"))
        assertFalse(script.contains("href*=\"jd.com"))
        assertFalse(script.contains("includes('京东')"))
        assertFalse(script.contains("直达链接"))
        assertFalse(script.contains("广告声明"))
    }

    @Test
    fun currentProductionHostPassesTheMasterGatedCommissionToggle() {
        val source = File("src/main/java/io/github/ghsssss/ithomeadkill/hook/HostRuntime.kt").readText()
        assertTrue(source.contains("settings?.enabled(Toggle.ARTICLE_COMMISSION) == true"))
    }
}
