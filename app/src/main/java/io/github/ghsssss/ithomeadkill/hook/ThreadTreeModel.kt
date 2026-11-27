package io.github.ghsssss.ithomeadkill.hook

internal data class TreeRecord(
    val position: Int,
    val id: Long,
    val rootId: Long,
    val replyId: Long,
    val itemType: Int,
    val hot: Boolean = false,
)

internal data class TreeNode(
    val position: Int,
    val parentPosition: Int?,
    val rootPosition: Int,
    val depth: Int,
)

internal data class TreeTopology(val records: List<TreeRecord>, val nodes: Map<Int, TreeNode>)
internal data class TreeRect(val left: Float, val top: Float, val right: Float, val bottom: Float)
internal data class TreePlacement(val row: TreeRect, val avatar: TreeRect,
    val clearanceBottom: Float = avatar.bottom)
internal data class TreePoint(val x: Float, val y: Float)
internal data class TreeStroke(
    val start: TreePoint,
    val corner: TreePoint,
    val tip: TreePoint,
    val radius: Float,
    val terminal: Boolean,
)

internal object ThreadTreeModel {
    private const val MAX_RECORDS = 4096

    /** Keep every row, including barriers; never truncate a snapshot into a false last child. */
    fun topology(records: List<TreeRecord>): TreeTopology {
        if (records.size > MAX_RECORDS) return TreeTopology(emptyList(), emptyMap())
        val snapshot = records.sortedBy { it.position }
        if (snapshot.zipWithNext().any { (a, b) -> a.position == b.position }) {
            return TreeTopology(snapshot, emptyMap())
        }
        // Hot cards legitimately repeat normal-section IDs and do not make those IDs ambiguous.
        val counts = snapshot.filter { !it.hot && it.itemType in 0..4 && it.id > 0 }
            .groupingBy { it.id }.eachCount()
        val nodes = linkedMapOf<Int, TreeNode>()
        val byId = mutableMapOf<Long, TreeNode>()
        var activeRoot: TreeRecord? = null
        var previousPosition: Int? = null
        for (record in snapshot) {
            if (previousPosition != null && record.position.toLong() != previousPosition.toLong() + 1L) {
                activeRoot = null
                byId.clear()
            }
            previousPosition = record.position
            if (record.position < 0 || record.hot || record.itemType !in 0..4) {
                activeRoot = null
                byId.clear()
                continue
            }
            val unique = record.id > 0 && counts[record.id] == 1
            val node = if (record.itemType == 0) {
                activeRoot = record.takeIf { unique && it.rootId == 0L }
                byId.clear()
                if (activeRoot == null) continue
                TreeNode(record.position, null, record.position, 0)
            } else {
                val root = activeRoot ?: continue
                if (!unique || root.id != record.rootId || record.replyId < 0) continue
                // parentCommentId identifies the root, not the direct parent.
                val target = if (record.replyId == 0L) record.rootId else record.replyId
                val parent = byId[target] ?: continue
                // Only earlier accepted nodes in this section can be parents: no cycles/future/cross-root edges.
                TreeNode(record.position, parent.position, parent.rootPosition, parent.depth + 1)
            }
            nodes[record.position] = node
            byId[record.id] = node
        }
        return TreeTopology(snapshot, nodes)
    }

    /** The host owns depth 0/1 indentation. Only available content width caps additional depth. */
    fun extraIndent(depth: Int, density: Float, availableWidth: Float, maximumDepth: Int = depth): Float {
        if (depth <= 1 || !density.isFinite() || density <= 0f || !availableWidth.isFinite()) return 0f
        val budget = (availableWidth.toDouble() - 200.0 * density).coerceAtLeast(0.0)
        val layers = (maxOf(depth, maximumDepth).toDouble() - 1.0).coerceAtLeast(1.0)
        val step = minOf(36.0 * density, budget / layers)
        return ((depth.toDouble() - 1.0) * step).coerceIn(0.0, budget).toFloat()
    }

    fun replyColumns(depth: Int, center: Float, density: Float, availableWidth: Float,
        maximumDepth: Int, rtl: Boolean = false): Map<Int, Float> {
        if (depth !in 1..MAX_RECORDS || maximumDepth !in depth..MAX_RECORDS || !center.isFinite() ||
            !density.isFinite() || density <= 0f || !availableWidth.isFinite() || availableWidth <= 0f) return emptyMap()
        val direction = if (rtl) -1f else 1f
        val first = center - direction * extraIndent(depth, density, availableWidth, maximumDepth)
        return (1..maximumDepth).associateWith { first + direction * extraIndent(it, density, availableWidth, maximumDepth) }
    }

