package cz.titulky.tv

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class PrekladKlicTest {
    @Test fun apiKlicNeniVUlozistiCitelnyaZmenaDatNeboKliceSeOdmitne() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val secret = "test-secret-key:fx"
        val first = PrekladKlic.zabal(key, secret)
        val second = PrekladKlic.zabal(key, secret)
        assertFalse(first.contains(secret)); assertNotEquals(first, second)
        assertEquals(secret, PrekladKlic.rozbal(key, first))
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertTrue(runCatching { PrekladKlic.rozbal(other, first) }.isFailure)
        val bytes = java.util.Base64.getDecoder().decode(first)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertTrue(runCatching { PrekladKlic.rozbal(key, java.util.Base64.getEncoder().encodeToString(bytes)) }.isFailure)
        assertTrue(runCatching { PrekladKlic.rozbal(key, "x".repeat(1025)) }.isFailure)
    }
}
