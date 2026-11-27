package io.github.ghsssss.ithomeadkill.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStatePublisherTest {
    @Test
    fun busyDoesNotReadOrPublishStateUntilTheUiDispatchRuns() {
        val ui = ArrayDeque<() -> Unit>()
        var state = SettingsState()
        var reads = 0
        var writes = 0
        val publisher = SettingsStatePublisher(
            dispatch = { ui.addLast(it) },
            current = { reads++; state },
            publish = { writes++; state = it },
        )

        publisher.busy()
        assertEquals(0, reads)
        assertEquals(0, writes)
        val confirmed = snapshot(2)
        state = SettingsState(confirmed, busy = false)
        ui.removeFirst().invoke()

        assertEquals(1, reads)
        assertEquals(1, writes)
        assertSame(confirmed, state.settings)
        assertTrue(state.busy)
    }

    @Test
    fun successQueuedBeforeAnotherBusyNeverReturnsTheFormToNull() {
        val fixture = Fixture()
        val confirmed = snapshot(1)

        fixture.publisher.publish(SettingsState(confirmed, busy = false))
        fixture.publisher.busy()
        fixture.drain()

        assertEquals(2, fixture.history.size)
        assertTrue(fixture.history.all { it.settings === confirmed })
        assertTrue(fixture.state.busy)
    }

    @Test
    fun busyRetainsTheLatestConfirmedRevisionNotTheSnapshotAtSchedulingTime() {
        val fixture = Fixture(SettingsState(snapshot(1), busy = false))
        val latest = snapshot(2)

        fixture.publisher.publish(SettingsState(latest, busy = false))
        fixture.publisher.busy()
        fixture.drain()

        assertSame(latest, fixture.state.settings)
        assertEquals(2L, fixture.state.settings!!.revision)
    }

    @Test
    fun busyClearsThePreviousErrorButKeepsTheConfirmedSettings() {
        val confirmed = snapshot(1)
        val fixture = Fixture(SettingsState(confirmed, "保存失败", busy = false, error = true))

        fixture.publisher.busy()
        fixture.drain()

        assertSame(confirmed, fixture.state.settings)
        assertTrue(fixture.state.busy)
        assertFalse(fixture.state.error)
        assertEquals("正在读取或保存框架设置", fixture.state.message)
    }

    @Test
    fun terminalFailureStillDropsTheUnavailableSnapshot() {
        val fixture = Fixture(SettingsState(snapshot(1), busy = false))
        val failure = SettingsState(null, "框架连接已变化", busy = false, error = true)

        fixture.publisher.busy()
        fixture.publisher.publish(failure)
        fixture.drain()

        assertSame(failure, fixture.state)
        assertNull(fixture.state.settings)
        assertTrue(fixture.state.error)
        assertFalse(fixture.state.busy)
    }

    @Test
    fun multipleResultsAndBusyUpdatesKeepTheirDispatchOrder() {
        val fixture = Fixture()
        val first = snapshot(1)
        val second = snapshot(2)

        fixture.publisher.publish(SettingsState(first, busy = false))
        fixture.publisher.busy()
        fixture.publisher.publish(SettingsState(second, busy = false))
        fixture.publisher.busy()
        fixture.drain()

        assertEquals(listOf(first, first, second, second), fixture.history.map { it.settings })
        assertEquals(listOf(false, true, false, true), fixture.history.map { it.busy })
    }

    private class Fixture(initial: SettingsState = SettingsState()) {
        val ui = ArrayDeque<() -> Unit>()
        var state = initial
        val history = mutableListOf<SettingsState>()
        val publisher = SettingsStatePublisher(
            dispatch = { ui.addLast(it) },
            current = { state },
            publish = { state = it; history += it },
        )

        fun drain() {
            while (ui.isNotEmpty()) ui.removeFirst().invoke()
        }
    }

    private fun snapshot(revision: Long) =
        ModuleSettings.defaults("12345678-1234-1234-1234-123456789abc").copy(revision = revision)
}
