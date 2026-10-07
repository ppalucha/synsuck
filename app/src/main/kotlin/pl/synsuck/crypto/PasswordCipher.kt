package pl.synsuck.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts account passwords with an AES-256-GCM key that lives in the Android Keystore
 * (non-exportable, hardware-backed where available).
 *
 * The key deliberately does not require user authentication: background sync must be able
 * to log in while the phone is locked.
 */
class PasswordCipher {
    class Encrypted(val ciphertext: ByteArray, val iv: ByteArray)

    fun encrypt(password: CharArray): Encrypted {
        val plain = toUtf8(password)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            return Encrypted(cipher.doFinal(plain), cipher.iv)
        } finally {
            plain.fill(0)
        }
    }

    /** Caller must zero the returned array after use. */
    fun decrypt(ciphertext: ByteArray, iv: ByteArray): CharArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        val plain = cipher.doFinal(ciphertext)
        try {
            val chars = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(plain))
            val result = CharArray(chars.remaining())
            chars.get(result)
            if (chars.hasArray()) chars.array().fill('\u0000')
            return result
        } finally {
            plain.fill(0)
        }
    }

    private fun toUtf8(password: CharArray): ByteArray {
        val buffer = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password))
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        if (buffer.hasArray()) buffer.array().fill(0)
        return bytes
    }

    @Synchronized
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "synsuck.password.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
