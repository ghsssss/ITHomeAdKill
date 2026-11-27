package io.github.ghsssss.ithomeadkill.hook

import java.lang.reflect.Field
import java.lang.reflect.Method

/** Read-only contract for the verified 9.55 adapter; never reads comment/user text. */
internal class ThreadTreeDataSource {
    data class Snapshot(val adapter: Any, val data: List<*>, val headerCount: Int,
        val itemCount: Int, val topology: TreeTopology)

    private val fields = mutableMapOf<Pair<Class<*>, String>, Field?>()
    private val methods = mutableMapOf<Pair<Class<*>, String>, Method?>()

    @Suppress("UNCHECKED_CAST")
    fun mutableData(adapter: Any): MutableList<Any?>? {
        if (adapter.javaClass.name != "com.ruanmei.ithome.adapters.CommentListAdapterV8") return null
        val data = field(adapter, "mData")
        // The verified host uses ArrayList; do not attempt writes against an unknown List implementation.
        if (data?.javaClass?.name != "java.util.ArrayList") return null
        return data as? MutableList<Any?>
    }

    fun read(adapter: Any): Snapshot? = runCatching {
        if (adapter.javaClass.name != "com.ruanmei.ithome.adapters.CommentListAdapterV8") return null
        val data = field(adapter, "mData") as? List<*> ?: return null
        val headers = invoke(adapter, "getHeaderLayoutCount") as? Int ?: return null
        val count = invoke(adapter, "getItemCount") as? Int ?: return null
        if (data.size !in 1..4096 || headers !in 0..1 || count !in (data.size + headers)..(data.size + headers + 3)) return null
        val records = data.mapIndexed { index, entity ->
            val position = index + headers
            if (entity == null || entity.javaClass.name != "com.ruanmei.ithome.entities.NewsCommentEntity") {
                TreeRecord(position, 0L, 0L, 0L, -1)
            } else {
                TreeRecord(position, number(entity, "id") ?: return null,
                    number(entity, "parentCommentId") ?: return null,
                    number(entity, "replyCommentId") ?: return null,
                    field(entity, "itemType") as? Int ?: return null,
                    field(entity, "_isHot") as? Boolean ?: return null)
            }
        }
        Snapshot(adapter, data, headers, count, ThreadTreeModel.topology(records))
    }.getOrNull()

    fun currentRecord(adapter: Any, position: Int, headers: Int): TreeRecord? = runCatching {
        val data = field(adapter, "mData") as? List<*> ?: return null
        val entity = data.getOrNull(position - headers) ?: return null
        if (entity.javaClass.name != "com.ruanmei.ithome.entities.NewsCommentEntity") return null
        TreeRecord(position, number(entity, "id") ?: return null,
            number(entity, "parentCommentId") ?: return null,
            number(entity, "replyCommentId") ?: return null,
            field(entity, "itemType") as? Int ?: return null,
            field(entity, "_isHot") as? Boolean ?: return null)
    }.getOrNull()

    private fun number(target: Any, name: String): Long? = when (val value = field(target, name)) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }

    private fun field(target: Any, name: String): Any? = runCatching {
        val key = target.javaClass to name
        val accessor = if (fields.containsKey(key)) fields[key] else {
            var type: Class<*>? = target.javaClass
            var result: Field? = null
            while (type != null && result == null) {
                val current = type
                result = runCatching { current.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
                type = type.superclass
            }
            fields[key] = result
            result
        }
        accessor?.get(target)
    }.getOrNull()

    private fun invoke(target: Any, name: String): Any? = runCatching {
        val key = target.javaClass to name
        val method = if (methods.containsKey(key)) methods[key] else {
            target.javaClass.methods.singleOrNull { it.name == name && it.parameterCount == 0 }
                .also { methods[key] = it }
        }
        method?.invoke(target)
    }.getOrNull()
}
