package dev.streamer.app.data.remote

/**
 * Failures talking to the server, with user-presentable messages. Messages
 * never include request URLs (which carry auth tokens).
 */
sealed class ApiError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Worth retrying later without user action. */
    open val isTransient: Boolean get() = false

    /** Wrong credentials or an auth method the server rejects. Do not retry automatically. */
    class Auth(val code: Int, serverMessage: String?) : ApiError(
        when (code) {
            40 -> "Wrong username or password."
            41 -> "This server doesn't accept token sign-in for this account (for example LDAP users)."
            44 -> "The server rejected the sign-in key."
            else -> serverMessage?.takeIf { it.isNotBlank() } ?: "The server rejected the sign-in."
        },
    )

    class Permission(serverMessage: String?) :
        ApiError(serverMessage?.takeIf { it.isNotBlank() } ?: "Your account isn't allowed to do that.")

    class NotFound(serverMessage: String?) : ApiError(serverMessage?.takeIf { it.isNotBlank() } ?: "Not found on the server.")

    class Incompatible(val code: Int, serverMessage: String?) :
        ApiError("This server and app aren't compatible (${serverMessage ?: "error $code"}).")

    class Server(val code: Int, serverMessage: String?) :
        ApiError(serverMessage?.takeIf { it.isNotBlank() } ?: "The server reported an error ($code).")

    class Http(val status: Int) : ApiError(
        when (status) {
            404 -> "No Navidrome server found at this address. Check the address, including any path."
            401, 403 -> "The server or a proxy in front of it refused the request (HTTP $status)."
            in 500..599 -> "The server had a problem (HTTP $status). Try again later."
            else -> "Unexpected response from the server (HTTP $status)."
        },
    ) {
        override val isTransient: Boolean get() = status in 500..599
    }

    class Timeout(cause: Throwable?) : ApiError("The server took too long to respond.", cause) {
        override val isTransient: Boolean get() = true
    }

    class Unreachable(cause: Throwable?) :
        ApiError("Can't reach the server. Check the address and that you're connected (including Tailscale or VPN).", cause) {
        override val isTransient: Boolean get() = true
    }

    class Tls(cause: Throwable?) :
        ApiError("A secure connection couldn't be established. The server's certificate may be invalid or untrusted.", cause)

    /** HTTP was used for a server the user hasn't allowed it for, or the platform blocked cleartext. */
    class InsecureNotAllowed :
        ApiError("This server uses unencrypted HTTP. Allow HTTP for it explicitly, or use an https:// address.")

    class Malformed(cause: Throwable?) : ApiError("The server's response wasn't understood.", cause)

    /** No server account is active (signed out). */
    class NoAccount : ApiError("Not connected to a server.")

    /** Offline-only mode or similar policy prevented the request. */
    class Offline : ApiError("You're offline.")
}
