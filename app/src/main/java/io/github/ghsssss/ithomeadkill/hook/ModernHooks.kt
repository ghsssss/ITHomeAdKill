package io.github.ghsssss.ithomeadkill.hook

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Method

// BEGIN PURE HOOK BOUNDARIES
/** Observations must never replace the downstream result or exception. */
internal object ModernHookFlow {
    fun prepareArguments(
        original: List<Any?>,
        transform: (Array<Any?>) -> Unit,
        onFailure: (Throwable) -> Unit = {},
    ): Array<Any?>? {
        var result: Array<Any?>? = null
        safely({
            val copy = original.toTypedArray()
            transform(copy)
            result = copy
        }, onFailure)
        return result
    }

    fun <T> observe(
        proceed: () -> T,
        before: () -> Unit = {},
        after: () -> Unit = {},
        onFailure: (Throwable) -> Unit = {},
    ): T {
        safely(before, onFailure)
        try {
            return proceed()
        } finally {
            safely(after, onFailure)
        }
    }

    fun safely(action: () -> Unit, onFailure: (Throwable) -> Unit = {}) {
        try {
            action()
        } catch (error: Throwable) {
            // Logging belongs to the module too; it cannot replace a host outcome.
            try { onFailure(error) } catch (_: Throwable) { }
        }
    }
}
internal class ModernHookGate {
    private var initialized = false

    @Synchronized fun enter(packageName: String, firstPackage: Boolean, processName: String?, systemServer: Boolean): Boolean {
        if (initialized || systemServer || !firstPackage || packageName != HOST_PACKAGE || processName != HOST_PACKAGE) return false
        initialized = true
        return true
    }

    private companion object { const val HOST_PACKAGE = "com.ruanmei.ithome" }
}
// END PURE HOOK BOUNDARIES

/** Small API-102 boundary, not a legacy callback/MethodHookParam emulation. */
internal class ModernHooks(
    private val module: XposedModule,
    private val moduleLog: (String) -> Unit,
) {
    private val handles = linkedMapOf<Executable, XposedInterface.HookHandle>()

    val size: Int get() = synchronized(this) { handles.size }

    fun observe(
        owner: Class<*>,
        name: String,
        vararg parameterTypes: Class<*>,
        before: (XposedInterface.Chain) -> Unit = {},
        after: (XposedInterface.Chain) -> Unit = {},
        arguments: ((XposedInterface.Chain, Array<Any?>) -> Unit)? = null,
    ): XposedInterface.HookHandle? {
        val method = try {
            owner.getDeclaredMethod(name, *parameterTypes)
        } catch (error: Throwable) {
            report("hook-lookup-failed:${owner.name}.$name:${error.javaClass.simpleName}")
            return null
        }
        return observe(method, before, after, arguments)
    }

    @Synchronized fun observe(
        method: Method,
        before: (XposedInterface.Chain) -> Unit = {},
        after: (XposedInterface.Chain) -> Unit = {},
        arguments: ((XposedInterface.Chain, Array<Any?>) -> Unit)? = null,
    ): XposedInterface.HookHandle? {
        handles[method]?.let { return it }
        val name = "${method.declaringClass.name}.${method.name}"
        return try {
            val handle = module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    var replacement: Array<Any?>? = null
                    val failure: (Throwable) -> Unit = { error ->
                        report("hook-observer-failed:$name:${error.javaClass.simpleName}")
                    }
                    ModernHookFlow.observe(
                        before = {
                            before(chain)
                            if (arguments != null) {
                                replacement = ModernHookFlow.prepareArguments(chain.args, { copy ->
                                    arguments(chain, copy)
                                }, failure)
                            }
                        },
                        proceed = {
                            val prepared = replacement
                            // The immutable Chain.args list is never written to. No origin replay.
                            if (prepared == null) chain.proceed() else chain.proceed(prepared)
                        },
                        after = { after(chain) },
                        onFailure = failure,
                    )
                }
            // Failed installs remain retryable. Every successful hook keeps its unhook handle.
            handles[method] = handle
            handle
        } catch (error: Throwable) {
            report("hook-install-failed:$name:${error.javaClass.simpleName}")
            null
        }
    }

    /** A narrowly scoped void-event gate, used only to coalesce adapter notifications inside a native transaction. */
    @Synchronized fun consumeVoid(
        method: Method,
        consume: (XposedInterface.Chain) -> Boolean,
        after: (XposedInterface.Chain) -> Unit = {},
    ): XposedInterface.HookHandle? {
        if (method.returnType != Void.TYPE) return null
        handles[method]?.let { return it }
        return try {
            val handle = module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val consumed = runCatching { consume(chain) }.getOrElse {
                        report("hook-notify-gate-failed:${method.name}:${it.javaClass.simpleName}")
                        false
                    }
                    if (consumed) null else try { chain.proceed() } finally {
                        ModernHookFlow.safely({ after(chain) })
                    }
                }
            handles[method] = handle
            handle
        } catch (error: Throwable) {
            report("hook-install-failed:${method.name}:${error.javaClass.simpleName}")
            null
        }
    }

    @Synchronized fun unhook(executable: Executable): Boolean {
        val handle = handles[executable] ?: return false
        return try {
            handle.unhook()
            handles.remove(executable)
            true
        } catch (error: Throwable) {
            report("hook-unhook-failed:${executable.name}:${error.javaClass.simpleName}")
            false
        }
    }

    @Synchronized fun unhookAll() {
        handles.keys.toList().forEach(::unhook)
    }

    private fun report(message: String) = ModernHookFlow.safely({ moduleLog(message) })
}
