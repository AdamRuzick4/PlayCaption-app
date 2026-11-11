package cz.titulky.tv

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Režim "telefon čte, televize ukazuje".
 *
 * Telefon se připojí k PS5, čte a překládá titulky (to je rychlé) a hotový český text posílá
 * po domácí síti do televize. Televize se k PS5 nepřipojuje, jen titulky zobrazuje přes obraz.
 *
 *  - televize každé 2 s ohlásí do sítě "jsem tu" (PORT_HLEDANI) → telefon ji najde v seznamu
 *  - telefon posílá titulky přímo na adresu televize (PORT_TITULKY)
 *  - posílá je hned při změně a pak každou sekundu znovu (kdyby se zpráva po Wi-Fi ztratila,
 *    a televize tak zároveň ví, že telefon je pořád připojený)
 */
object Propojeni {
    const val PORT_HLEDANI = 41988
    const val PORT_TITULKY = 41989
    private const val OHLASENI = "PLAYCAPTION_TV"
    private const val TITULEK = "PCT1"
    /** Zpráva s volbami odpovědí: PCV1, číslo, rámeček 0/1, oblast (4 čísla), vybraná, volby (oddělené \n). */
    private const val VOLBY = "PCV1"

    /** Jméno televize, jak ho uvidí telefon v seznamu. */
    fun jmenoZarizeni(c: Context): String =
        runCatching { Settings.Global.getString(c.contentResolver, "device_name") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    private fun zamekSite(c: Context) = runCatching {
        (c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("playcaption-propojeni").apply { setReferenceCounted(false); acquire() }
    }.getOrNull()

    // ================================================================ TELEVIZE (přijímá)

    /**
     * Televize: ohlašuje se do sítě a přijímá titulky z telefonu.
     * [priTitulku] dostane (text, doba v ms, nápověda) – volá se z vlákna sítě.
     */
    class Prijimac(
        context: Context,
        private val priTitulku: (String, Long, String, FloatArray?) -> Unit,
        /** (české volby, která je vybraná (-1 = nevíme), oblast voleb, rámeček: 0 = ne, 1 = chvíli, 2 = trvale) */
        private val priVolbach: ((List<String>, Int, FloatArray, Int) -> Unit)? = null
    ) {
        @Volatile private var bezi = true
        private val c = context.applicationContext
        private val zamek = zamekSite(c)
        private var socket: DatagramSocket? = null
        private val jmeno = jmenoZarizeni(c).replace("\t", " ")

        /** Kdy naposledy přišla zpráva od telefonu (0 = ještě nikdy). */
        @Volatile var posledniZprava = 0L
            private set
        private var posledniCislo = -1L
        private var posledniCisloVoleb = -1L

        init {
            // ohlašování "jsem tu"
            Thread {
                runCatching {
                    DatagramSocket().use { s ->
                        s.broadcast = true
                        val kam = InetAddress.getByName("255.255.255.255")
                        while (bezi) {
                            val data = "$OHLASENI\t${Predavani.mojeId}\t$jmeno".toByteArray()
                            runCatching { s.send(DatagramPacket(data, data.size, kam, PORT_HLEDANI)) }
                            Thread.sleep(2000)
                        }
                    }
                }
            }.apply { isDaemon = true }.start()

            // příjem titulků
            Thread {
                runCatching {
                    val s = DatagramSocket(null).apply {
                        reuseAddress = true
                        soTimeout = 1000
                        bind(InetSocketAddress(PORT_TITULKY))
                    }
                    socket = s
                    val buf = ByteArray(8192)
                    while (bezi) {
                        val p = DatagramPacket(buf, buf.size)
                        try { s.receive(p) } catch (e: SocketTimeoutException) { continue }
                        val casti = String(p.data, 0, p.length, Charsets.UTF_8).split("\t")
                        if (casti.size >= 9 && casti[0] == VOLBY) {
                            posledniZprava = System.currentTimeMillis()
                            val c = casti[1].toLongOrNull() ?: continue
                            if (c == posledniCisloVoleb) continue
                            posledniCisloVoleb = c
                            runCatching {
                                val oblast = floatArrayOf(casti[3].toFloat(), casti[4].toFloat(), casti[5].toFloat(), casti[6].toFloat())
                                val text = odEscapuj(casti[8])
                                val seznam = if (text.isBlank()) emptyList() else text.split("\n")
                                priVolbach?.invoke(seznam, casti[7].toIntOrNull() ?: -1, oblast, casti[2].toIntOrNull() ?: 0)
                            }
                            continue
                        }
                        if (casti.size < 5 || casti[0] != TITULEK) continue
                        posledniZprava = System.currentTimeMillis()
                        val cislo = casti[1].toLongOrNull() ?: continue
                        // stejná zpráva znovu (kontrolní opakování) → jen "telefon žije"
                        if (cislo == posledniCislo) continue
                        posledniCislo = cislo
                        val doba = casti[2].toLongOrNull() ?: 6000L
                        // vlastní poloha a velikost titulků z telefonu (střed X, spodní hrana Y, šířka, velikost písma,
                        // 1 = právě se upravuje → ukázat rámeček)
                        val rozvrh = if (casti.size >= 9) runCatching {
                            val uprava = if (casti.getOrNull(9)?.trim() == "1") 1f else 0f
                            floatArrayOf(casti[5].toFloat(), casti[6].toFloat(), casti[7].toFloat(), casti[8].toFloat(), uprava)
                        }.getOrNull() else null
                        priTitulku(odEscapuj(casti[3]), doba, odEscapuj(casti[4]), rozvrh)
                    }
                }
                runCatching { socket?.close() }
            }.apply { isDaemon = true }.start()
        }

        fun zavri() {
            bezi = false
            runCatching { socket?.close() }
            runCatching { zamek?.release() }
        }
    }

    // ================================================================ TELEFON (hledá a posílá)

    data class Televize(val id: String, val jmeno: String, val ip: String)

    /** Telefon: po dobu [doba] ms poslouchá ohlášení televizí a vrátí, co našel. */
    fun hledej(context: Context, doba: Long = 3500, hotovo: (List<Televize>) -> Unit) {
        Thread {
            val nalez = LinkedHashMap<String, Televize>()
            val zamek = zamekSite(context)
            runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 500
                    bind(InetSocketAddress(PORT_HLEDANI))
                }.use { s ->
                    val konec = System.currentTimeMillis() + doba
                    val buf = ByteArray(512)
                    while (System.currentTimeMillis() < konec) {
                        val p = DatagramPacket(buf, buf.size)
                        try { s.receive(p) } catch (e: SocketTimeoutException) { continue }
                        val casti = String(p.data, 0, p.length, Charsets.UTF_8).split("\t")
                        if (casti.size >= 3 && casti[0] == OHLASENI && casti[1] != Predavani.mojeId) {
                            val ip = p.address.hostAddress ?: continue
                            nalez[casti[1]] = Televize(casti[1], casti[2], ip)
                        }
                    }
                }
            }
            runCatching { zamek?.release() }
            hotovo(nalez.values.toList())
        }.apply { isDaemon = true }.start()
    }

