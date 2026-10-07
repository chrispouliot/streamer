package dev.streamer.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.SingletonImageLoader
import dev.streamer.app.data.account.AccountRepository
import dev.streamer.app.data.account.SessionState
import dev.streamer.app.data.downloads.DownloadRepository
import dev.streamer.app.playback.PlayerController
import dev.streamer.app.settings.SettingsRepository
import dev.streamer.app.settings.ThemeMode
import dev.streamer.app.ui.components.BackBar
import dev.streamer.app.ui.components.ScreenTitle
import dev.streamer.app.ui.components.appViewModel
import dev.streamer.app.ui.components.readableColumn
import dev.streamer.app.ui.downloads.formatBytes
import dev.streamer.app.ui.navigation.AppNavigator
import dev.streamer.app.ui.navigation.LocalFloatingPlayerHeight
import dev.streamer.app.ui.navigation.LocalShellLayout
import dev.streamer.app.ui.theme.Dimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val accounts: AccountRepository,
    private val player: PlayerController,
    private val downloads: DownloadRepository,
) : ViewModel() {
    val themeMode: StateFlow<ThemeMode?> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val session: StateFlow<SessionState> = accounts.state

    val wifiOnly = settings.wifiOnlyDownloads.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val offlineOnly = settings.offlineOnly.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val storage = downloads.downloads.debounce(1_000).mapLatest { downloads.storageUsedBytes() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch { settings.setWifiOnlyDownloads(enabled) }
    }

    fun setOfflineOnly(enabled: Boolean) {
        viewModelScope.launch { settings.setOfflineOnly(enabled) }
    }

    fun removeAllDownloads() {
        viewModelScope.launch { downloads.removeAll() }
    }

    /** Stops playback and removes the account, its password, downloads and cached library from the device. */
    fun signOut() {
        player.clearQueue()
        viewModelScope.launch {
            downloads.removeAll()
            accounts.signOut()
        }
    }
}

@Composable
fun SettingsRoute(navigator: AppNavigator) {
    val vm = appViewModel { SettingsViewModel(it.settings, it.accounts, it.player, it.downloads) }
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val wifiOnly by vm.wifiOnly.collectAsStateWithLifecycle()
    val offlineOnly by vm.offlineOnly.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    SettingsScreen(
        themeMode = themeMode,
        onThemeMode = vm::setThemeMode,
        session = session as? SessionState.Active,
        onReconnect = navigator::openReconnect,
        onSignOut = vm::signOut,
        downloads = DownloadSettings(
            wifiOnly = wifiOnly,
            onWifiOnly = vm::setWifiOnly,
            offlineOnly = offlineOnly,
            onOfflineOnly = vm::setOfflineOnly,
            storageBytes = storage,
            onRemoveAll = vm::removeAllDownloads,
        ),
        onBack = navigator::back,
        showBack = !LocalShellLayout.current.usesRail,
    )
}

@Composable
fun SettingsScreen(
    themeMode: ThemeMode?,
    onThemeMode: (ThemeMode) -> Unit,
    session: SessionState.Active?,
    onReconnect: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
    showBack: Boolean,
    downloads: DownloadSettings? = null,
) {
    var confirmRemoveDownloads by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    val pad = LocalShellLayout.current.pagePadding
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }
    LazyColumn(Modifier.readableColumn(), contentPadding = PaddingValues(bottom = Dimens.xl + LocalFloatingPlayerHeight.current)) {
        if (showBack) item { BackBar(onBack) }
        item { ScreenTitle("Settings", pad) }

        item { SettingsSection("Account", pad) }
        val account = session?.account
        if (account == null) {
            item { SettingsText("No server connected", "Sign in to browse your library.", pad) }
        } else {
            item { SettingsText("Server", account.baseUrl, pad) }
            item { SettingsText("Username", account.username, pad) }
            item {
                SettingsText(
                    "Server version",
                    listOfNotNull(account.serverType?.replaceFirstChar { it.uppercase() }, account.serverVersion).joinToString(" ").ifEmpty { "Unknown" },
                    pad,
                )
            }
            if (!account.baseUrl.startsWith("https://")) {
                item { SettingsText("Connection", "Unencrypted HTTP, allowed for this server.", pad) }
            }
            session.reauthReason?.let { reason ->
                item { SettingsText("Signed out", reason, pad) }
            }
            item {
                FlowRow(Modifier.padding(horizontal = pad - Dimens.s), horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    TextButton(onClick = onReconnect) { Text("Reconnect") }
                    TextButton(onClick = { confirmSignOut = true }) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
                }
            }
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

        if (downloads != null) {
            item { SettingsSection("Downloads and offline", pad) }
            item {
                SettingsSwitch(
                    "Download on Wi-Fi only",
                    "Waits for Wi-Fi or another unmetered network before downloading.",
                    downloads.wifiOnly,
                    downloads.onWifiOnly,
                    pad,
                )
            }
            item {
                SettingsSwitch(
                    "Offline mode",
                    "Uses only downloaded and saved music and makes no requests to your server.",
                    downloads.offlineOnly,
                    downloads.onOfflineOnly,
                    pad,
                )
            }
            item { SettingsText("Downloads", "${formatBytes(downloads.storageBytes)} used on this device", pad) }
            item {
                FlowRow(Modifier.padding(horizontal = pad - Dimens.s), horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    TextButton(onClick = {
                        // Temporary images only; downloads and their saved covers are kept.
                        val loader = SingletonImageLoader.get(context)
                        loader.memoryCache?.clear()
                        scope.launch { withContext(Dispatchers.IO) { loader.diskCache?.clear() } }
                    }) { Text("Clear temporary cache") }
                    TextButton(onClick = { confirmRemoveDownloads = true }, enabled = downloads.storageBytes > 0) {
                        Text("Remove all downloads", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        item { SettingsSection("About", pad) }
        item { SettingsText("Version", version ?: "Unknown", pad) }
    }
    if (confirmSignOut && session != null) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = {
                Text(
                    "This stops playback and removes the saved password, downloads and this server's cached library from this device. " +
                        "Your music and playlists on the server aren't affected.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; onSignOut() }) {
                    Text("Sign out", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
    if (confirmRemoveDownloads && downloads != null) {
        AlertDialog(
            onDismissRequest = { confirmRemoveDownloads = false },
            title = { Text("Remove all downloads?") },
            text = { Text("Downloaded music will be deleted from this device and will need a connection to play. Your server isn't affected.") },
            confirmButton = {
                TextButton(onClick = { confirmRemoveDownloads = false; downloads.onRemoveAll() }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveDownloads = false }) { Text("Cancel") } },
        )
    }
}

class DownloadSettings(
    val wifiOnly: Boolean,
    val onWifiOnly: (Boolean) -> Unit,
    val offlineOnly: Boolean,
    val onOfflineOnly: (Boolean) -> Unit,
    val storageBytes: Long,
    val onRemoveAll: () -> Unit,
)

@Composable
private fun SettingsSwitch(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit, pad: androidx.compose.ui.unit.Dp) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onChange, role = Role.Switch)
            .padding(horizontal = pad, vertical = Dimens.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(Dimens.l))
        Switch(checked = checked, onCheckedChange = null)
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
