package cz.titulky.tv

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class LgDevModeTest {
    private val ted = 1_800_000_000_000L
    @Test fun obnovujeJenPlatnouRelaciVPoslednimTydnu() {
        val week = 604800L
        for (v in listOf(0L, -1L, week + 1)) assertFalse(LgDevMode.maSeProdlouzit(LgDevCas(v, ted)))
        for (v in listOf(1L, week)) assertTrue(LgDevMode.maSeProdlouzit(LgDevCas(v, ted)))
        assertTrue(LgDevMode.povolenPokus(0, ted))
        assertFalse(LgDevMode.povolenPokus(ted, ted + 86400000 - 1))
        assertTrue(LgDevMode.povolenPokus(ted, ted + 86400000))
    }
    @Test fun prodlouzeniVyžadujeSouhlasLgADelsiCas() {
        val stary = LgDevCas(3600, ted)
        val novy = LgDevCas(360000, ted + 1000)
        val souhlas = """{"result":"success"}"""
        assertEquals(novy, LgDevMode.prodluz(stary, { souhlas }, { novy }))
        assertEquals(novy, LgDevMode.prodluz(stary, { """{"errorCode":200}""" }, { novy }))
        assertTrue(runCatching { LgDevMode.prodluz(stary, { souhlas }, { stary }) }.isFailure)
        for (odpoved in listOf("{}", """{"result":"error"}""", "<html>")) {
            var overeno = false
            assertTrue(runCatching { LgDevMode.prodluz(stary, { odpoved }, { overeno = true; novy }) }.isFailure)
            assertFalse(overeno)
        }
    }
    @Test fun lgHoursMayExceedTwentyFourAndCountDownFromVerification() {
        val cas = LgDevCas.zOdpovedi("""{"result":"success","errorMsg":"999:32:04"}""", ted)
        assertEquals(999 * 3600L + 32 * 60 + 4, cas.sekundy)
        assertEquals(cas.sekundy - 60, cas.zbyva(ted + 60_000))
        assertEquals(ted, cas.overeno)
    }
    @Test fun invalidOrFailedResponseNeverBecomesZeroOrSuccess() {
        for (body in listOf("{}", "<html>error</html>", """{"result":"fail","errorMsg":"00:00:00"}""",
            """{"result":"success","errorMsg":"unknown"}""", """{"result":"success","errorMsg":"10:60:00"}""",
            """{"result":"success","errorMsg":"-1:00:00"}""", """{"result":"success","errorMsg":"999999:00:00"}""")) {
            try { LgDevCas.zOdpovedi(body, ted); fail("Invalid response accepted") } catch (_: Exception) {}
        }
    }
    @Test fun staleDataOrClockGoingBackCannotClaimCurrentTime() {
        val cas = LgDevCas(3600, ted)
        assertTrue(cas.cerstvy(ted))
        assertFalse(cas.cerstvy(ted - 1))
        assertFalse(cas.cerstvy(ted + 24 * 3600_000L + 1))
        assertFalse(cas.brzy(ted + 24 * 3600_000L + 1))
    }
    @Test fun warningStartsAtSeventyTwoHoursAndRemainingNeverNegative() {
        assertFalse(LgDevCas(72 * 3600L + 1, ted).brzy(ted))
        assertTrue(LgDevCas(72 * 3600L, ted).brzy(ted))
        assertEquals(0L, LgDevCas(1, ted).zbyva(ted + 2000))
        assertEquals(0L, LgDevCas.zOdpovedi("""{"result":"success","errorMsg":"0:00:00"}""", ted).sekundy)
    }
    @Test fun sessionEncryptedWithRandomIvAndBoundToOneTv() {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val token = "syntheticSession123"
        val a = LgDevUloziste.zabal(key, "tv-A", token)
        val b = LgDevUloziste.zabal(key, "tv-A", token)
        assertNotEquals(a, b)
        assertFalse(a.contains(token))
        assertEquals(token, LgDevUloziste.rozbal(key, "tv-A", a))
        try { LgDevUloziste.rozbal(key, "tv-B", a); fail("Token accepted for another TV") } catch (_: Exception) {}
        val bytes = java.util.Base64.getDecoder().decode(a)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        try { LgDevUloziste.rozbal(key, "tv-A", java.util.Base64.getEncoder().encodeToString(bytes)); fail("Tampering accepted") } catch (_: Exception) {}
    }
    @Test fun cancelledReadDoesNotLoadOrInstallPackageAndWipesPassphrase() {
        val job = LgInstalator(LgZdrojBalicku { error("Must not install") }, {null}, {_,_->})
        job.zrus()
        val pass = "ABCDEF".toCharArray()
        try { job.nactiRelaci("192.168.0.1", pass); fail("Cancelled read accepted") }
        catch (e: LgInstalacniChyba) { assertEquals(LgChyba.ZRUSENO, e.kod) }
        assertTrue(pass.all { it == '\u0000' })
    }
    @Test fun sshMaterialEncryptedSeparatelyFromSessionAndOtherTv() {
        val key=SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val data=ByteArray(2048) { (it % 251).toByte() }
        val encrypted=LgDevUloziste.zabalBytes(key,"tv-A:ssh",data)
        assertArrayEquals(data,LgDevUloziste.rozbalBytes(key,"tv-A:ssh",encrypted))
        for (aad in listOf("tv-A","tv-B:ssh")) {
            try { LgDevUloziste.rozbalBytes(key,aad,encrypted); fail("Wrong purpose or TV accepted") } catch (_: Exception) {}
        }
    }

}
