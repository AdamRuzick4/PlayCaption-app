package cz.titulky.tv

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Remote Play – krok 1: najde PlayStation v domácí síti.
 * Pošle do sítě dotaz "SRCH" (stejně jako oficiální aplikace PS Remote Play)
 * a vypíše, které konzole odpověděly.
 */
object HledacPS {

    data class Konzole(
        val ip: String,
        val jmeno: String,
        val typ: String,
        val stav: String,
        val id: String
    )

    private const val PORT_PS5 = 9302
    private const val PORT_PS4 = 987
    private const val VERZE_PS5 = "00030010"
    private const val VERZE_PS4 = "00020020"

    /** Blokující – volat z vlákna na pozadí. */
    fun hledej(casMs: Int = 3000): List<Konzole> {
        val nalezene = linkedMapOf<String, Konzole>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 500
            val broadcast = InetAddress.getByName("255.255.255.255")
            fun posli(port: Int, verze: String) {
                val zprava = "SRCH * HTTP/1.1\ndevice-discovery-protocol-version:$verze\n".toByteArray()
                socket.send(DatagramPacket(zprava, zprava.size, broadcast, port))
            }

            val konec = System.currentTimeMillis() + casMs
            var dalsiDotaz = 0L
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < konec) {
                if (System.currentTimeMillis() >= dalsiDotaz) {
                    runCatching { posli(PORT_PS5, VERZE_PS5) }
                    runCatching { posli(PORT_PS4, VERZE_PS4) }
                    dalsiDotaz = System.currentTimeMillis() + 1000
                }
                val paket = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(paket)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val text = String(paket.data, 0, paket.length)
                if (!text.startsWith("HTTP/1.1")) continue
                val pole = text.lines()
                    .mapNotNull { r -> r.indexOf(':').takeIf { it > 0 }?.let { r.substring(0, it).trim() to r.substring(it + 1).trim() } }
                    .toMap()
                val prvniRadek = text.lineSequence().first()
                val stav = when {
                    prvniRadek.contains("200") -> "zapnutá"
                    prvniRadek.contains("620") -> "v režimu odpočinku"
                    else -> prvniRadek.removePrefix("HTTP/1.1").trim()
                }
                val ip = paket.address.hostAddress ?: "?"
                nalezene[ip] = Konzole(
                    ip = ip,
                    jmeno = pole["host-name"] ?: "PlayStation",
                    typ = pole["host-type"] ?: "?",
                    stav = stav,
                    id = pole["host-id"] ?: "?"
                )
            }
        }
        return nalezene.values.toList()
    }
}
