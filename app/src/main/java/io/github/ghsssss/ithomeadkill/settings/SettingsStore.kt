package io.github.ghsssss.ithomeadkill.settings

import java.util.UUID

data class SettingsState(
    val settings: ModuleSettings? = null,
    val message: String = "正在读取设置",
    val busy: Boolean = true,
    val error: Boolean = false,
)

/** 同一偏好后端的确认状态由进程内所有 Repository 共用，不随设置页销毁。 */
internal class SettingsStoreState {
    @Volatile var settings: ModuleSettings? = null
    var pendingWrite: ModuleSettings? = null
    var pendingBase: ModuleSettings? = null
    var firstRead = true
}

/** 各实例由自身 IO 队列调用；共享状态与完整存储事务都受同一个进程锁保护。 */
internal class SettingsStore(
    private val read: () -> Map<String, *>,
    private val commit: (Map<String, Any>) -> Boolean,
    private val state: SettingsStoreState,
    private val newWriterId: () -> String = { UUID.randomUUID().toString() },
) {
    var settings: ModuleSettings?
        get() = state.settings
        private set(value) { state.settings = value }
    // commit=false 也可能改变共享内存，失败确认不能因另建 Repository 而丢失。
    private var pendingWrite: ModuleSettings?
        get() = state.pendingWrite
        set(value) { state.pendingWrite = value }
    private var pendingBase: ModuleSettings?
        get() = state.pendingBase
        set(value) { state.pendingBase = value }
    private var firstRead: Boolean
        get() = state.firstRead
        set(value) { state.firstRead = value }

    fun reload(): SettingsState = synchronized(processWriteLock) {
        try {
            val values = read()
            val mayInitialize = firstRead
            firstRead = false
            val current = ModuleSettings.fromPreferences(values)
            val needsMigration = needsMigration(values)
            val pending = pendingWrite
            val retryEmptyInitialization = values.isEmpty() && pending != null &&
                pendingBase == null && pending.revision == 1L
            if (pending != null && (
                (current != null && (current == pending || current == pendingBase)) ||
                    retryEmptyInitialization
                )) {
                // 只重试仍属本次失败写入的状态；其它实例的新提交绝不能被旧 pending 覆盖。
                if (needsMigration) settings = null
                persist(pending, pendingBase)
            } else {
                pendingWrite = null
                pendingBase = null
                if (values.isEmpty() && mayInitialize) {
                    persist(ModuleSettings.defaults(newWriterId()))
                } else if (current != null && needsMigration) {
                    // 兼容解码不等于成功迁移；只有完整提交并回读后才向 UI 发布可用状态。
                    settings = null
                    if (current.revision == Long.MAX_VALUE) {
                        result("保存失败：设置版本已达到上限，无法迁移")
                    } else {
                        persist(current.copy(revision = current.revision + 1L), current)
                    }
                } else {
                    settings = current
                    if (current == null) {
                        result("设置数据无效或不完整，未覆盖原数据；请检查后重试")
                    } else {
                        result("已读取框架设置；IT之家生效状态未核验", error = false)
                    }
                }
            }
        } catch (_: Exception) {
            settings = null
            result("读取设置失败，请重试")
        }
    }

    fun setToggle(toggle: Toggle, value: Boolean): SettingsState = update {
        it.copy(toggles = it.toggles + (toggle to value))
    }

    fun setRefreshMinutes(minutes: Int): SettingsState {
        if (minutes !in SettingsContract.REFRESH_MINUTES) {
            return result("自动刷新间隔无效，仅支持关闭、1、5、10分钟")
        }
        return update { it.copy(autoRefreshMinutes = minutes) }
    }

    private fun update(change: (ModuleSettings) -> ModuleSettings): SettingsState =
        synchronized(processWriteLock) {
            val values = try {
                read()
            } catch (_: Exception) {
                settings = null
                return@synchronized result("读取设置失败，请重试")
            }
            firstRead = false
            val stored = ModuleSettings.fromPreferences(values)
            if (stored == null) {
                settings = null
                return@synchronized result("设置数据无效或不完整，无法保存；请检查后重试")
            }
            // 同实例失败写入可能仅改变内存；其它实例的不同成功快照必须作为新基线。
            val current = if (pendingWrite != null && stored == pendingWrite) settings else stored
            if (current == null) {
                return@synchronized result("设置尚未成功读取，无法保存；请重试")
            }
            val needsMigration = needsMigration(values)
            settings = if (needsMigration) null else current
            val changed = change(current)
            if (changed == current && !needsMigration) {
                return@synchronized if (pendingWrite != null) persist(current) else result("本机设置未改变", error = false)
            }
            if (current.revision == Long.MAX_VALUE) {
                return@synchronized result("保存失败：设置版本已达到上限")
            }
            persist(changed.copy(revision = current.revision + 1L), current)
        }

    private fun needsMigration(values: Map<String, *>): Boolean {
        val schema = values[SettingsContract.SCHEMA_KEY] as? Int ?: return false
        return schema in 1..4
    }

    // 调用者必须持有 processWriteLock，commit 与回读属于同一事务。
    private fun persist(next: ModuleSettings, base: ModuleSettings? = settings): SettingsState {
        pendingBase = base
        pendingWrite = next
        return try {
            val expected = next.toPreferences()
            if (!commit(expected)) {
                result("保存设置失败，修改未确认写入；请重试")
            } else if (read() != expected) {
                // 模型相等不足以证明迁移完成：schema 与废弃 key 的清理也必须回读一致。
                result("保存设置校验失败，修改未确认写入；请重试")
            } else {
                settings = next
                pendingWrite = null
                pendingBase = null
                result("设置已保存到框架；IT之家生效状态未核验", error = false)
            }
        } catch (_: Exception) {
            result("保存设置失败，修改未确认写入；请重试")
        }
    }

    private fun result(message: String, error: Boolean = true): SettingsState = SettingsState(
        settings = settings,
        message = message,
        busy = false,
        error = error,
    )

    private companion object {
        val processWriteLock = Any()
    }
}
