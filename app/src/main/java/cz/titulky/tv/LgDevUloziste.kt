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

internal class LgDevUloziste(context: Context) {
    private val prefs = context.getSharedPreferences("lg_dev_mode", Context.MODE_PRIVATE)
    private fun k(id: String) = lgSha256(id.toByteArray())
    fun maRelaci(id: String) = prefs.contains(k(id) + ".token")
    fun verze(id: String) = prefs.getString(k(id) + ".token", null)?.let { lgSha256(it.toByteArray()) }
    fun cas(id: String): LgDevCas? {
        val key = k(id)
        return if (prefs.contains("$key.sekundy")) LgDevCas(prefs.getLong("$key.sekundy", 0), prefs.getLong("$key.overeno", 0)) else null
    }
    fun cas(id: String, verze: String, value: LgDevCas): Boolean = synchronized(ZAMEK) {
        if (verze(id) != verze) return@synchronized false
        prefs.edit().putLong(k(id) + ".sekundy", value.sekundy).putLong(k(id) + ".overeno", value.overeno).apply()
        true
    }
    fun token(id: String): String = rozbal(klic(), id, prefs.getString(k(id) + ".token", null) ?: error("No session"))
    fun token(id: String, token: String) = synchronized(ZAMEK) {
        if (runCatching { token(id) }.getOrNull() == token) return@synchronized
        require(token.matches(Regex("[A-Za-z0-9]{8,256}")))
        val data = zabal(klic(), id, token)
        prefs.edit().putString(k(id) + ".token", data).remove(k(id) + ".sekundy").remove(k(id) + ".overeno")
            .remove(k(id) + ".auto").remove(k(id) + ".pokusObnovy").remove(k(id) + ".chybaObnovy").apply()
    }
    fun automaticky(id: String) = prefs.getBoolean(k(id) + ".auto", false)
    fun nastavAutomaticky(id: String, zapnuto: Boolean) = synchronized(ZAMEK) {
        prefs.edit().putBoolean(k(id) + ".auto", zapnuto).apply()
    }
    fun chybaProdlouzeni(id: String) = automaticky(id) && prefs.getBoolean(k(id) + ".chybaObnovy", false)
    fun zahajProdlouzeni(id: String, verze: String, ted: Long): Boolean = synchronized(ZAMEK) {
        if (!automaticky(id) || verze(id) != verze) return@synchronized false
        val key = k(id) + ".pokusObnovy"
        val posledni = prefs.getLong(key, 0)
        if (!LgDevMode.povolenPokus(posledni, ted)) return@synchronized false
        prefs.edit().putLong(key, ted).commit()
    }
    fun vysledekProdlouzeni(id: String, verze: String, uspel: Boolean) = synchronized(ZAMEK) {
        if (verze(id) == verze) prefs.edit().putBoolean(k(id) + ".chybaObnovy", !uspel).apply()
    }
    fun parovaniId(ip: String): String? = prefs.getString("ip." + k(ip), null)
    fun maParovani(ip: String) = parovaniId(ip)?.let { prefs.contains(k(it) + ".ssh") } == true
    fun ssh(id: String): ByteArray? = prefs.getString(k(id) + ".ssh", null)?.let { rozbalBytes(klic(), id + ":ssh", it) }
    fun otisk(id: String) = prefs.getString(k(id) + ".host", null)
    fun parovani(id: String, ip: String, host: String, key: ByteArray) {
        require(id.matches(Regex("lg-[a-f0-9]{12}")) && key.size in 256..8192)
        val encrypted = zabalBytes(klic(), id + ":ssh", key)
        prefs.edit().putString(k(id) + ".ssh", encrypted).putString(k(id) + ".host", host)
            .putString("ip." + k(ip), id).apply()
    }
    fun prijimac(id: String, verze: String?) {
        prefs.edit().putString(k(id) + ".receiver", verze ?: "").putLong(k(id) + ".receiverChecked", System.currentTimeMillis()).apply()
    }
    fun verzePrijimace(id: String) = prefs.getString(k(id) + ".receiver", null)
    fun zapomenParovani(ip: String, profilId: String? = null) {
        val id = profilId ?: parovaniId(ip) ?: return
        prefs.edit().remove(k(id) + ".ssh").remove(k(id) + ".host").remove("ip." + k(ip))
            .remove(k(id) + ".auto").remove(k(id) + ".chybaObnovy").apply()
    }
    private fun klic(): SecretKey = synchronized(ZAMEK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    companion object {
        private const val ALIAS = "playcaption-lg-session-v1"
        private val ZAMEK = Any()
        internal fun zabal(key: SecretKey, id: String, token: String) = zabalBytes(key, id, token.toByteArray())
        internal fun rozbal(key: SecretKey, id: String, data: String) = rozbalBytes(key, id, data).toString(Charsets.UTF_8)
        internal fun zabalBytes(key: SecretKey, id: String, data: ByteArray): String {
            require(data.size <= 8192)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(id.toByteArray())
            require(cipher.iv.size == 12)
            return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(data))
        }
        internal fun rozbalBytes(key: SecretKey, id: String, data: String): ByteArray {
            require(data.length <= 16384)
            val bytes = Base64.getDecoder().decode(data)
            require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(id.toByteArray())
            return cipher.doFinal(bytes.copyOfRange(12, bytes.size))
        }
    }
}
