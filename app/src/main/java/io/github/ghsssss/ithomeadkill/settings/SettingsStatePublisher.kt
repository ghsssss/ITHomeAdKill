package io.github.ghsssss.ithomeadkill.settings

internal class SettingsStatePublisher(
    private val dispatch: (() -> Unit) -> Unit,
    private val current: () -> SettingsState,
    private val publish: (SettingsState) -> Unit,
) {
    fun publish(next: SettingsState) = dispatch { publish.invoke(next) }

    fun busy() = dispatch {
        // Read and reduce on the UI queue, after any earlier confirmed result.
        val next = current().copy(message = "正在读取或保存框架设置", busy = true, error = false)
        publish.invoke(next)
    }
}
