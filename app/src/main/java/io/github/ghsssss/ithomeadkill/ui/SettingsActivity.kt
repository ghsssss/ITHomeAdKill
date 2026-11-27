package io.github.ghsssss.ithomeadkill.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.ghsssss.ithomeadkill.R
import io.github.ghsssss.ithomeadkill.settings.SettingsRepository
import java.util.concurrent.Executors
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class SettingsActivity : ComponentActivity() {
    private lateinit var repository: SettingsRepository
    private lateinit var launcherIconTasks: LauncherIconTasks
    private val launcherHandler = Handler(Looper.getMainLooper())
    private val resumeRefresh = SettingsResumeRefresh { repository.reload() }
    private var launcherIconHidden by mutableStateOf<Boolean?>(null)
    private var launcherIconError by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repository = SettingsRepository(applicationContext)
        val launcherIconController = LauncherIconController.from(applicationContext)
        launcherIconTasks = LauncherIconTasks(
            worker = launcherWorker,
            dispatch = { update -> launcherHandler.post { update() } },
            readHidden = launcherIconController::isHidden,
            writeHidden = launcherIconController::setHidden,
            publish = { result ->
                launcherIconHidden = result.hidden
                launcherIconError = when (result.failure) {
                    LauncherIconFailure.READ -> getString(R.string.settings_launcher_read_error)
                    LauncherIconFailure.CHANGE -> getString(R.string.settings_launcher_change_error)
                    null -> null
                }
            },
        )

        setContent {
            val themeController = remember { ThemeController(ColorSchemeMode.System) }
            MiuixTheme(controller = themeController) {
                SettingsScreen(
                    state = repository.state.value,
                    onToggleChange = repository::setToggle,
                    onRefreshMinutesChange = repository::setRefreshMinutes,
                    launcherIconHidden = launcherIconHidden,
                    launcherIconError = launcherIconError,
                    onLauncherIconChange = launcherIconTasks::setHidden,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        launcherIconTasks.refresh()
        resumeRefresh.onResume()
    }

    override fun onDestroy() {
        if (::launcherIconTasks.isInitialized) {
            launcherIconTasks.close()
        }
        launcherHandler.removeCallbacksAndMessages(null)
        if (::repository.isInitialized) {
            repository.close()
        }
        super.onDestroy()
    }

    private companion object {
        val launcherWorker = Executors.newSingleThreadExecutor { Thread(it, "ITHomeLauncher") }
    }
}
