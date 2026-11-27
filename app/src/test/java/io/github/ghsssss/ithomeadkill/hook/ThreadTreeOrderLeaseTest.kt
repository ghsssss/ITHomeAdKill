package io.github.ghsssss.ithomeadkill.hook

import org.junit.Assert.*
import org.junit.Test

class ThreadTreeOrderLeaseTest {
    @Test
    fun permutationOnlyReordersReferencesAndRestoresNativeOrder() {
        val root = Any()
        val sibling = Any()
        val child = Any()
        val native = listOf(root, sibling, child)
        val lease = ThreadTreeOrderLease()
        val displayed = lease.display(native, listOf(0, 2, 1))
        assertReferences(listOf(root, child, sibling), displayed)
        assertReferences(listOf(root, sibling, child), native)
        assertReferences(native, lease.restore(displayed))
    }

    @Test
    fun restoreDoesNotOverwriteExternalInsertionOrReuseTheOldLease() {
        val native = List(3) { Any() }
        val lease = ThreadTreeOrderLease()
        val displayed = lease.display(native, listOf(0, 2, 1))
        val expanded = displayed.toMutableList().apply { add(Any()) }
        assertSame(expanded, lease.restore(expanded))
        assertSame(displayed, lease.restore(displayed))
    }

    @Test
    fun restoreDoesNotUndoExternalDeletionOrReordering() {
        val native = List(3) { Any() }
        for (changed in listOf(listOf(native[0], native[2]), listOf(native[2], native[0], native[1]))) {
            val lease = ThreadTreeOrderLease()
            lease.display(native, listOf(0, 2, 1))
            assertSame(changed, lease.restore(changed))
        }
    }

    @Test
    fun equalButDifferentIdentityIsAnExternalReplacement() {
        val first = EqualRow(1)
        val second = EqualRow(2)
        val lease = ThreadTreeOrderLease()
        lease.display(listOf(first, second), listOf(1, 0))
        val replaced = listOf(EqualRow(2), first)
        assertSame(replaced, lease.restore(replaced))
    }

    @Test
    fun displayUsesUnknownCurrentAsTheNewCompleteCanonicalOrder() {
        val oldNative = List(3) { Any() }
        val lease = ThreadTreeOrderLease()
        lease.display(oldNative, listOf(0, 2, 1))
        val newNative = listOf(oldNative[0], Any(), oldNative[2], oldNative[1])
        val displayed = lease.display(newNative, listOf(0, 3, 1, 2))
        assertReferences(listOf(newNative[0], newNative[3], newNative[1], newNative[2]), displayed)
        assertReferences(newNative, lease.restore(displayed))
    }

    @Test
    fun nativeExpansionResetRetainsEveryInsertedReference() {
        val native = MutableList<Any?>(3) { Any() }
        val lease = ThreadTreeOrderLease()
        native.replaceWith(lease.display(native, listOf(0, 2, 1)))
        native.replaceWith(lease.restore(native))
        val inserted = Any()
        native.add(1, inserted)
        val expandedNative = native.toList()
        lease.reset(native)
        native.replaceWith(lease.display(native, listOf(0, 2, 1, 3)))
        native.replaceWith(lease.restore(native))
        assertReferences(expandedNative, native)
        assertSame(inserted, native[1])
    }

    @Test
    fun aFutureListReferenceWithTheSameIdentitySequenceCanRestore() {
        val native = List(3) { Any() }
        val lease = ThreadTreeOrderLease()
        val displayed = lease.display(native, listOf(0, 2, 1))
        val futureReference = ArrayList(displayed)
        assertNotSame(displayed, futureReference)
        assertReferences(native, lease.restore(futureReference))
    }

    @Test
    fun mutatingReturnedDisplayCannotChangeTheStoredDisplayWitness() {
        val native = List(3) { Any() }
        val lease = ThreadTreeOrderLease()
        val result = lease.display(native, listOf(0, 2, 1))
        val displayed = result as? MutableList<Any?> ?: result.toMutableList()
        displayed[1] = Any()
        assertSame(displayed, lease.restore(displayed))
    }

