package io.github.ghsssss.ithomeadkill.hook

import org.junit.Assert.*
import org.junit.Test

class ThreadTreeOrderModelTest {
    @Test
    fun interleavedSiblingRepliesBecomeStableCompleteSubtrees() {
        // Real host shape: native floors 1, 2, 3->1, 4->1, 5->2.
        // Floors live on the host entities, not in TreeRecord; return only their positions.
        val records = listOf(
            root(1, 100),
            reply(2, 1, 100), reply(3, 2, 100),
            reply(4, 3, 100, 1), reply(5, 4, 100, 1), reply(6, 5, 100, 2),
        )
        val result = assertOrder(records, listOf(1, 2, 4, 5, 3, 6))
        val byPosition = records.associateBy { it.position }
        assertEquals(listOf(100L, 1L, 3L, 4L, 2L, 5L), result.map { byPosition.getValue(it).id })
    }

    @Test
    fun invalidScalarIdsRejectTheWholeGroupInsteadOfReorderingItsValidPrefix() {
        val base = interleaved()
        val invalid = listOf(
            base.map { if (it.position == 5) it.copy(id = 0) else it },
            base.map { if (it.position == 5) it.copy(id = -1) else it },
            base.map { if (it.position == 5) it.copy(replyId = -1) else it },
            base.map { if (it.position == 1) it.copy(replyId = -1) else it },
            base.map { if (it.position == 1) it.copy(rootId = -1) else it },
            base.map { if (it.position == 1) it.copy(id = 0) else it },
            base.map { if (it.position == 5) it.copy(rootId = -1) else it },
        )
        for (records in invalid) assertOrder(records, records.map { it.position })
    }

    @Test
    fun crossRootOrMixedHotReplyIsAnImmovableBarrierAndRejectsTheCurrentGroup() {
        val base = interleaved()
        for (barrier in listOf(
            reply(5, 4, 999),
            reply(5, 4, 100, 2, hot = true),
        )) {
            val records = base.dropLast(1) + barrier + reply(6, 5, 100, 1) +
                root(7, 200) + reply(8, 201, 200)
            assertOrder(records, records.map { it.position })
        }
    }

    @Test
    fun duplicateIdsRejectEveryCopyAndDoNotPartiallySortTheGroup() {
        val base = interleaved()
        for (duplicate in listOf(1L, 100L)) {
            val records = base.map { if (it.position == 5) it.copy(id = duplicate) else it }
            assertOrder(records, records.map { it.position })
        }
    }

    @Test
    fun missingExactParentRejectsTheWholeGroupRatherThanFallingBackToItsRoot() {
        val records = interleaved().map { if (it.position == 5) it.copy(replyId = 999) else it }
        assertOrder(records, records.map { it.position })
    }

    @Test
    fun selfParentAndDisconnectedCycleRejectTheWholeGroup() {
        val self = interleaved().map { if (it.position == 5) it.copy(replyId = it.id) else it }
        assertOrder(self, self.map { it.position })
        val cycle = interleaved() + reply(6, 8, 100, 9) + reply(7, 9, 100, 8)
        assertOrder(cycle, cycle.map { it.position })
    }

    @Test
    fun unorderedInputUsesCanonicalPositionsIncludingAHeaderOffset() {
        val records = interleaved().reversed()
        assertOrder(records, listOf(1, 2, 4, 3, 5))
    }

    @Test
    fun duplicatePositionsFailOpenToTheExactInputSequence() {
        val records = interleaved().map { if (it.position == 5) it.copy(position = 3) else it }
        assertOrder(records, records.map { it.position })
        assertOrder(records.reversed(), records.reversed().map { it.position })
    }

    @Test
    fun overBudgetInputIsPreservedWithoutSortingOrTruncation() {
        val records = interleaved() + (6..4097).map { TreeRecord(it, 0, 0, 0, 8) }
        assertEquals(4097, records.size)
        assertOrder(records, records.map { it.position })
        assertOrder(records.reversed(), records.reversed().map { it.position })
    }

