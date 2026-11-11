package cz.titulky.tv

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Předávání PS5 mezi zařízeními (televize ↔ telefon).
 * PS5 pustí přes Remote Play jen jedno zařízení. Když na jednom stiskneš Připojit,
 * pošle do domácí sítě zprávu "odpoj se" a PlayCaption na druhém zařízení se samo odpojí.
 */
object Predavani {
    const val PORT = 41987
    private const val ZPRAVA = "GAMELINGO_ODPOJ"
    /** Náhodné jméno tohoto zařízení – vlastní zprávy se ignorují. */
    val mojeId: String = java.util.UUID.randomUUID().toString().take(8)

    /** Řekne ostatním PlayCaption v síti, ať se odpojí od PS5. */
    fun posli() {
        Thread {
            runCatching {
                DatagramSocket().use { s ->
                    s.broadcast = true
                    val data = "$ZPRAVA $mojeId".toByteArray()
                    val kam = InetAddress.getByName("255.255.255.255")
                    repeat(3) {
                        s.send(DatagramPacket(data, data.size, kam, PORT))
                        Thread.sleep(200)
                    }
                }
            }
        }.start()
    }

    /** Poslouchá, jestli si PS5 nechce vzít jiné zařízení. */
    class Posluchac(context: Context, private val priOdpojeni: () -> Unit) {
        @Volatile private var bezi = true
        private var socket: DatagramSocket? = null
        private val zamek = runCatching {
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createMulticastLock("gamelingo").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()

        init {
            Thread {
                runCatching {
                    val s = DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        soTimeout = 1000
                        bind(InetSocketAddress(PORT))
                    }
                    socket = s
                    val buf = ByteArray(128)
                    while (bezi) {
                        val p = DatagramPacket(buf, buf.size)
                        try { s.receive(p) } catch (e: SocketTimeoutException) { continue }
                        val text = String(p.data, 0, p.length)
                        if (text.startsWith(ZPRAVA) && !text.endsWith(mojeId)) priOdpojeni()
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
}
