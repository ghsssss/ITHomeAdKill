package io.github.ghsssss.ithomeadkill.hook

import java.util.IdentityHashMap

/** Identity-only reversible display order; never mutates host lists or their entities. */
internal class ThreadTreeOrderLease {
    private var canonical: List<Any?>? = null
    private var lastDisplay: List<Any?>? = null

    /** order[displayedPosition] is a canonical index, never an index into the old display. */
    fun display(current: List<Any?>, order: List<Int>): List<Any?> {
        // Validate before touching the lease, including when current is an unknown host list.
        if (current.size > MAX_ROWS || !validOrder(order, current.size) || !uniqueReferences(current)) {
            return current
        }
        if (!sameReferences(current, canonical) && !sameReferences(current, lastDisplay)) {
            reset(current)
        }
        val native = canonical ?: return current
        val displayed = order.map { native[it] }
        lastDisplay = displayed.toList()
        return if (sameReferences(current, displayed)) current else displayed
    }

    fun restore(current: List<Any?>): List<Any?> {
        if (!sameReferences(current, lastDisplay)) {
            // A host insertion/deletion/replacement revokes the old restore witness.
            reset(current)
            return current
        }
        val native = canonical ?: return current
        return if (sameReferences(current, native)) current else native.toList()
    }

    fun nativePosition(current: List<Any?>, displayedPosition: Int): Int {
        if (displayedPosition !in current.indices || !sameReferences(current, lastDisplay)) {
            return displayedPosition
        }
        val native = canonical ?: return displayedPosition
        val clicked = current[displayedPosition]
        val position = native.indexOfFirst { it === clicked }
        return if (position >= 0) position else displayedPosition
    }

    /** Call with the full native list after the host callback, including expansion results. */
    fun reset(current: List<Any?>) {
        canonical = if (current.size <= MAX_ROWS && uniqueReferences(current)) current.toList() else null
        lastDisplay = null
    }

    private fun validOrder(order: List<Int>, size: Int): Boolean {
        if (order.size != size) return false
        val seen = BooleanArray(size)
        for (position in order) {
            if (position !in 0 until size || seen[position]) return false
            seen[position] = true
        }
        return true
    }

    private fun uniqueReferences(current: List<Any?>): Boolean {
        val seen = IdentityHashMap<Any?, Boolean>(current.size)
        return current.all { seen.put(it, true) == null }
    }

    private fun sameReferences(current: List<Any?>, snapshot: List<Any?>?): Boolean {
        if (snapshot == null || current.size != snapshot.size) return false
        val actual = current.iterator()
        return snapshot.all { actual.next() === it }
    }

    private companion object {
        const val MAX_ROWS = 4096
    }
}
