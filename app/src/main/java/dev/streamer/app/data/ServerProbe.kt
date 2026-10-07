package dev.streamer.app.data

import dev.streamer.app.data.account.AccountRepository
import dev.streamer.app.data.remote.ApiError
import dev.streamer.app.data.remote.SubsonicClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Checks whether the server answers, with a quick ping: when the network
 * changes, when the app returns to the foreground, and on request. Keeps the
 * connection state honest without waiting for a refresh or a failed song.
 */
class ServerProbe(
    private val accounts: AccountRepository,
    /** A client with short timeouts, so an unreachable server is noticed quickly. */
    private val client: SubsonicClient,
    private val policy: NetworkPolicy,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    init {
        scope.launch {
            policy.networkAvailable.drop(1).distinctUntilChanged().collect { available -> if (available) probe() }
        }
    }

    fun probe() {
        if (policy.offlineOnly.value || !policy.networkAvailable.value) return
        val (_, auth) = accounts.currentAuth() ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            val reachable = try {
                client.ping(auth)
                true
            } catch (e: ApiError) {
                // Any answer, even an error, means the server is reachable.
                e !is ApiError.Unreachable && e !is ApiError.Timeout
            }
            policy.reportServerReachable(reachable)
        }
    }
}