    @Test
    fun canonicalSnapshotDoesNotAliasTheHostListOrARestoreResult() {
        val native = MutableList<Any?>(3) { Any() }
        val expected = native.toList()
        val lease = ThreadTreeOrderLease()
        val displayed = lease.display(native, listOf(0, 2, 1))
        native.clear()
        val restored = lease.restore(displayed)
        assertReferences(expected, restored)
        if (restored is MutableList<Any?>) restored.clear()
        assertReferences(expected, lease.restore(lease.display(expected, listOf(0, 2, 1))))
    }

    @Test
    fun alreadyDfsAndRepeatedDisplayDoNotDoublePermute() {
        val native = List(3) { Any() }
        val lease = ThreadTreeOrderLease()
        assertSame(native, lease.display(native, listOf(0, 1, 2)))
        val displayed = lease.display(native, listOf(0, 2, 1))
        assertSame(displayed, lease.display(displayed, listOf(0, 2, 1)))
        assertReferences(native, lease.restore(displayed))
    }

    @Test
    fun emptyListIsAnIdentityPreservingNoOp() {
        val native = ArrayList<Any?>()
        val lease = ThreadTreeOrderLease()
        assertSame(native, lease.display(native, emptyList()))
        assertSame(native, lease.restore(native))
        lease.reset(native)
        assertSame(native, lease.display(native, emptyList()))
    }

    @Test
    fun displayedClickMapsToTheCanonicalPositionOfTheSameObject() {
        val native = List(4) { Any() }
        val lease = ThreadTreeOrderLease()
        val order = listOf(0, 3, 1, 2)
        val displayed = lease.display(native, order)
        for (position in displayed.indices) {
            val mapped = lease.nativePosition(ArrayList(displayed), position)
            assertEquals(order[position], mapped)
            assertSame(displayed[position], native[mapped])
        }
    }

    @Test
    fun canonicalUnknownAndResetListsDoNotUseAStaleClickMapping() {
        val native = List(3) { Any() }
        val lease = ThreadTreeOrderLease()
        val displayed = lease.display(native, listOf(0, 2, 1))
        assertEquals(1, lease.nativePosition(native, 1))
        val unknown = displayed.toMutableList().apply { this[1] = Any() }
        assertEquals(1, lease.nativePosition(unknown, 1))
        lease.reset(displayed)
        assertEquals(1, lease.nativePosition(displayed, 1))
    }

    @Test
    fun equalDifferentEntitiesAndOneNullRemainDistinctForClickMapping() {
        val first = EqualRow(7)
        val second = EqualRow(7)
        val native = listOf(first, null, second)
        val lease = ThreadTreeOrderLease()
        val displayed = lease.display(native, listOf(2, 0, 1))
        assertReferences(listOf(second, first, null), displayed)
        assertEquals(2, lease.nativePosition(displayed, 0))
        assertEquals(0, lease.nativePosition(displayed, 1))
        assertEquals(1, lease.nativePosition(displayed, 2))
        assertReferences(native, lease.restore(displayed))
    }

    @Test
    fun invalidClickPositionsPassThroughUnchanged() {
        val native = List(3) { Any() }
        val lease = ThreadTreeOrderLease()
        val displayed = lease.display(native, listOf(0, 2, 1))
        for (position in listOf(-1, Int.MIN_VALUE, 3, Int.MAX_VALUE)) {
            assertEquals(position, lease.nativePosition(displayed, position))
        }
        assertEquals(0, ThreadTreeOrderLease().nativePosition(emptyList(), 0))
    }

    @Test
    fun invalidPermutationsReturnTheInputWithoutChangingAnExistingLease() {
        val native = List(3) { Any() }
        val invalidOrders = listOf(
            emptyList(), listOf(0, 1), listOf(0, 1, 2, 3), listOf(0, 0, 2),
            listOf(-1, 1, 2), listOf(0, 1, 3), listOf(Int.MIN_VALUE, 1, 2),
            listOf(0, 1, Int.MAX_VALUE),
        )
        for (invalidOrder in invalidOrders) {
            val lease = ThreadTreeOrderLease()
            val displayed = lease.display(native, listOf(0, 2, 1))
            assertSame(displayed, lease.display(displayed, invalidOrder))
            val unknown = List(3) { Any() }
            assertSame(unknown, lease.display(unknown, invalidOrder))
            assertEquals(2, lease.nativePosition(displayed, 1))
            assertReferences(native, lease.restore(displayed))
        }
    }

