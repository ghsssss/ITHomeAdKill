package io.github.ghsssss.ithomeadkill.hook

import org.junit.Assert.*
import org.junit.Test

class ThreadTreeClearanceTest {
    private val tree = ThreadTreeModel.topology(listOf(TreeRecord(0, 10L, 0L, 0L, 0),
        TreeRecord(1, 11L, 10L, 10L, 1)))
    private val parent = TreePlacement(TreeRect(0f, 0f, 400f, 100f), TreeRect(16f, 16f, 46f, 46f), 65f)
    private val child = TreePlacement(TreeRect(56f, 100f, 400f, 200f), TreeRect(69f, 116f, 99f, 146f), 165f)
    private val viewport = TreeRect(0f, 0f, 400f, 500f)

    @Test fun parentLevelBadgeIsClearedButChildAvatarCenterIsUnchanged() {
        val stroke = ThreadTreeModel.geometry(tree, mapOf(0 to parent, 1 to child), viewport, 1f).single()
        assertEquals(69f, stroke.start.y, 0.001f)
        assertEquals(131f, stroke.corner.y, 0.001f)
        assertEquals(65f, stroke.tip.x, 0.001f)
    }
    @Test fun nonFiniteOrOutOfRowClearanceFailsOpen() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, 101f, 40f)) {
            assertTrue(ThreadTreeModel.geometry(tree, mapOf(0 to parent.copy(clearanceBottom = bad), 1 to child), viewport, 1f).isEmpty())
        }
    }
    @Test fun optionalBadgeDoesNotAlterOldAvatarOnlyPlacement() {
        val noBadge = parent.copy(clearanceBottom = parent.avatar.bottom)
        val stroke = ThreadTreeModel.geometry(tree, mapOf(0 to noBadge, 1 to child), viewport, 1f).single()
        assertEquals(50f, stroke.start.y, 0.001f)
    }
}
