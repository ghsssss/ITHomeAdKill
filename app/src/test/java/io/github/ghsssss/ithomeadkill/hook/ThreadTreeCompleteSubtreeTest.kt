package io.github.ghsssss.ithomeadkill.hook

import org.junit.Assert.*
import org.junit.Test

class ThreadTreeCompleteSubtreeTest {
    private val viewport = TreeRect(0f, 0f, 500f, 400f)
    private fun row(x: Float, y: Float) = TreePlacement(TreeRect(x - 13f, y - 10f, 484f, y + 85f),
        TreeRect(x, y, x + 30f, y + 30f), y + 49f)
    private fun chain() = ThreadTreeModel.topology(listOf(
        TreeRecord(0, 10, 0, 0, 0), TreeRecord(1, 11, 10, 10, 1),
        TreeRecord(2, 12, 10, 11, 1), TreeRecord(3, 13, 10, 12, 1),
    ))

    @Test fun realCachedAncestorColumnKeepsASoleVisibleDeepReplyConnected() {
        val strokes = ThreadTreeModel.geometry(chain(), mapOf(3 to row(141f, 100f)), viewport, 1f,
            columns = mapOf(0 to 31f, 1 to 84f, 2 to 120f))
        assertEquals(listOf(TreeStroke(TreePoint(120f, 0f), TreePoint(120f, 115f), TreePoint(137f, 115f), 6f, true)), strokes)
    }
    @Test fun invalidAncestorColumnsStillFailOpen() {
        for (x in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 501f, 150f)) {
            assertTrue(ThreadTreeModel.geometry(chain(), mapOf(3 to row(141f, 100f)), viewport, 1f,
                columns = mapOf(2 to x)).isEmpty())
        }
    }
    @Test fun fourthAndFifthReplyLevelsNoLongerShareAnArtificialThreeLayerCap() {
        assertEquals(144f, ThreadTreeModel.extraIndent(5, 1f, 900f), 0.001f)
        assertEquals(180f, ThreadTreeModel.extraIndent(6, 1f, 900f), 0.001f)
        assertEquals(0f, ThreadTreeModel.extraIndent(4, 1f, 190f), 0.001f)
        assertTrue(ThreadTreeModel.extraIndent(Int.MAX_VALUE, 1f, 500f) <= 300f)
    }

    @Test fun narrowWidthCompressesEveryLayerWithTheSamePositiveStepRatherThanFlatteningTheLastLevels() {
        val positions = (1..8).map { ThreadTreeModel.extraIndent(it, 1f, 328f, 8) }
        assertEquals(0f, positions.first(), 0.001f)
        assertEquals(128f, positions.last(), 0.001f)
        assertTrue(positions.zipWithNext().all { (a, b) -> b - a > 4f })
    }

    @Test fun aColdDeepViewportCanCalibrateUnseenReplyLevelsFromItsMeasuredMargin() {
        val columns = ThreadTreeModel.replyColumns(3, 156f, 1f, 900f, 5)
        assertEquals(84f, columns.getValue(1), 0.001f)
        assertEquals(120f, columns.getValue(2), 0.001f)
        assertEquals(228f, columns.getValue(5), 0.001f)
        assertFalse(columns.containsKey(0))
        val mirrored = ThreadTreeModel.replyColumns(3, 344f, 1f, 900f, 5, true)
        assertEquals(416f, mirrored.getValue(1), 0.001f)
        assertEquals(272f, mirrored.getValue(5), 0.001f)
    }
}
