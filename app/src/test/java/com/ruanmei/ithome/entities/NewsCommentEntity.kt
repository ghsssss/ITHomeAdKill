package com.ruanmei.ithome.entities

/** Test-only field contract, not host binary code. */
open class CommentFields(
    @JvmField var id: Any = 101L,
    @JvmField var parentCommentId: Any = 0L,
    @JvmField var replyCommentId: Any = 0L,
    @JvmField var itemType: Any = 0,
    @JvmField var _isHot: Any = false,
)

class NewsCommentEntity(id: Any = 101L, root: Any = 0L, reply: Any = 0L,
    type: Any = 0, hot: Any = false) : CommentFields(id, root, reply, type, hot)