    /**
     * Telefon: posílá titulky do vybrané televize.
     * Zapíná se výběrem televize v TelefonActivity; [cilIp] null = jen telefon.
     */
    object Vysilac {
        @Volatile var cilIp: String? = null
            private set
        @Volatile var cilJmeno: String = ""
            private set

        @Volatile private var cislo = 0L
        @Volatile private var text = ""
        @Volatile private var doba = 0L
        @Volatile private var napoveda = ""
        @Volatile private var bezi = false
        private val zamek = Any()
        /** Poloha a velikost titulků na televizi: střed X, spodní hrana Y, šířka (0..1), velikost písma (1 = normální). */
        @Volatile private var rozvrh: FloatArray? = null
        // volby odpovědí
        @Volatile private var cisloVoleb = 0L
        @Volatile private var volbyText = ""
        @Volatile private var vybrana = -1
        @Volatile private var oblastVoleb = floatArrayOf(0.55f, 0.45f, 0.95f, 0.8f)
        @Volatile private var volbyUpravaDo = 0L
        /** Rámeček voleb ukazovat na televizi pořád (tlačítko v telefonu), ne jen při posouvání. */
        @Volatile var trvaleOblast = false
        @Volatile private var ukazkaText = ""

        fun nastavTrvaleOblast(ano: Boolean) {
            synchronized(zamek) { trvaleOblast = ano; cisloVoleb++ }
            posliData(zpravaVoleb())
        }

        /** Prst puštěn → rámeček voleb na televizi hned schovat (když není "ukazovat pořád"). */
        fun konecOblasti() {
            synchronized(zamek) { volbyUpravaDo = 0L; cisloVoleb++ }
            posliData(zpravaVoleb())
        }

        /** Prst puštěn → zelený rámeček a ukázkový text na televizi hned schovat. */
        fun konecUkazky() {
            synchronized(zamek) {
                upravaDo = 0L
                if (ukazkaText.isNotEmpty() && text == ukazkaText) { text = ""; doba = 0L }
                cislo++
            }
            posliTed()
        }

