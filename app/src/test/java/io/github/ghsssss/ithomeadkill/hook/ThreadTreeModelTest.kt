package io.github.ghsssss.ithomeadkill.hook

import org.junit.Assert.*
import org.junit.Test

class ThreadTreeModelTest {
    @Test
    fun rootAndDirectReplyUseRealIds() {
        val records = listOf(root(0, 10), reply(1, 11, 10))
        val tree = ThreadTreeModel.topology(records)
        assertEquals(records, tree.records)
        assertEquals(TreeNode(0, null, 0, 0), tree.nodes[0])
        assertEquals(TreeNode(1, 0, 0, 1), tree.nodes[1])
    }

    @Test
    fun onlyNormalCommentsBecomeNodesAndFoldBreaksTheGroup() {
        val tree = ThreadTreeModel.topology(listOf(
            root(0, 10).copy(hot = true),
            reply(1, 11, 10),
            TreeRecord(2, 0, 0, 0, 8),
            root(3, 20),
            reply(4, 21, 20, type = 3),
            TreeRecord(5, 0, 0, 0, 5),
            reply(6, 22, 20, type = 4),
            TreeRecord(7, 23, 20, 21, 9),
        ))
        assertEquals(setOf(3, 4), tree.nodes.keys)
        assertEquals(TreeNode(4, 3, 3, 1), tree.nodes[4])
    }

    @Test
    fun realSnapshotHasThreeVerifiedGroupsAndIgnoresHotCopies() {
        val tree = ThreadTreeModel.topology(realSnapshot)
        assertEquals(41, tree.records.size)
        assertEquals(34, tree.nodes.size)
        assertEquals(listOf(16, 17, 18, 19), tree.nodes.values.filter { it.parentPosition == 15 }.map { it.position })
        assertEquals(listOf(22, 23, 24), tree.nodes.values.filter { it.parentPosition == 21 }.map { it.position })
        assertEquals(TreeNode(25, 22, 21, 2), tree.nodes[25])
        assertEquals(listOf(32, 34, 35), tree.nodes.values.filter { it.parentPosition == 31 }.map { it.position })
        assertEquals(TreeNode(33, 32, 31, 2), tree.nodes[33])
        for (position in listOf(0, 1, 2, 3, 4, 20, 36)) assertNull(tree.nodes[position])
    }

    @Test
    fun zeroReplyIdFallsBackToTheRootWithoutFlatteningOtherReplies() {
        val tree = ThreadTreeModel.topology(listOf(
            root(0, 10), reply(1, 11, 10, 0), reply(2, 12, 10, 11),
            root(3, 20), reply(4, 21, 20, 0),
        ))
        assertEquals(TreeNode(1, 0, 0, 1), tree.nodes[1])
        assertEquals(TreeNode(2, 1, 0, 2), tree.nodes[2])
        assertEquals(TreeNode(4, 3, 3, 1), tree.nodes[4])
    }

    @Test
    fun duplicateNormalIdsInvalidateEveryCopyAndTheirDescendants() {
        val tree = ThreadTreeModel.topology(listOf(
            root(0, 10), reply(1, 11, 10), reply(2, 12, 10, 11), reply(3, 11, 10),
            root(4, 20), reply(5, 21, 20), root(6, 20), reply(7, 22, 20),
        ))
        assertEquals(setOf(0), tree.nodes.keys)
    }

    @Test
    fun selfFutureCycleAndCrossRootTargetsAreNotParents() {
        val tree = ThreadTreeModel.topology(listOf(
            root(0, 10), reply(1, 11, 10, 11),
            reply(2, 12, 10, 13), reply(3, 13, 10, 12), reply(4, 14, 10, 999),
            root(5, 20), reply(6, 21, 20, 10), reply(7, 22, 10, 10), reply(8, 23, 20),
        ))
        assertEquals(setOf(0, 5, 8), tree.nodes.keys)
        assertEquals(TreeNode(8, 5, 5, 1), tree.nodes[8])
    }

    @Test
    fun invalidIdsAndAmbiguousPositionsFailOpen() {
        assertTrue(ThreadTreeModel.topology(listOf(root(0, 0), reply(1, 11, 0))).nodes.isEmpty())
        assertTrue(ThreadTreeModel.topology(listOf(root(-1, 10), reply(0, 11, 10))).nodes.isEmpty())
        assertTrue(ThreadTreeModel.topology(listOf(root(0, 10), reply(0, 11, 10))).nodes.isEmpty())
        val tree = ThreadTreeModel.topology(listOf(root(0, 10), reply(1, -1, 10), reply(2, 12, 10, -1)))
        assertEquals(setOf(0), tree.nodes.keys)
    }