    @Test
    fun invalidPermutationDoesNotInitializeACanonicalSnapshot() {
        val lease = ThreadTreeOrderLease()
        val discarded = List(3) { Any() }
        assertSame(discarded, lease.display(discarded, listOf(0, 0, 2)))
        val native = List(3) { Any() }
        val displayed = lease.display(native, listOf(0, 2, 1))
        assertReferences(listOf(native[0], native[2], native[1]), displayed)
        assertReferences(native, lease.restore(displayed))
    }

    @Test
    fun repeatedObjectIdentityFailsOpenInsteadOfSortingAmbiguousOccurrences() {
        val repeated = Any()
        val native = listOf(repeated, Any(), repeated)
        val lease = ThreadTreeOrderLease()
        assertSame(native, lease.display(native, listOf(1, 0, 2)))
        assertSame(native, lease.restore(native))
        assertEquals(2, lease.nativePosition(native, 2))
        lease.reset(native)
        assertSame(native, lease.display(native, listOf(1, 0, 2)))
    }

    @Test
    fun repeatedNullIdentityAlsoFailsOpen() {
        val native = listOf(null, Any(), null)
        val lease = ThreadTreeOrderLease()
        assertSame(native, lease.display(native, listOf(1, 0, 2)))
        assertSame(native, lease.restore(native))
        assertEquals(2, lease.nativePosition(native, 2))
    }

    @Test
    fun exactly4096RowsCanReorderMapAndRestore() {
        val native = List(4096) { Any() }
        val lease = ThreadTreeOrderLease()
        val order = native.indices.reversed().toList()
        val displayed = lease.display(native, order)
        assertReferences(native.reversed(), displayed)
        assertEquals(4095, lease.nativePosition(displayed, 0))
        assertReferences(native, lease.restore(displayed))
    }

    @Test
    fun overBudgetFailsOpenWithoutInspectingRowsOrChangingAnActiveDisplayLease() {
        val native = List(3) { Any() }
        val lease = ThreadTreeOrderLease()
        val displayed = lease.display(native, listOf(0, 2, 1))
        val tooLarge = object : AbstractList<Any?>() {
            override val size: Int get() = 4097
            override fun get(index: Int): Any? = error("over-budget row access")
        }
        assertSame(tooLarge, lease.display(tooLarge, (0..4096).toList()))
        assertEquals(2, lease.nativePosition(displayed, 1))
        assertEquals(1, lease.nativePosition(tooLarge, 1))
        assertReferences(native, lease.restore(displayed))
        assertSame(tooLarge, lease.restore(tooLarge))
        lease.reset(tooLarge)
        assertSame(tooLarge, lease.display(tooLarge, (0..4096).toList()))
    }

    @Test
    fun entityEqualsAndHashCodeAreNeverInvoked() {
        class IdentityOnlyRow {
            override fun equals(other: Any?): Boolean = error("entity equals invoked")
            override fun hashCode(): Int = error("entity hashCode invoked")
        }
        val native = List(3) { IdentityOnlyRow() }
        val lease = ThreadTreeOrderLease()
        val displayed = lease.display(native, listOf(0, 2, 1))
        assertReferences(listOf(native[0], native[2], native[1]), displayed)
        assertEquals(2, lease.nativePosition(displayed, 1))
        assertReferences(native, lease.restore(displayed))
    }

    private data class EqualRow(val value: Int)

    private fun MutableList<Any?>.replaceWith(replacement: List<Any?>) {
        if (this === replacement) return
        clear()
        addAll(replacement)
    }

    private fun assertReferences(expected: List<Any?>, actual: List<Any?>) {
        assertEquals("list size", expected.size, actual.size)
        expected.indices.forEach { assertSame("position $it", expected[it], actual[it]) }
    }
}
