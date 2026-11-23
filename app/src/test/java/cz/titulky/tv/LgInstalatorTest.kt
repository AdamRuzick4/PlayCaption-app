package cz.titulky.tv

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class LgInstalatorTest {
    private fun odmitnuti(json: JSONObject): LgInstalacniChyba {
        try { LgInstalacniProtokol.instalace(json) }
        catch (e: LgInstalacniChyba) { return e }
        throw AssertionError("LG chyba nebyla zachycena")
    }

    @Test fun installationFailureIncludesLgCodeReasonAndState() {
        val error = odmitnuti(JSONObject("""{"id":"cz.playcaption.overlaytest","details":{"state":"install failed","errorCode":-5,"reason":"ipk verified failed"}}"""))
        assertEquals(LgChyba.INSTALACE, error.kod)
        assertTrue(error.message!!.contains("errorCode=-5"))
        assertTrue(error.message!!.contains("reason=ipk verified failed"))
        assertTrue(error.message!!.contains("state=install failed"))
    }

    @Test fun directLunaErrorKeepsCodeAndErrorText() {
        val error = odmitnuti(JSONObject("""{"returnValue":false,"errorCode":-1,"errorText":"Not enough storage"}"""))
        assertTrue(error.message!!.contains("errorCode=-1"))
        assertTrue(error.message!!.contains("reason=Not enough storage"))
        assertEquals(LgChyba.INSTALACE, odmitnuti(JSONObject("""{"returnValue":false}""")).kod)
    }

    @Test fun nonzeroErrorCodeCannotBeTreatedAsProgressOrSuccess() {
        odmitnuti(JSONObject("""{"id":"cz.playcaption.overlaytest","details":{"state":"installing","errorCode":-5,"reason":"ipk verified failed"}}"""))
        odmitnuti(JSONObject("""{"returnValue":true,"errorCode":-5,"details":{"state":"installed"}}"""))
        assertEquals(true, LgInstalacniProtokol.instalace(JSONObject("""{"returnValue":true,"errorCode":0,"details":{"state":"installed"}}""")))
    }

    @Test fun diagnosticContainsOnlyBoundedErrorFields() {
        val json = JSONObject().put("returnValue", false).put("errorCode", -5)
            .put("errorText", "bad\n\u0000" + "x".repeat(1000))
            .put("passphrase", "SECRET-PASS").put("token", "SECRET-TOKEN").put("key", "SECRET-KEY")
        val message = odmitnuti(json).message!!
        assertFalse(message.contains("SECRET"))
        assertFalse(message.contains('\u0000'))
        assertFalse(message.contains('\n'))
        assertTrue(message.length < 400)
    }

    @Test fun missingOrStructuredReasonIsNotInventedOrSerialized() {
        assertNull(odmitnuti(JSONObject("""{"returnValue":false}""")).detail)
        val json = JSONObject().put("returnValue", false)
            .put("errorText", JSONObject().put("token", "SECRET"))
        assertNull(odmitnuti(json).detail)
    }

    private val bytes = "!<arch>\nsynteticky testovaci balicek".toByteArray()
    private fun manifest() = JSONObject().put("schema", 1).put("platform", "webos").put("id", LG_APP_ID)
        .put("version", "0.1.0").put("file", "${LG_APP_ID}_0.1.0_all.ipk").put("size", bytes.size).put("sha256", lgSha256(bytes))
    private fun chyba(kod: LgChyba, action: () -> Unit) {
        try { action(); fail("Expected $kod") } catch (e: LgInstalacniChyba) { assertEquals(kod, e.kod) }
    }
    @Test fun onlyPrivateNumericAddressesAndNoShellOrDnsInput() {
        for (v in listOf("192.168.0.1", "10.0.1.0", "172.16.2.255", "169.254.1.2")) assertEquals(v, LgInstalacniProtokol.adresa(v))
        for (v in listOf("127.0.0.1", "8.8.8.8", "0.0.0.0", "224.0.0.1", "tv.local", "192.168.0.1;id", "192.168.0.999", "192.168.00.1"))
            chyba(LgChyba.ADRESA) { LgInstalacniProtokol.adresa(v) }
    }
    @Test fun wizardAcceptsKeyboardSeparatorsButKeepsLocalAddressRestrictions() {
        assertEquals("192.168.1.23", LgInstalacniProtokol.adresaZPole(" 192,168,1,23 "))
        assertEquals("192.168.1.23", LgInstalacniProtokol.adresaZPole("192.168.1.23"))
        for (v in listOf("", "192,168,1,23:9922", "8,8,8,8", "192,168,001,23", "http://192.168.1.23"))
            chyba(LgChyba.ADRESA) { LgInstalacniProtokol.adresaZPole(v) }
    }
    @Test fun packageIdentitySizeAndChecksumMustAllMatch() {
        assertEquals("0.1.0", LgBalicek.over(manifest().toString(), bytes).verze)
        for ((k,v) in listOf("id" to "cizi.app", "version" to "../../x", "file" to "cizi.ipk", "size" to 1, "sha256" to "wrong", "platform" to "android", "schema" to 2)) {
            try { LgBalicek.over(manifest().put(k,v).toString(),bytes); fail("Accepted $k") } catch (_: IllegalArgumentException) {}
        }
        try { LgBalicek.over(manifest().toString(), bytes + 1); fail("Accepted changed content") } catch (_: IllegalArgumentException) {}
    }
    @Test fun httpRejectsRedirectHtmlChunkingAndTruncation() {
        val key = "-----BEGIN RSA PRIVATE KEY-----\nFAKE\n-----END RSA PRIVATE KEY-----\n"
        fun response(status: String = "200 OK", extra: String = "", body: String = key) = "HTTP/1.0 $status\r\n${extra}Connection: close\r\n\r\n$body".toByteArray()
        assertArrayEquals(key.toByteArray(),LgInstalacniProtokol.klicHttp(response(extra="Content-Length: ${key.length}\r\n")))
        for (r in listOf(response("302 Found"),response(body="<html>error</html>"),response(extra="Transfer-Encoding: chunked\r\n"), response(extra="Content-Length: 9999\r\n")))
            chyba(LgChyba.KEY_SERVER) { LgInstalacniProtokol.klicHttp(r) }
    }
    @Test fun acceptedRequestIsNotInstallationSuccess() {
        for (state in listOf("", "DOWNLOADING", "INSTALLING", "NOT_INSTALLED"))
            assertNull(LgInstalacniProtokol.instalace(JSONObject().put("returnValue",true).put("details",JSONObject().put("state",state))))
        for (state in listOf("SUCCESS", "INSTALLED")) assertEquals(true,LgInstalacniProtokol.instalace(JSONObject().put("returnValue",true).put("details",JSONObject().put("state",state))))
        for (state in listOf("FAILED", "ERROR", "CANCELLED")) chyba(LgChyba.INSTALACE) {
            LgInstalacniProtokol.instalace(JSONObject().put("returnValue",true).put("details",JSONObject().put("state",state)))
        }
        chyba(LgChyba.INSTALACE) { LgInstalacniProtokol.instalace(JSONObject("{\"returnValue\":false}")) }
        chyba(LgChyba.INSTALACE) { LgInstalacniProtokol.instalace(JSONObject("{\"details\":{\"state\":\"SUCCESS\"}}")) }
    }
    @Test fun verifiesExactInstalledVersionAndId() {
        val b=LgBalicek.over(manifest().toString(),bytes)
        LgInstalacniProtokol.verze(JSONObject().put("id",LG_APP_ID).put("version","0.1.0"),b)
        chyba(LgChyba.OVERENI) { LgInstalacniProtokol.verze(JSONObject().put("id",LG_APP_ID).put("version","0.0.10"),b) }
        chyba(LgChyba.OVERENI) { LgInstalacniProtokol.verze(JSONObject().put("id","other").put("version","0.1.0"),b) }
    }
    @Test fun wrongPassphraseRejectedBeforeSshAndPlainKeyRejected() {
        val j=JSch()
        val k=KeyPair.genKeyPair(j,KeyPair.RSA,2048)
        try {
            val encrypted=ByteArrayOutputStream().also { k.writePrivateKey(it,"Ab12Cd".toByteArray()) }.toByteArray()
            LgInstalator.overHeslo(j,encrypted,"Ab12Cd".toByteArray())
            chyba(LgChyba.PASSPHRASE) { LgInstalator.overHeslo(j,encrypted,"wrong!".toByteArray()) }
            val plain=ByteArrayOutputStream().also { k.writePrivateKey(it) }.toByteArray()
            chyba(LgChyba.KEY_SERVER) { LgInstalator.overHeslo(j,plain,"Ab12Cd".toByteArray()) }
        } finally { k.dispose() }
    }
    @Test fun cancelledJobNeverReadsPackageOrConnects() {
        val i=LgInstalator(LgZdrojBalicku { fail("Must not read package"); error("unused") },{null},{_,_->})
        i.zrus()
        val pass="ABCDEF".toCharArray()
        chyba(LgChyba.ZRUSENO) { i.nainstaluj("192.168.0.1",pass) {} }
        assertTrue(pass.all { it=='\u0000' })
    }
    @Test fun knownHostCannotBeSilentlyReplaced() {
        val first=LgSshOtisk(null)
        assertEquals(com.jcraft.jsch.HostKeyRepository.OK,first.check("tv",byteArrayOf(1,2,3)))
        assertNotNull(first.novy)
        val next=LgSshOtisk(first.novy)
        assertEquals(com.jcraft.jsch.HostKeyRepository.OK,next.check("tv",byteArrayOf(1,2,3)))
        assertEquals(com.jcraft.jsch.HostKeyRepository.CHANGED,next.check("tv",byteArrayOf(4,5,6)))
        assertTrue(next.zmeneny)
    }

    @Test fun legacyLgRsaCompatibilityIsSessionLocal() {
        val global=JSch.getConfig("server_host_key")
        val ssh=JSch().getSession("prisoner","192.168.0.1",9922)
        LgInstalator.nastavKompatibilitu(ssh)
        assertTrue(ssh.getConfig("server_host_key").split(',').contains("ssh-rsa"))
        assertTrue(ssh.getConfig("PubkeyAcceptedAlgorithms").split(',').contains("ssh-rsa"))
        assertEquals(global,JSch.getConfig("server_host_key"))
    }

    @Test fun c5SubscriptionEventsOmitReturnValueAfterAcknowledgement() {
        assertNull(LgInstalacniProtokol.instalace(JSONObject("""{"subscribed":true,"returnValue":true}""")))
        assertNull(LgInstalacniProtokol.instalace(JSONObject("""{"statusValue":268,"details":{"state":"ipk verifying"},"id":"cz.playcaption.overlaytest"}""")))
        assertEquals(true,LgInstalacniProtokol.instalace(JSONObject("""{"details":{"state":"installed"},"id":"cz.playcaption.overlaytest"}""")))
        chyba(LgChyba.INSTALACE) { LgInstalacniProtokol.instalace(JSONObject("""{"details":{"state":"installed"},"id":"other.app"}""")) }
        chyba(LgChyba.INSTALACE) { LgInstalacniProtokol.instalace(JSONObject("""{"details":{"state":"FAILED"},"id":"cz.playcaption.overlaytest"}""")) }
    }

    @Test fun versionPolicyProtectsSameNewerAndUnrecognisedReceivers() {
        assertEquals(LgStavVerze.CHYBI, LgInstalacniProtokol.stavVerze(null, "0.1.0"))
        assertEquals(LgStavVerze.AKTUALNI, LgInstalacniProtokol.stavVerze("0.1.0", "0.1.0"))
        assertEquals(LgStavVerze.AKTUALIZACE, LgInstalacniProtokol.stavVerze("0.0.10", "0.1.0"))
        assertEquals(LgStavVerze.NOVEJSI, LgInstalacniProtokol.stavVerze("0.10.0", "0.9.9"))
        assertEquals(LgStavVerze.NOVEJSI, LgInstalacniProtokol.stavVerze("1.0.0", "0.99.99"))
        assertEquals(LgStavVerze.AKTUALIZACE, LgInstalacniProtokol.stavVerze("1.2.9", "1.2.10"))
        for (version in listOf("garbage", "0.1", "1.2.3.4", "1.0.0-story", "999999999999999.0.0"))
            chyba(LgChyba.OVERENI) { LgInstalacniProtokol.stavVerze(version, "0.1.0") }
    }
    @Test fun persistedSshKeyDecryptsWithoutPassphraseAndCanBeWiped() {
        val j=JSch(); val k=KeyPair.genKeyPair(j,KeyPair.RSA,2048)
        try {
            val encrypted=ByteArrayOutputStream().also { k.writePrivateKey(it,"Ab12Cd".toByteArray()) }.toByteArray()
            val plain=LgInstalator.odemkni(j,encrypted,"Ab12Cd".toByteArray())
            val identity=KeyPair.load(j,plain,null)
            try { assertFalse(identity.isEncrypted) } finally { identity.dispose() }
            plain.fill(0); assertTrue(plain.all { it==0.toByte() })
            chyba(LgChyba.PASSPHRASE) { LgInstalator.odemkni(j,encrypted,"wrong!".toByteArray()) }
        } finally { k.dispose() }
    }

    @Test fun numericIpDoesNotReplaceStableReceiverIdentity() {
        val id="lg-abcdef123456"
        assertEquals(id,LgInstalacniProtokol.identita(id,id))
        assertEquals(id,LgInstalacniProtokol.identita(id,null))
        for (other in listOf("lg-000000000000", "lg-ABCDEF123456", "arbitrary", ""))
            chyba(LgChyba.IDENTITA) { LgInstalacniProtokol.identita(other,id) }
    }

}