    @Test
    fun snapshotIsPositionOrderedAndIndependentOfTheMutableInput() {
        val input = mutableListOf(reply(1, 11, 10), root(0, 10))
        val tree = ThreadTreeModel.topology(input)
        input.clear()
        assertEquals(listOf(root(0, 10), reply(1, 11, 10)), tree.records)
        assertEquals(TreeNode(1, 0, 0, 1), tree.nodes[1])
    }

    @Test
    fun overBudgetSnapshotIsRejectedRatherThanTruncatedIntoATerminalChild() {
        val input = (0..4096).map { root(it, it + 1L) }
        assertTrue(ThreadTreeModel.topology(input).nodes.isEmpty())
        assertTrue(ThreadTreeModel.topology(input).records.isEmpty())
        assertEquals(4096, ThreadTreeModel.topology(input.take(4096)).nodes.size)
    }

    @Test
    fun indentPreservesTheHostFirstLevelAndOnlyCapsAtAvailableContentWidth() {
        assertEquals(0f, ThreadTreeModel.extraIndent(0, 1f, 800f), 0f)
        assertEquals(0f, ThreadTreeModel.extraIndent(1, 1f, 800f), 0f)
        assertEquals(36f, ThreadTreeModel.extraIndent(2, 1f, 800f), 0f)
        assertEquals(72f, ThreadTreeModel.extraIndent(3, 1f, 800f), 0f)
        assertEquals(600f, ThreadTreeModel.extraIndent(Int.MAX_VALUE, 1f, 800f), 0f)
        assertEquals(216f, ThreadTreeModel.extraIndent(4, 2f, 1200f), 0f)
    }

    @Test
    fun indentBudgetKeepsAtLeastTwoHundredDpOfContent() {
        assertEquals(50f, ThreadTreeModel.extraIndent(4, 1f, 250f), 0f)
        assertEquals(0f, ThreadTreeModel.extraIndent(2, 1f, 200f), 0f)
        assertEquals(0f, ThreadTreeModel.extraIndent(2, 1f, 150f), 0f)
        assertEquals(100f, ThreadTreeModel.extraIndent(4, 2f, 500f), 0f)
    }

    @Test
    fun indentInvalidInputsNeverProduceNonFiniteValues() {
        for (density in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(0f, ThreadTreeModel.extraIndent(2, density, 500f), 0f)
        }
        for (width in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(0f, ThreadTreeModel.extraIndent(2, 1f, width), 0f)
        }
        assertEquals(0f, ThreadTreeModel.extraIndent(Int.MIN_VALUE, 1f, 500f), 0f)
        assertTrue(ThreadTreeModel.extraIndent(4, Float.MAX_VALUE, Float.MAX_VALUE).isFinite())
    }

    @Test
    fun realFixtureGeometryUsesTheCompleteThreeGroups() {
        val topology = ThreadTreeModel.topology(realSnapshot)
        val placements = (15..19).associateWith { position ->
            when (position) {
                15 -> place(10f, 10f)
                16, 17, 18, 19 -> place(66f, 100f + (position - 16) * 80f)
                else -> error("unexpected position")
            }
        }
        val strokes = ThreadTreeModel.geometry(topology, placements, viewport, 1f)
        assertEquals(4, strokes.count { it.tip.x != it.corner.x })
        assertEquals(listOf(false, false, false, true), strokes.filter { it.tip.x != it.corner.x }.map { it.terminal })
        assertTrue(strokes.all { it.start.x == 26f })
    }

    @Test
    fun singleChildStartsBelowMeasuredParentAndEndsBeforeTheChildAvatar() {
        val tree = ThreadTreeModel.topology(listOf(root(0, 10), reply(1, 11, 10)))
        val strokes = ThreadTreeModel.geometry(tree, mapOf(0 to place(10f, 10f), 1 to place(66f, 100f)), viewport, 1f)
        assertEquals(listOf(TreeStroke(TreePoint(26f, 46f), TreePoint(26f, 116f), TreePoint(62f, 116f), 6f, true)), strokes)
    }

