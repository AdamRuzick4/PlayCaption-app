package cz.titulky.tv

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Přihlášení k PSN přes telefon.
 * Sony blokuje přihlášení v prohlížeči uvnitř aplikace ("Can't connect to the server"),
 * proto televize spustí malou webovou stránku v domácí síti. Telefon ji otevře přes QR kód,
 * člověk se přihlásí v normálním prohlížeči a vloží adresu, kde skončil → kód přijde do televize.
 */
class PrihlaseniTelefonem(private val cesky: Boolean, private val priKodu: (String) -> Unit) {

    @Volatile private var bezi = true
    private var server: ServerSocket? = null

    fun spust() {
        Thread {
            runCatching {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(PORT))
                server = s
                while (bezi) {
                    val c = s.accept()
                    Thread { obsluz(c) }.apply { isDaemon = true }.start()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    fun zastav() {
        bezi = false
        runCatching { server?.close() }
    }

    private fun obsluz(c: Socket) {
        runCatching {
            c.use {
                c.soTimeout = 15000
                val r = c.getInputStream().bufferedReader()
                // otevřel to sám telefon, na kterém PlayCaption běží (ne telefon u televize)
                val mistni = c.inetAddress.isLoopbackAddress
                val prvni = r.readLine() ?: return
                val cesta = prvni.split(" ").getOrNull(1) ?: "/"
                while (true) { val h = r.readLine() ?: break; if (h.isEmpty()) break }

                val html = if (cesta.startsWith("/kod")) {
                    val vlozeno = Regex("[?&]u=([^&]*)").find(cesta)?.groupValues?.get(1)
                        ?.let { URLDecoder.decode(it, "UTF-8") }?.trim() ?: ""
                    val kod = PsnPrihlaseni.kodZAdresy(vlozeno)
                        ?: Regex("code=([^&\\s]+)").find(vlozeno)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }
                    if (kod != null) { priKodu(kod); stranka(mistni, hotovo = true) } else stranka(mistni, chyba = true)
                } else stranka(mistni)

                val telo = html.toByteArray()
                val o = c.getOutputStream()
                o.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${telo.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
                o.write(telo)
                o.flush()
            }
        }
    }

    private fun stranka(mistni: Boolean, hotovo: Boolean = false, chyba: Boolean = false): String {
        val T = (if (cesky) CS else EN).toMutableList()
        if (mistni) {
            // na telefonu se nic neskenuje – vrátit se do aplikace a otevřít stránku znovu
            val Tt = if (cesky) CS_TELEFON else EN_TELEFON
            T[0] = Tt[0]; T[9] = Tt[1]
        }
        val obsah = when {
            hotovo -> "<div class=ok>${T[0]}</div>"
            else -> (if (chyba) "<div class=err>${T[1]}</div>" else "") + """
                <h2>1.</h2>
                <p>${T[2]}</p>
                <a class=btn href="${PsnPrihlaseni.ADRESA_PRIHLASENI}">${T[3]}</a>
                <h2>2.</h2>
                <p>${T[4]}</p>
                <div class=dva>
                  <div class=tel><div class=nad>iPhone</div>
                    <div class=bila>${T[7]}</div>
                    <div class=sipka>⬇ ${T[6]}</div>
                    <div class=lista><div class=adr>remoteplay.dl…</div></div>
                  </div>
                  <div class=tel><div class=nad>Android</div>
                    <div class=lista><div class=adr>remoteplay.dl…</div></div>
                    <div class=sipka>⬆ ${T[6]}</div>
                    <div class=bila>${T[7]}</div>
                  </div>
                </div>
                <p>${T[8]}</p>
                <h2>3.</h2>
                <p>${T[9]}</p>
                <form id=f action="/kod" method="get">
                  <textarea id=u name="u" rows="4" placeholder="${T[10]}"></textarea>
                  <button class=btn type="submit">${T[5]}</button>
                </form>
                <script>
                  // jakmile se vloží adresa s kódem, rovnou ji odeslat
                  var u=document.getElementById('u');
                  u.addEventListener('input',function(){ if(u.value.indexOf('code=')>=0) document.getElementById('f').submit(); });
                </script>"""
        }
        return """<!DOCTYPE html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>PlayCaption</title><style>
            body{background:#111;color:#eee;font-family:sans-serif;margin:0;padding:20px;max-width:560px}
            h1{color:#e5091a;margin:0 0 6px}h2{margin:22px 0 4px;color:#e5091a}
            p{line-height:1.45}
            .btn{display:block;width:100%;box-sizing:border-box;background:#e5091a;color:#fff;border:0;border-radius:14px;
                 padding:16px;font-size:18px;font-weight:bold;text-align:center;text-decoration:none;margin-top:8px}
            textarea{width:100%;box-sizing:border-box;font-size:15px;border-radius:10px;padding:10px;background:#222;color:#fff;border:1px solid #555}
            .ok{background:#14532d;padding:20px;border-radius:14px;font-size:20px;margin-top:20px}
            .err{background:#5b1111;padding:14px;border-radius:12px;margin-top:12px}
            .dva{display:flex;gap:12px;margin:10px 0}
            .tel{flex:1;border:3px solid #555;border-radius:22px;overflow:hidden;background:#fff}
            .nad{background:#333;color:#fff;text-align:center;font-weight:bold;padding:4px;font-size:13px}
            .lista{background:#e9e9ee;padding:10px}
            .adr{background:#fff;border:3px solid #e5091a;border-radius:10px;padding:8px;color:#333;font-size:13px;text-align:center}
            .sipka{color:#e5091a;font-weight:bold;text-align:center;padding:6px;background:#fff}
            .bila{height:70px;display:flex;align-items:center;justify-content:center;color:#999;font-size:14px;background:#fff}
            </style></head><body><h1>PlayCaption</h1>$obsah</body></html>"""
    }

    companion object {
        const val PORT = 8765

        private val CS = listOf(
            "✔ Hotovo! Účet je v televizi. Můžeš tuhle stránku zavřít a pokračovat na televizi.",
            "✘ V adrese jsem nenašel kód. Zkopíruj prosím celou adresu z řádku s adresou (musí obsahovat „code=“).",
            "Přihlas se k PlayStation <b>DRUHÝM účtem z PS5 – ne tím hlavním, na kterém hraješ</b>.",
            "Přihlásit se k PlayStation",
            "Po přihlášení se ukáže <b>BÍLÁ (prázdná) stránka</b> nebo chyba. <b>To je správně</b> – přihlášení proběhlo. Vypadá nějak takhle:",
            "Vložit do televize",
            "1. klepni sem",
            "bílá stránka",
            "Klepni na <b>řádek s adresou</b> a dej <b>Kopírovat</b>.<br>" +
                "• <b>iPhone (Safari):</b> adresa je <b>DOLE</b>. Klepni na ni → ikona <b>Kopírovat</b> (dva čtverečky).<br>" +
                "• <b>Android (Chrome):</b> adresa je <b>NAHOŘE</b>. Klepni na ni → ikona <b>Kopírovat</b> (dva čtverečky).",
            "<b>Naskenuj QR kód z televize znovu</b> – otevře se zase tahle stránka. Do pole dole podrž prst → <b>Vložit</b>. Odešle se to samo.",
            "Sem podrž prst a dej Vložit"
        )
        private val CS_TELEFON = listOf(
            "✔ Hotovo! Účet je uložený. Zavři prohlížeč a vrať se do aplikace PlayCaption.",
            "<b>Vrať se do aplikace PlayCaption a klepni znovu na „Otevřít v prohlížeči“</b> – otevře se zase tahle stránka. Do pole dole podrž prst → <b>Vložit</b>. Odešle se to samo."
        )
        private val EN_TELEFON = listOf(
            "✔ Done! The account is saved. Close the browser and go back to the PlayCaption app.",
            "<b>Go back to the PlayCaption app and tap “Open in browser” again</b> – this page opens again. Long-press the box below → <b>Paste</b>. It sends itself."
        )
        private val EN = listOf(
            "✔ Done! The account is on the TV. You can close this page and continue on the TV.",
            "✘ No code found in the address. Please copy the whole address from the bar at the top (it must contain “code=”).",
            "Sign in with your <b>SECOND PS5 account – not the main one you play on</b>.",
            "Sign in to PlayStation",
            "After signing in you'll see a <b>WHITE (blank) page</b> or an error. <b>That's correct</b> – you're signed in. It looks like this:",
            "Send to TV",
            "1. tap here",
            "white page",
            "Tap the <b>address bar</b> and choose <b>Copy</b>.<br>" +
                "• <b>iPhone (Safari):</b> the address is at the <b>BOTTOM</b>. Tap it → <b>Copy</b> icon (two squares).<br>" +
                "• <b>Android (Chrome):</b> the address is at the <b>TOP</b>. Tap it → <b>Copy</b> icon (two squares).",
            "<b>Scan the QR code on the TV again</b> – this page opens again. Long-press the box below → <b>Paste</b>. It sends itself.",
            "Long-press here and choose Paste"
        )

        /** IP adresa zařízení v domácí síti (pro QR kód). */
        fun mojeIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()

        /** QR kód jako obrázek. */
        fun qr(text: String, velikost: Int): Bitmap {
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, velikost, velikost, mapOf(EncodeHintType.MARGIN to 1))
            val bmp = Bitmap.createBitmap(velikost, velikost, Bitmap.Config.RGB_565)
            for (x in 0 until velikost) for (y in 0 until velikost)
                bmp.setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
            return bmp
        }
    }
}
