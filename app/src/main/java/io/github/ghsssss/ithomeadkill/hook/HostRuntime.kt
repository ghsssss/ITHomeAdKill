package io.github.ghsssss.ithomeadkill.hook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.WebView
import android.widget.TextView
import io.github.ghsssss.ithomeadkill.settings.Toggle
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.util.WeakHashMap

internal fun entryName(view: View): String = try {
    if (view.id == View.NO_ID || view.resources.getResourcePackageName(view.id) != "com.ruanmei.ithome") ""
    else view.resources.getResourceEntryName(view.id)
} catch (_: Exception) { "" }

internal fun activityOf(context: Context): Activity? {
    var current = context
    repeat(12) {
        if (current is Activity) return current as Activity
        if (current !is ContextWrapper) return null
        val next = (current as ContextWrapper).baseContext
        if (next === current) return null
        current = next
    }
    return null
}

internal fun hasSuperclass(view: View, name: String): Boolean {
    var type: Class<*>? = view.javaClass
    while (type != null) {
        if (type.name == name) return true
        type = type.superclass
    }
    return false
}

/** UI-thread owner. All views, listeners and activities retained here are weak. */
internal class HostRuntime(
    module: XposedModule,
    private val hooks: ModernHooks,
    private val moduleLog: (String) -> Unit,
    private val hostLoader: ClassLoader,
) {
    private val main = Handler(Looper.getMainLooper())
    private val screens = WeakHashMap<Activity, Screen>()
    private val hidden = WeakHashMap<View, Hidden>()
    private val refreshListeners = WeakHashMap<View, WeakReference<Any>>()

    private val webVersions = WeakHashMap<WebView, String>()
    private val schedule = RefreshSchedule()
    private val preferences = HostPreferences(module, moduleLog, ::settingsChanged)
    private val logged = mutableSetOf<String>()
    private var mutating = false
    private var ticking = false
    private var lastTouch = 0L
    private var touching = false

    private val pagerIdle = WeakHashMap<View, Boolean>()
    private val commentOrder: ThreadTreeOrderController = ThreadTreeOrderController(hooks,
        { preferences.snapshot?.enabled(Toggle.COMMENT_TREE) == true },
        ::commentOrderChanged, ::logOnce)
    private val commentTree: ThreadTreeController = ThreadTreeController(
        { preferences.snapshot?.enabled(Toggle.COMMENT_TREE) == true },
        ::logOnce,
        commentOrder::prepare,
        commentOrder::restoreList,
    )

    private fun commentOrderChanged(adapter: Any): Unit = commentTree.adapterChanged(adapter)

    private class Hidden(view: View) {
        var height = view.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
        var minimumHeight = view.minimumHeight
        var visibility = view.visibility
        var top = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        var bottom = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
    }

    private class Screen(activity: Activity) {
        val activity = WeakReference(activity)
        val root = WeakReference(activity.window.decorView)
        var resumed = true
        var pending = false
        var layout: ViewTreeObserver.OnGlobalLayoutListener? = null
        var focus: ViewTreeObserver.OnWindowFocusChangeListener? = null
    }

    fun attach(context: Context) {
        preferences.reload(true)
        installRefreshHook(hostLoader)
        if (context.classLoader !== hostLoader) installRefreshHook(context.classLoader)
    }

    fun resumed(activity: Activity) {
        if (!isHost(activity)) return
        val screen = screens.getOrPut(activity) { Screen(activity) }
        screen.resumed = true
        val root = screen.root.get() ?: return
        if (screen.layout == null) {
            screen.layout = ViewTreeObserver.OnGlobalLayoutListener { requestSweep(screen) }
            screen.focus = ViewTreeObserver.OnWindowFocusChangeListener { focus ->
                if (focus) {
                    preferences.reload(true)
                    requestSweep(screen)
                    startTick()
                } else {
                    schedule.stop()
                    stopTick()
                }
            }
            root.viewTreeObserver.addOnGlobalLayoutListener(screen.layout)
            root.viewTreeObserver.addOnWindowFocusChangeListener(screen.focus)
        }
        installRefreshHook(activity.classLoader)
        preferences.reload(true)
        requestSweep(screen)
        startTick()
    }

    fun paused(activity: Activity) {
        screens[activity]?.resumed = false
        schedule.stop()
        touching = false
        stopTick()
    }

    fun destroyed(activity: Activity) {
        screens[activity]?.root?.get()?.let(commentOrder::restoreWithin)
        commentTree.removeFor(activity)
        val screen = screens.remove(activity) ?: return
        val observer = screen.root.get()?.viewTreeObserver
        if (observer?.isAlive == true) {
            screen.layout?.let(observer::removeOnGlobalLayoutListener)
            screen.focus?.let(observer::removeOnWindowFocusChangeListener)
        }
        schedule.stop()
        stopTick()
    }

    fun touched(action: Int) {
        lastTouch = SystemClock.uptimeMillis()
        when (action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                touching = true
                schedule.stop()
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> touching = false
        }
    }

    fun viewChanged(view: View) {
        val activity = activityOf(view.context) ?: return
        screens[activity]?.let(::requestSweep)
    }

    fun desiredVisibility(view: View, value: Int): Int {
        if (mutating) return value
        hidden[view]?.let { it.visibility = value; return View.GONE }
        return value
    }

    fun desiredMinimumHeight(view: View, value: Int): Int {
        if (mutating) return value
        hidden[view]?.let { it.minimumHeight = value; return 0 }
        return value
    }

    fun desiredLayout(view: View, params: ViewGroup.LayoutParams) {
        commentTree.desiredLayout(view, params)
        if (mutating) return
        hidden[view]?.let {
            // RecyclerView can resubmit the very LayoutParams we collapsed.
            // Do not overwrite the original height/margins with our own zeros.
            val resubmittedCollapsed = params === view.layoutParams && params.height == 0
            if (!resubmittedCollapsed) {
                it.height = params.height
                if (params is ViewGroup.MarginLayoutParams) {
                    it.top = params.topMargin; it.bottom = params.bottomMargin
                }
            }
            if (params is ViewGroup.MarginLayoutParams) {
                params.topMargin = 0; params.bottomMargin = 0
            }
            params.height = 0
        }
    }

    fun webClient(client: Any) {
        var type: Class<*>? = client.javaClass
        while (type != null) {
            val current = type
            val method = runCatching {
                current.getDeclaredMethod("onPageFinished", WebView::class.java, String::class.java)
            }.getOrNull()
            if (method != null) {
                hooks.observe(method, after = { chain ->
                    val view = chain.args[0] as? WebView ?: return@observe
                    webVersions.remove(view)
                    articleStyle(view)
                })
                return
            }
            type = type.superclass
        }
    }

    private fun isHost(activity: Activity) = activity.packageName == "com.ruanmei.ithome"
    private fun isMain(activity: Activity) = activity.javaClass.name == "com.ruanmei.ithome.ui.MainActivity"
    private fun isArticle(activity: Activity) = activity.javaClass.name == "com.ruanmei.ithome.ui.NewsInfoV8Activity"

    private fun logOnce(key: String, message: String) {
        if (logged.size < 128 && logged.add(key)) moduleLog(message)
    }

    private fun requestSweep(screen: Screen) {
        if (screen.pending) return
        screen.pending = true
        main.postDelayed({
            screen.pending = false
            if (screen.activity.get()?.isDestroyed != false) return@postDelayed
            runCatching { sweep(screen) }.onFailure {
                logOnce("sweep:${it.javaClass.name}", "view-sweep-failed:${it.javaClass.simpleName}")
            }
        }, 32)
    }

    private fun sweepAll() { screens.values.toList().forEach(::requestSweep) }

    private fun settingsChanged() {
        if (preferences.snapshot?.enabled(Toggle.COMMENT_TREE) != true) {
            commentOrder.clear()
            commentTree.clearAll()
        }
        sweepAll()
        startTick()
    }

    private fun sweep(screen: Screen) {
        val activity = screen.activity.get() ?: return
        val root = screen.root.get() ?: return
        runCatching { HostSettingsEntry.attach(activity, root) }.onFailure {
            logOnce("settings-entry:${it.javaClass.name}", "settings-entry-failed:${it.javaClass.simpleName}")
        }
        val settings = preferences.snapshot
        fun enabled(toggle: Toggle) = settings?.enabled(toggle) == true
        var visited = 0
        fun walk(view: View) {
            if (++visited > 4_000) return
            if (view is ViewGroup && ThreadTreeAdmission.isSupported(activity.javaClass.name, entryName(view))) {
                commentTree.update(view, activity)
            }
            val id = entryName(view)
            val nativeToggle = when {
                id == "fl_slide" && isMain(activity) && hasChild(view, "banner") -> Toggle.HOME_CAROUSEL
                id == "ll_news_grade_view" && isArticle(activity) -> Toggle.ARTICLE_RATING
                id == "rl_bottom_ad_view" && isArticle(activity) -> Toggle.AD_CARDS
                id == "ll_related_view" && isArticle(activity) -> Toggle.ARTICLE_RECOMMENDATIONS
                id == "includeFloatAd" && isMain(activity) && hasChild(view, "btn_float") && hasChild(view, "btn_float_close") -> Toggle.AD_CARDS
                else -> null
            }
            if (nativeToggle != null) {
                setHidden(view, enabled(nativeToggle))
                logOnce("selector:$id", "native-selector=$id present=true")
            }
            if (view is WebView) {
                articleStyle(view, activity)
                return
            }
            if (hasSuperclass(view, "androidx.viewpager.widget.ViewPager") && id == "viewPager" && isMain(activity)) {
                installPagerHooks(view.javaClass.classLoader)
            }
            if (view is ViewGroup) {
                if (hasSuperclass(view, "androidx.recyclerview.widget.RecyclerView")) {
                    installRecyclerHook(view.javaClass.classLoader)
                    for (index in 0 until view.childCount) {
                        val card = view.getChildAt(index)
                        val markers = mutableListOf<Pair<String, String>>()
                        var webPromo = false
                        var adSignature = false
                        var cardVisited = 0
                        fun inspect(part: View) {
                            if (++cardVisited > 200) return
                            val key = entryName(part)
                            if (part is TextView && key in setOf("tv_small_tags", "tv_title")) markers += key to part.text.toString()
                            if (key == "ad_title_creative_btn_layout" || key == "ad_contentPanel") adSignature = true
                            if (key == "ll_web" && isMain(activity)) webPromo = true
                            if (part is ViewGroup && part !is WebView) for (i in 0 until part.childCount) inspect(part.getChildAt(i))
                        }
                        inspect(card)
                        val hide = CardRules.shouldHide(
                            settings = settings,
                            hasAdSignature = adSignature,
                            hasWebContainer = webPromo,
                            markers = markers,
                        ) || (entryName(card) == "fl_slide" && isMain(activity) && enabled(Toggle.HOME_CAROUSEL))
                        setHidden(card, hide)
                    }
                }
                if (id in setOf("srl_refresh_tool", "swl_news_list")) {
                    installRefreshHook(view.javaClass.classLoader)
                    discoverRefreshListener(view)
                }
                for (index in 0 until view.childCount) walk(view.getChildAt(index))
            }
        }
        walk(root)
        startTick()
    }

    private fun hasChild(view: View, name: String): Boolean {
        if (view !is ViewGroup) return false
        for (index in 0 until view.childCount) {
            val child = view.getChildAt(index)
            if (entryName(child) == name || hasChild(child, name)) return true
        }
        return false
    }

    private fun setHidden(view: View, hide: Boolean) {
        val old = hidden[view]
        if (!hide && old == null) return
        mutating = true
        try {
            if (hide) {
                if (old == null) hidden[view] = Hidden(view)
                val params = view.layoutParams
                if (params != null && (params.height != 0 || (params is ViewGroup.MarginLayoutParams && (params.topMargin != 0 || params.bottomMargin != 0)))) {
                    params.height = 0
                    if (params is ViewGroup.MarginLayoutParams) { params.topMargin = 0; params.bottomMargin = 0 }
                    view.layoutParams = params
                }
                if (view.minimumHeight != 0) view.minimumHeight = 0
                if (view.visibility != View.GONE) view.visibility = View.GONE
            } else if (old != null) {
                hidden.remove(view)
                view.layoutParams?.let { params ->
                    params.height = old.height
                    if (params is ViewGroup.MarginLayoutParams) { params.topMargin = old.top; params.bottomMargin = old.bottom }
                    view.layoutParams = params
                }
                view.minimumHeight = old.minimumHeight
                view.visibility = old.visibility
            }
        } finally { mutating = false }
    }

    private fun articleStyle(view: WebView, owner: Activity? = null) {
        // IT之家 can reuse a WebView created with an application context.
        // The containing article window, not the WebView's construction context,
        // owns this intervention.
        val activity = owner ?: screens.values.firstOrNull { it.root.get() === view.rootView }?.activity?.get()
            ?: activityOf(view.context) ?: return
        if (!isArticle(activity)) return
        val settings = preferences.snapshot
        val key = "${settings?.writerId}:${settings?.revision}:${view.url}"
        if (webVersions[view] == key) return
        webVersions[view] = key
        view.evaluateJavascript(ArticleStyle.script(settings?.enabled(Toggle.AI_SUMMARY) == true,
            settings?.enabled(Toggle.ARTICLE_RATING) == true,
            settings?.enabled(Toggle.ARTICLE_REWARDS) == true,
            settings?.enabled(Toggle.ARTICLE_COMMISSION) == true)) { result ->
            // Only selector presence/visibility is returned, never article text or URLs.
            logOnce("article-dom:$key:$result", "article-dom=$result")
            if (!result.contains("article") || !result.contains("true")) {
                webVersions.remove(view)
            }
        }
    }

    private fun installRecyclerHook(loader: ClassLoader?) {
        if (loader == null) return
        val method = runCatching {
            val type = Class.forName("androidx.recyclerview.widget.RecyclerView\$Adapter", false, loader)
            val holder = Class.forName("androidx.recyclerview.widget.RecyclerView\$ViewHolder", false, loader)
            type.getDeclaredMethod("bindViewHolder", holder, Int::class.javaPrimitiveType!!)
        }.getOrNull() ?: return
        val handle = hooks.observe(method, before = { chain ->
            val holder = chain.args[0] ?: return@observe
            val view = runCatching { holder.javaClass.getField("itemView").get(holder) as? View }.getOrNull() ?: return@observe
            commentTree.releaseWithin(view)
            fun release(part: View) {
                setHidden(part, false)
                if (part is ViewGroup && part !is WebView) for (i in 0 until part.childCount) release(part.getChildAt(i))
            }
            release(view)
        }, after = { chain ->
            val holder = chain.args[0] ?: return@observe
            val view = runCatching { holder.javaClass.getField("itemView").get(holder) as? View }.getOrNull() ?: return@observe
            viewChanged(view)
        })
        if (handle != null) logOnce("recycler-bind", "recycler-bind-hook=ready")
        for (notify in method.declaringClass.declaredMethods.filter {
            it.name in setOf("notifyDataSetChanged", "notifyItemRangeChanged", "notifyItemRangeInserted",
                "notifyItemRangeRemoved", "notifyItemMoved")
        }) {
            hooks.consumeVoid(notify,
                consume = { chain -> commentOrder.notification(chain.thisObject) },
                after = { chain -> commentTree.adapterChanged(chain.thisObject) })
        }
    }

    private fun installPagerHooks(loader: ClassLoader?) {
        if (loader == null) return
        val type = runCatching { Class.forName("androidx.viewpager.widget.ViewPager", false, loader) }.getOrNull() ?: return
        for (method in type.declaredMethods.filter { it.name in setOf("setScrollState", "dispatchOnPageSelected") && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) }) {
            val handle = hooks.observe(method, before = { chain ->
                val pager = chain.thisObject as? View ?: return@observe
                if (entryName(pager) != "viewPager") return@observe
                if (method.name == "setScrollState") pagerIdle[pager] = chain.args[0] == 0
                schedule.stop()
            })
            if (handle != null) logOnce("pager:${method.name}", "pager-event-hook=${method.name}")
        }
    }

    private fun trackRefreshListener(view: View, listener: Any?) {
        if (listener == null) { refreshListeners.remove(view); return }
        refreshListeners[view] = WeakReference(listener)
        val method = runCatching { listener.javaClass.getMethod("onRefresh") }.getOrNull() ?: return
        hooks.observe(method, before = { chain ->
            if (refreshListeners.values.any { it.get() === chain.thisObject }) schedule.stop()
        })
    }

    private fun installRefreshHook(loader: ClassLoader?) {
        if (loader == null) return
        val type = runCatching { Class.forName("com.iruanmi.multitypeadapter.MySwipeRefreshLayout", false, loader) }.getOrNull() ?: return
        val candidate = type.methods.firstOrNull { it.name == "setOnRefreshListener" && it.parameterCount == 1
            && it.parameterTypes[0].methods.any { item -> item.name == "onRefresh" && item.parameterCount == 0 } } ?: return
        val method = type.getMethod("setOnRefreshListener", candidate.parameterTypes[0])
        val handle = hooks.observe(method, after = { chain ->
            val view = chain.thisObject as? View ?: return@observe
            trackRefreshListener(view, chain.args[0])
        })
        if (handle != null) logOnce("refresh-api", "refresh-api=${method.declaringClass.name}.${method.name}")
    }

    private fun discoverRefreshListener(view: View) {
        if (refreshListeners[view]?.get() != null) return
        var type: Class<*>? = view.javaClass
        while (type != null) {
            val field = type.declaredFields.firstOrNull { item -> item.type.methods.any { it.name == "onRefresh" && it.parameterCount == 0 }
                && item.type.name.endsWith("OnRefreshListener") }
            if (field != null) {
                runCatching {
                    field.isAccessible = true
                    trackRefreshListener(view, field.get(view))
                }
                return
            }
            type = type.superclass
        }
    }

    private fun focusedScreen(): Screen? = screens.values.firstOrNull {
        val activity = it.activity.get()
        it.resumed && activity != null && !activity.isFinishing && !activity.isDestroyed && activity.window.decorView.hasWindowFocus()
    }

    private fun currentRefresh(screen: Screen): View? {
        val activity = screen.activity.get() ?: return null
        if (!isMain(activity) || touching) return null
        if (!(activity.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) return null
        val matches = mutableListOf<View>()
        for (view in refreshListeners.keys.toList()) {
            if (activityOf(view.context) !== activity || !view.isAttachedToWindow || !view.isShown) continue
            if (entryName(view) !in setOf("srl_refresh_tool", "swl_news_list")) continue
            var ancestor = view.parent as? View
            var pager: View? = null
            var inMainContent = false
            while (ancestor != null) {
                if (hasSuperclass(ancestor, "androidx.viewpager.widget.ViewPager") && entryName(ancestor) == "viewPager") pager = ancestor
                if (entryName(ancestor) == "fl_main_content") inMainContent = true
                ancestor = ancestor.parent as? View
            }
            val page = pager ?: continue
            if (!inMainContent || pagerIdle[page] == false) continue
            val busy = runCatching { view.javaClass.getMethod("isRefreshing").invoke(view) == true }.getOrDefault(true)
            if (busy) continue
            val area = Rect(); val pageArea = Rect()
            if (!view.getGlobalVisibleRect(area) || !page.getGlobalVisibleRect(pageArea)) continue
            // Off-screen preloaded pages can be isShown. Require one full-width visible page.
            if (area.width() < pageArea.width() - 4 || area.height() < pageArea.height() * 0.85) continue
            val editing = findView(screen.root.get(), "fl_column_setting")?.isShown == true || findView(screen.root.get(), "sv_filter_panel")?.isShown == true
            if (!editing) matches += view
        }
        return matches.singleOrNull()
    }

    private fun findView(root: View?, id: String): View? {
        if (root == null) return null
        if (entryName(root) == id) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) findView(root.getChildAt(i), id)?.let { return it }
        return null
    }

    private fun startTick() {
        if (ticking || focusedScreen() == null) return
        ticking = true
        main.postDelayed(tick, 1_000)
    }

    private fun stopTick() {
        ticking = false
        main.removeCallbacks(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            ticking = false
            val screen = focusedScreen() ?: run { schedule.stop(); return }
            preferences.reload()
            val settings = preferences.snapshot
            val target = currentRefresh(screen)
            val minutes = if (settings?.enabled(Toggle.MASTER) == true) settings.autoRefreshMinutes else 0
            val now = SystemClock.uptimeMillis()
            schedule.update(minutes, target != null, target?.let(System::identityHashCode), now)
            if (target != null && schedule.due(now) && now - lastTouch > 1_500) {
                try {
                    val busy = target.javaClass.getMethod("isRefreshing").invoke(target) == true
                    val list = findView(target, "rcv_list") ?: findView(target, "list")
                    val scrollState = list?.javaClass?.methods?.firstOrNull { it.name == "getScrollState" && it.parameterCount == 0 }?.invoke(list) as? Int
                    val scrolling = scrollState != null && scrollState != 0
                    if (!busy && !scrolling) {
                        val listener = refreshListeners[target]?.get()
                        val callback = listener?.javaClass?.methods?.firstOrNull { it.name == "onRefresh" && it.parameterCount == 0 }
                        if (listener != null && callback != null) {
                            callback.isAccessible = true
                            target.javaClass.getMethod("setRefreshing", Boolean::class.javaPrimitiveType).invoke(target, true)
                            try { callback.invoke(listener) } catch (error: Exception) {
                                target.javaClass.getMethod("setRefreshing", Boolean::class.javaPrimitiveType).invoke(target, false)
                                refreshListeners.remove(target)
                                throw error
                            }
                            schedule.consumed(now)
                            moduleLog("auto-refresh:minutes=$minutes target=${entryName(target)} column=${System.identityHashCode(target)}")
                        }
                    }
                } catch (error: Exception) {
                    schedule.stop()
                    refreshListeners.remove(target)
                    logOnce("refresh-failure", "auto-refresh-failed:${error.javaClass.simpleName}")
                }
            }
            startTick()
        }
    }
}
