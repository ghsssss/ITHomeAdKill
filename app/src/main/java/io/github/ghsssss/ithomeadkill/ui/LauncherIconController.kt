package io.github.ghsssss.ithomeadkill.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** The launcher alias is the only persisted owner of desktop icon visibility. */
internal class LauncherIconController(
    private val readState: () -> Int,
    private val writeState: (Int) -> Unit,
) {
    fun isHidden(): Boolean = when (readState()) {
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> false
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> true
        else -> error("Unknown launcher component state")
    }

    fun setHidden(hidden: Boolean) {
        if (isHidden() == hidden) return
        writeState(if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        check(isHidden() == hidden) { "Launcher component change was not confirmed" }
    }

    companion object {
        fun from(context: Context): LauncherIconController {
            val manager = context.packageManager
            val launcher = ComponentName(context.packageName, "${context.packageName}.Launcher")
            return LauncherIconController(
                readState = { manager.getComponentEnabledSetting(launcher) },
                writeState = { state ->
                    manager.setComponentEnabledSetting(launcher, state, PackageManager.DONT_KILL_APP)
                },
            )
        }
    }
}
