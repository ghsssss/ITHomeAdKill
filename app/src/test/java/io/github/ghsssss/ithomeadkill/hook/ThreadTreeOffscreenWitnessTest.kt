package io.github.ghsssss.ithomeadkill.hook

import org.junit.Assert.*
import org.junit.Test

class ThreadTreeOffscreenWitnessTest {
    private val tree = ThreadTreeModel.topology(listOf(
        TreeRecord(0, 10L, 0L, 0L, 0), TreeRecord(1, 11L, 10L, 10L, 1),
        TreeRecord(2, 12L, 10L, 11L, 1), TreeRecord(3, 13L, 10L, 10L, 1),
        TreeRecord(4, 14L, 10L, 10L, 1),
    ))
    private val viewport = TreeRect(0f, 0f, 400f, 500f)
    // Real host marginStart: depth1=56dp, depth2=92dp. Child and peer bounds intentionally differ.
    private val child = TreePlacement(TreeRect(92f, 95f, 384f, 180f), TreeRect(105f, 100f, 135f, 130f), 149f)
    private val peer = TreePlacement(TreeRect(56f, 195f, 384f, 280f), TreeRect(69f, 200f, 99f, 230f), 249f)
    private val otherPeer = TreePlacement(TreeRect(56f, 295f, 384f, 380f), TreeRect(69f, 300f, 99f, 330f), 349f)

    @Test fun anOffscreenParentUsesItsPeerColumnNotItsDeeperChildRowBounds() {
        val result = ThreadTreeModel.geometry(tree, mapOf(2 to child, 3 to peer, 4 to otherPeer), viewport, 1f)
        assertEquals(listOf(TreeStroke(TreePoint(84f, 0f), TreePoint(84f, 115f), TreePoint(101f, 115f), 6f, true)), result)
    }

    @Test fun differentDepthMarginsAlsoAllowTheExactMirroredRtlContinuation() {
        fun mirror(rect: TreeRect) = TreeRect(400f - rect.right, rect.top, 400f - rect.left, rect.bottom)
        val rtl = mapOf(2 to child, 3 to peer).mapValues { (_, value) ->
            TreePlacement(mirror(value.row), mirror(value.avatar), value.clearanceBottom)
        }
        assertEquals(listOf(TreeStroke(TreePoint(316f, 0f), TreePoint(316f, 115f), TreePoint(299f, 115f), 6f, true)),
            ThreadTreeModel.geometry(tree, rtl, viewport, 1f, rtl = true))
    }

    @Test fun absentOrDifferentRootWitnessStillCannotInventAnOffscreenColumn() {
        assertTrue(ThreadTreeModel.geometry(tree, mapOf(2 to child), viewport, 1f).isEmpty())
        val differentRoot = ThreadTreeModel.topology(tree.records + TreeRecord(5, 20L, 0L, 0L, 0) +
            TreeRecord(6, 21L, 20L, 20L, 1))
        assertTrue(ThreadTreeModel.geometry(differentRoot, mapOf(2 to child, 6 to peer), viewport, 1f).isEmpty())
    }

    @Test fun sameDepthPeersMustAgreeWithEachOtherInBoundsSizeAndAvatarColumn() {
        val variants = listOf(
            otherPeer.copy(row = otherPeer.row.copy(left = 62f)),
            otherPeer.copy(row = otherPeer.row.copy(right = 380f)),
            otherPeer.copy(avatar = otherPeer.avatar.copy(left = 70f, right = 98f)),
            otherPeer.copy(avatar = otherPeer.avatar.copy(left = 70f, right = 100f)),
        )
        for (inconsistent in variants) {
            assertTrue(ThreadTreeModel.geometry(tree, mapOf(2 to child, 3 to peer, 4 to inconsistent), viewport, 1f).isEmpty())
        }
    }
}
