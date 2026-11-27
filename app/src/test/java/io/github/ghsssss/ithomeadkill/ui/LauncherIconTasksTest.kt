package io.github.ghsssss.ithomeadkill.ui

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIconTasksTest {
    @Test
    fun refreshRunsOnTheWorkerAndPublishesOnlyOnTheUiQueue() {
        val fixture = Fixture(hidden = true)

        fixture.tasks.refresh()
        assertTrue(fixture.calls.isEmpty())
        assertTrue(fixture.results.isEmpty())
        fixture.worker.runNext()
        assertEquals(listOf("read"), fixture.calls)
        assertTrue(fixture.results.isEmpty())
        fixture.drainUi()
        assertEquals(listOf(LauncherIconResult(true)), fixture.results)
    }

    @Test
    fun writeNeverOptimisticallyPublishesAndReadsTheConfirmedComponentState() {
        val fixture = Fixture()

        fixture.tasks.setHidden(true)
        assertFalse(fixture.hidden)
        assertTrue(fixture.calls.isEmpty())
        fixture.worker.runNext()
        assertTrue(fixture.hidden)
        assertTrue(fixture.results.isEmpty())
        fixture.drainUi()

        assertEquals(listOf("write:true", "read"), fixture.calls)
        assertEquals(listOf(LauncherIconResult(true)), fixture.results)
    }

    @Test
    fun refreshAndWritesUseOneFifoWorkerQueue() {
        val fixture = Fixture()

        fixture.tasks.refresh()
        fixture.tasks.setHidden(true)
        fixture.tasks.setHidden(false)
        assertTrue(fixture.calls.isEmpty())
        fixture.worker.drain()
        fixture.drainUi()

        assertEquals(listOf("read", "write:true", "read", "write:false", "read"), fixture.calls)
        assertEquals(listOf(false, true, false), fixture.results.map { it.hidden })
    }

    @Test
    fun readFailureRemainsUnknownInsteadOfPretendingTheIconIsVisible() {
        val fixture = Fixture(hidden = true)
        fixture.readFailure = IllegalStateException("unavailable")

        fixture.tasks.refresh()
        fixture.worker.drain()
        fixture.drainUi()

        assertEquals(listOf(LauncherIconResult(null, LauncherIconFailure.READ)), fixture.results)
    }

    @Test
    fun deniedWriteReportsThePersistedValueAndTheChangeError() {
        val fixture = Fixture()
        fixture.writeFailure = SecurityException("denied")

        fixture.tasks.setHidden(true)
        fixture.worker.drain()
        fixture.drainUi()

        assertFalse(fixture.hidden)
        assertEquals(listOf(LauncherIconResult(false, LauncherIconFailure.CHANGE)), fixture.results)
    }

    @Test
    fun failedWriteAndFailedFallbackReadRemainUnknownWithTheChangeError() {
        val fixture = Fixture()
        fixture.writeFailure = SecurityException("denied")
        fixture.readFailure = IllegalStateException("unavailable")

        fixture.tasks.setHidden(true)
        fixture.worker.drain()
        fixture.drainUi()

        assertEquals(listOf(LauncherIconResult(null, LauncherIconFailure.CHANGE)), fixture.results)
    }

    @Test
    fun successfulWriteFollowedByAnUnavailableRefreshReportsAReadError() {
        val fixture = Fixture()
        fixture.afterWrite = { fixture.readFailure = IllegalStateException("unavailable") }

        fixture.tasks.setHidden(true)
        fixture.worker.drain()
        fixture.drainUi()

        assertTrue(fixture.hidden)
        assertEquals(listOf(LauncherIconResult(null, LauncherIconFailure.READ)), fixture.results)
    }

    @Test
    fun closingSuppressesAnAlreadyPostedUiPublication() {
        val fixture = Fixture(hidden = true)
        fixture.tasks.refresh()
        fixture.worker.drain()
        assertEquals(1, fixture.ui.size)

        fixture.tasks.close()
        fixture.drainUi()

        assertTrue(fixture.results.isEmpty())
        assertFalse(fixture.worker.isShutdown)
    }

    @Test
    fun closingBeforeExecutionFinishesAcceptedWritesWithoutPublishingToTheDeadPage() {
        val fixture = Fixture()
        fixture.tasks.setHidden(true)

        fixture.tasks.close()
        fixture.tasks.close()
        fixture.tasks.setHidden(false)
        fixture.tasks.refresh()
        fixture.worker.drain()
        fixture.drainUi()

        assertTrue(fixture.hidden)
        assertEquals(listOf("write:true", "read"), fixture.calls)
        assertTrue(fixture.results.isEmpty())
        assertFalse(fixture.worker.isShutdown)
    }

    @Test
    fun closingDoesNotDropEitherOfTheAlreadyAcceptedWrites() {
        val fixture = Fixture()
        fixture.tasks.setHidden(true)
        fixture.tasks.setHidden(false)

        fixture.tasks.close()
        fixture.worker.drain()
        fixture.drainUi()

        assertFalse(fixture.hidden)
        assertEquals(listOf("write:true", "read", "write:false", "read"), fixture.calls)
        assertTrue(fixture.results.isEmpty())
    }

    @Test
    fun recreatedPageReadsAfterTheOldPagesAcceptedWriteOnTheSharedQueue() {
        val worker = ManualWorker()
        val ui = ArrayDeque<() -> Unit>()
        val oldResults = mutableListOf<LauncherIconResult>()
        val newResults = mutableListOf<LauncherIconResult>()
        var persisted = false
        val oldPage = LauncherIconTasks(
            worker = worker,
            dispatch = { ui.addLast(it) },
            readHidden = { persisted },
            writeHidden = { persisted = it },
            publish = { oldResults += it },
        )
        oldPage.setHidden(true)
        oldPage.close()
        val newPage = LauncherIconTasks(
            worker = worker,
            dispatch = { ui.addLast(it) },
            readHidden = { persisted },
            writeHidden = { persisted = it },
            publish = { newResults += it },
        )

        newPage.refresh()
        worker.drain()
        while (ui.isNotEmpty()) ui.removeFirst().invoke()

        assertTrue(persisted)
        assertTrue(oldResults.isEmpty())
        assertEquals(listOf(LauncherIconResult(true)), newResults)
        newPage.close()
    }

    @Test
    fun realExecutorDoesNotRunTheReadOrPublishOnTheCallingThread() {
        val caller = Thread.currentThread()
        val worker = Executors.newSingleThreadExecutor()
        val ui = LinkedBlockingQueue<() -> Unit>()
        val readDone = CountDownLatch(1)
        var readThread: Thread? = null
        var publishThread: Thread? = null
        val tasks = LauncherIconTasks(
            worker = worker,
            dispatch = { ui.add(it) },
            readHidden = {
                readThread = Thread.currentThread()
                readDone.countDown()
                true
            },
            writeHidden = {},
            publish = { publishThread = Thread.currentThread() },
        )
        try {
            tasks.refresh()
            assertTrue(readDone.await(5, TimeUnit.SECONDS))
            assertNotSame(caller, readThread)
            val publication = ui.poll(5, TimeUnit.SECONDS)
            assertTrue(publication != null)
            assertNull(publishThread)
            publication!!.invoke()
            assertSame(caller, publishThread)
        } finally {
            tasks.close()
            worker.shutdown()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private class Fixture(var hidden: Boolean = false) {
        val worker = ManualWorker()
        val ui = ArrayDeque<() -> Unit>()
        val calls = mutableListOf<String>()
        val results = mutableListOf<LauncherIconResult>()
        var readFailure: RuntimeException? = null
        var writeFailure: RuntimeException? = null
        var afterWrite: () -> Unit = {}
        val tasks = LauncherIconTasks(
            worker = worker,
            dispatch = { ui.addLast(it) },
            readHidden = {
                calls += "read"
                readFailure?.let { throw it }
                hidden
            },
            writeHidden = {
                calls += "write:$it"
                writeFailure?.let { failure -> throw failure }
                hidden = it
                afterWrite()
            },
            publish = { results += it },
        )

        fun drainUi() {
            while (ui.isNotEmpty()) ui.removeFirst().invoke()
        }
    }

    private class ManualWorker : AbstractExecutorService() {
        private val queue = ArrayDeque<Runnable>()
        private var stopped = false

        override fun execute(command: Runnable) {
            check(!stopped)
            queue.addLast(command)
        }

        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> {
            shutdown()
            return queue.toMutableList().also { queue.clear() }
        }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && queue.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
        fun runNext() { queue.removeFirst().run() }
        fun drain() { while (queue.isNotEmpty()) runNext() }
    }
}
