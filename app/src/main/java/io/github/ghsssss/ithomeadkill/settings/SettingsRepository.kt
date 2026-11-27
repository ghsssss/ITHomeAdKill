package io.github.ghsssss.ithomeadkill.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import io.github.libxposed.service.XposedService
import java.util.IdentityHashMap
import java.util.concurrent.Executors

/** UI state confirms framework persistence, never claims host hooks are active. */
class SettingsRepository(context: Context) {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lifecycleLock = Any()
    private var closed = false
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "ITHomeSettings") }
    private val mutableState = mutableStateOf(SettingsState())
    val state: State<SettingsState> = mutableState
    private val statePublisher = SettingsStatePublisher(
        dispatch = ::dispatchState,
        current = { mutableState.value },
        publish = { mutableState.value = it },
    )
    private val connectionChanged: () -> Unit = { reload() }

    init {
        FrameworkConnection.subscribe(connectionChanged)
        FrameworkConnection.start()
        reload()
    }

    fun setToggle(toggle: Toggle, value: Boolean) = enqueue { it.setToggle(toggle, value) }
    fun setRefreshMinutes(minutes: Int) = enqueue { it.setRefreshMinutes(minutes) }
    fun reload() = enqueue { it.reload() }

    fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            FrameworkConnection.unsubscribe(connectionChanged)
            mainHandler.removeCallbacksAndMessages(null)
            // Already accepted writes finish; destroyed pages cannot publish.
            executor.shutdown()
        }
    }

    private fun store(service: XposedService): SettingsStore {
        val backend = synchronized(backends) {
            backends[service] ?: Backend(service.getRemotePreferences(SettingsContract.PREFS_NAME)).also {
                backends[service] = it
            }
        }
        fun requireSession() {
            check(FrameworkConnection.selected() === service) { "框架连接已变化，请重试" }
        }
        return SettingsStore(
            state = backend.state,
            read = {
                requireSession()
                val remote = backend.preferences.all
                // A nonempty remote profile is authoritative, including invalid
                // data which the strict codec must reject rather than overwrite.
                if (remote.isNotEmpty()) remote
                else LegacySettingsExport.read(applicationContext) ?: emptyMap<String, Any>()
            },
            commit = { values ->
                requireSession()
                val editor = backend.preferences.edit().clear()
                values.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is String -> editor.putString(key, value)
                        else -> error("Unsupported settings value for $key")
                    }
                }
                // service 102 updates its memory snapshot before the Binder
                // commit. A matching read alone is not persistence evidence;
                // SettingsStore retains shared pending confirmation on false.
                editor.commit().also { requireSession() }
            },
        )
    }

    private fun enqueue(operation: (SettingsStore) -> SettingsState) {
        synchronized(lifecycleLock) {
            if (closed) return
            executor.execute {
                val service = FrameworkConnection.selected()
                if (service == null) {
                    publish(SettingsState(null,
                        "未连接到唯一可用的 API 102 框架，请启用模块后重新打开设置。", busy = false, error = true))
                    return@execute
                }
                statePublisher.busy()
                val next = try {
                    operation(store(service))
                } catch (error: Exception) {
                    SettingsState(null, "框架设置暂不可用：${error.javaClass.simpleName}", busy = false, error = true)
                }
                if (FrameworkConnection.selected() === service) publish(next)
                else publish(SettingsState(null, "框架连接已变化，请重新载入。", busy = false, error = true))
            }
        }
    }

    private fun publish(next: SettingsState) = statePublisher.publish(next)

    private fun dispatchState(update: () -> Unit) {
        synchronized(lifecycleLock) {
            if (closed) return
            mainHandler.post {
                synchronized(lifecycleLock) { if (!closed) update() }
            }
        }
    }

    private class Backend(val preferences: SharedPreferences) {
        val state = SettingsStoreState()
    }

    private companion object {
        // Confirmation survives page recreation and is never shared across
        // different framework Binder identities.
        val backends = IdentityHashMap<XposedService, Backend>()
    }
}