    @Test
    fun siblingsShareOneTrunkAndOnlyTheTrueLastBranchTerminates() {
        val tree = ThreadTreeModel.topology(listOf(root(0, 10), reply(1, 11, 10), reply(2, 12, 10), reply(3, 13, 10)))
        val placements = mapOf(0 to place(10f, 10f), 1 to place(66f, 100f), 2 to place(66f, 200f), 3 to place(66f, 300f))
        val strokes = ThreadTreeModel.geometry(tree, placements, viewport, 1f)
        assertEquals(listOf(
            TreeStroke(TreePoint(26f, 46f), TreePoint(26f, 116f), TreePoint(62f, 116f), 6f, false),
            TreeStroke(TreePoint(26f, 116f), TreePoint(26f, 216f), TreePoint(62f, 216f), 6f, false),
            TreeStroke(TreePoint(26f, 216f), TreePoint(26f, 316f), TreePoint(62f, 316f), 6f, true),
        ), strokes.filter { it.tip.x != it.corner.x })
        assertEquals(2, strokes.count { it.tip.x == it.corner.x })
        assertTrunkIsContinuousWithoutOverlap(strokes, 26f, 46f, 310f)
    }

    @Test
    fun unattachedTrueLastChildKeepsTrunkOpenToViewportBottom() {
        val tree = ThreadTreeModel.topology(listOf(root(0, 10), reply(1, 11, 10), reply(2, 12, 10), reply(3, 13, 10)))
        val placements = mapOf(0 to place(10f, 10f), 1 to place(66f, 100f), 2 to place(66f, 200f))
        val strokes = ThreadTreeModel.geometry(tree, placements, viewport, 1f)
        assertEquals(listOf(
            TreeStroke(TreePoint(26f, 46f), TreePoint(26f, 116f), TreePoint(62f, 116f), 6f, false),
            TreeStroke(TreePoint(26f, 116f), TreePoint(26f, 216f), TreePoint(62f, 216f), 6f, false),
        ), strokes.filter { it.tip.x != it.corner.x })
        assertTrue(strokes.none { it.terminal })
        assertEquals(TreeStroke(TreePoint(26f, 216f), TreePoint(26f, 500f), TreePoint(26f, 500f), 0f, false), strokes.last())
        assertTrunkIsContinuousWithoutOverlap(strokes, 26f, 46f, 500f)
    }

    @Test
    fun offscreenDeepParentContinuesFromViewportTopAtKnownIndentedColumn() {
        val tree = ThreadTreeModel.topology(listOf(root(0, 10), reply(1, 11, 10), reply(2, 12, 10, 11)))
        val placements = mapOf(
            1 to TreePlacement(TreeRect(0f, -100f, 400f, -20f), TreeRect(10f, -80f, 42f, -48f)),
            2 to place(46f, 100f),
        )
        val strokes = ThreadTreeModel.geometry(tree, placements, viewport, 1f)
        assertEquals(listOf(TreeStroke(TreePoint(26f, 0f), TreePoint(26f, 116f), TreePoint(42f, 116f), 6f, true)), strokes)
    }

    @Test
    fun missingOrUnknownRowsAreHardBarriersToTopologyAndLines() {
        val unknown = ThreadTreeModel.topology(listOf(root(0, 10), reply(1, 11, 10), TreeRecord(2, 0, 0, 0, 5), reply(3, 12, 10)))
        assertEquals(setOf(0, 1), unknown.nodes.keys)
        assertTrue(ThreadTreeModel.geometry(unknown, mapOf(0 to place(10f, 10f), 1 to place(66f, 100f)), viewport, 1f).isEmpty())
        val gap = ThreadTreeModel.topology(listOf(root(0, 10), reply(1, 11, 10), reply(3, 12, 10)))
        assertEquals(setOf(0, 1), gap.nodes.keys)
        assertTrue(ThreadTreeModel.geometry(gap, mapOf(0 to place(10f, 10f), 1 to place(66f, 100f)), viewport, 1f).isEmpty())
    }

    @Test
    fun unrelatedSiblingInCrossParentIntervalRetainsDepthButSuppressesDeepConnector() {
        val tree = ThreadTreeModel.topology(listOf(
            root(0, 10), reply(1, 11, 10), reply(2, 12, 10), reply(3, 13, 10, 11),
        ))
        assertEquals(TreeNode(3, 1, 0, 2), tree.nodes[3])
        val placements = mapOf(0 to place(10f, 10f), 1 to place(66f, 100f), 2 to place(66f, 200f), 3 to place(102f, 300f))
        assertEquals(2, ThreadTreeModel.geometry(tree, placements, viewport, 1f).count { it.tip.x != it.corner.x })
    }

