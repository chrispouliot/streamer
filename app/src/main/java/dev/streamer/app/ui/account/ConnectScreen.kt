package dev.streamer.app.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.streamer.app.data.account.Account
import dev.streamer.app.data.account.AccountRepository
import dev.streamer.app.data.account.ConnectResult
import dev.streamer.app.data.remote.ServerUrl
import dev.streamer.app.data.remote.ServerUrlResult
import dev.streamer.app.ui.components.appViewModel
import dev.streamer.app.ui.theme.Dimens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ConnectUiState(val connecting: Boolean = false, val error: String? = null)

class ConnectViewModel(private val accounts: AccountRepository) : ViewModel() {
    private val _state = MutableStateFlow(ConnectUiState())
    val state: StateFlow<ConnectUiState> = _state.asStateFlow()

    fun connect(address: String, username: String, password: String, allowHttp: Boolean, onSuccess: () -> Unit) {
        if (_state.value.connecting) return
        _state.value = ConnectUiState(connecting = true)
        viewModelScope.launch {
            when (val result = accounts.connect(address, username, password, allowHttp)) {
                is ConnectResult.Success -> {
                    _state.value = ConnectUiState()
                    onSuccess()
                }
                is ConnectResult.Failure -> _state.value = ConnectUiState(error = result.message)
            }
        }
    }
}

/**
 * Server sign-in. With [reconnect] set, the address and username are fixed
 * (only the password is asked for) so cached data for that account is kept.
 */
@Composable
fun ConnectRoute(reconnect: Account? = null, onConnected: () -> Unit = {}, onBack: (() -> Unit)? = null) {
    val vm = appViewModel { ConnectViewModel(it.accounts) }
    val state by vm.state.collectAsStateWithLifecycle()
    ConnectScreen(state, reconnect, onBack) { address, user, password, allowHttp ->
        vm.connect(address, user, password, allowHttp, onConnected)
    }
}

@Composable
fun ConnectScreen(
    state: ConnectUiState,
    reconnect: Account?,
    onBack: (() -> Unit)?,
    onConnect: (address: String, username: String, password: String, allowHttp: Boolean) -> Unit,
) {
    var address by rememberSaveable { mutableStateOf(reconnect?.baseUrl ?: "") }
    var username by rememberSaveable { mutableStateOf(reconnect?.username ?: "") }
    // Not saveable: the password is not written to saved instance state.
    var password by remember { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var allowHttp by rememberSaveable { mutableStateOf(reconnect?.allowInsecureHttp ?: false) }
    val focus = LocalFocusManager.current
    val isHttp = (ServerUrl.normalize(address) as? ServerUrlResult.Valid)?.isHttps == false
    val submit = {
        focus.clearFocus()
        onConnect(address, username, password, allowHttp && isHttp)
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.xl, vertical = Dimens.l),
            verticalArrangement = Arrangement.spacedBy(Dimens.l),
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            } else {
                Spacer(Modifier.height(Dimens.xxl))
            }
            Text(
                if (reconnect != null) "Reconnect" else "Connect to Navidrome",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                if (reconnect != null) {
                    "Enter the password for ${reconnect.username}. Your saved library stays on this device."
                } else {
                    "Enter your server's address, including any path it uses (for example /navidrome)."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Server address") },
                placeholder = { Text("https://music.example.com") },
                singleLine = true,
                enabled = reconnect == null && !state.connecting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            if (isHttp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = allowHttp, enabled = !state.connecting, role = Role.Checkbox) { allowHttp = it },
                    verticalAlignment = Alignment.Top,
                ) {
                    Checkbox(checked = allowHttp, onCheckedChange = null, modifier = Modifier.padding(top = 2.dp))
                    Column(Modifier.padding(start = Dimens.m)) {
                        Text("Allow unencrypted HTTP for this server", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "This app won't encrypt your sign-in or music with this address. That's fine on Tailscale or a " +
                                "trusted home network, which protect the connection themselves. Use https:// otherwise.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
                enabled = reconnect == null && !state.connecting,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                enabled = !state.connecting,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                trailingIcon = {
                    TextButton(
                        onClick = { showPassword = !showPassword },
                        modifier = Modifier.semantics { contentDescription = if (showPassword) "Hide password" else "Show password" },
                    ) { Text(if (showPassword) "Hide" else "Show") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.error != null) {
                Text(
                    state.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Button(
                onClick = submit,
                enabled = !state.connecting && address.isNotBlank() && username.isNotBlank() && password.isNotEmpty() && (!isHttp || allowHttp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .then(if (state.connecting) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
            ) {
                if (state.connecting) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.size(Dimens.m))
                    Text("Connecting…")
                } else {
                    Text(if (reconnect != null) "Reconnect" else "Connect")
                }
            }
        }
    }
}
