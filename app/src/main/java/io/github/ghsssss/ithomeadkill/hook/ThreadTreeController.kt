package io.github.ghsssss.ithomeadkill.hook

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** One non-interactive overlay per verified list, with reversible reply-margin ownership. */
internal class ThreadTreeController(
    private val enabled: () -> Boolean,
    private val report: (String, String) -> Unit,
    private val prepareOrder: (ViewGroup) -> Boolean = { false },
    private val releaseOrder: (ViewGroup) -> Unit = {},
) {
    private val owners = WeakHashMap<ViewGroup, Lines>()
    private val indents = WeakHashMap<View, Indent>()
    private val source = ThreadTreeDataSource()
    private val fills = ThreadTreeFillController()
    private var mutating = false

    fun clearAll() {
        fills.clear()
        owners.keys.toList().forEach(::remove)
        indents.keys.toList().forEach(::release)
    }

    fun adapterChanged(adapter: Any?) {
        if (adapter == null) return
        owners.values.toList().forEach { it.changed(adapter) }
    }

    fun update(list: ViewGroup, activity: Activity) {
        if (!enabled() || !admitted(list, activity) || !list.isAttachedToWindow) {
            remove(list)
            return
        }
        val lines = owners[list] ?: Lines(list, activity).also {
            owners[list] = it
            list.overlay.add(it)
            list.viewTreeObserver.addOnPreDrawListener(it)
            list.viewTreeObserver.addOnGlobalLayoutListener(it)
            list.addOnAttachStateChangeListener(it)
        }
        lines.dirty = true
        lines.prepare()
    }

    fun removeFor(activity: Activity) {
        owners.entries.filter { it.value.activity.get() === activity }.map { it.key }.forEach(::remove)
    }

    fun releaseWithin(root: View) {
        fills.releaseWithin(root)
        indents.keys.toList().filter { descendant(it, root) }.forEach(::release)
        owners.values.forEach { it.dirty = true }
    }

    fun desiredLayout(view: View, params: ViewGroup.LayoutParams) {
        if (mutating || params !is ViewGroup.MarginLayoutParams) return
        val lease = indents[view] ?: return
        if (params !== view.layoutParams || params.marginStart != lease.owned) {
            lease.base = params.marginStart
            lease.owned = lease.base + lease.extra
            params.marginStart = lease.owned
        }
    }

    private fun remove(list: ViewGroup) {
        releaseOrder(list)
        val owner = owners.remove(list) ?: return
        list.overlay.remove(owner)
        val observer = list.viewTreeObserver
        if (observer.isAlive) {
            observer.removeOnPreDrawListener(owner)
            observer.removeOnGlobalLayoutListener(owner)
        }
        list.removeOnAttachStateChangeListener(owner)
        fills.releaseList(list)
        indents.entries.filter { it.value.list.get() === list }.map { it.key }.forEach(::release)
    }

    private fun release(view: View) {
        val lease = indents.remove(view) ?: return
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (params.marginStart != lease.owned) return
        mutating = true
        try {
            params.marginStart = lease.base
            view.layoutParams = params
        } finally { mutating = false }
    }

    private fun indent(view: View, extra: Int, list: ViewGroup): Boolean {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return false
        if (extra <= 0) {
            val changed = indents.containsKey(view)
            release(view)
            return changed
        }
        val lease = indents[view] ?: Indent(params.marginStart, extra, params.marginStart + extra, WeakReference(list)).also {
            indents[view] = it
        }
        if (params.marginStart != lease.owned) lease.base = params.marginStart
        lease.extra = extra
        lease.owned = lease.base + extra
        if (params.marginStart == lease.owned) return false
        mutating = true
        try {
            params.marginStart = lease.owned
            view.layoutParams = params
        } finally { mutating = false }
        return true
    }

    private inner class Lines(list: ViewGroup, owner: Activity) : Drawable(),
        ViewTreeObserver.OnPreDrawListener, ViewTreeObserver.OnGlobalLayoutListener,
        View.OnAttachStateChangeListener {
        val activity = WeakReference(owner)
        private val list = WeakReference(list)
        private var adapter = WeakReference<Any>(null)
        private var topology: TreeTopology? = null
        private var records = emptyMap<Int, TreeRecord>()
        private var headers = 0
        private var count = -1
        private var generation = 0L
        private val columns = mutableMapOf<Int, Float>()
        private var columnConfiguration = ""
        private var strokes = emptyList<TreeStroke>()
        private var clip = Rect()
        private var color = 0
        var dirty = true
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        fun changed(changedAdapter: Any) {
            if (adapter.get() !== changedAdapter) return
            generation++
            dirty = true
            topology = null
            if (strokes.isNotEmpty()) { strokes = emptyList(); invalidateSelf() }
            list.get()?.invalidate()
        }

        fun prepare() {
            val list = list.get() ?: return
            val activity = activity.get() ?: run { remove(list); return }
            if (!enabled() || !admitted(list, activity)) { remove(list); return }
            if (prepareOrder(list)) {
                if (strokes.isNotEmpty()) { strokes = emptyList(); invalidateSelf() }
                return
            }
            runCatching {
                val currentAdapter = list.javaClass.getMethod("getAdapter").invoke(list) ?: return@runCatching clear()
                val itemCount = currentAdapter.javaClass.getMethod("getItemCount").invoke(currentAdapter) as? Int
                    ?: return@runCatching clear()
                if (dirty || adapter.get() !== currentAdapter || count != itemCount) {
                    val snapshot = source.read(currentAdapter) ?: run {
                        report("comment-tree-data", "comment-tree:unavailable:data-contract")
                        return@runCatching clear()
                    }
                    adapter = WeakReference(currentAdapter)
                    topology = snapshot.topology
                    headers = snapshot.headerCount
                    count = snapshot.itemCount
                    records = snapshot.topology.records.associateBy { it.position }
                    dirty = false
                    val depth = snapshot.topology.nodes.values.maxOfOrNull { it.depth } ?: 0
                    report("comment-tree-ready:$depth", "comment-tree:ready records=${records.size} nodes=${snapshot.topology.nodes.size} depth=$depth")
                }
                val topology = topology ?: return@runCatching clear()
                val frameGeneration = generation
                val maximumDepth = topology.nodes.values.maxOfOrNull { it.depth } ?: 0
                val configuration = "${list.width}:${list.layoutDirection}:${list.resources.displayMetrics.density}:${list.resources.configuration.fontScale}:$maximumDepth"
                if (configuration != columnConfiguration) {
                    columns.clear()
                    columnConfiguration = configuration
                }
                val positionMethod = list.javaClass.getMethod("getChildAdapterPosition", View::class.java)
                val placements = linkedMapOf<Int, TreePlacement>()
                var calibratedColumns: Map<Int, Float> = emptyMap()
                val active = mutableSetOf<View>()
                var layoutChanged = false
                for (index in 0 until list.childCount.coerceAtMost(128)) {
                    val row = list.getChildAt(index)
                    val position = positionMethod.invoke(list, row) as? Int ?: continue
                    val node = topology.nodes[position] ?: continue
                    if (source.currentRecord(currentAdapter, position, headers) != records[position]) {
                        dirty = true
                        releaseWithin(row)
                        continue
                    }
                    val parts = parts(row, node.depth) ?: continue
                    if (node.depth > 0) {
                        fills.apply(row, list)
                        active += row
                        val params = row.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
                        val base = indents[row]?.base ?: params.marginStart
                        val available = list.width - base - params.marginEnd
                        val extra = ThreadTreeModel.extraIndent(node.depth, list.resources.displayMetrics.density,
                            available.toFloat(), maximumDepth).roundToInt()
                        if (indent(row, extra, list)) layoutChanged = true
                    }
                    val avatar = bounds(list, parts)
                    if (node.depth > 0 && calibratedColumns.isEmpty()) {
                        val margin = indents[row]?.base ?: (row.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart ?: 0
                        calibratedColumns = ThreadTreeModel.replyColumns(node.depth, (avatar.left + avatar.right) / 2f,
                            list.resources.displayMetrics.density, (list.width - margin).toFloat(), maximumDepth,
                            list.layoutDirection == View.LAYOUT_DIRECTION_RTL)
                    }
                    val level = find(row, "tv_userLevel")?.takeIf { it.visibility == View.VISIBLE && it.height > 0 }
                    val clearance = if (level != null) bounds(list, level).bottom.coerceAtLeast(avatar.bottom) else avatar.bottom
                    placements[position] = TreePlacement(bounds(list, row), avatar, clearance)
                }
                indents.keys.toList().filter { descendant(it, list) && it !in active }.forEach {
                    release(it)
                    layoutChanged = true
                }
                val viewport = Rect()
                if (layoutChanged || !list.getLocalVisibleRect(viewport) || viewport.isEmpty) {
                    if (strokes.isNotEmpty()) { strokes = emptyList(); invalidateSelf() }
                    return@runCatching
                }
                val density = list.resources.displayMetrics.density
                calibratedColumns.forEach { (depth, column) -> columns[depth] = column }
                placements.forEach { (position, placement) ->
                    topology.nodes[position]?.let { columns[it.depth] = (placement.avatar.left + placement.avatar.right) / 2f }
                }
                val computed = if (frameGeneration != generation) emptyList() else ThreadTreeModel.geometry(topology, placements,
                    TreeRect(viewport.left.toFloat(), viewport.top.toFloat(), viewport.right.toFloat(), viewport.bottom.toFloat()),
                    density, list.layoutDirection == View.LAYOUT_DIRECTION_RTL, columns)
                if (computed.isNotEmpty()) {
                    val visibleDepth = placements.keys.maxOfOrNull { topology.nodes[it]?.depth ?: 0 } ?: 0
                    report("comment-tree-visible:$visibleDepth", "comment-tree:visible rows=${placements.size} depth=$visibleDepth strokes=${computed.size}")
                }
                val dark = list.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                val nextColor = if (dark) 0xff626973.toInt() else 0xffd2d6dc.toInt()
                if (computed != strokes || clip != viewport || color != nextColor) {
                    strokes = computed
                    clip = viewport
                    color = nextColor
                    setBounds(0, 0, list.width, list.height)
                    invalidateSelf()
                }
            }.onFailure {
                clear()
                report("comment-tree-failure", "comment-tree:unavailable:${it.javaClass.simpleName}")
            }
        }

        private fun clear() {
            val list = list.get() ?: return
            topology = null
            records = emptyMap()
            dirty = true
            indents.keys.toList().filter { descendant(it, list) }.forEach(::release)
            if (strokes.isNotEmpty()) { strokes = emptyList(); invalidateSelf() }
        }

        override fun draw(canvas: Canvas) {
            val list = list.get() ?: return
            if (!enabled() || !list.isAttachedToWindow || strokes.isEmpty()) return
            paint.color = color
            paint.strokeWidth = (1.25f * list.resources.displayMetrics.density).coerceAtLeast(1f)
            canvas.save().let { save ->
                try {
                    canvas.clipRect(clip)
                    for (stroke in strokes) {
                        val dir = if (stroke.tip.x >= stroke.corner.x) 1f else -1f
                        val radius = stroke.radius.coerceAtMost(abs(stroke.tip.x - stroke.corner.x))
                            .coerceAtMost((stroke.corner.y - stroke.start.y).coerceAtLeast(0f))
                        path.reset()
                        path.moveTo(stroke.start.x, stroke.start.y)
                        path.lineTo(stroke.corner.x, stroke.corner.y - radius)
                        if (radius > 0f) path.quadTo(stroke.corner.x, stroke.corner.y,
                            stroke.corner.x + dir * radius, stroke.corner.y)
                        path.lineTo(stroke.tip.x, stroke.tip.y)
                        canvas.drawPath(path, paint)
                    }
                } finally { canvas.restoreToCount(save) }
            }
        }

        override fun onPreDraw(): Boolean { prepare(); return true }
        override fun onGlobalLayout() { dirty = true }
        override fun onViewAttachedToWindow(view: View) { dirty = true }
        override fun onViewDetachedFromWindow(view: View) { (view as? ViewGroup)?.let(::remove) }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Drawable contract") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private data class Indent(var base: Int, var extra: Int, var owned: Int, val list: WeakReference<ViewGroup>)

    private companion object {
        fun admitted(list: ViewGroup, activity: Activity): Boolean {
            return ThreadTreeAdmission.isSupported(activity.javaClass.name, entryName(list)) &&
                hasSuperclass(list, "androidx.recyclerview.widget.RecyclerView")
        }

        fun parts(row: View, depth: Int): View? {
            if (depth == 0) {
                if (entryName(row) != "ll_comment_container" || row.javaClass.name != "com.ruanmei.ithome.views.ClickEventCoordinatesRelativeLayout") return null
            } else if (entryName(row) != "shadow_child" || row.javaClass.name != "com.lihang.ShadowLayout") return null
            val avatar = find(row, "avatar_pendant") ?: return null
            val floor = find(row, "tv_floor") ?: return null
            if (find(row, "tv_user_name") == null || floor.visibility != View.VISIBLE || avatar.width <= 0 || avatar.height <= 0) return null
            return avatar
        }

        fun find(root: View, name: String, depth: Int = 0): View? {
            if (entryName(root) == name) return root
            if (root !is ViewGroup || depth >= 8) return null
            for (index in 0 until root.childCount.coerceAtMost(128)) find(root.getChildAt(index), name, depth + 1)?.let { return it }
            return null
        }

        fun bounds(list: ViewGroup, view: View): TreeRect {
            val rect = Rect(0, 0, view.width, view.height)
            list.offsetDescendantRectToMyCoords(view, rect)
            return TreeRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())
        }

        fun descendant(view: View, parent: View): Boolean {
            var node: View? = view
            repeat(32) {
                if (node === parent) return true
                node = node?.parent as? View ?: return false
            }
            return false
        }
    }
}
