package io.github.ghsssss.ithomeadkill.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ghsssss.ithomeadkill.R
import io.github.ghsssss.ithomeadkill.settings.ModuleSettings
import io.github.ghsssss.ithomeadkill.settings.SettingsContract
import io.github.ghsssss.ithomeadkill.settings.SettingsState
import io.github.ghsssss.ithomeadkill.settings.Toggle
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun SettingsScreen(
    state: SettingsState,
    onToggleChange: (Toggle, Boolean) -> Unit,
    onRefreshMinutesChange: (Int) -> Unit,
    launcherIconHidden: Boolean?,
    launcherIconError: String?,
    onLauncherIconChange: (Boolean) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val listState = rememberLazyListState()
    val settings = state.settings

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.settings_title),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(contentPadding)
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = contentPadding,
        ) {
            if (state.error || (settings == null && state.busy)) {
                item(key = "repository-status") {
                    StatusMessage(message = state.message)
                }
            }

            if (settings != null) {
                // Busy reports queued work; loaded controls keep their persisted raw values.
                val childControlsEnabled = settings.raw(Toggle.MASTER)

                item(key = "master") {
                    SettingsSection(titleResource = R.string.settings_group_master) {
                        SettingSwitch(
                            settings = settings,
                            toggle = Toggle.MASTER,
                            titleResource = R.string.settings_master_title,
                            enabled = true,
                            onToggleChange = onToggleChange,
                        )
                    }
                }

                item(key = "home") {
                    SettingsSection(titleResource = R.string.settings_group_home) {
                        SettingSwitch(
                            settings = settings,
                            toggle = Toggle.HOME_CAROUSEL,
                            titleResource = R.string.settings_home_carousel_title,
                            enabled = childControlsEnabled,
                            onToggleChange = onToggleChange,
                        )
                        RefreshPreference(
                            settings = settings,
                            enabled = childControlsEnabled,
                            onRefreshMinutesChange = onRefreshMinutesChange,
                        )
                    }
                }

                item(key = "article") {
                    SettingsSection(titleResource = R.string.settings_group_article) {
                        SettingSwitch(
                            settings = settings,
                            toggle = Toggle.AI_SUMMARY,
                            titleResource = R.string.settings_ai_summary_title,
                            enabled = childControlsEnabled,
                            onToggleChange = onToggleChange,
                        )
                        SettingSwitch(
                            settings = settings,
                            toggle = Toggle.ARTICLE_RATING,
                            titleResource = R.string.settings_article_rating_title,
                            enabled = childControlsEnabled,
                            onToggleChange = onToggleChange,
                        )
                        SettingSwitch(
                            settings = settings,
                            toggle = Toggle.ARTICLE_REWARDS,
                            titleResource = R.string.settings_article_rewards_title,
                            enabled = childControlsEnabled,
                            onToggleChange = onToggleChange,
                        )
                        SettingSwitch(
                            settings = settings,
                            toggle = Toggle.ARTICLE_COMMISSION,
                            titleResource = R.string.settings_article_commission_title,
                            enabled = childControlsEnabled,
                            onToggleChange = onToggleChange,
                        )
                        SettingSwitch(
                            settings = settings,
                            toggle = Toggle.COMMENT_TREE,
                            titleResource = R.string.settings_comment_tree_title,
                            enabled = childControlsEnabled,
                            onToggleChange = onToggleChange,
                        )
                    }
                }

                item(key = "purification") {
                    SettingsSection(titleResource = R.string.settings_group_purification) {
                        SettingSwitch(
                            settings = settings,
                            toggle = Toggle.AD_CARDS,
                            titleResource = R.string.settings_ad_cards_title,
                            enabled = childControlsEnabled,
                            onToggleChange = onToggleChange,
                        )
                        SettingSwitch(
                            settings = settings,
                            toggle = Toggle.PINNED_CARDS,
                            titleResource = R.string.settings_pinned_cards_title,
                            enabled = childControlsEnabled,
                            onToggleChange = onToggleChange,
                        )
                        SettingSwitch(
                            settings = settings,
                            toggle = Toggle.WEB_PROMOS,
                            titleResource = R.string.settings_web_promos_title,
                            enabled = childControlsEnabled,
                            onToggleChange = onToggleChange,
                        )
                    }
                }
            }

            item(key = "application") {
                SettingsSection(titleResource = R.string.settings_group_application) {
                    SwitchPreference(
                        title = stringResource(R.string.settings_hide_launcher_title),
                        checked = launcherIconHidden == true,
                        enabled = launcherIconHidden != null,
                        onCheckedChange = onLauncherIconChange,
                    )
                }
            }
            if (launcherIconError != null) {
                item(key = "launcher-error") {
                    StatusMessage(message = launcherIconError)
                }
            }
            item(key = "bottom-space") {
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SettingsSection(
    titleResource: Int,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SmallTitle(text = stringResource(titleResource))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            content = content,
        )
    }
}

@Composable
private fun SettingSwitch(
    settings: ModuleSettings,
    toggle: Toggle,
    titleResource: Int,
    enabled: Boolean,
    onToggleChange: (Toggle, Boolean) -> Unit,
) {
    SwitchPreference(
        checked = settings.raw(toggle),
        onCheckedChange = { checked -> onToggleChange(toggle, checked) },
        title = stringResource(titleResource),
        enabled = enabled,
    )
}

@Composable
private fun RefreshPreference(
    settings: ModuleSettings,
    enabled: Boolean,
    onRefreshMinutesChange: (Int) -> Unit,
) {
    val labels = SettingsContract.REFRESH_MINUTES.map { minutes ->
        if (minutes == 0) {
            stringResource(R.string.settings_refresh_off)
        } else {
            stringResource(R.string.settings_refresh_minutes, minutes)
        }
    }
    OverlayDropdownPreference(
        title = stringResource(R.string.settings_refresh_title),
        items = labels,
        selectedIndex = SettingsContract.REFRESH_MINUTES.indexOf(settings.autoRefreshMinutes),
        enabled = enabled,
        onSelectedIndexChange = { index ->
            SettingsContract.REFRESH_MINUTES.getOrNull(index)?.let { minutes ->
                onRefreshMinutesChange(minutes)
            }
        },
    )
}

@Composable
private fun StatusMessage(message: String) {
    Text(
        text = message,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = MiuixTheme.colorScheme.onSurface,
        style = MiuixTheme.textStyles.body2,
    )
}
