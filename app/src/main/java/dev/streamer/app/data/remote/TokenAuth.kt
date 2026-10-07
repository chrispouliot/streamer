package dev.streamer.app.data.remote

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Subsonic token authentication: `t = md5(password + salt)` with a fresh
 * random salt `s` per request. This avoids sending the password itself, but
 * is not a substitute for TLS.
 */
object TokenAuth {
    private val random = SecureRandom()

    fun newSalt(): String {
        val bytes = ByteArray(12)
        random.nextBytes(bytes)
        return bytes.toHex()
    }

    fun token(password: String, salt: String): String =
        MessageDigest.getInstance("MD5").digest((password + salt).toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
