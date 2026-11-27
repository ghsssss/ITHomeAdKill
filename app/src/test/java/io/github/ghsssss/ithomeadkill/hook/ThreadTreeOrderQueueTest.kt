package io.github.ghsssss.ithomeadkill.hook

import org.junit.Assert.*
import org.junit.Test

class ThreadTreeOrderQueueTest {
    private class MainQueue {
        val tasks = mutableListOf<Runnable>()
        val removed = mutableListOf<Runnable>()
        var accepting = true
        fun post(task: Runnable): Boolean = accepting.also { if (it) tasks += task }
        fun remove(task: Runnable) { removed += task; tasks.remove(task) }
        fun run() { val current = tasks.toList(); tasks.clear(); current.forEach { it.run() } }
    }

    @Test fun rebindCancelsPendingOldOwnerBeforeNewPrepare() {
        val main = MainQueue()
        val old = Any()
        val next = Any()
        val owner = ThreadTreeOrderQueue(old, main::post, main::remove)
        var calls = 0
        owner.schedule { calls++ }
        assertTrue(owner.posted)
        assertTrue(owner.bind(next))
        assertSame(next, owner.owner)
        assertFalse(owner.posted)
        assertEquals(1, main.removed.size)
        owner.schedule { assertSame(next, owner.owner); calls++ }
        main.run()
        assertEquals(1, calls)
    }

    @Test fun alreadyDequeuedOldTaskCannotClearNewPostedWork() {
        val main = MainQueue()
        val owner = ThreadTreeOrderQueue(Any(), main::post, main::remove)
        var stale = false
        var fresh = false
        owner.schedule { stale = true }
        val dequeued = main.tasks.single()
        owner.bind(Any())
        owner.schedule { fresh = true }
        dequeued.run()
        assertFalse(stale)
        assertTrue(owner.posted)
        main.run()
        assertTrue(fresh)
        assertFalse(owner.posted)
    }

    @Test fun detachWithPendingTaskAndDisabledOptionRestoresCanonicalOnMainQueue() {
        val main = MainQueue()
        val owner = ThreadTreeOrderQueue(Any(), main::post, main::remove)
        val root = Any(); val parent = Any(); val sibling = Any(); val reply = Any()
        val original = listOf(root, parent, sibling, reply)
        val lease = ThreadTreeOrderLease()
        var data = lease.display(original, listOf(0, 1, 3, 2))
        var enabled = true
        owner.schedule { if (enabled) data = lease.display(data, listOf(0, 1, 3, 2)) }
        val detachedTask = main.tasks.single()
        enabled = false
        assertTrue(owner.bind(null))
        assertNull(owner.owner)
        assertFalse(owner.posted)
        owner.schedule { data = lease.restore(data) }
        detachedTask.run()
        assertTrue(owner.posted)
        main.run()
        assertEquals(original, data)
        assertEquals(0, main.tasks.size)
    }

    @Test fun rebindWhileRestoringDoesNotLoseNewHostRecords() {
        val main = MainQueue()
        val owner = ThreadTreeOrderQueue(Any(), main::post, main::remove)
        val first = Any(); val second = Any(); val third = Any()
        val lease = ThreadTreeOrderLease()
        var data = lease.display(listOf(first, second, third), listOf(0, 2, 1))
        owner.schedule { data = lease.restore(data) }
        owner.bind(null)
        val added = Any()
        data = listOf(first, second, third, added)
        owner.bind(Any())
        owner.schedule { data = lease.restore(data) }
        main.run()
        assertEquals(listOf(first, second, third, added), data)
    }

    @Test fun sameOwnerKeepsOneQueuedTask() {
        val main = MainQueue()
        val list = Any()
        val owner = ThreadTreeOrderQueue(list, main::post, main::remove)
        var calls = 0
        owner.schedule { calls++ }
        assertFalse(owner.bind(list))
        owner.schedule { calls += 10 }
        assertEquals(1, main.tasks.size)
        main.run()
        assertEquals(1, calls)
    }

    @Test fun rejectedPostCanBeRetriedAndOldRunnableCannotPublish() {
        var rejected: Runnable? = null
        val main = MainQueue()
        val owner = ThreadTreeOrderQueue(Any(), { rejected = it; false }, main::remove)
        var calls = 0
        owner.schedule { calls++ }
        assertFalse(owner.posted)
        rejected!!.run()
        assertEquals(0, calls)
    }

    @Test fun completedTaskCanScheduleItsFollowup() {
        val main = MainQueue()
        val owner = ThreadTreeOrderQueue(Any(), main::post, main::remove)
        var calls = 0
        owner.schedule { calls++; owner.schedule { calls++ } }
        main.run()
        assertEquals(1, calls)
        assertTrue(owner.posted)
        main.run()
        assertEquals(2, calls)
        assertFalse(owner.posted)
    }
}
