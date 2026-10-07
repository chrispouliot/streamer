package dev.streamer.app.data.account

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets. Abstracted so storage logic can be tested without the Keystore. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/** AES-256-GCM with a non-exportable key held by the Android Keystore. Blob = 12-byte IV + ciphertext. */
class KeystoreCipher(private val alias: String = "streamer_credentials") : SecretCipher {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "Invalid credential blob" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_BYTES))
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

/**
 * Stores each account's password encrypted in app-private, no-backup storage.
 * Passwords are needed because Subsonic token auth requires a fresh salt per
 * request. Never written to DataStore, the database, URLs or logs.
 */
class CredentialStore(private val dir: File, private val cipher: SecretCipher) {
    private fun file(accountId: String) = AtomicFile(File(dir, "${accountId.filter { it.isLetterOrDigit() || it == '-' }}.bin"))

    fun save(accountId: String, password: String) {
        dir.mkdirs()
        val f = file(accountId)
        val out = f.startWrite()
        try {
            out.write(cipher.encrypt(password.toByteArray(Charsets.UTF_8)))
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            throw e
        }
    }

    /** Null when missing or unreadable (e.g. the Keystore key was invalidated). */
    fun load(accountId: String): String? = try {
        val f = file(accountId)
        if (!f.baseFile.exists()) null else String(cipher.decrypt(f.readFully()), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    fun delete(accountId: String) {
        file(accountId).delete()
    }
}