    @Test
    fun geometryMirrorsBranchAndClipsParentStartForRtl() {
        val tree = ThreadTreeModel.topology(listOf(root(0, 10), reply(1, 11, 10)))
        val placements = mapOf(
            0 to TreePlacement(TreeRect(0f, -90f, 400f, 10f), TreeRect(358f, -80f, 390f, -48f)),
            1 to place(302f, 100f),
        )
        assertEquals(listOf(TreeStroke(TreePoint(374f, 0f), TreePoint(374f, 116f), TreePoint(338f, 116f), 6f, true)),
            ThreadTreeModel.geometry(tree, placements, viewport, 1f, rtl = true))
    }

    @Test
    fun invalidGeometryInputsReturnNoStrokes() {
        val tree = ThreadTreeModel.topology(listOf(root(0, 10), reply(1, 11, 10)))
        val placements = mapOf(0 to place(10f, 10f), 1 to place(66f, 100f))
        assertTrue(ThreadTreeModel.geometry(tree, placements, viewport, 0f).isEmpty())
        assertTrue(ThreadTreeModel.geometry(tree, placements, TreeRect(0f, 0f, Float.NaN, 500f), 1f).isEmpty())
    }

    @Test
    fun missingMiddleMeasurementCannotBeInterpretedAsOffscreenTail() {
        val tree = ThreadTreeModel.topology(listOf(root(0, 10), reply(1, 11, 10), reply(2, 12, 10), reply(3, 13, 10)))
        val measured = mapOf(0 to place(10f, 10f), 1 to place(66f, 100f), 3 to place(66f, 300f))
        assertTrue(ThreadTreeModel.geometry(tree, measured, viewport, 1f).isEmpty())
    }

    @Test
    fun missingIntermediateDescendantAlsoBlocksTheRootTrunk() {
        val tree = ThreadTreeModel.topology(listOf(root(0, 10), reply(1, 11, 10), reply(2, 12, 10, 11), reply(3, 13, 10)))
        val measured = mapOf(0 to place(10f, 10f), 1 to place(66f, 100f), 3 to place(66f, 300f))
        assertTrue(ThreadTreeModel.geometry(tree, measured, viewport, 1f).isEmpty())
    }

    @Test
    fun unknownMiddleRecordDoesNotMakeEarlierChildLookTerminal() {
        val tree = ThreadTreeModel.topology(listOf(root(0, 10), reply(1, 11, 10), TreeRecord(2, 0, 0, 0, 9), reply(3, 12, 10)))
        val measured = mapOf(0 to place(10f, 10f), 1 to place(66f, 100f), 2 to place(66f, 200f), 3 to place(66f, 300f))
        assertTrue(ThreadTreeModel.geometry(tree, measured, viewport, 1f).isEmpty())
    }

    @Test
    fun unmeasuredLastChildBeforeAnotherAttachedRowIsNotAValidContinuation() {
        val tree = ThreadTreeModel.topology(listOf(root(0, 10), reply(1, 11, 10), reply(2, 12, 10), root(3, 20)))
        val measured = mapOf(0 to place(10f, 10f), 1 to place(66f, 100f), 3 to place(10f, 300f))
        assertTrue(ThreadTreeModel.geometry(tree, measured, viewport, 1f).isEmpty())
    }

    @Test
    fun absentParentUsesOnlyAMeasuredSameDepthColumnWitness() {
        val tree = ThreadTreeModel.topology(listOf(root(0, 10), reply(1, 11, 10), reply(2, 12, 10, 11), reply(3, 13, 10)))
        val measured = mapOf(2 to place(102f, 100f), 3 to place(66f, 200f))
        val result = ThreadTreeModel.geometry(tree, measured, viewport, 1f)
        assertEquals(listOf(TreeStroke(TreePoint(82f, 0f), TreePoint(82f, 116f), TreePoint(98f, 116f), 6f, true)), result)
        assertTrue(ThreadTreeModel.geometry(tree, mapOf(2 to place(102f, 100f)), viewport, 1f).isEmpty())
    }

