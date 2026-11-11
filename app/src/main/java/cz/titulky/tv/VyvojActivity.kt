package cz.titulky.tv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Vývojářská obrazovka (stará): oprávnění, spuštění titulků a test překladu EN → CS.
 * Ovládání dálkovým ovladačem (šipky + OK).
 */
class VyvojActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var stavPrekladu: TextView
    private lateinit var vstup: EditText
    private lateinit var btnPermission: Button
    private lateinit var btnStart: Button
    private lateinit var btnPreloz: Button
    private lateinit var btnUkazka: Button
    private lateinit var btnStop: Button
    private lateinit var btnObrazek: Button
    private lateinit var btnSnimek: Button
    private lateinit var btnHledej: Button
    private lateinit var stavRemote: TextView
    private lateinit var vstupPin: EditText
    private lateinit var btnSparuj: Button
    private lateinit var btnRemote: Button
    private val udaje by lazy { RemotePlayUdaje(this) }
    private var nalezenaIp: String? = null
    private lateinit var nahled: ImageView

    private val prekladac = Prekladac()
    private val ctecka = Ctecka()

    /** Testovací obrázky ve složce assets/testy. */
    private val obrazky by lazy { assets.list("testy")?.sorted().orEmpty() }
    private var obrazekIndex = -1

    /** Ukázkové věty ve stylu her – postupně se přepínají. */
    private val ukazky = listOf(
        "Where the hell have you been?",
        "We need to get out of here before the cops show up.",
        "Get in the car, we're leaving now!",
        "I told you this job was a bad idea.",
        "Meet me at the docks at midnight. Come alone.",
        "You owe me money, and I want it by tomorrow."
    )
    private var ukazkaIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#1F2A44"))
            setPadding(80, 40, 80, 40)
        }

        val title = TextView(this).apply {
            text = "Titulky TV (EN → CS)"
            textSize = 34f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        status = info("#F2B84B")
        stavPrekladu = info("#9FD3FF").apply { text = "Překladač: připravuji…" }

        btnPermission = button("1) Povolit zobrazení přes jiné aplikace") { requestOverlayPermission() }
        btnStart = button("2) Spustit titulky") { startOverlay() }

        vstup = EditText(this).apply {
            hint = "Napiš anglickou větu…"
            setText(ukazky[0])
            textSize = 20f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#8899BB"))
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine()
            layoutParams = LinearLayout.LayoutParams(900, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 24, 0, 8) }
            setOnEditorActionListener { _, _, _ -> prelozAZobraz(text.toString()); true }
        }
        btnPreloz = button("3) Přeložit a zobrazit") { prelozAZobraz(vstup.text.toString()) }
        btnUkazka = button("Ukázková věta ze hry ▶") { dalsiUkazka() }
        btnObrazek = button("4) Přečíst titulek z obrázku hry ▶") { prectiObrazek() }
        nahled = ImageView(this).apply {
            adjustViewBounds = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(900, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 8, 0, 8) }
        }
        btnSnimek = button("5) TEST: vyfotit obrazovku za 10 s (přepni na PS5)") { zadejOSnimek() }
        btnHledej = button("6) Najít PS5 v síti (Remote Play)") { najdiPS() }
        stavRemote = info("#9FFFB0").apply { text = "" }
        vstupPin = EditText(this).apply {
            hint = "PIN z PS5 (8 číslic)"
            textSize = 20f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#8899BB"))
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine()
            layoutParams = LinearLayout.LayoutParams(900, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 16, 0, 8) }
        }
        btnSparuj = button("7) Spárovat s PS5 (zadej PIN výš)") { sparuj() }
        btnRemote = button("8) ▶ Spustit čtení titulků z PS5") { prepniRemote() }
        btnStop = button("Zastavit") { stopService(Intent(this, OverlayService::class.java)) }

        listOf(title, status, stavPrekladu, btnPermission, btnStart, vstup, btnPreloz, btnUkazka, btnObrazek, btnSnimek, btnHledej, stavRemote, vstupPin, btnSparuj, btnRemote, nahled, btnStop)
            .forEach { root.addView(it) }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#1F2A44"))
            addView(root)
        })

        pripravPrekladac()
        prevezmiAccountId(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        prevezmiAccountId(intent)
    }

    /** Skript psn-account-id.ps1 v počítači pošle Account ID rovnou sem. */
    private fun prevezmiAccountId(intent: Intent?) {
        val id = intent?.getStringExtra("psn_account_id") ?: return
        udaje.accountId = id
        obnovRemote()
        stavRemote.text = if (udaje.accountIdBajty() != null) "PSN Account ID uloženo ✔" else "Account ID má špatný tvar ✘ ($id)"
    }

    override fun onResume() {
        super.onResume()
        refreshState()
        ukazPosledniSnimek()
        // automaticky: když je spárováno a uživatel čtení nevypnul, spustit samo
        if (udaje.sparovano && Settings.canDrawOverlays(this) &&
            AutoStart.jeZapnuto(this) && !RemotePlayService.aktivni) {
            RemotePlayService.spustVse(this)
        }
        btnRemote.postDelayed({ obnovRemote() }, 800)
        obnovRemote()
    }

    override fun onDestroy() {
        prekladac.zavri()
        ctecka.zavri()
        super.onDestroy()
    }

    private fun pripravPrekladac() {
        stavPrekladu.text = "Překladač: stahuji český jazyk (jen poprvé, ~30 MB)…"
        btnPreloz.isEnabled = false
        btnUkazka.isEnabled = false
        btnObrazek.isEnabled = false
        prekladac.priprav(
            hotovo = {
                stavPrekladu.text = "Překladač: připraven ✔"
                refreshState()
            },
            chyba = { zprava ->
                stavPrekladu.text = "Překladač: chyba při stahování ✘ ($zprava). Je TV připojená k internetu?"
            }
        )
    }

    private fun refreshState() {
        val ok = Settings.canDrawOverlays(this)
        status.text = if (ok) "Oprávnění overlay: povoleno ✔" else "Oprávnění overlay: chybí ✘"
        btnPermission.visibility = if (ok) View.GONE else View.VISIBLE
        btnStart.isEnabled = ok
        btnPreloz.isEnabled = ok && prekladac.pripraven
        btnUkazka.isEnabled = ok && prekladac.pripraven
        btnObrazek.isEnabled = ok && prekladac.pripraven
        if (!ok) btnPermission.requestFocus()
    }

    private fun dalsiUkazka() {
        ukazkaIndex = (ukazkaIndex + 1) % ukazky.size
        vstup.setText(ukazky[ukazkaIndex])
        prelozAZobraz(ukazky[ukazkaIndex])
    }

    /** Vezme další testovací obrázek, přečte z něj titulek, přeloží a zobrazí. */
    private fun prectiObrazek() {
        if (obrazky.isEmpty()) {
            stavPrekladu.text = "Ve složce assets/testy nejsou žádné obrázky."
            return
        }
        obrazekIndex = (obrazekIndex + 1) % obrazky.size
        val nazev = obrazky[obrazekIndex]
        val bitmapa = assets.open("testy/$nazev").use { BitmapFactory.decodeStream(it) }
        nahled.setImageBitmap(bitmapa)
        nahled.visibility = View.VISIBLE
        stavPrekladu.text = "Čtu titulek z obrázku $nazev…"
        ctecka.prectiTitulek(
            bitmapa,
            hotovo = { precteno ->
                if (precteno.isBlank()) {
                    stavPrekladu.text = "V obrázku $nazev jsem nenašel žádný titulek."
                } else {
                    vstup.setText(precteno)
                    prelozAZobraz(precteno)
                }
            },
            chyba = { zprava -> stavPrekladu.text = "Chyba čtení ✘ ($zprava)" }
        )
    }

    /** Požádá televizi o povolení snímat obrazovku. */
    private fun zadejOSnimek() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), KOD_SNIMEK)
        } catch (e: ActivityNotFoundException) {
            stavPrekladu.text = "Tahle televize snímání obrazovky vůbec nenabízí ✘ → zbývá Remote Play."
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != KOD_SNIMEK) return
        if (resultCode != RESULT_OK || data == null) {
            stavPrekladu.text = "Snímání nebylo povoleno."
            return
        }
        stavPrekladu.text = "Za 10 s fotím obrazovku – přepni na PS5!"
        startForegroundService(
            Intent(this, SnimekService::class.java)
                .putExtra(SnimekService.EXTRA_KOD, resultCode)
                .putExtra(SnimekService.EXTRA_DATA, data)
        )
    }

    /** Remote Play – krok 1: najdi PlayStation v domácí síti. */
    private fun najdiPS() {
        stavPrekladu.text = "Hledám PlayStation v síti… (3 s)"
        btnHledej.isEnabled = false
        Thread {
            val vysledek = runCatching { HledacPS.hledej() }
            runOnUiThread {
                btnHledej.isEnabled = true
                stavPrekladu.text = vysledek.fold(
                    onSuccess = { seznam ->
                        if (seznam.isEmpty()) {
                            "Žádný PlayStation jsem nenašel ✘\n" +
                                "Je PS5 zapnutá (nebo v odpočinku) a ve stejné síti jako TV?"
                        } else {
                            nalezenaIp = (seznam.firstOrNull { it.typ == "PS5" } ?: seznam.first()).ip
                            obnovRemote()
                            "Nalezeno ✔\n" + seznam.joinToString("\n") {
                                "${it.jmeno} (${it.typ}) – ${it.stav} – IP ${it.ip}"
                            }
                        }
                    },
                    onFailure = { "Chyba hledání ✘ (${it.message})" }
                )
            }
        }.start()
    }

    /** Shrnutí stavu Remote Play pod tlačítkem 6. */
    private fun obnovRemote() {
        val radky = mutableListOf<String>()
        radky += if (udaje.accountIdBajty() != null) "PSN Account ID: ✔" else "PSN Account ID: chybí (spusť v PC skript psn-account-id.ps1)"
        radky += when {
            udaje.sparovano -> "Spárováno s PS5 (${udaje.ipKonzole}) ✔"
            nalezenaIp != null -> "PS5 nalezena (${nalezenaIp}) – zadej PIN a spáruj (7)"
            else -> "Nejdřív najdi PS5 (6)"
        }
        radky += "Remote Play: ${RemotePlayService.stav}"
        stavRemote.text = radky.joinToString("\n")
        btnRemote.text = if (RemotePlayService.aktivni) "8) ■ Zastavit čtení titulků z PS5" else "8) ▶ Spustit čtení titulků z PS5"
    }

    /** Spáruje televizi s PS5 pomocí PIN z PS5 (Nastavení → Systém → Remote Play → Propojit zařízení). */
    private fun sparuj() {
        val ip = nalezenaIp ?: udaje.ipKonzole
        if (ip == null) { stavRemote.text = "Nejdřív najdi PS5 tlačítkem 6."; return }
        val pin = vstupPin.text.toString().filter { it.isDigit() }
        if (pin.length != 8) { stavRemote.text = "PIN musí mít 8 číslic (ukáže ho PS5)."; return }
        stavRemote.text = "Páruji s PS5 ($ip)…"
        btnSparuj.isEnabled = false
        Parovani.sparuj(this, ip, pin.toInt(),
            log = { },
            hotovo = { ok, zprava ->
                runOnUiThread {
                    btnSparuj.isEnabled = true
                    obnovRemote()
                    stavRemote.text = zprava + "\n" + stavRemote.text
                    if (ok) vstupPin.setText("")
                }
            })
    }

    private fun prepniRemote() {
        if (RemotePlayService.aktivni) {
            AutoStart.nastavZapnuto(this, false)
            startService(Intent(this, RemotePlayService::class.java).setAction(RemotePlayService.ACTION_STOP))
        } else {
            AutoStart.nastavZapnuto(this, true)
            RemotePlayService.spustVse(this)
        }
        btnRemote.postDelayed({ obnovRemote() }, 800)
    }

    /** Po návratu do aplikace ukáže, co se na snímku povedlo zachytit. */
    private fun ukazPosledniSnimek() {
        val f = java.io.File(filesDir, SnimekService.SOUBOR)
        if (!f.exists()) return
        BitmapFactory.decodeFile(f.absolutePath)?.let {
            nahled.setImageBitmap(it)
            nahled.visibility = View.VISIBLE
        }
    }

    private fun prelozAZobraz(anglicky: String) {
        if (anglicky.isBlank()) return
        stavPrekladu.text = "Překládám…"
        // "LUCIA: Stay close." → jméno nepřekládáme, jen větu za ním
        val shoda = Regex("^([A-Z][A-Z .'-]{1,20}):\\s*(.+)$").find(anglicky.trim())
        val jmeno = shoda?.groupValues?.get(1)
        val veta = shoda?.groupValues?.get(2) ?: anglicky
        prekladac.preloz(
            veta,
            hotovo = { preklad ->
                val cesky = if (jmeno != null) "$jmeno: $preklad" else preklad
                stavPrekladu.text = "EN: $anglicky\nCS: $cesky"
                posliTitulek(cesky)
            },
            chyba = { zprava -> stavPrekladu.text = "Chyba překladu ✘ ($zprava)" }
        )
    }

    private fun posliTitulek(text: String) {
        startForegroundService(
            Intent(this, OverlayService::class.java)
                .setAction(OverlayService.ACTION_SET_TEXT)
                .putExtra(OverlayService.EXTRA_TEXT, text)
        )
    }

    private fun requestOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            status.text = "Tato TV nemá obrazovku pro overlay. Povol ručně: Nastavení → Aplikace → Titulky TV → Zobrazit přes jiné aplikace."
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
    }

    private fun startOverlay() {
        startForegroundService(Intent(this, OverlayService::class.java))
    }

    private fun info(barva: String) = TextView(this).apply {
        textSize = 18f
        setTextColor(Color.parseColor(barva))
        gravity = Gravity.CENTER
        setPadding(0, 12, 0, 12)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 20f
        isFocusable = true
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(900, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 8, 0, 8) }
    }

    companion object {
        private const val KOD_SNIMEK = 42
    }
}
