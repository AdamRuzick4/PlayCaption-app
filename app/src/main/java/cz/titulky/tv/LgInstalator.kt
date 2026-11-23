package cz.titulky.tv

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID

internal enum class LgStavVerze { CHYBI, AKTUALNI, AKTUALIZACE, NOVEJSI }
internal data class LgKontrola(val id: String, val verze: String?, val dostupna: String, val stav: LgStavVerze)
/** spusteno potvrzuje přijetí launch požadavku TV, nikoli běžící síťový přijímač. */
internal data class LgInstalaceVysledek(val verze: String, val spusteno: Boolean, val zmeneno: Boolean = true)

internal class LgInstalator(
    private val zdroj: LgZdrojBalicku,
    private val znamyOtisk: (String) -> String?,
    private val ulozOtisk: (String, String) -> Unit,
    private val uloziste: LgDevUloziste? = null,
    private val ocekavaneId: String? = null
) {
    @Volatile private var zruseno = false
    @Volatile private var session: Session? = null
    @Volatile private var http: Socket? = null
    private var faze = LgFaze.BALICEK
    private var platnost = 0L
    private var skutecneId = ""

    fun zrus() {
        zruseno = true
        Thread { runCatching { http?.close() }; runCatching { session?.disconnect() } }.apply { isDaemon = true }.start()
    }

    private fun kontrola() {
        if (zruseno) throw LgInstalacniChyba(LgChyba.ZRUSENO)
        if (System.nanoTime() > platnost) throw LgInstalacniChyba(LgChyba.CAS)
    }

    fun nainstaluj(adresa: String, passphrase: CharArray, prubeh: (LgFaze) -> Unit): LgInstalaceVysledek {
        lateinit var b: LgBalicek
        return pripojene(adresa, passphrase, prubeh, { b = zdroj.nacti() }) { ssh, krok ->
            krok(LgFaze.KONTROLA)
            val stav = zkontroluj(ssh, b)
            if (stav.stav == LgStavVerze.AKTUALNI || stav.stav == LgStavVerze.NOVEJSI) {
                val spusteno = spust()
                kontrola()
                return@pripojene LgInstalaceVysledek(stav.verze!!, spusteno, false)
            }
            krok(LgFaze.PRENOS)
            val cesta = "/media/developer/temp/playcaption-" + UUID.randomUUID().toString() + ".ipk"
            var pozadovanaInstalace = false
            var potvrzenaInstalace = false
            val sftp = ssh.openChannel("sftp") as ChannelSftp
            try {
                sftp.connect(8000)
                runCatching { sftp.mkdir("/media/developer/temp") }
                b.data.inputStream().use { sftp.put(it, cesta) }
                kontrola()
                val kopie = sftp.get(cesta).use { omezene(it, b.data.size + 1) }
                if (!kopie.contentEquals(b.data)) throw LgInstalacniChyba(LgChyba.PRENOS)
                krok(LgFaze.INSTALACE)
                val payload = JSONObject().put("id", LG_APP_ID).put("ipkUrl", cesta).put("subscribe", true)
                pozadovanaInstalace = true
                luna("luna://com.webos.appInstallService/dev/install", payload, true) { LgInstalacniProtokol.instalace(it) == true }
                potvrzenaInstalace = true
                krok(LgFaze.OVERENI)
                val info = sftp.get("/media/developer/apps/usr/palm/applications/$LG_APP_ID/appinfo.json").use { omezene(it, 16384) }
                LgInstalacniProtokol.verze(JSONObject(info.toString(Charsets.UTF_8)), b)
                kontrola()
                uloziste?.prijimac(skutecneId, b.verze)
                val spusteno = spust()
                kontrola()
                return@pripojene LgInstalaceVysledek(b.verze, spusteno)
            } finally {
                // Pouze vlastní náhodně pojmenovaný dočasný balíček; nainstalovaná aplikace se nemaže.
                // Při přerušení může LG stále číst IPK; soubor jí v takovém případě ponecháme.
                if (!pozadovanaInstalace || potvrzenaInstalace) runCatching { sftp.rm(cesta) }
                sftp.disconnect()
            }
        }
    }

    fun zkontroluj(adresa: String, passphrase: CharArray, prubeh: (LgFaze) -> Unit): LgKontrola {
        lateinit var b: LgBalicek
        return pripojene(adresa, passphrase, prubeh, { b = zdroj.nacti() }) { ssh, krok ->
            krok(LgFaze.KONTROLA)
            zkontroluj(ssh, b)
        }
    }

    private fun zkontroluj(ssh: Session, b: LgBalicek): LgKontrola {
        val sftp = ssh.openChannel("sftp") as ChannelSftp
        try {
            sftp.connect(8000)
            val cesta = "/media/developer/apps/usr/palm/applications/$LG_APP_ID/appinfo.json"
            val info = try { sftp.get(cesta).use { omezene(it, 16384) } }
                catch (e: SftpException) { if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw e }
            val version = info?.let {
                val json = JSONObject(it.toString(Charsets.UTF_8))
                if (json.optString("id") != LG_APP_ID) throw LgInstalacniChyba(LgChyba.OVERENI)
                json.getString("version")
            }
            val stav = LgInstalacniProtokol.stavVerze(version, b.verze)
            uloziste?.prijimac(skutecneId, version)
            return LgKontrola(skutecneId, version, b.verze, stav)
        } finally { sftp.disconnect() }
    }

    private fun spust() = runCatching {
        luna("luna://com.webos.applicationManager/launch", JSONObject().put("id", LG_APP_ID), false) {
            it.optBoolean("returnValue", false)
        }
    }.isSuccess

    /**
     * Znovu spustí přijímač nad aktuálním obrazem (bez výběru HDMI), když ho TV zavřela.
     * Používá jen uložené spárování (bez passphrase).
     */
    fun obnovPrekryv(adresa: String, staleVybrana: () -> Boolean): Boolean = pripojene(adresa, CharArray(0), {}, {}) { _, _ ->
        if (!staleVybrana()) return@pripojene false
        val payload = JSONObject().put("id", LG_APP_ID).put("params", JSONObject().put("resumeOverlay", true))
        luna("luna://com.webos.applicationManager/launch", payload, false) { it.optBoolean("returnValue", false) }
        true
    }

    /** Pouze čtení relace; neinstaluje přijímač a nemění Developer Mode. */
    fun nactiRelaci(adresa: String, passphrase: CharArray): String = pripojene(adresa, passphrase, {}, {}) { ssh, krok ->
        krok(LgFaze.OVERENI)
        prectiRelaci(ssh)
    }

    private fun prectiRelaci(ssh: Session): String {
        val sftp = ssh.openChannel("sftp") as ChannelSftp
        try {
            sftp.connect(8000)
            val data = sftp.get("/var/luna/preferences/devmode_enabled").use { omezene(it, 1024) }
            try {
                return data.toString(Charsets.US_ASCII).trim().also { require(it.matches(Regex("[A-Za-z0-9]{8,256}"))) }
            } finally { data.fill(0) }
        } catch (e: Exception) {
            if (e is LgInstalacniChyba) throw e
            throw LgInstalacniChyba(LgChyba.RELACE)
        } finally { sftp.disconnect() }
    }

    private fun identita(ssh: Session): String {
        val channel = ssh.openChannel("exec") as ChannelExec
        // Stejná identita jako discovery přijímače; nezávisí na instalaci IPK ani IP adrese.
        channel.setCommand("""/usr/bin/node -e 'var os=require("os"),c=require("crypto"),i=os.hostname(),n=os.networkInterfaces();Object.keys(n).forEach(function(k){n[k].forEach(function(a){if(!a.internal&&a.family==="IPv4")i+=a.mac;});});console.log("lg-"+c.createHash("sha256").update(i).digest("hex").slice(0,12));'""")
        val input = channel.inputStream
        try {
            channel.connect(8000)
            val value = omezene(input, 128).toString(Charsets.US_ASCII).trim()
            return LgInstalacniProtokol.identita(value, ocekavaneId)
        } finally { channel.disconnect() }
    }

    private fun <T> pripojene(adresa: String, passphrase: CharArray, prubeh: (LgFaze) -> Unit,
                              predPripojenim: () -> Unit, akce: (Session, (LgFaze) -> Unit) -> T): T {
        var key: ByteArray? = null
        val heslo = passphrase.concatToString().toByteArray(Charsets.UTF_8)
        passphrase.fill('\u0000')
        val jsch = JSch()
        platnost = System.nanoTime() + 120_000_000_000L
        val limit = java.util.Timer(true)
        limit.schedule(object : java.util.TimerTask() {
            override fun run() { runCatching { http?.close() }; runCatching { session?.disconnect() } }
        }, 120000)
        fun krok(f: LgFaze) { kontrola(); faze = f; prubeh(f) }
        try {
            val ip = LgInstalacniProtokol.adresa(adresa)
            if (heslo.isNotEmpty() && heslo.size != 6) throw LgInstalacniChyba(LgChyba.PASSPHRASE)
            krok(LgFaze.BALICEK)
            predPripojenim()
            krok(LgFaze.KLIC)
            val savedId = ocekavaneId ?: uloziste?.parovaniId(ip)
            val zeZalohy = heslo.isEmpty()
            key = if (zeZalohy) {
                try { savedId?.let { uloziste?.ssh(it) } ?: throw LgInstalacniChyba(LgChyba.PAROVANI) }
                catch (_: Exception) { throw LgInstalacniChyba(LgChyba.PAROVANI) }
            } else {
                val encrypted = nactiKlic(ip)
                try { odemkni(jsch, encrypted, heslo) } finally { encrypted.fill(0) }
            }
            try { jsch.addIdentity("playcaption-lg", key, null, null) }
            catch (e: Exception) {
                if (zeZalohy) throw LgInstalacniChyba(LgChyba.PAROVANI)
                throw e
            }
            krok(LgFaze.PRIPOJENI)
            val expected = savedId?.let { uloziste?.otisk(it) } ?: znamyOtisk(ip)
            if (zeZalohy && expected == null) throw LgInstalacniChyba(LgChyba.PAROVANI)
            val otisk = LgSshOtisk(expected)
            jsch.hostKeyRepository = otisk
            val ssh = jsch.getSession("prisoner", ip, 9922).also { session = it }
            nastavKompatibilitu(ssh)
            ssh.setConfig("PreferredAuthentications", "publickey")
            ssh.setConfig("StrictHostKeyChecking", "yes")
            ssh.timeout = 8000
            try { ssh.connect(8000) } catch (e: Exception) {
                if (otisk.zmeneny) throw LgInstalacniChyba(LgChyba.IDENTITA)
                throw e
            }
            // shortcut: první spárování důvěřuje domácí síti a klíči Key Serveru; pozdější změnu identity odmítáme.
            val host = otisk.novy ?: throw LgInstalacniChyba(LgChyba.SSH)
            skutecneId = identita(ssh)
            if (zeZalohy && savedId != skutecneId) throw LgInstalacniChyba(LgChyba.IDENTITA)
            ulozOtisk(ip, host)
            uloziste?.parovani(skutecneId, ip, host, key)
            // Kontrola relace je doplněk: její odmítnutí nesmí zablokovat instalaci přijímače.
            uloziste?.let { store -> runCatching { store.token(skutecneId, prectiRelaci(ssh)) } }
            kontrola()
            return akce(ssh, ::krok)
        } catch (e: Exception) {
            if (zruseno) throw LgInstalacniChyba(LgChyba.ZRUSENO)
            if (e is SocketTimeoutException || System.nanoTime() > platnost) throw LgInstalacniChyba(LgChyba.CAS)
            if (e is LgInstalacniChyba) throw e
            throw LgInstalacniChyba(when (faze) {
                LgFaze.BALICEK -> LgChyba.BALICEK
                LgFaze.KLIC -> LgChyba.KEY_SERVER
                LgFaze.PRIPOJENI -> LgChyba.SSH
                LgFaze.PRENOS -> LgChyba.PRENOS
                LgFaze.INSTALACE -> LgChyba.INSTALACE
                LgFaze.OVERENI, LgFaze.KONTROLA -> LgChyba.OVERENI
            })
        } finally {
            limit.cancel()
            heslo.fill(0)
            key?.fill(0)
            jsch.removeAllIdentity()
            runCatching { http?.close() }
            http = null
            session?.disconnect()
            session = null
        }
    }

    private fun nactiKlic(ip: String): ByteArray {
        val socket = Socket().also { http = it }
        socket.use {
            it.soTimeout = 5000
            it.connect(InetSocketAddress(ip, 9991), 5000)
            kontrola()
            // LG Key Server je lokální HTTP služba; nezapínáme cleartext pro celou Android aplikaci.
            it.getOutputStream().write("GET /webos_rsa HTTP/1.0\r\nHost: $ip:9991\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            return LgInstalacniProtokol.klicHttp(omezene(it.getInputStream(), 24576))
        }
    }

    private fun luna(uri: String, payload: JSONObject, subscribe: Boolean, hotovo: (JSONObject) -> Boolean) {
        val channel = session!!.openChannel("exec") as ChannelExec
        // URI i JSON obsahují pouze pevné ID a námi vytvořenou cestu; žádný shellový vstup uživatele.
        channel.setCommand("/usr/bin/luna-send-pub ${if (subscribe) "-i" else "-n 1"} '$uri' '$payload'")
        // -i drží subscription do EOF na stdin; zavřeme ji až po koncovém výsledku.
        val stdin = channel.outputStream
        val vstup = channel.inputStream
        try {
            channel.connect(8000)
            val pending = StringBuilder()
            var bytes = 0
            while (true) {
                kontrola()
                if (vstup.available() == 0) {
                    if (channel.isClosed) throw LgInstalacniChyba(if (pending.isNotBlank()) LgChyba.ODPOVED else LgChyba.KOMUNIKACE)
                    Thread.sleep(40)
                    continue
                }
                val value = vstup.read()
                if (value < 0) throw LgInstalacniChyba(if (pending.isNotBlank()) LgChyba.ODPOVED else LgChyba.KOMUNIKACE)
                if (++bytes > 65536) throw LgInstalacniChyba(LgChyba.ODPOVED)
                pending.append(value.toChar())
                if (value == 10) {
                    val json = try { JSONObject(pending.toString()) } catch (_: JSONException) { continue }
                    pending.setLength(0)
                    if (!json.optBoolean("returnValue", true)) throw LgInstalacniProtokol.chybaOdpovedi(json)
                    if (hotovo(json)) return
                }
            }
        } finally { runCatching { stdin.close() }; channel.disconnect() }
    }

    private fun omezene(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            kontrola()
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > max) throw LgInstalacniChyba(if (faze == LgFaze.KLIC) LgChyba.KEY_SERVER else LgChyba.PRENOS)
            out.write(buffer, 0, n)
        }
    }

    companion object {
        fun vytvor(context: android.content.Context, profilId: String? = null): LgInstalator {
            val app = context.applicationContext
            val pins = app.getSharedPreferences("lg_ssh_otisky", android.content.Context.MODE_PRIVATE)
            return LgInstalator(LgAssetBalicek(app), { pins.getString(lgSha256(it.toByteArray()), null) },
                { ip, hash -> pins.edit().putString(lgSha256(ip.toByteArray()), hash).apply() }, LgDevUloziste(app), profilId)
        }

        internal fun odemkni(jsch: JSch, key: ByteArray, heslo: ByteArray): ByteArray {
            val pair = KeyPair.load(jsch, key, null)
            try {
                if (!pair.isEncrypted) throw LgInstalacniChyba(LgChyba.KEY_SERVER)
                if (!pair.decrypt(heslo)) throw LgInstalacniChyba(LgChyba.PASSPHRASE)
                val out = ByteArrayOutputStream()
                pair.writePrivateKey(out)
                return out.toByteArray()
            } finally { pair.dispose() }
        }

        internal fun nastavKompatibilitu(ssh: Session) {
            // Developer Mode na C5 nabízí pouze ssh-rsa; povolujeme jej jen tomuto spojení s kontrolou otisku.
            for (key in listOf("server_host_key", "PubkeyAcceptedAlgorithms"))
                ssh.setConfig(key, JSch.getConfig(key) + ",ssh-rsa")
        }

        internal fun overHeslo(jsch: JSch, key: ByteArray, passphrase: ByteArray) {
            val pair = KeyPair.load(jsch, key, null)
            try {
                if (!pair.isEncrypted) throw LgInstalacniChyba(LgChyba.KEY_SERVER)
                if (!pair.decrypt(passphrase)) throw LgInstalacniChyba(LgChyba.PASSPHRASE)
            } finally { pair.dispose() }
        }
    }
}
