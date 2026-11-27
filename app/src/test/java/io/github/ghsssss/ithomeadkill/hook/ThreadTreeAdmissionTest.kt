package io.github.ghsssss.ithomeadkill.hook

import org.junit.Assert.*
import org.junit.Test

class ThreadTreeAdmissionTest {
    @Test fun verifiedHotCommentPopupIsSupported() {
        assertTrue(ThreadTreeAdmission.isSupported(
            "com.ruanmei.ithome.ui.NewsCommentUnfoldActivity", "rv_hotComment",
        ))
    }

    @Test fun supportedPageAndListMatrixContainsOnlyTheThreeVerifiedPairs() {
        val supported = setOf(
            "com.ruanmei.ithome.ui.NewsInfoV8Activity" to "rv_comment",
            "com.ruanmei.ithome.ui.CommentListV8Activity" to "rv_commentList",
            "com.ruanmei.ithome.ui.NewsCommentUnfoldActivity" to "rv_hotComment",
        )
        val activities = supported.map { it.first } + listOf(
            "com.ruanmei.ithome.ui.QuanMoreReplyActivity",
            "com.ruanmei.ithome.ui.MainActivity",
            "",
        )
        val listIds = supported.map { it.second } + listOf("rv_reply", "")
        for (activity in activities) for (listId in listIds) {
            assertEquals("$activity / $listId", (activity to listId) in supported,
                ThreadTreeAdmission.isSupported(activity, listId))
        }
    }

    @Test fun circleRepliesDoNotBecomeSupportedByReusingThePopupListId() {
        assertFalse(ThreadTreeAdmission.isSupported(
            "com.ruanmei.ithome.ui.QuanMoreReplyActivity", "rv_hotComment",
        ))
    }

    @Test fun nearMatchActivityNamesDoNotWidenAdmission() {
        val popup = "com.ruanmei.ithome.ui.NewsCommentUnfoldActivity"
        for (activity in listOf("NewsCommentUnfoldActivity", "$popup.Child", "$popup ", popup.lowercase())) {
            assertFalse(activity, ThreadTreeAdmission.isSupported(activity, "rv_hotComment"))
        }
    }

    @Test fun nearMatchListIdsDoNotWidenAdmission() {
        for (listId in listOf("rv_hotcomment", "rv_hotComment ", "id/rv_hotComment", "rv_hotComment_extra")) {
            assertFalse(listId, ThreadTreeAdmission.isSupported(
                "com.ruanmei.ithome.ui.NewsCommentUnfoldActivity", listId,
            ))
        }
    }
}
