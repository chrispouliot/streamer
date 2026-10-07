package dev.streamer.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.streamer.app.settings.SettingsRepository
import dev.streamer.app.settings.ThemeMode
import dev.streamer.app.ui.components.BackBar
import dev.streamer.app.ui.components.ScreenTitle
import dev.streamer.app.ui.components.appViewModel
import dev.streamer.app.ui.navigation.AppNavigator
import dev.streamer.app.ui.navigation.LocalFloatingPlayerHeight
import dev.streamer.app.ui.navigation.LocalShellLayout
import dev.streamer.app.ui.theme.Dimens
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val settings: SettingsRepository) : ViewModel() {
    val themeMode: StateFlow<ThemeMode?> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }
}

@Composable
fun SettingsRoute(navigator: AppNavigator) {
    val vm = appViewModel { SettingsViewModel(it.settings) }
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    SettingsScreen(themeMode, vm::setThemeMode, onBack = navigator::back, showBack = !LocalShellLayout.current.usesRail)
}

@Composable
fun SettingsScreen(themeMode: ThemeMode?, onThemeMode: (ThemeMode) -> Unit, onBack: () -> Unit, showBack: Boolean) {
    val pad = LocalShellLayout.current.pagePadding
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Dimens.xl + LocalFloatingPlayerHeight.current)) {
        if (showBack) item { BackBar(onBack) }
        item { ScreenTitle("Settings", pad) }

        item { SettingsSection("Account", pad) }
        item {
            SettingsText(
                "No server connected",
                "Connecting to a Navidrome server isn't available in this build yet.",
                pad,
            )
        }

        item { SettingsSection("Appearance", pad) }
        item {
            Column(Modifier.selectableGroup()) {
                ThemeMode.entries.forEach { mode ->
                    val label = when (mode) {
                        ThemeMode.System -> "System default"
                        ThemeMode.Light -> "Light"
                        ThemeMode.Dark -> "Dark"
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = Dimens.minTouchTarget)
                            .selectable(selected = themeMode == mode, onClick = { onThemeMode(mode) }, role = Role.RadioButton)
                            .padding(horizontal = pad),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = themeMode == mode, onClick = null)
                        Spacer(Modifier.width(Dimens.l))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        item { SettingsSection("About", pad) }
        item { SettingsText("Version", version ?: "Unknown", pad) }
    }
}

@Composable
private fun SettingsSection(title: String, pad: androidx.compose.ui.unit.Dp) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = pad, end = pad, top = Dimens.xl, bottom = Dimens.s).semantics { heading() },
    )
}

@Composable
private fun SettingsText(title: String, body: String, pad: androidx.compose.ui.unit.Dp) {
    Column(Modifier.fillMaxWidth().padding(horizontal = pad, vertical = Dimens.s)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
