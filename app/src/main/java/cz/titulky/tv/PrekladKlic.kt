package cz.titulky.tv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** API klíč zůstává v soukromém úložišti zašifrovaný Android Keystore klíčem. */
internal class PrekladKlic(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("story_preklad_klic", Context.MODE_PRIVATE)
    val ulozen get() = prefs.contains("deepl")
    fun nacti(): String? = prefs.getString("deepl", null)?.let { rozbal(klic(), it) }
    fun uloz(value: String): Boolean = synchronized(ZAMEK) {
        require(DeepLPreklad.platnyKlic(value))
        prefs.edit().putString("deepl", zabal(klic(), value)).commit()
    }
    fun smaz(): Boolean = synchronized(ZAMEK) {
        prefs.edit().remove("deepl").commit()
    }
    private fun klic(): SecretKey = synchronized(ZAMEK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    companion object {
        private const val ALIAS = "playcaption-story-deepl-v1"
        private val ZAMEK = Any()
        internal fun zabal(key: SecretKey, value: String): String {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key); c.updateAAD(ALIAS.toByteArray(Charsets.UTF_8))
            require(c.iv.size == 12)
            return Base64.getEncoder().encodeToString(c.iv + c.doFinal(value.toByteArray(Charsets.UTF_8)))
        }
        internal fun rozbal(key: SecretKey, value: String): String {
            require(value.length <= 1024)
            val bytes = Base64.getDecoder().decode(value); require(bytes.size > 28)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            c.updateAAD(ALIAS.toByteArray(Charsets.UTF_8))
            return c.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        }
    }
}
