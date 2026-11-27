package io.github.ghsssss.ithomeadkill.hook

import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Only the verified ShadowLayout fill is leased. Its existing ripple and listeners remain host-owned. */
internal class ThreadTreeFillController {
    private data class Access(val color: Field, val setColor: Method)
    private data class Lease(val original: Int, val list: WeakReference<ViewGroup>)
    private val accessors = mutableMapOf<Class<*>, Access?>()
    private val leases = WeakHashMap<View, Lease>()

    fun apply(view: View, list: ViewGroup) {
        val access = access(view) ?: return
        runCatching {
            val current = access.color.getInt(view)
            if (current != 0) {
                leases[view] = Lease(current, WeakReference(list))
                access.setColor.invoke(view, 0)
            }
        }
    }

    fun release(view: View) {
        val lease = leases.remove(view) ?: return
        val access = access(view) ?: return
        runCatching { if (access.color.getInt(view) == 0) access.setColor.invoke(view, lease.original) }
    }

    fun releaseList(list: ViewGroup) {
        leases.entries.filter { it.value.list.get() === list }.map { it.key }.forEach(::release)
    }

    fun releaseWithin(root: View) {
        leases.keys.toList().filter { view ->
            var node: View? = view
            var found = false
            repeat(32) { if (node === root) found = true; node = node?.parent as? View }
            found
        }.forEach(::release)
    }

    fun clear() { leases.keys.toList().forEach(::release) }

    private fun access(view: View): Access? {
        if (view.javaClass.name != "com.lihang.ShadowLayout") return null
        return accessors.getOrPut(view.javaClass) {
            runCatching {
                Access(view.javaClass.getDeclaredField("mBackGroundColor").apply { isAccessible = true },
                    view.javaClass.getMethod("setLayoutBackground", Int::class.javaPrimitiveType))
            }.getOrNull()
        }
    }
}
