package dev.streamer.app.data.account

import dev.streamer.app.data.local.AccountDao
import dev.streamer.app.data.local.AccountEntity
import dev.streamer.app.data.remote.ApiError
import dev.streamer.app.data.remote.ServerAuth
import dev.streamer.app.data.remote.ServerUrl
import dev.streamer.app.data.remote.ServerUrlResult
import dev.streamer.app.data.remote.SubsonicClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.UUID

/** Non-secret description of the connected server account. */
data class Account(
    val id: String,
    val baseUrl: String,
    val username: String,
    val allowInsecureHttp: Boolean,
    val serverType: String?,
    val serverVersion: String?,
    val openSubsonic: Boolean,
)

sealed interface SessionState {
    data object Loading : SessionState
    data object SignedOut : SessionState

    /**
     * An account is active. [reauthReason] is set after the server rejected the
     * stored credentials: cached data stays usable and requests stop until the
     * user reconnects.
     */
    data class Active(val account: Account, val reauthReason: String? = null) : SessionState
}

sealed interface ConnectResult {
    data class Success(val account: Account) : ConnectResult
    data class Failure(val message: String) : ConnectResult
}

/** Owns the single active account and its credentials. */
class AccountRepository(
    private val accounts: AccountDao,
    private val credentials: CredentialStore,
    private val client: SubsonicClient,
    scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Decrypted once and held in memory for the session only. */
    @Volatile private var password: String? = null

    init {
        scope.launch {
            val active = accounts.active()
            if (active == null) {
                _state.value = SessionState.SignedOut
                return@launch
            }
            password = withContext(Dispatchers.IO) { credentials.load(active.id) }
            _state.value = SessionState.Active(
                active.toAccount(),
                reauthReason = if (password == null) "Your saved password is no longer available. Sign in again." else null,
            )
        }
    }

    /** Credentials for requests now, or null when signed out or awaiting reauthentication. */
    fun currentAuth(): Pair<Account, ServerAuth>? {
        val active = _state.value as? SessionState.Active ?: return null
        if (active.reauthReason != null) return null
        val pw = password ?: return null
        val a = active.account
        return a to ServerAuth(a.baseUrl.toHttpUrl(), a.username, pw, a.allowInsecureHttp)
    }

    /**
     * Verifies the server and credentials, then stores the account. Connecting
     * again to the same server and user keeps its cached data.
     */
    suspend fun connect(address: String, username: String, password: String, allowInsecureHttp: Boolean): ConnectResult {
        val base = when (val r = ServerUrl.normalize(address)) {
            is ServerUrlResult.Invalid -> return ConnectResult.Failure(r.reason)
            is ServerUrlResult.Valid -> r.base
        }
        if (username.isBlank()) return ConnectResult.Failure("Enter your username.")
        if (password.isEmpty()) return ConnectResult.Failure("Enter your password.")
        if (!base.isHttps && !allowInsecureHttp) {
            return ConnectResult.Failure("This address uses unencrypted HTTP. Allow HTTP for this server to continue, or use https://.")
        }
        val auth = ServerAuth(base, username.trim(), password, allowInsecureHttp && !base.isHttps)
        val info = try {
            client.ping(auth)
        } catch (e: ApiError) {
            return ConnectResult.Failure(e.message ?: "Couldn't connect.")
        }
        val existing = accounts.find(base.toString(), auth.username)
        val current = (_state.value as? SessionState.Active)?.account
        // One account at a time. Replacing one deletes its cache, which needs the
        // user's confirmation through sign-out, so never do it implicitly here.
        if (current != null && current.id != existing?.id) {
            return ConnectResult.Failure("Sign out of ${current.username} first to connect a different server or user.")
        }
        val entity = AccountEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            baseUrl = base.toString(),
            username = auth.username,
            allowInsecureHttp = auth.allowInsecureHttp,
            serverType = info.type,
            serverVersion = info.serverVersion,
            openSubsonic = info.openSubsonic,
            active = true,
        )
        withContext(Dispatchers.IO) { credentials.save(entity.id, password) }
        accounts.activate(entity)
        this.password = password
        val account = entity.toAccount()
        _state.value = SessionState.Active(account)
        return ConnectResult.Success(account)
    }

    /** Called when the server rejects stored credentials. Stops further requests until reconnect. */
    fun reportAuthFailure(error: ApiError.Auth) {
        _state.update { s -> if (s is SessionState.Active) s.copy(reauthReason = error.message) else s }
    }

    /** Removes the account, its saved password and all of its cached data from this device. */
    suspend fun signOut() {
        val active = (_state.value as? SessionState.Active)?.account ?: return
        password = null
        removeAccountData(active.id)
        _state.value = SessionState.SignedOut
    }

    private suspend fun removeAccountData(accountId: String) {
        withContext(Dispatchers.IO) { credentials.delete(accountId) }
        accounts.delete(accountId)
    }
}

private fun AccountEntity.toAccount() = Account(id, baseUrl, username, allowInsecureHttp, serverType, serverVersion, openSubsonic)