    /**
     * Shared trunks use the complete topology, never the last visible adapter row.
     * A radius-0, same-x stroke fills only the small vertical gap beside a nonterminal bend.
     * Renderers must draw these returned paths as-is, not add another full parent trunk.
     */
    fun geometry(
        topology: TreeTopology,
        placements: Map<Int, TreePlacement>,
        viewport: TreeRect,
        density: Float,
        rtl: Boolean = false,
        columns: Map<Int, Float> = emptyMap(),
    ): List<TreeStroke> {
        if (!density.isFinite() || density <= 0f || !viewport.isValid()) return emptyList()
        if (topology.records.size > MAX_RECORDS || placements.size > MAX_RECORDS || placements.isEmpty()) return emptyList()
        val gap = 4f * density
        val bend = 6f * density
        if (!gap.isFinite() || !bend.isFinite()) return emptyList()
        // Also fail open for stale or manually assembled inconsistent topologies.
        val checked = topology(topology.records)
        if (checked.records != topology.records || checked.nodes != topology.nodes || checked.nodes.isEmpty()) return emptyList()
        val records = topology.records
        val nodes = topology.nodes
        val index = records.withIndex().associate { it.value.position to it.index }
        val byPosition = records.associateBy { it.position }
        val children = nodes.values.filter { it.parentPosition != null }.groupBy { it.parentPosition!! }
            .mapValues { (_, values) -> values.sortedBy { it.position } }
        val bounds = subtreeBounds(nodes, children)
        val rawDirect = records.filter { !it.hot && it.itemType in 1..4 && it.rootId > 0 }
            .groupBy { it.rootId to if (it.replyId == 0L) it.rootId else it.replyId }
        val measured = placements.filterValues { it.isValid() }
        val firstAttached = placements.keys.minOrNull() ?: return emptyList()
        val lastAttached = placements.keys.maxOrNull() ?: return emptyList()
        val epsilon = 0.001f * density
        val output = mutableListOf<TreeStroke>()

        for ((parentPosition, direct) in children) {
            val parent = nodes.getValue(parentPosition)
            val parentRecord = byPosition.getValue(parentPosition)
            val rootRecord = byPosition.getValue(parent.rootPosition)
            // A rejected later direct child must not turn an earlier accepted one into a terminal.
            val claimed = rawDirect[rootRecord.id to parentRecord.id].orEmpty().map { it.position }
            if (claimed != direct.map { it.position }) continue
            val lastChild = direct.last()
            if (lastChild.position < firstAttached || parentPosition > lastAttached) continue
            val interval = records.subList(index.getValue(parentPosition), index.getValue(lastChild.position) + 1)
            if (interval.zipWithNext().any { (a, b) -> b.position.toLong() != a.position.toLong() + 1L }) continue
            // Across-order reply targets keep their real depth, but an unrelated sibling blocks the whole connector group.
            if (interval.drop(1).any { record -> !isDescendant(record.position, parentPosition, bounds) }) continue
            // Missing/invalid measurements inside the attached adapter range are holes, never offscreen evidence.
            val attachedInterval = interval.filter { it.position in firstAttached..lastAttached }
            if (attachedInterval.any { it.position !in measured }) continue
            val ordered = attachedInterval.map { measured.getValue(it.position) }
            if (ordered.zipWithNext().any { (a, b) -> a.row.bottom > b.row.top + epsilon }) continue

            val parentPlacement = measured[parentPosition]
            if (ordered.isEmpty()) continue
            val x = parentPlacement?.avatar?.centerX()
                ?: witnessedColumn(parent, nodes, measured, epsilon)
                ?: columns[parent.depth]
                ?: continue
            if (!x.isFinite() || x < viewport.left || x > viewport.right) continue
            var cursor = if (parentPlacement == null) viewport.top else {
                (parentPlacement.clearanceBottom.toDouble() + gap).coerceIn(viewport.top.toDouble(), viewport.bottom.toDouble()).toFloat()
            }
            if (cursor >= viewport.bottom) continue
            val group = mutableListOf<TreeStroke>()
            var continues = false
            var valid = true
            for ((childIndex, child) in direct.withIndex()) {
                val placement = measured[child.position]
                if (placement == null) {
                    if (child.position < firstAttached) continue // Proven prefix before the first attached row.
                    if (child.position > lastAttached) {
                        continues = true // Complete, gap-free descendant suffix after every attached row.
                        break
                    }
                    valid = false
                    break
                }
                val y = placement.avatar.centerY()
                if (y < viewport.top) continue
                if (y > viewport.bottom) {
                    continues = true
                    break
                }
                val tip = if (rtl) placement.avatar.right + gap else placement.avatar.left - gap
                val span = if (rtl) x.toDouble() - tip else tip.toDouble() - x
                if (!tip.isFinite() || tip < viewport.left || tip > viewport.right || span <= 0.0 || y < cursor) {
                    valid = false
                    break
                }
                val radius = minOf(bend.toDouble(), span, y.toDouble() - cursor).toFloat()
                val terminal = childIndex == direct.lastIndex
                group += TreeStroke(TreePoint(x, cursor), TreePoint(x, y), TreePoint(tip, y), radius, terminal)
                if (!terminal && radius > 0f) {
                    // The curved branch leaves a radius-high hole on the trunk. Fill exactly that hole once.
                    group += vertical(x, y - radius, y)
                }
                cursor = y
            }
            if (!valid) continue
            if (continues && cursor < viewport.bottom) group += vertical(x, cursor, viewport.bottom)
            output += group
        }
        return output
    }

