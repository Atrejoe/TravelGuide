package io.github.atrejoe.travelguide.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps one of the listener's own API keys, under [pref]: Anthropic (issue #7) or ElevenLabs
 * (issue #75). Encrypted with a key that never leaves the
 * Android Keystore. A backup restored on another phone can't be decrypted there, and then simply
 * reads as no key.
 */
class ApiKeyStore(context: Context, private val pref: String = ANTHROPIC) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    private val _hasKey = MutableStateFlow(get() != null)

    /** Whether a key is stored; the key itself is never shown again. */
    val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()

    fun get(): String? {
        val stored = prefs.getString(pref, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /** Stores [key], or forgets the stored one when it is null or blank. */
    fun set(key: String?) {
        val trimmed = key?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            prefs.edit().remove(pref).apply()
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val sealed = cipher.iv + cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(pref, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
        }
        _hasKey.value = trimmed.isNotEmpty()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val ANTHROPIC = "anthropic_api_key"
        const val ELEVENLABS = "elevenlabs_api_key"

        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "story-api-key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
