package io.github.ghsssss.ithomeadkill.hook

/** Exact page/list contract shared by discovery and the overlay owner. */
internal object ThreadTreeAdmission {
    fun isSupported(activityName: String, listId: String): Boolean = when (activityName) {
        "com.ruanmei.ithome.ui.NewsInfoV8Activity" -> listId == "rv_comment"
        "com.ruanmei.ithome.ui.CommentListV8Activity" -> listId == "rv_commentList"
        "com.ruanmei.ithome.ui.NewsCommentUnfoldActivity" -> listId == "rv_hotComment"
        else -> false
    }
}
