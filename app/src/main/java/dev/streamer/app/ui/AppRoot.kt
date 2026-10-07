package dev.streamer.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.streamer.app.AppContainer
import dev.streamer.app.data.account.SessionState
import dev.streamer.app.ui.account.ConnectRoute
import dev.streamer.app.ui.navigation.AppShell

/** Shows sign-in until an account exists, then the app. */
@Composable
fun AppRoot(container: AppContainer) {
    val session by container.accounts.state.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (session) {
            SessionState.Loading -> Unit // Brief; avoids flashing sign-in before the saved account loads.
            SessionState.SignedOut -> ConnectRoute()
            is SessionState.Active -> AppShell(container.player)
        }
    }
}