    /** A missing parent has no invented x/indent. A measured peer at the same depth/root can witness its column. */
    private fun witnessedColumn(
        parent: TreeNode,
        nodes: Map<Int, TreeNode>,
        measured: Map<Int, TreePlacement>,
        epsilon: Float,
    ): Float? {
        val witnesses = measured.mapNotNull { (position, placement) ->
            val node = nodes[position] ?: return@mapNotNull null
            if (node.rootPosition != parent.rootPosition || node.depth != parent.depth) return@mapNotNull null
            placement
        }
        val first = witnesses.firstOrNull() ?: return null
        val x = first.avatar.centerX()
        // Descendant cards deliberately have different margins; compare only measured parent-level peers.
        return x.takeIf { witnesses.all { other ->
            kotlin.math.abs(other.row.left - first.row.left) <= epsilon &&
                kotlin.math.abs(other.row.right - first.row.right) <= epsilon &&
                kotlin.math.abs(other.avatar.width() - first.avatar.width()) <= epsilon &&
                kotlin.math.abs(other.avatar.centerX() - x) <= epsilon
        } }
    }

    /** Iterative Euler intervals make ancestry checks constant-time without recursive deep-tree overflow. */
    private fun subtreeBounds(nodes: Map<Int, TreeNode>, children: Map<Int, List<TreeNode>>): Map<Int, IntRange> {
        val entry = mutableMapOf<Int, Int>()
        val bounds = mutableMapOf<Int, IntRange>()
        val stack = ArrayDeque<Pair<Int, Boolean>>()
        for (root in nodes.values.filter { it.parentPosition == null }.reversed()) stack.addLast(root.position to false)
        var tick = 0
        while (stack.isNotEmpty()) {
            val (position, leaving) = stack.removeLast()
            if (leaving) {
                bounds[position] = entry.getValue(position) until tick
            } else {
                entry[position] = tick++
                stack.addLast(position to true)
                for (child in children[position].orEmpty().asReversed()) stack.addLast(child.position to false)
            }
        }
        return bounds
    }

    private fun isDescendant(position: Int, ancestor: Int, bounds: Map<Int, IntRange>): Boolean {
        val node = bounds[position] ?: return false
        val parent = bounds[ancestor] ?: return false
        return node.first > parent.first && node.first in parent
    }

    private fun vertical(x: Float, top: Float, bottom: Float) =
        TreeStroke(TreePoint(x, top), TreePoint(x, bottom), TreePoint(x, bottom), 0f, terminal = false)

    private fun TreePlacement.isValid(): Boolean = row.isValid() && avatar.isValid() &&
        avatar.left >= row.left && avatar.right <= row.right && avatar.top >= row.top && avatar.bottom <= row.bottom &&
        clearanceBottom.isFinite() && clearanceBottom >= avatar.bottom && clearanceBottom <= row.bottom

    private fun TreeRect.isValid(): Boolean =
        left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() && right > left && bottom > top

    private fun TreeRect.width(): Double = right.toDouble() - left
    private fun TreeRect.centerX(): Float = ((left.toDouble() + right) / 2.0).toFloat()
    private fun TreeRect.centerY(): Float = ((top.toDouble() + bottom) / 2.0).toFloat()
}
