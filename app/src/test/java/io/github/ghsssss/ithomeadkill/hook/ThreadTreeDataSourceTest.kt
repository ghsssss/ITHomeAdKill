package io.github.ghsssss.ithomeadkill.hook

import com.ruanmei.ithome.adapters.CommentListAdapterV8
import com.ruanmei.ithome.entities.NewsCommentEntity
import org.junit.Assert.*
import org.junit.Test

class ThreadTreeDataSourceTest {
    private val source = ThreadTreeDataSource()
    private fun rows() = listOf(NewsCommentEntity(), NewsCommentEntity(102L, 101L, 101L, 1))

    @Test fun headerOffsetIsAppliedAndInheritedFieldsAreReadWithoutCommentText() {
        val snapshot = source.read(CommentListAdapterV8(rows()))!!
        assertEquals(listOf(1, 2), snapshot.topology.records.map { it.position })
        assertEquals(1, snapshot.topology.nodes[2]!!.parentPosition)
        assertEquals(1, snapshot.topology.nodes[2]!!.depth)
    }
    @Test fun zeroHeaderAndBoundedFooterAreAcceptedWithoutInventingDataRows() {
        val snapshot = source.read(CommentListAdapterV8(rows(), 0, 5))!!
        assertEquals(listOf(0, 1), snapshot.topology.records.map { it.position })
    }
    @Test fun wrongAdapterAndUnverifiedHeaderOrCountAreRejected() {
        assertNull(source.read(Any()))
        assertNull(source.read(CommentListAdapterV8(rows(), 2)))
        assertNull(source.read(CommentListAdapterV8(rows(), 1, 2)))
        assertNull(source.read(CommentListAdapterV8(rows(), 1, 9)))
    }
    @Test fun malformedScalarTypesFailOpenRatherThanCoercingTextOrBoolean() {
        for (bad in listOf(
            NewsCommentEntity(id = "101"), NewsCommentEntity(root = "0"),
            NewsCommentEntity(reply = 1.5), NewsCommentEntity(type = "1"), NewsCommentEntity(hot = 0)
        )) assertNull(source.read(CommentListAdapterV8(listOf(bad))))
    }
    @Test fun changedBoundEntityIsNotMistakenForTheCachedRecord() {
        val rows = rows().toMutableList()
        val adapter = CommentListAdapterV8(rows)
        val snapshot = source.read(adapter)!!
        assertEquals(snapshot.topology.records[1], source.currentRecord(adapter, 2, 1))
        rows[1] = NewsCommentEntity(103L, 101L, 101L, 1)
        assertNotEquals(snapshot.topology.records[1], source.currentRecord(adapter, 2, 1))
        assertNull(source.currentRecord(adapter, -1, 1))
    }
    @Test fun inputBudgetAndEmptyDataAreBounded() {
        assertNull(source.read(CommentListAdapterV8(emptyList<Any>())))
        assertNull(source.read(CommentListAdapterV8(List(4097) { NewsCommentEntity(it.toLong() + 1) })))
    }
}
