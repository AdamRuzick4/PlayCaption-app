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

    fun zapni(context: Context) {
        if (vlakno?.isAlive == true) return
        val app = context.applicationContext
        vlakno = Thread {
            var pauzaDo = 0L
            var hlasenoBezParovani = false
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val ip = Propojeni.Vysilac.cilIp
                    val store = LgDevUloziste(app)
                    val id = ip?.let { runCatching { store.parovaniId(it) }.getOrNull() }
                    if (ip == null) {
                        stav = "čeká, až vybereš televizi"
                    } else if (id == null || !store.maParovani(ip)) {
                        stav = "chybí spárování s LG (Výstup titulků → Jak připojit televizi → Ověřit TV)"
                        if (!hlasenoBezParovani) { zapis(app, "udržování LG: chybí uložené spárování pro $ip"); hlasenoBezParovani = true }
                    } else {
                        hlasenoBezParovani = false
                        if (prijimacBezi(ip)) {
                            stav = "zapnuto, přijímač běží"
                        } else if (System.currentTimeMillis() >= pauzaDo) {
                            val ok = runCatching { LgInstalator.vytvor(app, id).obnovPrekryv(ip) }
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
        }.apply { isDaemon = true; name = "lg-udrzovani"; start() }
    }

    fun vypni() {
        vlakno?.interrupt()
        vlakno = null
        stav = "vypnuto (zapne se s překladem do LG)"
    }

    /** Přijímač odpovídá na PLAYCAPTION_FIND jen když běží. */
    private fun prijimacBezi(ip: String): Boolean = runCatching {
        DatagramSocket().use { s ->
            s.soTimeout = 1500
            val nonce = java.util.UUID.randomUUID().toString().replace("-", "")
            val dotaz = "PLAYCAPTION_FIND\t$nonce".toByteArray(Charsets.UTF_8)
            val kam = InetAddress.getByName(ip)
            repeat(3) {
                s.send(DatagramPacket(dotaz, dotaz.size, kam, Propojeni.PORT_TITULKY))
                try {
                    val buf = ByteArray(2048)
                    s.receive(DatagramPacket(buf, buf.size))
                    return@runCatching true
                } catch (_: SocketTimeoutException) { /* další pokus */ }
            }
            false
        }
    }.getOrDefault(false)

    internal fun zapis(c: Context, t: String) {
        runCatching {
            val cas = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
            java.io.File(c.filesDir, "titulky.log").appendText("$cas $t\n")
        }
    }
}