        /** Nové (přeložené) volby a která je vybraná. Prázdný seznam = volby zmizely. */
        fun nastavVolby(seznam: List<String>, vyb: Int) {
            synchronized(zamek) {
                val t = seznam.joinToString("\n")
                if (t == volbyText && vyb == vybrana) return
                volbyText = t; vybrana = vyb
                cisloVoleb++
            }
            posliData(zpravaVoleb())
        }

        /** Uživatel posouvá / mění rámeček voleb → televize ho 15 s ukazuje (žlutý šrafovaný). */
        fun upravujiOblast(o: FloatArray, ukazat: Boolean = true) {
            synchronized(zamek) {
                oblastVoleb = o.copyOf()
                volbyUpravaDo = if (ukazat) System.currentTimeMillis() + 8_000 else 0L
                cisloVoleb++
            }
            posliData(zpravaVoleb())
        }

        private fun zpravaVoleb(): ByteArray = synchronized(zamek) {
            val o = oblastVoleb
            val ramecek = if (trvaleOblast) 2 else if (System.currentTimeMillis() < volbyUpravaDo) 1 else 0
            "$VOLBY\t$cisloVoleb\t$ramecek\t${o[0]}\t${o[1]}\t${o[2]}\t${o[3]}\t$vybrana\t${escapuj(volbyText)}".toByteArray(Charsets.UTF_8)
        }

        /** Do kdy právě upravujeme polohu (televize do té doby ukazuje zelený rámeček). */
        @Volatile private var upravaDo = 0L

        fun nastavRozvrh(r: FloatArray?) {
            synchronized(zamek) { rozvrh = r; cislo++ }
            posliTed()
        }

        /** Ukázkový text při úpravě polohy (televize ho ukáže, ať je vidět, kde titulky budou). */
        fun ukazka(t: String) {
            synchronized(zamek) { text = t; ukazkaText = t; doba = 15_000; upravaDo = System.currentTimeMillis() + 15_000; cislo++ }
            posliTed()
        }

        fun nastavCil(ip: String?, jmeno: String) {
            cilIp = ip
            cilJmeno = jmeno
            if (ip != null) spustOpakovani() else bezi = false
            posliTed()
        }

        /** Nový titulek / nápověda z telefonu. */
        fun zmena(t: String, d: Long, n: String) {
            synchronized(zamek) {
                if (t == text && n == napoveda) return
                text = t; doba = d; napoveda = n
                cislo++
            }
            posliTed()
        }

        private fun zprava(): ByteArray = synchronized(zamek) {
            val r = rozvrh
            val uprava = if (System.currentTimeMillis() < upravaDo) 1 else 0
            val konec = if (r != null && r.size == 4) "\t${r[0]}\t${r[1]}\t${r[2]}\t${r[3]}\t$uprava" else ""
            "$TITULEK\t$cislo\t$doba\t${escapuj(text)}\t${escapuj(napoveda)}$konec".toByteArray(Charsets.UTF_8)
        }

        private fun posliTed() = posliData(zprava())

        private fun posliData(data: ByteArray) {
            val ip = cilIp ?: return
            Thread {
                runCatching {
                    DatagramSocket().use { s ->
                        val kam = InetAddress.getByName(ip)
                        s.send(DatagramPacket(data, data.size, kam, PORT_TITULKY))
                        Thread.sleep(60)
                        // podruhé, kdyby se první zpráva po Wi-Fi ztratila
                        s.send(DatagramPacket(data, data.size, kam, PORT_TITULKY))
                    }
                }
            }.start()
        }

        /** Každou sekundu poslat aktuální stav znovu (= "telefon žije" + oprava ztracené zprávy). */
        private fun spustOpakovani() {
            if (bezi) return
            bezi = true
            Thread {
                runCatching {
                    DatagramSocket().use { s ->
                        while (bezi) {
                            val ip = cilIp ?: break
                            val data = zprava()
                            val kam = InetAddress.getByName(ip)
                            runCatching { s.send(DatagramPacket(data, data.size, kam, PORT_TITULKY)) }
                            if (cisloVoleb > 0) {
                                val v = zpravaVoleb()
                                runCatching { s.send(DatagramPacket(v, v.size, kam, PORT_TITULKY)) }
                            }
                            Thread.sleep(1000)
                        }
                    }
                }
                bezi = false
            }.apply { isDaemon = true }.start()
        }
    }

    private fun escapuj(s: String) = s.replace("\\", "\\\\").replace("\t", " ").replace("\n", "\\n")
    private fun odEscapuj(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                when (s[i + 1]) { 'n' -> sb.append('\n'); '\\' -> sb.append('\\'); else -> sb.append(s[i + 1]) }
                i += 2
            } else { sb.append(ch); i++ }
        }
        return sb.toString()
    }
}
