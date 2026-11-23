package cz.titulky.tv

import android.content.Context
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Starší LG (webOS 3.x, ověřeno na 43UH6107) po chvíli bez ovladače sama zavře překryv s titulky
 * a prodloužení aktivity (restartUserActivityTimer) odmítá. Dokud telefon posílá titulky do LG,
 * každé 3 s ověří, že přijímač odpovídá na PLAYCAPTION_FIND, a když ne, spustí ho znovu nad
 * aktuálním obrazem (resumeOverlay, bez výběru HDMI) přes uložené spárování Developer Mode.
 * Na TV, kde překryv zůstává otevřený, se nic nespouští.
 */
internal object LgUdrzovani {
    @Volatile private var vlakno: Thread? = null
    /** Poslední stav (pro diagnostiku). */
    @Volatile var stav: String = "vypnuto (zapne se s překladem do LG)"

    @Synchronized fun zapni(context: Context) {
        if (vlakno?.isAlive == true) return
        val app = context.applicationContext
        val store = LgDevUloziste(app)
        val profily = TvProfily(app)
        val noveVlakno = Thread {
            var pauzaDo = 0L
            var hlasenoBezParovani = false
            val pracovnik = Thread.currentThread()
            while (vlakno === pracovnik && !pracovnik.isInterrupted) {
                try {
                    val ip = Propojeni.Vysilac.cilIp
                    val id = TvProfily.aktivniId
                    fun staleVybrana() = vlakno === pracovnik && !pracovnik.isInterrupted &&
                        RemotePlayService.aktivni && !RemotePlayService.pozastaveno &&
                        TvProfily.aktivniId == id && Propojeni.Vysilac.cilIp == ip
                    if (ip == null || id == null || !staleVybrana() || profily.platformaPrijimace(id) != TvPlatforma.WEBOS) {
                        stav = "čeká, až vybereš televizi"
                    } else if (store.parovaniId(ip) != id || !store.maParovani(ip)) {
                        stav = "chybí spárování s LG (Výstup titulků → Jak připojit televizi → Ověřit TV)"
                        if (!hlasenoBezParovani) { zapis(app, "udržování LG: chybí uložené spárování pro $ip"); hlasenoBezParovani = true }
                    } else {
                        hlasenoBezParovani = false
                        val bezi = prijimacBezi(ip, id, ::staleVybrana)
                        if (!staleVybrana()) continue
                        if (bezi) {
                            stav = "zapnuto, přijímač běží"
                        } else if (System.currentTimeMillis() >= pauzaDo) {
                            val ok = runCatching { LgInstalator.vytvor(app, id).obnovPrekryv(ip, ::staleVybrana) }
                            if (!staleVybrana()) continue
                            val spusteno = ok.getOrDefault(false)
                            stav = if (spusteno) "zapnuto, přijímač znovu spuštěn" else "televize nedostupná, zkouším dál"
                            zapis(app, "udržování LG: přijímač neodpovídal → " +
                                (if (spusteno) "znovu spuštěn" else "spuštění selhalo (${ok.exceptionOrNull()?.javaClass?.simpleName ?: "odmítnuto"})"))
                            pauzaDo = System.currentTimeMillis() + if (spusteno) 10_000L else 30_000L
                        }
                    }
                    Thread.sleep(3000)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
                }
            }
        }.apply { isDaemon = true; name = "lg-udrzovani" }
        vlakno = noveVlakno
        noveVlakno.start()
    }

    @Synchronized fun vypni() {
        vlakno?.interrupt()
        vlakno = null
        stav = "vypnuto (zapne se s překladem do LG)"
    }

    /** Přijímač odpovídá na PLAYCAPTION_FIND jen když běží. */
    internal fun prijimacBezi(ip: String, id: String, staleVybrana: () -> Boolean): Boolean {
        DatagramSocket().use { s ->
            val nonce = java.util.UUID.randomUUID().toString().replace("-", "")
            val dotaz = "PLAYCAPTION_FIND\t$nonce".toByteArray(Charsets.UTF_8)
            val kam = InetAddress.getByName(ip)
            val buf = ByteArray(2048)
            repeat(3) {
                if (!staleVybrana()) return false
                s.send(DatagramPacket(dotaz, dotaz.size, kam, Propojeni.PORT_TITULKY))
                val konec = System.nanoTime() + 1_500_000_000L
                while (staleVybrana()) {
                    val zbyva = konec - System.nanoTime()
                    if (zbyva <= 0) break
                    s.soTimeout = (zbyva / 1_000_000L + 1).toInt()
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (_: SocketTimeoutException) { break }
                    val casti = String(buf, 0, p.length, Charsets.UTF_8).split('\t')
                    if (Propojeni.primaOdpoved(casti, nonce, p.address.hostAddress ?: continue, id, ip)) return staleVybrana()
                }
            }
            return false
        }
    }

    internal fun zapis(c: Context, t: String) {
        runCatching {
            val cas = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
            java.io.File(c.filesDir, "titulky.log").appendText("$cas $t\n")
        }
    }
}