    @Test(timeout = 3000)
    fun budgetSizedForwardParentChainIsOrderedIterativelyWithoutStackOverflow() {
        val records = listOf(root(1, 10000)) + (2..4096).map { position ->
            reply(position, position.toLong(), 10000,
                if (position == 4096) 10000 else position + 1L)
        }
        assertEquals(4096, records.size)
        assertOrder(records, listOf(1) + (4096 downTo 2))
    }

    @Test
    fun negativePositionsAndPositionGapsAreBarriersInsteadOfInventedContiguity() {
        val negative = interleaved().map { it.copy(position = it.position - 2) }
        assertOrder(negative, negative.map { it.position })
        val gap = interleaved().map { if (it.position >= 5) it.copy(position = it.position + 1) else it }
        assertOrder(gap, gap.map { it.position })
    }

    @Test
    fun depthFourCompletesEverySubtreeBeforeTheNextNativeSibling() {
        val records = listOf(
            root(0, 100), reply(1, 80, 100, 0, 1), reply(2, 20, 100, 100, 2),
            reply(3, 7, 100, 80, 3), reply(4, 90, 100, 7, 4),
            reply(5, 4, 100, 20, 1), reply(6, 6, 100, 90, 2), reply(7, 3, 100, 80, 3),
        )
        assertOrder(records, listOf(0, 1, 3, 4, 6, 7, 2, 5))
    }

    @Test
    fun forestKeepsTrueRootsAndHotNormalCopiesWithTheSameIdsSeparate() {
        val records = listOf(
            TreeRecord(0, 0, 0, 0, 7),
            root(1, 100, true), reply(2, 1, 100, hot = true),
            reply(3, 2, 100, 0, hot = true), reply(4, 3, 100, 1, 4, true),
            TreeRecord(5, 100, 100, 100, 5, true), TreeRecord(6, 0, 0, 0, 8),
            root(7, 100), reply(8, 1, 100), reply(9, 2, 100, 0), reply(10, 3, 100, 1),
            root(11, 200), reply(12, 201, 200, 0),
        )
        val ordered = assertOrder(records, listOf(0, 1, 2, 4, 3, 5, 6, 7, 8, 10, 9, 11, 12))
        for (position in listOf(0, 1, 5, 6, 7, 11)) {
            assertEquals("root/header/fold cannot move", position, ordered.indexOf(position))
        }
    }

    @Test
    fun futureParentsUseTheFullGroupGraphAndNativeSiblingOrderRatherThanIdOrder() {
        val records = listOf(
            root(1, 100), reply(2, 11, 100, 10), reply(3, 20, 100),
            reply(4, 10, 100), reply(5, 12, 100, 11), reply(6, 21, 100, 20),
        )
        assertOrder(records, listOf(1, 3, 6, 4, 2, 5))
    }

    @Test
    fun parentIdInAnotherRootOrHotCopyNeverCompletesThisGroup() {
        val missing = interleaved().map { if (it.position == 5) it.copy(replyId = 999) else it }
        for ((id, hot) in listOf(200L to false, 100L to true)) {
            val records = missing + root(6, id, hot) + reply(7, 999, id, hot = hot)
            assertOrder(records, records.map { it.position })
        }
    }

    @Test
    fun invalidGroupDoesNotDisableSortingForLaterValidRoots() {
        val invalid = interleaved().map { if (it.position == 5) it.copy(replyId = 999) else it }
        val records = invalid + TreeRecord(6, 0, 0, 0, 5) + root(7, 200) +
            reply(8, 1, 200) + reply(9, 2, 200) + reply(10, 3, 200, 1)
        assertOrder(records, listOf(1, 2, 3, 4, 5, 6, 7, 8, 10, 9))
    }

    @Test
    fun unknownBarriersStayInPlaceAndDoNotResumeThePrecedingReplyBlock() {
        for (type in listOf(-1, 6, 7, 8, 99, Int.MAX_VALUE)) {
            val records = interleaved() + TreeRecord(6, 100, 100, 1, type) +
                reply(7, 5, 100, 1) + reply(8, 6, 100, 5) + root(9, 200) +
                reply(10, 1, 200) + reply(11, 2, 200) + reply(12, 3, 200, 1)
            val ordered = assertOrder(records, listOf(1, 2, 4, 3, 5, 6, 7, 8, 9, 10, 12, 11))
            assertEquals(5, ordered.indexOf(6))
            assertEquals(6, ordered.indexOf(7))
            assertEquals(7, ordered.indexOf(8))
        }
    }

