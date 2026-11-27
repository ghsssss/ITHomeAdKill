package io.github.ghsssss.ithomeadkill.hook

import java.lang.ref.WeakReference

/** Weak list ownership and one main-queue task, independent of an attached View queue. */
internal class ThreadTreeOrderQueue<L : Any>(
    list: L,
    private val enqueue: (Runnable) -> Boolean,
    private val remove: (Runnable) -> Unit,
) {
    private var reference = WeakReference<L>(list)
    private var task: Runnable? = null
    val owner: L? get() = reference.get()
    val posted: Boolean get() = task != null

    fun bind(list: L?): Boolean {
        if (owner === list) return false
        val previous = task
        task = null
        if (previous != null) remove(previous)
        reference = WeakReference(list)
        return true
    }

    fun schedule(block: () -> Unit) {
        if (posted) return
        val next = object : Runnable {
            override fun run() {
                if (task !== this) return
                task = null
                block()
            }
        }
        task = next
        if (!enqueue(next) && task === next) task = null
    }
}