    @Test
    fun realRoot18CrossOrderReplyRetainsIndentButDoesNotConnectPastSiblings() {
        val tree = ThreadTreeModel.topology(realSnapshot)
        val measured = (21..25).associateWith { position ->
            place(if (position == 21) 10f else if (position == 25) 102f else 66f, 10f + (position - 21) * 90f)
        }
        val branches = ThreadTreeModel.geometry(tree, measured, viewport, 1f).filter { it.tip.x != it.corner.x }
        assertEquals(3, branches.size)
        assertEquals(listOf(false, false, true), branches.map { it.terminal })
        assertTrue(branches.all { it.corner.x == 26f })
        assertEquals(2, tree.nodes.getValue(25).depth)
    }

    @Test
    fun realRoot28HasAnIndependentDeeperTrunkWithoutFlatteningTheGrandchild() {
        val tree = ThreadTreeModel.topology(realSnapshot)
        val measured = (31..35).associateWith { position ->
            place(if (position == 31) 10f else if (position == 33) 102f else 66f, 10f + (position - 31) * 90f)
        }
        val branches = ThreadTreeModel.geometry(tree, measured, viewport, 1f).filter { it.tip.x != it.corner.x }
        assertEquals(4, branches.size)
        assertEquals(3, branches.count { it.corner.x == 26f })
        assertEquals(1, branches.count { it.corner.x == 82f && it.terminal })
        assertEquals(2, branches.count { it.terminal })
    }

    @Test
    fun realRoot4PartialAttachmentMustNotCloseAtTheLastVisibleChild() {
        val tree = ThreadTreeModel.topology(realSnapshot)
        val measured = mapOf(15 to place(10f, 10f), 16 to place(66f, 100f), 17 to place(66f, 200f))
        val result = ThreadTreeModel.geometry(tree, measured, viewport, 1f)
        assertEquals(2, result.count { it.tip.x != it.corner.x })
        assertFalse(result.any { it.terminal })
        assertTrunkIsContinuousWithoutOverlap(result, 26f, 46f, 500f)
    }

