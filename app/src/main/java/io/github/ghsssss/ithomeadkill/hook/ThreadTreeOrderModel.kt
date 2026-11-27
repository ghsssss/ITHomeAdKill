package io.github.ghsssss.ithomeadkill.hook

internal object ThreadTreeOrderModel {
    private const val MAX_RECORDS = 4096

    /**
     * Returns every original adapter position exactly once, without touching host entities.
     * Positions define native order; only a true root's contiguous reply block may change.
     * Roots, folds and other barriers keep their native slots. An invalid block stays native;
     * duplicate positions or an exceeded budget preserve the exact input sequence instead.
     * No state is retained: expansion and changed parent metadata are evaluated on every call.
     */
    fun order(records: List<TreeRecord>): List<Int> {
        val originalPositions = records.map { it.position }
        if (records.size > MAX_RECORDS || originalPositions.toSet().size != records.size) {
            return originalPositions
        }
        val canonical = records.sortedBy { it.position }
        val result = canonical.map { it.position }.toMutableList()
        var index = 0
        while (index < canonical.size) {
            val root = canonical[index]
            if (root.position < 0 || root.itemType != 0 || root.id <= 0 || root.rootId != 0L) {
                index++
                continue
            }
            var end = index + 1
            var completeBlock = true
            while (end < canonical.size) {
                val reply = canonical[end]
                // A header/fold/unknown ends this block; replies after it need a new true root.
                if (reply.itemType !in 1..4) break
                if (reply.rootId != root.id || reply.hot != root.hot ||
                    reply.position.toLong() != canonical[end - 1].position.toLong() + 1L) {
                    // Do not sort a valid-looking prefix of a contradictory reply block.
                    completeBlock = false
                    break
                }
                end++
            }
            val ordered = if (completeBlock) orderGroup(canonical.subList(index, end)) else null
            if (ordered != null) {
                for ((offset, position) in ordered.withIndex()) result[index + offset] = position
            }
            index = end
        }
        return result
    }

    private fun orderGroup(group: List<TreeRecord>): List<Int>? {
        if (group.any { it.id <= 0 || it.replyId < 0 }) return null
        val root = group.first()
        // Index the complete block before resolving parents: a target can occur later natively.
        val byId = group.associateBy { it.id }
        if (byId.size != group.size) return null
        val children = mutableMapOf<Long, MutableList<TreeRecord>>()
        for (reply in group.drop(1)) {
            val target = if (reply.replyId == 0L) root.id else reply.replyId
            if (target == reply.id || target !in byId) return null
            children.getOrPut(target) { mutableListOf() }.add(reply)
        }
        val stack = ArrayDeque<TreeRecord>()
        val visited = mutableSetOf<Long>()
        val ordered = mutableListOf<Int>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val record = stack.removeLast()
            if (!visited.add(record.id)) return null
            ordered += record.position
            // LIFO reversal preserves canonical sibling order without recursive call-stack depth.
            for (child in children[record.id].orEmpty().asReversed()) stack.addLast(child)
        }
        // A disconnected component in a complete, single-parent graph contains a cycle.
        return ordered.takeIf { it.size == group.size }
    }
}
