package io.github.ghsssss.ithomeadkill.hook

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Native callbacks run against native order, then one complete display permutation is published. */
internal class ThreadTreeOrderController(
    private val hooks: ModernHooks,
    private val enabled: () -> Boolean,
    private val changed: (Any) -> Unit,
    private val report: (String, String) -> Unit,
) {
    private class State(list: ViewGroup, handler: Handler) {
        val work = ThreadTreeOrderQueue(list, handler::post, handler::removeCallbacks)
        val lease = ThreadTreeOrderLease()
        var depth = 0
        var pending = false
        var publishing = false

        var restoring = false
        var beforeTransaction: List<Any?>? = null
    }
    private val states = WeakHashMap<Any, State>()
    private val handler = Handler(Looper.getMainLooper())
    private val source = ThreadTreeDataSource()
    private val installed = mutableSetOf<Method>()

    fun prepare(list: ViewGroup): Boolean {
        if (!enabled() || Looper.myLooper() != Looper.getMainLooper()) return false
        val adapter = adapterOf(list) ?: return false
        states.entries.filter { it.key !== adapter && it.value.work.owner === list }.map { it.key }.forEach { previous ->
            states[previous]?.work?.bind(null)
            restore(previous)
        }
        states[adapter]?.let { state ->
            if (state.work.bind(list)) {
                // Finish the old lease before admitting the same adapter on its new list.
                state.restoring = true
                schedule(adapter, state)
            }
            if (state.depth > 0 || state.work.posted) return true
            if (state.pending || state.restoring) { schedule(adapter, state); return true }
        }
        if (source.mutableData(adapter) == null || !install(adapter)) return false
        val state = states[adapter] ?: State(list, handler).also { states[adapter] = it }
        val snapshot = source.read(adapter) ?: return false
        val order = ThreadTreeOrderModel.order(snapshot.topology.records).map { it - snapshot.headerCount }
        val data = source.mutableData(adapter) ?: return false
        if (order.size != data.size) return false
        // Pre-draw never mutates adapter data. Apply after this layout/scroll transaction has ended.
        val desired = order.map { data[it] }
        if (same(data, desired)) return false
        schedule(adapter, state)
        return true
    }

    fun notification(adapter: Any?): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        val state = states[adapter] ?: return false
        if (state.publishing) return false
        if (state.depth == 0) return false
        state.pending = true
        return true
    }

    fun restoreList(list: ViewGroup) {
        states.entries.filter { it.value.work.owner === list }.map { it.key }.forEach { adapter ->
            states[adapter]?.work?.bind(null)
            restore(adapter)
        }
    }

    fun restoreWithin(root: View) {
        states.entries.filter { it.value.work.owner?.rootView === root.rootView }.map { it.key }.forEach(::restore)
    }

    fun clear() { states.keys.toList().forEach(::restore) }

    private fun restore(adapter: Any) {
        val state = states[adapter] ?: return
        state.restoring = true
        schedule(adapter, state)
    }

    private fun begin(adapter: Any, position: Int?): Int? {
        if (Looper.myLooper() != Looper.getMainLooper()) return position
        val state = states[adapter] ?: return position
        if (state.depth > 0) { state.depth++; return position }
        val data = source.mutableData(adapter) ?: return position
        val mapped = position?.let { state.lease.nativePosition(data.toList(), it) }
        if (state.beforeTransaction == null) state.beforeTransaction = data.toList()
        val native = state.lease.restore(data.toList())
        if (!same(data, native)) replace(data, native)
        state.depth = 1
        return mapped
    }

    private fun end(adapter: Any) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        val state = states[adapter] ?: return
        if (state.depth <= 0) return
        if (--state.depth > 0) return
        // Capture every native mutation now, but never reorder or notify inside the host's layout transaction.
        state.lease.reset(source.mutableData(adapter)?.toList() ?: emptyList())
        changed(adapter)
        schedule(adapter, state)
    }

    private fun schedule(adapter: Any, state: State) {
        if (states[adapter] !== state) return
        val reference = WeakReference(adapter)
        state.work.schedule {
            val current = reference.get() ?: return@schedule
            if (states[current] === state) flush(current, state)
        }
    }

    private fun flush(adapter: Any, state: State) {
        if (state.depth > 0) return // The matching outer exit schedules the complete transaction again.
        val list = state.work.owner
        if (list != null && computing(list)) { schedule(adapter, state); return }
        val restoring = state.restoring || !enabled() || list?.isAttachedToWindow != true || list?.let { adapterOf(it) } !== adapter
        val values = source.mutableData(adapter)
        if (values != null) {
            val before = state.beforeTransaction ?: values.toList()
            val native = state.lease.restore(values.toList())
            replace(values, native)
            val snapshot = if (restoring) null else source.read(adapter)
            val permutation = snapshot?.let { ThreadTreeOrderModel.order(it.topology.records).map { p -> p - it.headerCount } }
            val next = if (permutation != null) state.lease.display(native, permutation) else native
            replace(values, next)
            state.pending = state.pending || !same(before, next)
        } else {
            // An unsupported replacement revokes sorting, not an already consumed native notification.
            state.lease.reset(emptyList())
        }
        state.beforeTransaction = null
        if (state.pending && !publish(adapter, state)) return
        if (restoring) {
            state.work.bind(null)
            states.remove(adapter)
        }
    }

    private fun publish(adapter: Any, state: State): Boolean {
        changed(adapter)
        state.publishing = true
        return try {
            adapter.javaClass.getMethod("notifyDataSetChanged").invoke(adapter)
            state.pending = false
            true
        } catch (error: Throwable) {
            state.pending = true
            report("comment-tree-order-notify", "comment-tree:notification-pending:${error.javaClass.simpleName}")
            false
        } finally { state.publishing = false }
    }

    private fun install(adapter: Any): Boolean {
        val loader = adapter.javaClass.classLoader
        val base = runCatching { Class.forName("com.chad.library.adapter.base.BaseQuickAdapter", false, loader) }.getOrNull() ?: return false
        val callback = field(adapter, "mOnItemClickListener") ?: return false
        val callbacks = callback.javaClass.declaredMethods.filter {
            it.name in setOf("onItemClick", "onItemLongClick") && it.parameterTypes.contentEquals(
                arrayOf(base, Int::class.javaPrimitiveType, View::class.java))
        }
        if (callbacks.none { it.name == "onItemClick" }) return false
        for (method in callbacks) {
            if (method in installed) continue
            val handle = hooks.observe(method,
                arguments = { chain, args ->
                    val target = chain.args[0] ?: return@observe
                    val position = chain.args[1] as? Int ?: return@observe
                    args[1] = begin(target, position) ?: position
                },
                after = { chain -> chain.args[0]?.let(::end) })
            if (handle == null) return false
            installed += method
        }
        for (method in base.declaredMethods.filter { it.name in setOf("setNewData", "replaceData", "setData", "addData", "remove") }) {
            if (method in installed) continue
            val indexed = method.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType
            val handle = hooks.observe(method,
                arguments = { chain, args ->
                    val target = chain.thisObject ?: return@observe
                    val position = if (indexed) chain.args.firstOrNull() as? Int else null
                    val mapped = begin(target, position)
                    if (indexed && mapped != null) args[0] = mapped
                }, after = { chain -> chain.thisObject?.let(::end) })
            if (handle == null) return false
            installed += method
        }
        report("comment-tree-order-hooks", "comment-tree:order-transactions=ready")
        return true
    }

    private fun field(target: Any, name: String): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val current = type
            val result = runCatching { current.getDeclaredField(name).apply { isAccessible = true }.get(target) }.getOrNull()
            if (result != null) return result
            type = current.superclass
        }
        return null
    }

    private fun adapterOf(list: ViewGroup): Any? = runCatching {
        list.javaClass.getMethod("getAdapter").invoke(list)
    }.getOrNull()

    private fun computing(list: ViewGroup): Boolean = runCatching {
        list.javaClass.getMethod("isComputingLayout").invoke(list) == true
    }.getOrDefault(true)

    private fun same(first: List<*>, second: List<*>): Boolean = first.size == second.size && first.indices.all { first[it] === second[it] }

    private fun replace(data: MutableList<Any?>, desired: List<Any?>): Boolean {
        if (same(data, desired) || data.size != desired.size) return false
        for (index in data.indices) data[index] = desired[index]
        return true
    }
}