    @Test
    fun malformedRowsDensityOrViewportCannotLeakNonFiniteOrReversedStrokes() {
        val tree = ThreadTreeModel.topology(listOf(root(0, 10), reply(1, 11, 10)))
        val measured = mapOf(0 to place(10f, 10f), 1 to place(66f, 100f))
        for (density in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.MAX_VALUE)) {
            assertTrue(ThreadTreeModel.geometry(tree, measured, viewport, density).isEmpty())
        }
        for (bad in listOf(TreeRect(0f, 0f, 0f, 10f), TreeRect(0f, 0f, 10f, 0f), TreeRect(10f, 0f, 0f, 10f))) {
            assertTrue(ThreadTreeModel.geometry(tree, measured, bad, 1f).isEmpty())
        }
        val bad = measured + (1 to place(66f, Float.NaN))
        assertTrue(ThreadTreeModel.geometry(tree, bad, viewport, 1f).isEmpty())
        assertTrue(ThreadTreeModel.geometry(tree, mapOf(0 to place(10f, 200f), 1 to place(66f, 100f)), viewport, 1f).isEmpty())
    }

    @Test
    fun aChildBelowTheViewportProducesOnlyANonTerminalTrunk() {
        val tree = ThreadTreeModel.topology(listOf(root(0, 10), reply(1, 11, 10)))
        val measured = mapOf(0 to place(10f, 10f), 1 to place(66f, 600f))
        assertEquals(listOf(TreeStroke(TreePoint(26f, 46f), TreePoint(26f, 500f), TreePoint(26f, 500f), 0f, false)),
            ThreadTreeModel.geometry(tree, measured, viewport, 1f))
    }

    @Test
    fun aGroupEndingAboveTheViewportDoesNotLeaveATail() {
        val tree = ThreadTreeModel.topology(listOf(root(0, 10), reply(1, 11, 10)))
        val measured = mapOf(0 to place(10f, -200f), 1 to place(66f, -100f))
        assertTrue(ThreadTreeModel.geometry(tree, measured, viewport, 1f).isEmpty())
    }

    @Test
    fun forgedTopologyCannotCreateATreeWithUnvalidatedNodes() {
        val records = listOf(root(0, 10), reply(1, 11, 10))
        val forged = TreeTopology(records, mapOf(0 to TreeNode(0, null, 0, 0), 1 to TreeNode(1, 0, 0, 2)))
        assertTrue(ThreadTreeModel.geometry(forged, mapOf(0 to place(10f, 10f), 1 to place(66f, 100f)), viewport, 1f).isEmpty())
    }

    @Test
    fun multipleRootsHaveDisjointTrunksAndIndependentTerminals() {
        val tree = ThreadTreeModel.topology(listOf(root(0, 10), reply(1, 11, 10), root(2, 20), reply(3, 21, 20)))
        val measured = mapOf(0 to place(10f, 10f), 1 to place(66f, 100f), 2 to place(10f, 200f), 3 to place(66f, 300f))
        assertEquals(listOf(
            TreeStroke(TreePoint(26f, 46f), TreePoint(26f, 116f), TreePoint(62f, 116f), 6f, true),
            TreeStroke(TreePoint(26f, 236f), TreePoint(26f, 316f), TreePoint(62f, 316f), 6f, true),
        ), ThreadTreeModel.geometry(tree, measured, viewport, 1f))
    }

    @Test
    fun aLaterCrossOrderChildSuppressesItsWholeParentGroupNotOnlyTheBadEdge() {
        val tree = ThreadTreeModel.topology(listOf(
            root(0, 10), reply(1, 11, 10), reply(2, 12, 10, 11), reply(3, 13, 10), reply(4, 14, 10, 11),
        ))
        val measured = (0..4).associateWith { position ->
            place(if (position == 0) 10f else if (position == 2 || position == 4) 102f else 66f, 10f + position * 90f)
        }
        val branches = ThreadTreeModel.geometry(tree, measured, viewport, 1f).filter { it.tip.x != it.corner.x }
        assertEquals(2, branches.size)
        assertTrue(branches.all { it.corner.x == 26f })
        assertEquals(2, tree.nodes.getValue(4).depth)
    }

    @Test
    fun sharedGeometryIsAnExactHorizontalMirrorInRtl() {
        val tree = ThreadTreeModel.topology(listOf(root(0, 10), reply(1, 11, 10), reply(2, 12, 10)))
        val ltr = mapOf(0 to place(10f, 10f), 1 to place(66f, 100f), 2 to place(66f, 200f))
        fun mirror(rect: TreeRect) = TreeRect(400f - rect.right, rect.top, 400f - rect.left, rect.bottom)
        val rtl = ltr.mapValues { (_, p) -> TreePlacement(mirror(p.row), mirror(p.avatar)) }
        val expected = ThreadTreeModel.geometry(tree, ltr, viewport, 1f).map { stroke ->
            stroke.copy(start = TreePoint(400f - stroke.start.x, stroke.start.y),
                corner = TreePoint(400f - stroke.corner.x, stroke.corner.y), tip = TreePoint(400f - stroke.tip.x, stroke.tip.y))
        }
        assertEquals(expected, ThreadTreeModel.geometry(tree, rtl, viewport, 1f, rtl = true))
    }

    @Test
    fun densityScalesGapsAndRadiusWithoutChangingTopology() {
        val tree = ThreadTreeModel.topology(listOf(root(0, 10), reply(1, 11, 10)))
        val measured = mapOf(0 to place(10f, 10f), 1 to place(90f, 100f))
        assertEquals(listOf(TreeStroke(TreePoint(26f, 50f), TreePoint(26f, 116f), TreePoint(82f, 116f), 12f, true)),
            ThreadTreeModel.geometry(tree, measured, viewport, 2f))
    }

    @Test
    fun radiusCannotExceedAvailableRiseOrHorizontalSpan() {
        val tree = ThreadTreeModel.topology(listOf(root(0, 10), reply(1, 11, 10)))
        val parent = TreePlacement(TreeRect(0f, 0f, 400f, 40f), TreeRect(10f, 0f, 42f, 32f))
        val child = TreePlacement(TreeRect(0f, 40f, 400f, 100f), TreeRect(31f, 40f, 63f, 72f))
        val result = ThreadTreeModel.geometry(tree, mapOf(0 to parent, 1 to child), viewport, 1f)
        assertEquals(1f, result.single().radius, 0f)
        assertTrue(result.single().corner.y - result.single().radius >= result.single().start.y)
    }

    @Test
    fun deepBudgetSizedTreeUsesIterativeAncestryAndOnlyMeasuredFiniteColumns() {
        val records = (0 until 4096).map { position ->
            if (position == 0) root(0, 10) else reply(position, 10L + position, 10, 9L + position)
        }
        val tree = ThreadTreeModel.topology(records)
        assertEquals(4095, tree.nodes.getValue(4095).depth)
        val measured = records.associate { record ->
            record.position to place(if (record.position == 0) 10f else 66f + minOf(record.position - 1, 3) * 36f,
                10f + record.position * 90f)
        }
        val result = ThreadTreeModel.geometry(tree, measured, TreeRect(0f, 0f, 400f, 370000f), 1f)
        assertEquals(4, result.size)
        assertTrue(result.all { stroke ->
            listOf(stroke.start.x, stroke.start.y, stroke.corner.x, stroke.corner.y, stroke.tip.x, stroke.tip.y, stroke.radius).all { it.isFinite() }
        })
    }

    private fun assertTrunkIsContinuousWithoutOverlap(strokes: List<TreeStroke>, x: Float, firstY: Float, lastY: Float) {
        val verticalSegments = strokes.filter { it.start.x == x }.mapNotNull { stroke ->
            val endY = stroke.corner.y - stroke.radius
            if (endY > stroke.start.y) stroke.start.y to endY else null
        }.sortedBy { it.first }
        assertTrue("no vertical trunk", verticalSegments.isNotEmpty())
        var cursor = firstY
        for ((start, end) in verticalSegments) {
            assertEquals("gap or overlap in the shared trunk", cursor, start, 0.001f)
            assertTrue(end > start)
            cursor = end
        }
        assertEquals(lastY, cursor, 0.001f)
    }

    private val viewport = TreeRect(0f, 0f, 400f, 500f)
    private fun place(x: Float, top: Float) = TreePlacement(
        TreeRect(0f, top - 5f, 400f, top + 70f), TreeRect(x, top, x + 32f, top + 32f),
    )

    private fun root(position: Int, id: Long) = TreeRecord(position, id, 0, 0, 0)
    private fun reply(position: Int, id: Long, root: Long, parent: Long = root, type: Int = 1) =
        TreeRecord(position, id, root, parent, type)

    // focused-relation-summary.json: scalar-2 is the anonymous zero, all other scalar-N map to N.
    private val realSnapshot = listOf(
        TreeRecord(0, 0, 0, 0, 7),
        TreeRecord(1, 3, 4, 4, 0, true),
        TreeRecord(2, 5, 4, 4, 0, true),
        TreeRecord(3, 6, 4, 4, 0, true),
        TreeRecord(4, 0, 0, 0, 8),
        TreeRecord(5, 7, 0, 0, 0),
        TreeRecord(6, 8, 0, 0, 0),
        TreeRecord(7, 9, 0, 0, 0),
        TreeRecord(8, 10, 0, 0, 0),
        TreeRecord(9, 11, 0, 0, 0),
        TreeRecord(10, 12, 11, 11, 2),
        TreeRecord(11, 13, 0, 0, 0),
        TreeRecord(12, 14, 0, 0, 0),
        TreeRecord(13, 15, 0, 0, 0),
        TreeRecord(14, 16, 0, 0, 0),
        TreeRecord(15, 4, 0, 0, 0),
        TreeRecord(16, 5, 4, 4, 3),
        TreeRecord(17, 3, 4, 4, 1),
        TreeRecord(18, 6, 4, 4, 1),
        TreeRecord(19, 17, 4, 4, 1),
        TreeRecord(20, 0, 0, 0, 5),
        TreeRecord(21, 18, 0, 0, 0),
        TreeRecord(22, 19, 18, 18, 3),
        TreeRecord(23, 20, 18, 18, 1),
        TreeRecord(24, 21, 18, 18, 1),
        TreeRecord(25, 22, 18, 19, 4),
        TreeRecord(26, 23, 0, 0, 0),
        TreeRecord(27, 24, 23, 23, 2),
        TreeRecord(28, 25, 0, 0, 0),
        TreeRecord(29, 26, 0, 0, 0),
        TreeRecord(30, 27, 0, 0, 0),
        TreeRecord(31, 28, 0, 0, 0),
        TreeRecord(32, 29, 28, 28, 3),
        TreeRecord(33, 30, 28, 29, 1),
        TreeRecord(34, 31, 28, 28, 1),
        TreeRecord(35, 32, 28, 28, 1),
        TreeRecord(36, 0, 0, 0, 5),
        TreeRecord(37, 33, 0, 0, 0),
        TreeRecord(38, 34, 33, 33, 2),
        TreeRecord(39, 35, 0, 0, 0),
        TreeRecord(40, 36, 0, 0, 0),
    )
}