    @Test
    fun foldMarkerIsNotAnEntityOrParentAndItsTailSlotNeverMoves() {
        val fold = TreeRecord(6, 100, 100, 1, 5)
        val records = interleaved() + fold + reply(7, 5, 100, 1)
        val ordered = assertOrder(records, listOf(1, 2, 4, 3, 5, 6, 7))
        assertEquals(5, ordered.indexOf(fold.position))
        assertSame(fold, records[5])
    }

    @Test
    fun dynamicExpansionRecomputesFromTheCurrentMutableSnapshotWithoutCachedIdentity() {
        val collapsed = listOf(
            root(1, 100), reply(2, 1, 100), reply(3, 2, 100), reply(4, 3, 100, 1),
            TreeRecord(5, 0, 0, 0, 5), root(6, 200),
        )
        val expanded = listOf(
            root(1, 100), reply(2, 1, 100), reply(3, 2, 100), reply(4, 3, 100, 1),
            reply(5, 4, 100, 3), reply(6, 5, 100, 2), TreeRecord(7, 0, 0, 0, 5), root(8, 200),
        )
        val current = collapsed.toMutableList()
        val previous = assertOrder(current, listOf(1, 2, 4, 3, 5, 6))
        current.clear()
        current.addAll(expanded)
        assertOrder(current, listOf(1, 2, 4, 5, 3, 6, 7, 8))
        // Same list, size, positions and IDs, but a changed exact parent must be reread.
        current[3] = current[3].copy(replyId = 2)
        assertOrder(current, listOf(1, 2, 3, 4, 5, 6, 7, 8))
        assertEquals(listOf(1, 2, 4, 3, 5, 6), previous)
        current.clear()
        current.addAll(collapsed)
        assertOrder(current, listOf(1, 2, 4, 3, 5, 6))
    }

    @Test
    fun futureParentArrivingOnExpansionTurnsPreviousFailOpenIntoValidDfs() {
        val missing = interleaved().map { if (it.position == 5) it.copy(replyId = 99) else it }
        assertOrder(missing + TreeRecord(6, 0, 0, 0, 5), listOf(1, 2, 3, 4, 5, 6))
        val expanded = missing + reply(6, 99, 100, 2) + TreeRecord(7, 0, 0, 0, 5)
        assertOrder(expanded, listOf(1, 2, 4, 3, 6, 5, 7))
    }

    @Test
    fun business25StyleKeepsNativeFloorsAndFoldSlotsBeforeAndAfterExpansion() {
        // Native floors: 1, 2->1, 3->root, 4->2, fold; then 5->root, 6->5.
        val replies = listOf(
            root(25, 2500), reply(26, 1, 2500), reply(27, 2, 2500, 1, 4),
            reply(28, 3, 2500), reply(29, 4, 2500, 2, 4),
        )
        val collapsed = replies + TreeRecord(30, 0, 0, 0, 5)
        assertOrder(collapsed, listOf(25, 26, 27, 29, 28, 30))
        val expanded = replies + reply(30, 5, 2500) + reply(31, 6, 2500, 5) + TreeRecord(32, 0, 0, 0, 5)
        assertOrder(expanded, listOf(25, 26, 27, 29, 28, 30, 31, 32))
        assertEquals(listOf(2500L, 1L, 2L, 3L, 4L, 0L), collapsed.map { it.id })
    }

    @Test
    fun alreadyDfsInputPreservesTheExactOriginalSequenceAndEveryRecordIdentity() {
        val records = java.util.Collections.unmodifiableList(listOf(
            root(1, 100), reply(2, 1, 100), reply(3, 2, 100, 1), reply(4, 3, 100, 2),
            reply(5, 4, 100), TreeRecord(6, 0, 0, 0, 5), root(7, 200), reply(8, 5, 200),
        ))
        assertOrder(records, records.map { it.position })
        assertOrder(records, records.map { it.position })
    }

