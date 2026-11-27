package io.github.ghsssss.ithomeadkill.ui

import java.util.concurrent.Executor

internal enum class LauncherIconFailure { READ, CHANGE }
internal data class LauncherIconResult(val hidden: Boolean?, val failure: LauncherIconFailure? = null)

/** The process owns the serial worker; a page owns only its publications. */
internal class LauncherIconTasks(
    private val worker: Executor,
    private val dispatch: (() -> Unit) -> Unit,
    private val readHidden: () -> Boolean,
    private val writeHidden: (Boolean) -> Unit,
    private val publish: (LauncherIconResult) -> Unit,
) {
    private val lifecycleLock = Any()
    private var closed = false

    fun refresh() = enqueue { readResult() }

    fun setHidden(hidden: Boolean) = enqueue {
        runCatching { writeHidden(hidden) }.fold(
            onSuccess = { readResult() },
            onFailure = {
                LauncherIconResult(runCatching { readHidden() }.getOrNull(), LauncherIconFailure.CHANGE)
            },
        )
    }

    fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            // Accepted writes finish before a recreated page's reads on the same queue.
        }
    }

    private fun readResult() = runCatching { readHidden() }.fold(
        onSuccess = { LauncherIconResult(it) },
        onFailure = { LauncherIconResult(null, LauncherIconFailure.READ) },
    )

    private fun enqueue(operation: () -> LauncherIconResult) {
        synchronized(lifecycleLock) {
            if (closed) return
            worker.execute {
                val next = operation()
                synchronized(lifecycleLock) {
                    if (!closed) {
                        dispatch {
                            synchronized(lifecycleLock) { if (!closed) publish(next) }
                        }
                    }
                }
            }
        }
    }
}
