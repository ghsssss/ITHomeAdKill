package io.github.ghsssss.ithomeadkill.hook

import org.junit.Assert.*
import org.junit.Test

class ThreadTreePopupFixtureTest {
    @Test fun runtimePopupRecordsKeepTwoIndependentGrandchildren() {
        val tree = ThreadTreeModel.topology(popupRecords)
        assertEquals(popupRecords, tree.records)
        assertEquals(mapOf(
            0 to TreeNode(0, null, 0, 0),
            1 to TreeNode(1, 0, 0, 1),
            2 to TreeNode(2, 1, 0, 2),
            3 to TreeNode(3, 0, 0, 1),
            4 to TreeNode(4, 0, 0, 1),
            5 to TreeNode(5, 4, 0, 2),
        ), tree.nodes)
        assertTrue(tree.records.none { it.hot })
    }

    @Test fun popupRecordsUseTheExistingGeometryWithoutFlatteningReplies() {
        val tree = ThreadTreeModel.topology(popupRecords)
        // 归一化摆位只验证纯几何，不冒充真机坐标；完整拓扑保留两个二级回复。
        val measured = tree.nodes.mapValues { (position, node) ->
            val top = position * 100f
            val left = if (node.depth == 0) 10f else 66f +
                ThreadTreeModel.extraIndent(node.depth, 1f, 400f, maximumDepth = 2)
            TreePlacement(TreeRect(0f, top, 400f, top + 100f),
                TreeRect(left, top + 10f, left + 32f, top + 42f))
        }
        val branches = ThreadTreeModel.geometry(tree, measured,
            TreeRect(0f, 0f, 400f, 600f), 1f).filter { it.tip.x != it.corner.x }
        assertEquals(5, branches.size)
        assertEquals(3, branches.count { it.corner.x == 26f })
        assertEquals(listOf(226f, 526f), branches.filter { it.corner.x == 82f }.map { it.tip.y })
        assertTrue(branches.filter { it.corner.x == 82f }.all { it.terminal })
        assertEquals(listOf(false, false, true), branches.filter { it.corner.x == 26f }.map { it.terminal })
    }

    // popup-contract-1.json：仅保留结构字段；1..6 是稳定别名，不含正文或用户标识。
    private val popupRecords = listOf(
        TreeRecord(0, 1, 0, 0, 0, false),
        TreeRecord(1, 2, 1, 1, 1, false),
        TreeRecord(2, 3, 1, 2, 1, false),
        TreeRecord(3, 4, 1, 1, 1, false),
        TreeRecord(4, 5, 1, 1, 1, false),
        TreeRecord(5, 6, 1, 5, 1, false),
    )
}