    @Test
    fun emptyHeadersOrphansAndMalformedRootsAreAllRetained() {
        assertOrder(emptyList(), emptyList())
        val records = listOf(
            TreeRecord(0, 0, 0, 0, 7), reply(1, 1, 100),
            TreeRecord(2, 100, 200, 200, 0, true), reply(3, 2, 100),
            TreeRecord(4, 0, 0, 0, 8), TreeRecord(5, 0, 0, 0, 5),
        )
        assertOrder(records, records.map { it.position })
    }

    @Test
    fun longIdsAndLargeAdapterPositionsAreNotNarrowedOrComparedBySubtraction() {
        val records = interleaved().mapIndexed { index, record ->
            record.copy(
                position = Int.MAX_VALUE - 4 + index,
                id = if (record.id == 100L) Long.MAX_VALUE else Long.MAX_VALUE - record.id,
                rootId = if (record.rootId == 0L) 0 else Long.MAX_VALUE,
                replyId = if (record.replyId == 100L) Long.MAX_VALUE else Long.MAX_VALUE - record.replyId,
            )
        }
        // Roots have no parent; keep their unused parent field canonical as well.
        val canonical = records.map { if (it.itemType == 0) it.copy(replyId = 0) else it }
        assertOrder(canonical.reversed(), listOf(0, 1, 3, 2, 4).map { canonical[it].position })
    }

    @Test
    fun seededCompleteTreesMatchIndependentLexicographicAncestorPaths() {
        val random = kotlin.random.Random(20261127)
        repeat(120) {
            val count = random.nextInt(1, 45)
            val hot = random.nextBoolean()
            val native = (1..count).map { id ->
                val parent = random.nextInt(id)
                reply(0, id.toLong(), 1000, if (parent == 0) 1000 else parent.toLong(),
                    type = random.nextInt(1, 5), hot = hot)
            }.shuffled(random).mapIndexed { index, record -> record.copy(position = index + 2) }
            val records = listOf(root(1, 1000, hot)) + native
            val byId = records.associateBy { it.id }
            val paths = records.associate { record ->
                val path = mutableListOf(record.position)
                var ancestor = record
                while (ancestor.itemType != 0) {
                    ancestor = byId.getValue(if (ancestor.replyId == 0L) 1000 else ancestor.replyId)
                    path += ancestor.position
                }
                record.position to path.asReversed()
            }
            val expected = records.map { it.position }.sortedWith(Comparator { left, right ->
                val a = paths.getValue(left)
                val b = paths.getValue(right)
                var compared = 0
                for (index in 0 until minOf(a.size, b.size)) {
                    compared = a[index].compareTo(b[index])
                    if (compared != 0) break
                }
                if (compared == 0) a.size.compareTo(b.size) else compared
            })
            assertOrder(records, expected)
        }
    }

    private fun interleaved() = listOf(
        root(1, 100), reply(2, 1, 100), reply(3, 2, 100),
        reply(4, 3, 100, 1), reply(5, 4, 100, 2),
    )

    private fun assertOrder(records: List<TreeRecord>, expected: List<Int>): List<Int> {
        val before = records.toList()
        val result = ThreadTreeOrderModel.order(records)
        assertEquals(expected, result)
        assertEquals("input records must not be mutated", before, records)
        for (index in before.indices) assertSame("record identity must survive", before[index], records[index])
        assertEquals("every input position must survive exactly once",
            records.map { it.position }.groupingBy { it }.eachCount(), result.groupingBy { it }.eachCount())
        return result
    }

    private fun root(position: Int, id: Long, hot: Boolean = false) =
        TreeRecord(position, id, 0, 0, 0, hot)

    private fun reply(position: Int, id: Long, root: Long, parent: Long = root,
        type: Int = 1, hot: Boolean = false) = TreeRecord(position, id, root, parent, type, hot)
}
