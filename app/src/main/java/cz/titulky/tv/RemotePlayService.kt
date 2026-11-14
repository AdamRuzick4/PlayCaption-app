package cz.titulky.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import com.metallic.chiaki.lib.Codec
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.lib.ConnectedEvent
import com.metallic.chiaki.lib.CreateError
import com.metallic.chiaki.lib.Event
import com.metallic.chiaki.lib.LoginPinRequestEvent
import com.metallic.chiaki.lib.QuitEvent
import com.metallic.chiaki.lib.Session
import java.io.File

/**
 * Hlavní služba: přes Remote Play (Chiaki) přijímá obraz z PS5 na pozadí,
 * několikrát za sekundu vezme snímek, přečte anglický titulek, přeloží ho
 * a ukáže česky přes obraz z HDMI.
 */
class RemotePlayService : Service() {

    private var session: Session? = null
    private var reader: ImageReader? = null
    private val vlakno = HandlerThread("remote-play").apply { start() }
    private val bg = Handler(vlakno.looper)
    private val ui = Handler(Looper.getMainLooper())

    /** Jedna čtečka (dvě najednou televize nestíhá – každá pak čte 2–3× pomaleji). */
    private val ctecky = listOf(Ctecka())
    private val obsazeno = Array(ctecky.size) { java.util.concurrent.atomic.AtomicBoolean(false) }
    private val obsazenoOd = LongArray(ctecky.size)
    /** "Otisk" oblasti titulků z posledního čtení – když se nezměnil, znovu se nečte. */
    private var posledniOtisk: IntArray? = null
    @Volatile private var nesmysluZaSebou = 0
    @Volatile private var nejistychZaSebou = 0
    @Volatile private var useknutychZaSebou = 0
    // statistika do záznamu (jednou za 30 s): kolikrát se četlo / přeskočilo
    @Volatile private var stCteni = 0
    @Volatile private var stSkipStejne = 0
    @Volatile private var stSkipVynoreni = 0
    private var stOd = 0L
    private fun statistika(ted: Long) {
        if (stOd == 0L) { stOd = ted; return }
        if (ted - stOd < 30_000) return
        val zprava = "[za 30 s: čteno $stCteni×, beze změny $stSkipStejne×, čekání na titulek $stSkipVynoreni×, druhý pokus $stKontrast×, nad nápovědou $stNad×, poškozený obraz $stPoskozeno×, snímků $snimkuCelkem]"
        ui.post { zapis(zprava) }
        stCteni = 0; stSkipStejne = 0; stSkipVynoreni = 0; stKontrast = 0; stNad = 0; stPoskozeno = 0; stOd = ted
    }
    private var otiskMinulehoSnimku: IntArray? = null
    private var zmenaOd = 0L
    private var posledniCteniKdy = 0L
    private val poradi = java.util.concurrent.atomic.AtomicLong(0)
    /** Pořadí posledního zpracovaného / ukázaného snímku – starší výsledky se zahodí. */
    private var posledniVysledek = 0L
    private var posledniUkazany = 0L
    private val popredi by lazy { Popredi(this) }
    @Volatile private var posledniPopredi: String? = null
    private val nast by lazy { Nastaveni(this) }
    private val prekladac by lazy { Prekladac(this, nast.jazykNeboCs) }

    private var prectenoPosledne = ""
    private var prazdnychZaSebou = 0
    @Volatile private var snimkuCelkem = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        when (intent?.action) {
            ACTION_PREPNOUT -> {
                // tlačítko ⇄ v aplikaci: odpojit a připojit se k druhé konzoli
                zapis("[přepínám na " + (if (preferXbox(this)) "Xbox" else "PS5") + "]")
                ui.removeCallbacks(hlidac)
                zastav("", ukazat = false)
                naXboxu = preferXbox(this)
                xboxKontrolaKdy = 0L
                neuspechu = 0
                ui.postDelayed(hlidac, 1500)
            }
            ACTION_STOP -> {
                if (aktivni) zapis("[ukončeno v aplikaci – tlačítko, Zpět nebo zhasnutý displej]")
                automat = false
                aktivni = false
                ui.removeCallbacks(hlidac)
                zastav("Remote Play zastaveno.")
                stopSelf()
            }
            else -> {
                automat = true
                aktivni = true
                if (posluchac == null) posluchac = Predavani.Posluchac(this) { ui.post { prevzaloJine() } }
                // spojení "visí" (televize/PS5 spaly, obraz nechodí) → zahodit a připojit znovu
                if (pripojeno() && System.currentTimeMillis() - posledniSnimekKdy > 8000) {
                    zapis("[staré spojení bez obrazu – připojuji znovu]")
                    zastav("", ukazat = false)
                }
                ui.removeCallbacks(hlidacSpojeni)
                ui.postDelayed(hlidacSpojeni, 5000)
                ui.removeCallbacks(kontrolaObou)
                ui.post(kontrolaObou)
                if (!pripojeno()) {
                    ui.removeCallbacks(hlidac)
                    // stisknuto Připojit → druhé zařízení (TV/telefon) se odpojí; chvilku mu dát
                    val prevzit = intent?.getBooleanExtra(EXTRA_PREVZIT, false) == true
                    if (prevzit) chciPS5 = true
                    if (prevzit) {
                        zapis("[Hotovo – hrát: připojuji (případné jiné zařízení s PlayCaption se odpojí)]")
                        neuspechu = 0
                        Predavani.posli()
                        ui.postDelayed(hlidac, 3000)
                    } else ui.post(hlidac)
                }
            }
        }
        return START_STICKY
    }

    private var posluchac: Predavani.Posluchac? = null
    @Volatile private var chybaObrazu = false

    override fun onCreate() {
        super.onCreate()
        Pady.hlidej(this)
        // televize, která sama o sobě hlásí "málo paměti" → rovnou šetřit
        runCatching {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            if (!telefon && am.isLowRamDevice) zapniUsporu("televize hlásí málo paměti")
        }
        // slabá televize už byla jednou poznaná → šetřit hned od začátku (bez rozehřívání,
        // které ji při spouštění samo zahltí)
        if (!telefon && getSharedPreferences("uspora", MODE_PRIVATE).getBoolean("slaba", false)) {
            uspora = true
            zapis("[ÚSPORNÝ REŽIM (slabá televize – uloženo)]")
        }
    }

    // ---------------------------------------------------------------- úsporný režim (slabé televize)
    /** Slabá televize (čtení trvá dlouho) → číst méně často, bez rychlého náhledu a bez druhého pokusu. */
    // ---------------------------------------------------------------- Wi-Fi bez úspory energie
    /**
     * Telefony (hlavně Xiaomi) šetří Wi-Fi – vypínají příjem na desítky ms a pakety z PS5 se
     * ztrácejí ("Takion re-sending … tries: 19"). Po dobu spojení držet Wi-Fi v režimu nízkého
     * zpoždění, jako to dělají aplikace na streamování her.
     */
    private var wifiZamek: android.net.wifi.WifiManager.WifiLock? = null

    private fun drzWifi(ano: Boolean) {
        runCatching {
            if (ano) {
                if (wifiZamek == null) {
                    val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
                    val rezim = if (Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    wifiZamek = wm.createWifiLock(rezim, "playcaption:stream").apply { setReferenceCounted(false) }
                }
                if (wifiZamek?.isHeld != true) wifiZamek?.acquire()
            } else {
                if (wifiZamek?.isHeld == true) wifiZamek?.release()
            }
        }
    }

    @Volatile private var uspora = false
    @Volatile private var zahrivani = false
    private var pomalaCteni = 0
    private var rychlaCteni = 0

    private fun zapniUsporu(duvod: String) {
        if (uspora || telefon) return
        uspora = true
        zapis("[ÚSPORNÝ REŽIM zapnut: $duvod]")
        // zapamatovat – příště šetřit hned od spuštění
        runCatching { getSharedPreferences("uspora", MODE_PRIVATE).edit().putBoolean("slaba", true).apply() }
    }
    /** Tady uživatel stiskl Připojit → PS5 patří nám; druhé zařízení opakovaně žádat, ať se odpojí. */
    @Volatile private var chciPS5 = false

    /** Na jiném zařízení (TV/telefon) někdo stiskl Připojit → uvolnit mu PS5. */
    private fun prevzaloJine() {
        if (!aktivni) return
        zapis("[PS5 si vzalo jiné zařízení – odpojuji]")
        chciPS5 = false
        automat = false
        aktivni = false
        pozastaveno = false
        AutoStart.nastavZapnuto(this, false)
        ui.removeCallbacks(hlidac)
        zastav("", ukazat = false)
        TelefonTitulky.odpojeno = true
        stopSelf()
    }

    /** Automatický režim: hlídá PS5 a připojuje se sám, dokud uživatel nedá Zastavit. */
    @Volatile private var automat = true
    @Volatile private var hledamPrave = false
    @Volatile private var hledamOd = 0L
    private var nenalezenaZaSebou = 0
    /** Kdy jsme poprvé viděli PS5 zapnutou (0 = je vypnutá). */
    private var psZapnutaOd = 0L
    /** Viděli jsme už PS5 vypnutou? Jen pak čekat na její rozběh (jinak připojit hned). */
    private var videnaVypnuta = false
    private var rozehrato = false
    /** Kolikrát po sobě se nepovedlo připojit – čekání se pak prodlužuje. */
    private var neuspechu = 0
    private var posledniHlaska = ""

    /** Hlídač: je PS5 zapnutá? → připojit. Jinak zkusit znovu za chvíli. */
    private val hlidac = Runnable { hlidej() }

    /** Kdy naposledy přišel obraz z PS5. */
    @Volatile private var posledniSnimekKdy = 0L

    /**
     * Některé televize (Philips aj.) obraz z PS5 dekódují, ale nedají ho jako surová data (YUV) –
     * po připojení pak "nechodí žádný obraz". Takové televizi se obraz bere přes grafický čip
     * (OpenGL) jako u telefonů. Zjistí se samo po prvním připojení a televize si to zapamatuje.
     */
    private val prefsObraz by lazy { getSharedPreferences("obraz", MODE_PRIVATE) }
    @Volatile private var obrazPresGl = false
    private var pripojenoKdy = 0L

    /** 15 s po připojení: přišel obraz? Když ne a ještě jsme nezkusili OpenGL → přepnout a připojit znovu. */
    private val kontrolaObrazu = Runnable {
        if (session == null || telefon || obrazPresGl) return@Runnable
        val ted = System.currentTimeMillis()
        val bezObrazu = posledniSnimekKdy <= pripojenoKdy || ted - posledniSnimekKdy > 6000
        if (bezObrazu) {
            obrazPresGl = true
            prefsObraz.edit().putBoolean("gl", true).apply()
            zapis("[televize nedává obraz (YUV) → přepínám na OpenGL a připojuji znovu]")
            zapniUsporu("obraz se zastavil")
            zastav("", ukazat = false)
            neuspechu = 0
            ui.removeCallbacks(hlidac)
            ui.postDelayed(hlidac, 8000)
        } else zapis("[obraz OK: YUV]")
    }

    /**
     * Hlídač "mrtvého" spojení: když televize nebo PS5 usnuly, spojení se často neukončí samo
     * (nepřijde žádná zpráva o konci) – aplikace si pak myslí, že je připojená, a nic nedělá.
     * Když 30 s nepřišel žádný obraz, spojení zahodit a zkusit se připojit znovu.
     */
    private val hlidacSpojeni: Runnable = object : Runnable {
        override fun run() {
            if (!aktivni || !automat) return
            val ted = System.currentTimeMillis()
            if (pripojeno() && posledniSnimekKdy > 0 && ted - posledniSnimekKdy > 30_000) {
                zapis("[žádný obraz ${(ted - posledniSnimekKdy) / 1000} s – spojení je mrtvé, připojuji znovu]")
                // s H.265 nepřišel ani jeden snímek → telefon ho asi neumí, příště H.264
                if (pouzitHevc && snimkuCelkem == 0) {
                    prefsObraz.edit().putBoolean("bezHevc", true).apply()
                    zapis("[H.265 nefunguje – přepínám na H.264]")
                }
                zapniUsporu("spojení padá")
                zastav("", ukazat = false)
                neuspechu = 0
                ui.removeCallbacks(hlidac)
                ui.postDelayed(hlidac, 3000)
            }
            ui.postDelayed(this, 5000)
        }
    }

    private fun hlidej() {
        // pojistka: hledání PS5 se zaseklo (víc než 15 s) → zkusit znovu
        if (hledamPrave && System.currentTimeMillis() - hledamOd > 15_000) {
            zapis("[hledání PS5 se zaseklo – zkouším znovu]")
            hledamPrave = false
        }
        if (!automat || pripojeno() || hledamPrave) return
        // překladač "rozehřát" hned (první překlad jinak trvá ~4 s)
        if (!rozehrato) {
            rozehrato = true
            prekladac.priprav(hotovo = { prekladac.preloz("Hello, friend.", {}, {}) }, chyba = {})
        }
        val udaje = RemotePlayUdaje(this)
        val ip = udaje.ipKonzole
        val xboxId = XboxUcet.konzole(this)?.first
        val maPs5 = udaje.sparovano && ip != null
        val chciXbox = preferXbox(this)
        if (!maPs5 && xboxId == null) {
            stav = "Televize ještě není spárovaná s PS5 (tlačítko 7)."
            ukazJednou(stav)
            return
        }
        hledamPrave = true
        hledamOd = System.currentTimeMillis()
        // pojistka: zpráva "odpoj se" se mohla ztratit → při každém pokusu poslat znovu
        if (chciPS5) Predavani.posli()
        Thread {
            // PS5 může po restartu routeru dostat jinou IP adresu → najít ji znovu a adresu si opravit
            val zapnuta = maPs5 && runCatching {
                val seznam = HledacPS.hledej(2000, primaIp = ip)
                val ps5 = seznam.firstOrNull { it.ip == ip } ?: seznam.firstOrNull { it.typ == "PS5" }
                if (ps5 != null && ps5.ip != ip) udaje.ipKonzole = ps5.ip
                ps5?.stav == "zapnutá"
            }.getOrDefault(false)
            // PS5 nesvítí → zeptat se Microsoftu, jestli je zapnutý Xbox (ne častěji než 1× za 20 s)
            val xboxZapnuty: String? =
                if ((!zapnuta || chciXbox) && xboxId != null && System.currentTimeMillis() - xboxKontrolaKdy > 10_000) {
                    xboxKontrolaKdy = System.currentTimeMillis()
                    val k = runCatching { XboxSpojeni.konzole(this).firstOrNull { it.id == xboxId } }
                        .onFailure { ui.post { zapis("[Xbox: ${it.message}]") } }.getOrNull()
                    if (k != null && k.stav != xboxPosledniStav) { xboxPosledniStav = k.stav; ui.post { zapis("[Xbox: ${k.stav}]") } }
                    // probuzení Xboxu: kdy jsme ho poprvé viděli zapnutý po spánku / vypnutí
                    if (k != null && k.stav != "On") { xboxVidenVypnuty = true; xboxZapnutOd = 0L }
                    if (k?.stav == "On" && xboxZapnutOd == 0L) xboxZapnutOd = System.currentTimeMillis()
                    if (k?.stav == "On") xboxId else null
                } else null
            ui.post {
                hledamPrave = false
                if (!automat || pripojeno()) return@post
                // Xbox je zapnutý a (PS5 nesvítí, nebo uživatel chce Xbox) → hrát na Xboxu
                if (xboxZapnuty != null && (!zapnuta || chciXbox)) {
                    // Xbox se právě probudil → chvíli počkat, ať se zapne hráčův ovladač a hra si ho vezme
                    // (jinak si ho na chvíli „půjčí“ náš virtuální a hra pak hlásí „reconnect the controller“)
                    val ceka = ROZBEH_XBOX_MS - (System.currentTimeMillis() - xboxZapnutOd)
                    if (xboxVidenVypnuty && xboxZapnutOd > 0 && ceka > 0) {
                        stav = "Xbox se zapíná, chvilku počkám…"
                        ui.postDelayed(hlidac, minOf(ceka, 10_000L))
                        return@post
                    }
                    xboxVidenVypnuty = false
                    spustXbox(xboxZapnuty); return@post
                }
                val ted = System.currentTimeMillis()
                if (zapnuta && psZapnutaOd == 0L) psZapnutaOd = ted
                if (!zapnuta) { psZapnutaOd = 0L; videnaVypnuta = true }
                // čekat jen když se PS5 právě teď zapnula (předtím jsme ji viděli vypnutou)
                if (zapnuta && videnaVypnuta && ted - psZapnutaOd < ROZBEH_PS5_MS) {
                    // PS5 se právě zapnula – Remote Play na ní ještě nenaběhl, chvíli počkat
                    stav = "PS5 se zapíná, chvilku počkám…"
                    ui.postDelayed(hlidac, 5_000L)
                } else if (zapnuta) {
                    videnaVypnuta = false
                    spust()
                } else {
                    // uživatel chce hrát (Hotovo – hrát), ale PS5 v síti „není vidět“ – některé sítě
                    // hledání nepropustí, i když PS5 běží → každý 3. pokus se připojit rovnou na známou adresu
                    nenalezenaZaSebou++
                    if (maPs5 && chciPS5 && nenalezenaZaSebou % 3 == 0) {
                        zapis("[PS5 není v síti vidět – zkouším se připojit přímo]")
                        spust()
                    } else {
                        stav = if (maPs5) "Čekám na zapnutí PS5…" else "Čekám na zapnutí Xboxu…" // potichu
                        ui.postDelayed(hlidac, CEKANI_NA_PS5_MS)
                    }
                }
                if (zapnuta) nenalezenaZaSebou = 0
            }
        }.apply { isDaemon = true; name = "hledani-ps5" }.start()
    }

    /** Informační hláška – jen když je na televizi vstup HDMI (ne přes Disney+, YouTube…). */
    private fun ukazInfo(text: String, doba: Long) {
        // malá hláška nahoře uprostřed, ať nepřekáží hře ani titulkům
        if (runCatching { popredi.jeVstupTv() }.getOrDefault(true))
            ukaz(text, doba, floatArrayOf(0.4f, 0.025f, 0.6f, 0.06f), 1)
    }

    /** Hlášku ukáže jen jednou (ne každých 20 s, když PS5 pořád spí). */
    private fun ukazJednou(text: String) {
        if (text == posledniHlaska) return
        posledniHlaska = text
        ukaz(text)
    }

    /** Po výpadku spojení počkat (PS5 si potřebuje uklidit) a pak zase hlídat. */
    private fun znovuPozdeji(rychle: Boolean = false) {
        if (!automat) { stopSelf(); return }
        posledniHlaska = ""
        ui.removeCallbacks(hlidac)
        // poprvé zkusit brzo, při dalších neúspěších čekat déle (PS5 si musí uklidit staré spojení)
        neuspechu++
        // PS5 po odpojení drží staré spojení ~1–2 min ("already in use") → zkoušet často
        val cekat = if (rychle && neuspechu <= 30) 5_000L
            else when (neuspechu) { 1 -> 10_000L; 2, 3, 4 -> 15_000L; in 5..8 -> 20_000L; else -> PO_VYPADKU_MS }
        ui.postDelayed(hlidac, cekat)
    }

    private fun spust() {
        val udaje = RemotePlayUdaje(this)
        val ip = udaje.ipKonzole
        val registKey = udaje.registKey
        val rpKey = udaje.rpKey
        if (ip == null || registKey == null || rpKey == null) {
            ukaz("Remote Play: televize ještě není spárovaná s PS5 (tlačítko 7).")
            return
        }
        posledniHlaska = ""
        snimkuCelkem = 0
        naXboxu = false
        bezi = true
        logDelka = -1L; poskozenoKdy = 0L
        stav = "Připojuji k PS5 ($ip)…"
        zapis("[připojuji k PS5 $ip]")
        drzWifi(true)
        posledniSnimekKdy = System.currentTimeMillis() // hlídač spojení počítá od začátku připojování
        // zahřát čtečku a spojení s překladačem už teď – připojení k PS5 trvá ~6 s,
        // takže je všechno připravené dřív, než přijde první obraz
        ui.post { zahrejCtecku(predPripojenim = true) }
        runCatching { prekladac.udrzujSpojeni() }
        // hláška "spouští se" se neukazuje – rušila; stačí krátké "aktivní" po připojení

        // Snímky z dekodéru přistanou sem (neukazují se nikde na obrazovce).
        // Televize: surový obraz YUV přes ImageReader.
        // Telefony (Xiaomi aj.) do YUV dekódovat neumí → obraz přes grafický čip (GlSnimac).
        snimac?.zavri(); snimac = null
        val plocha: android.view.Surface
        obrazPresGl = obrazPresGl || prefsObraz.getBoolean("gl", false)
        if (telefon || obrazPresGl) {
            zapis(if (telefon) "[obraz: OpenGL (telefon)]" else "[obraz: OpenGL (televize)]")
            val g = GlSnimac(sirkaStreamu, vyskaStreamu) { gl ->
                try {
                    val volna = volnaCteckaProSnimek()
                    if (volna != null) zpracuj(volna, sirkaStreamu.toFloat(), vyskaStreamu.toFloat()) { vyrezZGl(gl) }
                } catch (e: Throwable) { chybaObrazu(e) }
                // volby odpovědí (žlutý rámeček) – vlastní čtečka, titulky nijak nebrzdí
                if (Volby.zapnuto) runCatching { ctiVolby(gl) }.onFailure { zapis("[CHYBA volby: ${it.message}]") }
            }
            plocha = g.start()
            snimac = g
        } else {
            val r = ImageReader.newInstance(SIRKA, VYSKA, ImageFormat.YUV_420_888, 3)
            reader = r
            r.setOnImageAvailableListener({ ir ->
                try {
                    ir.acquireLatestImage()?.let { img ->
                        val volna = volnaCteckaProSnimek()
                        if (volna != null) zpracuj(img, volna) else img.close()
                    }
                } catch (e: Throwable) { chybaObrazu(e) }
            }, bg)
            plocha = r.surface
        }

        // 720p / 30 fps / nízký datový tok – obraz nikdo nevidí, stačí čitelné titulky;
        // méně dat = méně práce pro procesor televize a stabilnější Wi-Fi
        // telefon bývá na slabší Wi-Fi → méně dat, ať se obraz po cestě neztrácí
        val tok = if (telefon) DATOVY_TOK_TELEFON else if (uspora) DATOVY_TOK_USPORA else DATOVY_TOK
        // telefon: H.265 (HEVC) – ze stejného množství dat čistší obraz a ostřejší písmo.
        // Jen když ho telefon umí rozbalit svým čipem; jinak (a vždy na televizi) H.264 jako dřív.
        val hevc = telefon && !prefsObraz.getBoolean("bezHevc", false) && umiHevc()
        pouzitHevc = hevc
        zapis(if (hevc) "[kodek: H.265]" else "[kodek: H.264]")
        val profil = ConnectVideoProfile(sirkaStreamu, vyskaStreamu, 30, tok, if (hevc) Codec.CODEC_H265 else Codec.CODEC_H264)
        val info = ConnectInfo(ps5 = true, host = ip, registKey = registKey, morning = rpKey, videoProfile = profil)
        try {
            val logSoubor = File(filesDir, "chiaki.log").absolutePath
            // staré spojení, které se neukončilo (zhasnutí, výpadek Wi-Fi…), by drželo PS5 obsazenou
            // ("already in use") → před novým připojením všechna stará ukončit
            session = null
            ukonciStareSpojeni()
            val s = Session(info, logSoubor, false)
            synchronized(vsechnaSpojeni) { vsechnaSpojeni += s }
            s.eventCallback = { udalost -> ui.post { onUdalost(udalost) } }
            // telefon: obraz přímo z dekodéru do paměti (jako u Xboxu) – musí se zapnout před setSurface;
            // když to dekodér neumí, jde obraz sám dál přes OpenGL (GlSnimac)
            pametZapsano = false
            if (telefon && !prefsObraz.getBoolean("bezPameti", false)) {
                PametObraz.priSnimku = { f -> snimekPamet(f) }
                if (!PametObraz.nastav(true, sirkaStreamu, vyskaStreamu)) PametObraz.priSnimku = null
            }
            s.start()
            s.setSurface(plocha)
            session = s
        } catch (e: CreateError) {
            zapis("[CHYBA spuštění: ${e.errorCode}]")
            zastav("Remote Play nešlo spustit ✘ (${e.errorCode})")
            znovuPozdeji()
        }
    }

    private var dalsiCteni = 0L
    @Volatile private var pouzitHevc = false

    /** Umí telefon rozbalit H.265 svým čipem (ne pomalu procesorem) v rozlišení streamu? */
    private fun umiHevc(): Boolean = runCatching {
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            if (info.isEncoder || info.supportedTypes.none { it.equals("video/hevc", true) }) return@any false
            val jmeno = info.name.lowercase()
            val hardware = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
                else !(jmeno.startsWith("omx.google.") || jmeno.startsWith("c2.android."))
            hardware && runCatching {
                info.getCapabilitiesForType("video/hevc").videoCapabilities.isSizeSupported(sirkaStreamu, vyskaStreamu)
            }.getOrDefault(false)
        }
    }.getOrDefault(false)

    private var snimac: GlSnimac? = null

    /** Rozhodne, jestli tenhle snímek číst; vrátí volnou čtečku, nebo null. */
    private var logDelka = -1L
    private var logKontrola = 0L
    @Volatile private var poskozenoKdy = 0L
    @Volatile private var poskozenoOd = 0L
    @Volatile private var stPoskozeno = 0

    /**
     * PS5: Chiaki zapisuje do chiaki.log, když po Wi-Fi přišel poškozený snímek ("corrupt frame").
     * Pak je obraz ~0,5 s kostičkovaný a čtečka by četla nesmysly ("et Davey", "Ineed") →
     * na 0,7 s přestat číst, než PS5 pošle čistý obraz. (Xbox poškozené snímky nepouští.)
     */
    private fun obrazPoskozen(ted: Long): Boolean {
        if (ted - logKontrola >= 100) {
            logKontrola = ted
            runCatching {
                val f = File(filesDir, "chiaki.log")
                val delka = f.length()
                if (logDelka < 0 || delka < logDelka) logDelka = delka
                else if (delka > logDelka) {
                    val novy = java.io.RandomAccessFile(f, "r").use { raf ->
                        raf.seek(maxOf(logDelka, delka - 65536))
                        val b = ByteArray((delka - raf.filePointer).toInt())
                        raf.readFully(b)
                        String(b, Charsets.ISO_8859_1)
                    }
                    logDelka = delka
                    if (novy.contains("corrupt")) {
                        if (ted - poskozenoKdy >= 700) poskozenoOd = ted // nová série poškození
                        poskozenoKdy = ted; stPoskozeno++
                    }
                }
            }
        }
        // pojistka: při hodně špatné Wi-Fi (poškozeno pořád dokola) nečekat déle než 2 s – radši číst
        return ted - poskozenoKdy < 700 && ted - poskozenoOd < 2000
    }

    private fun volnaCteckaProSnimek(): Int? {
        snimkuCelkem++
        val ted = System.currentTimeMillis()
        posledniSnimekKdy = ted
        // PS5: poškozený obraz (kostičky) nečíst
        if (xbox == null && obrazPoskozen(ted)) return null
        // pojistka: kdyby se čtení někde zaseklo, po 5 s čtečku uvolnit
        // (slabá televize čte i přes 5 s – pak čtečku neuvolňovat hned, jinak by běžela dvě čtení
        //  naráz, televize by se zahltila a systém by aplikaci vypnul → zapnout úsporný režim)
        for (i in obsazeno.indices) {
            if (!obsazeno[i].get()) continue
            val trva = ted - obsazenoOd[i]
            // jedno čtení (i rozehřátí) přes 5 s = slabá televize → úsporný režim.
            // Čtečku přitom NEuvolňovat: běžela by dvě čtení naráz, televize by se zahltila
            // a systém by aplikaci vypnul (= pořád dokola "připojuji").
            if (trva > 5000 && !uspora && !telefon)
                zapniUsporu(if (zahrivani) "rozehřátí trvá přes 5 s" else "čtení trvá přes 5 s")
            val limit = if (uspora || zahrivani) 20000 else 5000
            if (trva > limit) {
                zahrivani = false
                zapis("[čtení se zaseklo – znovu]")
                obsazeno[i].set(false)
            }
        }
        val volna = obsazeno.indices.firstOrNull { !obsazeno[it].get() } ?: return null
        if (ted < dalsiCteni) return null
        // telefon je výkonný → dívat se častěji (nový titulek chytí dřív); televize zůstává na 250 ms
        dalsiCteni = ted + (if (telefon) INTERVAL_MS_TELEFON else if (uspora) INTERVAL_MS_USPORA else INTERVAL_MS)
        val p = popredi.balicek()
        if (p != posledniPopredi) { posledniPopredi = p; zapis("[popředí: $p]") }
        // překlad pozastavený, nebo je na televizi jiná aplikace → nečíst, schovat
        if (pozastaveno || !popredi.jeVstupTv()) {
            if (prectenoPosledne.isNotEmpty()) { prectenoPosledne = ""; ukaz("", 0) }
            return null
        }
        return volna
    }

    /** Chyba při čtení obrazu → nespadnout, jen zapsat (jednou). */
    private fun chybaObrazu(e: Throwable) {
        if (chybaObrazu) return
        chybaObrazu = true
        val zprava = "[CHYBA obrazu: ${e.javaClass.simpleName}: ${e.message}]"
        ui.post { zapis(zprava); stav = "Chyba obrazu ✘ ${e.javaClass.simpleName}" }
    }

    /** Vezme snímek, vyřízne spodní část (titulky), přečte, přeloží, ukáže. */
    private fun zpracuj(img: android.media.Image, c: Int) =
        zpracuj(c, img.width.toFloat(), img.height.toFloat()) {
            try { vyrizni(img) } finally { img.close() }
        }

    private fun zpracuj(c: Int, sirkaObrazu: Float, vyskaObrazu: Float, ziskejVyrez: () -> Vyrez) {
        obsazeno[c].set(true)
        val zacatek = System.currentTimeMillis()
        obsazenoOd[c] = zacatek
        val cislo = poradi.incrementAndGet()
        val ctecka = ctecky[c]
        val rucni = rucniOblast()
        // jiná oblast než minule (zapnul/vypnul/posunul modrý rámeček) → zapomenout naučené pruhy
        val klic = rucni?.joinToString(",") ?: "auto"
        if (klic != oblastKlic) {
            oblastKlic = klic
            synchronized(dialogy) { dialogy.clear() }
            synchronized(napovedy) { napovedy.clear() }
            posledniOtisk = null; otiskMinulehoSnimku = null
        }
        val odX = if (rucni != null) (sirkaObrazu * rucni[0]).toInt().and(1.inv()).toFloat() else sirkaObrazu * (1 - STRED_SIRKA) / 2
        val odY = if (rucni != null) (vyskaObrazu * rucni[1]).toInt().and(1.inv()).toFloat() else vyskaObrazu * (1 - SPODNI_CAST)
        val vyrez = try { ziskejVyrez() } catch (e: Throwable) { obsazeno[c].set(false); throw e }
        // na obrazovce se nic nezměnilo (pořád stejný titulek) → nečíst znovu,
        // čtečka je pak volná a nový titulek přečte hned, jak se objeví
        val otisk = otisk(vyrez)
        val stary = posledniOtisk
        statistika(zacatek)
        // (když minule nebylo nic, kontrolovat stejnou obrazovku méně často → čtečka je volná pro nový titulek)
        if (stary != null && zacatek - posledniCteniKdy < 3000 && stejny(stary, otisk)) {
            stSkipStejne++
            // obraz se nezměnil → minulý titulek (když nějaký byl) pořád visí
            if (!posledniPrazdne) predchoziVidenKdy = zacatek
            obsazeno[c].set(false)
            return
        }
        // titulek se právě objevuje (stmívání) → počkat na další snímek, ať se nečte napůl
        // (čtení trvá ~1 s, přečíst polovičatý titulek by znamenalo sekundu ztratit)
        val minuly = otiskMinulehoSnimku
        otiskMinulehoSnimku = otisk
        if (minuly != null && !stejny(minuly, otisk)) {
            if (zmenaOd == 0L) zmenaOd = zacatek
            if (zacatek - zmenaOd < 200) { stSkipVynoreni++; obsazeno[c].set(false); return }
        }
        zmenaOd = 0L
        stCteni++
        posledniOtisk = otisk
        posledniCteniKdy = zacatek
        // jen pás, kde něco je (bílé písmo), a zmenšený → čtení je několikrát rychlejší
        // když se pás s písmem nenajde, čte se celá oblast (jen zmenšená) – ať se nikdy nic neztratí
        var uzkyPas = false
        val jas = prumernyJas(vyrez)
        // na obrazovce visí nápověda ("Hold ✕ to match speed…") → číst rovnou jen oblast NAD ní
        // (krátké repliky nad nápovědou jinak zmizí dřív, než proběhne druhý pokus);
        // každé 4. čtení je normální, aby se poznalo, že nápověda zmizela
        val hore = napovedaHore
        val nadNapovedou = CIST_NAD_NAPOVEDOU && hore > 30 && zacatek - napovedaKdy < 5000 && (cteniNad++ % 4 != 3)
        val pas = if (nadNapovedou) {
            try { pasNadNapovedou(vyrez, hore - 3, jas > 110) } catch (e: Throwable) { celaOblast(vyrez) }
        } else try {
            val omezeni = naucenyPruh(vyrez.h)
            pasSTextem(vyrez, omezeni)?.also { uzkyPas = true } ?: if (omezeni != null) prazdnyPas(vyrez) else celaOblast(vyrez)
        } catch (e: Throwable) {
            zapis("[CHYBA pás: ${e.message}]")
            celaOblast(vyrez)
        }
        // světlá scéna (mlha, sníh): bílý titulek splývá s pozadím → když se dialog nenajde,
        // zkusit to ještě jednou s vyšším kontrastem
        val svetla = jas > 140
        if (nadNapovedou) stNad++
        cti(c, cislo, zacatek, vyrez, pas, svetla, druhyPokus = nadNapovedou, odX, odY, sirkaObrazu, vyskaObrazu, ctecka, uzkyPas, jas, nadNapovedou)
    }

    private fun cti(
        c: Int, cislo: Long, zacatek: Long, vyrez: Vyrez, pas: Pas, svetla: Boolean, druhyPokus: Boolean,
        odX: Float, odY: Float, sirkaObrazu: Float, vyskaObrazu: Float, ctecka: Ctecka,
        uzkyPas: Boolean = false, jas: Int = 0, nadNapovedou: Boolean = false
    ) {
        // kde dřív byla nápověda (ve výřezu) → přepočítat do souřadnic čteného pásu
        val pasmaNapovedy = synchronized(napovedy) { napovedy.toList() }.map { (t, b) ->
            ((t - pas.nahore) * pas.meritko).toInt()..((b - pas.nahore) * pas.meritko).toInt()
        }
        ctecka.prectiPosledniTitulek(pas.bitmapa, pasmaNapovedy = pasmaNapovedy,
            hotovo = { surovy ->
                // Dialog nenalezen, ale je tu nápověda (RDR2: "Hold ⓧ to continue following") nebo je scéna světlá:
                // dialog nad nápovědou se ve sněhu/mlze často vůbec nenajde (čte se jen pruh s nápovědou)
                // → přečíst ještě jednou celou oblast titulků, ve světlejší scéně s vytaženým kontrastem
                // (jen kvůli nápovědě: televize má jedinou čtečku – každý druhý pokus ji zabere na další ~1 s
                // a nový titulek se mezitím nepřečte; čistě světlá scéna bez nápovědy proto druhý pokus nedělá)
                if (!druhyPokus && !uspora && surovy.repliky.isEmpty() && surovy.napoveda != null && (!CIST_NAD_NAPOVEDOU || zacatek - napovedaKdy >= 5000)) {
                    stKontrast++
                    val znovu = try { if (jas > 110) kontrastniPas(vyrez) else if (uzkyPas) celaOblast(vyrez) else null } catch (e: Throwable) { null }
                    if (znovu != null) {
                        cti(c, cislo, zacatek, vyrez, znovu, svetla, druhyPokus = true, odX, odY, sirkaObrazu, vyskaObrazu, ctecka, jas = jas)
                        return@prectiPosledniTitulek
                    }
                }
                obsazeno[c].set(false)
                // druhá čtečka mezitím přečetla novější snímek → tenhle výsledek je zastaralý
                if (cislo < posledniVysledek) return@prectiPosledniTitulek
                posledniVysledek = cislo
                // zkomolený text (rozmazaný snímek, slabá Wi-Fi) → neukazovat ho a hned přečíst další snímek,
                // ten bývá ostřejší. Dosavadní český titulek zůstává. Když je zkomolené i ~6 čtení po sobě,
                // pokračuje se jako by nic nebylo (titulek zmizí) – lepší nic než nesmysl.
                if (ctecka.nesmysl) {
                    nesmysluZaSebou++
                    zapis(ctecka.diagnostika)
                    posledniOtisk = null
                    if (nesmysluZaSebou < 6) return@prectiPosledniTitulek
                } else nesmysluZaSebou = 0
                // souřadnice ze zmenšeného pásu zpět do výřezu
                val titulek = Ctecka.Titulek(surovy.repliky, surovy.box?.let { pas.zpet(it) },
                    surovy.radku, surovy.jmenaBoxy.map { r -> r?.let { pas.zpet(it) } })
                val text = titulek.text
                // spodní nápověda / úkol mise → zvlášť, žlutě
                // jistá nápověda ("Hold … to …") → zapamatovat si její výšku
                val boxNapovedy = surovy.napovedaBox?.let { pas.zpet(it) }
                if (!nadNapovedou && boxNapovedy != null && surovy.napoveda != null && Ctecka.NAPOVEDA.containsMatchIn(surovy.napoveda)) {
                    synchronized(napovedy) {
                        napovedy.addLast(boxNapovedy.top to boxNapovedy.bottom)
                        while (napovedy.size > 10) napovedy.removeFirst()
                    }
                    napovedaHore = boxNapovedy.top
                    napovedaKdy = System.currentTimeMillis()
                }
                // při čtení jen nad nápovědou se nápověda nevidí → nechat ji, jak je
                if (!nadNapovedou) zpracujNapovedu(surovy.napoveda, boxNapovedy?.let { b ->
                    floatArrayOf(
                        (odX + b.left) / sirkaObrazu, (odY + b.top) / vyskaObrazu,
                        (odX + b.right) / sirkaObrazu, (odY + b.bottom) / vyskaObrazu
                    )
                })
                // kde je anglický titulek na celé obrazovce (0..1) – obraz z Remote Play = obraz na TV
                val box = titulek.box?.let { b ->
                    floatArrayOf(
                        (odX + b.left) / sirkaObrazu, (odY + b.top) / vyskaObrazu,
                        (odX + b.right) / sirkaObrazu, (odY + b.bottom) / vyskaObrazu
                    )
                }
                casCteni = System.currentTimeMillis() - zacatek
                // 3× po sobě čtení přes 2,5 s → slabá televize
                if (!druhyPokus && !telefon) {
                    pomalaCteni = if (casCteni > 2000) pomalaCteni + 1 else 0
                    if (pomalaCteni >= 1) {
                        zapniUsporu("pomalé čtení (${casCteni} ms)")
                        runCatching { getSharedPreferences("uspora", MODE_PRIVATE).edit().putBoolean("rychla", false).apply() }
                    }
                    // v úsporném režimu, ale čte rychle (10× po sobě pod 1,5 s) → televize není slabá,
                    // příště úsporný režim nezapínat hned od začátku
                    rychlaCteni = if (casCteni < 1500) rychlaCteni + 1 else 0
                    if (rychlaCteni == 10) runCatching {
                        // televize stíhá → příště bez úsporného režimu a bez upozornění „slabý výkon“
                        getSharedPreferences("uspora", MODE_PRIVATE).edit()
                            .putBoolean("slaba", false).putBoolean("varovat", false).putBoolean("rychla", true).apply()
                        zapis("[televize čte rychle – bez úsporného režimu a upozornění]")
                    }
                }
                zapis(ctecka.diagnostika)
                // nic se nenašlo – zapsat, ať je v záznamu vidět, že se četlo (a jak světlá byla scéna)
                posledniPrazdne = text.length < 4 && surovy.napoveda == null
                if (text.length < 4 && surovy.napoveda == null)
                    zapis("  [nic nepřečteno: jas $jas, ${casCteni} ms" +
                        (if (nadNapovedou) ", nad nápovědou" else if (druhyPokus) ", 2. pokus" else "") + "]")
                if (text.length < 4) {
                    prazdnychZaSebou++
                    if (prazdnychZaSebou >= 1 && prectenoPosledne.isNotEmpty()) {
                        prectenoPosledne = ""
                        schovejCesky() // titulek zmizel → schovat i český (až si ho stihneš přečíst)
                    }
                } else if ((!vypadaJakoVeta(text) && !(titulek.box != null && jeNaMisteDialogu(titulek.box))) || jeStaticky(text)) {
                    // nápověda k ovládání, menu, název úkolu… – nepřekládat
                    zapis("  → přeskočeno (" + (if (jeStaticky(text)) "visí dlouho" else "nevypadá jako věta") + ")")
                    // mezi dvěma titulky bylo něco jiného ("Jenny…") → další titulek už není pokračování
                    if (!podobne(text, prectenoPosledne)) predchoziAnglicky = null
                    // na obrazovce už je něco jiného → starý český titulek hned schovat
                    if (prectenoPosledne.isNotEmpty() && !podobne(text, prectenoPosledne)) {
                        prectenoPosledne = ""
                        schovejCesky()
                    }
                } else {
                    prazdnychZaSebou = 0
                    val ted = System.currentTimeMillis()
                    if (podobne(text, prectenoPosledne)) {
                        predchoziVidenKdy = ted // titulek pořád visí
                        // titulek se přečetl ještě při vynořování bez konce ("…they get her") a teď je celý
                        // ("…they get here...") → přeložit znovu, ať se nepřekládá useknutá věta
                        val konce = setOf('.', '!', '?', '…')
                        val docteno = text.length > prectenoPosledne.length && ted - prvniVidenoKdy < 3000 &&
                            text.trimEnd().lastOrNull() in konce && prectenoPosledne.trimEnd().lastOrNull() !in konce
                        if (docteno) {
                            zapis("  [titulek dočten – překládám celý]")
                            prectenoPosledne = text
                            zacatekPrekladu = ted
                            val barvy = titulek.repliky.indices.map { i ->
                                titulek.jmenaBoxy.getOrNull(i)?.let { vyrez.barvaTextu(it) } ?: 0
                            }
                            prelozAUkaz(titulek.repliky, box, titulek.radku, barvy.toIntArray(), cislo, oprava = true)
                        } else
                        // stejný text visí na obrazovce moc dlouho → není to replika, ale něco statického
                        if (ted - prvniVidenoKdy > MAX_DELKA_TITULKU_MS) {
                            statickeTexty += klic(text)
                            zapis("  → od teď ignorováno (na obrazovce přes 15 s)")
                            prectenoPosledne = ""
                            ukaz("", 0)
                        }
                        } else {
                        // nový titulek, ale některé slovo vypadá zkomoleně ("vQur primery … wenpon")
                        // → ještě nepřekládat, přečíst další snímek (bývá ostřejší). Nejvýš 2× – pak se
                        // přeloží to, co máme (překladač si s jedním zkomoleným slovem většinou poradí).
                        if (Ctecka.spatnychSlov(text) > 0 && nejistychZaSebou < 2) {
                            nejistychZaSebou++
                            zapis("  [nejisté čtení – zkusím ostřejší snímek]")
                            posledniOtisk = null
                            return@prectiPosledniTitulek
                        }
                        nejistychZaSebou = 0
                        // titulek se teprve vynořuje a začátek je ještě průhledný ("et Davey rest…" místo
                        // "Let Davey rest…") → začíná malým písmenem, ale nemůže to být pokračování věty
                        // → chvilku počkat a přečíst znovu (nejvýš ~0,4 s)
                        val pokracovaniMozne = predchoziAnglicky != null && ted - predchoziVidenKdy < 2500
                        if (text.trimStart().firstOrNull()?.isLowerCase() == true && !pokracovaniMozne && useknutychZaSebou < 4) {
                            useknutychZaSebou++
                            if (useknutychZaSebou == 1) zapis("  [začátek titulku se teprve vynořuje – čtu znovu]")
                            posledniOtisk = null
                            return@prectiPosledniTitulek
                        }
                        useknutychZaSebou = 0
                        prectenoPosledne = text
                        prvniVidenoKdy = ted
                        // nový titulek mohl být přečtený ještě při vynořování ("Watch nui here.") →
                        // za ~0,6 s ho přečíst znovu; když se liší, přeloží se opravená verze
                        posledniCteniKdy = ted - 2400
                        zacatekPrekladu = ted
                        val barvy = titulek.repliky.indices.map { i ->
                            titulek.jmenaBoxy.getOrNull(i)?.let { vyrez.barvaTextu(it) } ?: 0
                        }
                        // věta se jménem nebo s tečkou/otazníkem = jistý dialog → zapamatovat, kde byl
                        if (titulek.box != null && (text.contains(':') || text.trimEnd().lastOrNull() in setOf('.', '!', '?', '…')))
                            zapamatujDialog(titulek.box)
                        prelozAUkaz(titulek.repliky, box, titulek.radku, barvy.toIntArray(), cislo)
                    }
                }
            },
            chyba = { e -> obsazeno[c].set(false); zapis("  [chyba čtení: $e]") })
    }

    /** Jednou za minutu "ťukne" na Google, aby spojení zůstalo otevřené a překlad byl rychlý. */
    @Volatile private var posledniPrazdne = false

    /** Po připojení 3× přečíst malý obrázek s textem – první skutečné čtení pak netrvá 3 s. */
    private fun zahrejCtecku(predPripojenim: Boolean = false) {
        if (uspora) return // slabá televize: rozehřívání by ji zahltilo
        val c = 0
        if ((!predPripojenim && !pripojeno()) || !obsazeno[c].compareAndSet(false, true)) return
        obsazenoOd[c] = System.currentTimeMillis()
        val bmp = runCatching {
            Bitmap.createBitmap(480, 60, Bitmap.Config.ARGB_8888).also { b ->
                val cv = android.graphics.Canvas(b)
                cv.drawColor(android.graphics.Color.BLACK)
                val p = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE; textSize = 32f; isAntiAlias = true
                }
                cv.drawText("Warming up, partner.", 10f, 42f, p)
            }
        }.getOrNull()
        if (bmp == null) { obsazeno[c].set(false); return }
        zahrivani = true
        var kolikrat = 0
        fun jednou() {
            obsazenoOd[c] = System.currentTimeMillis()
            runCatching {
                ctecky[c].prectiPosledniTitulek(bmp,
                    hotovo = {
                        kolikrat++
                        if (kolikrat < 3 && !uspora && (predPripojenim || pripojeno())) jednou() else { zahrivani = false; obsazeno[c].set(false) }
                    },
                    chyba = { zahrivani = false; obsazeno[c].set(false) })
            }.onFailure { zahrivani = false; obsazeno[c].set(false) }
        }
        jednou()
    }

    private val ohrivac: Runnable = object : Runnable {
        override fun run() {
            if (!pripojeno()) return
            prekladac.udrzujSpojeni()
            ui.postDelayed(this, 60_000)
        }
    }

    private var posledniNapoveda = ""
    private var bezNapovedy = 0

    /** Nápověda / úkol mise: přeložit jednou, ukázat žlutě, schovat až zmizí. */
    private fun zpracujNapovedu(text: String?, box: FloatArray?) {
        if (!nast.napovedy) return
        if (text.isNullOrBlank() || box == null) {
            bezNapovedy++
            if (bezNapovedy == 3 && posledniNapoveda.isNotEmpty()) {
                posledniNapoveda = ""
                ukazNapovedu("", null)
            }
            return
        }
        bezNapovedy = 0
        if (podobne(text, posledniNapoveda)) return
        posledniNapoveda = text
        val (cista, tlacitko) = tlacitko(text)
        prekladac.preloz(cista,
            hotovo = { preklad ->
                if (posledniNapoveda != text) return@preloz
                val cesky = opravJmena(cista, preklad)
                zapis("  → NÁPOVĚDA: $cesky" + (tlacitko?.let { " [$it]" } ?: ""))
                ukazNapovedu(if (tlacitko != null) "$tlacitko  $cesky" else cesky, box)
            },
            chyba = {})
    }

    /**
     * Najde v nápovědě tlačítko ovladače. Televize ho čte jako písmeno přilepené ke slovesu
     * ("HoldD near…", "UseL to…") – vrátí text bez něj a značku tlačítka (□ ○ ✕ △ L R).
     */
    private fun tlacitko(text: String): Pair<String, String?> {
        val m = Regex("(?i)^(hold|press|use|tap|toggle|select)(\\S{0,2})\\s+(\\S{1,2}\\s+)?(.*)$").find(text.trim())
            ?: return text to null
        val sloveso = m.groupValues[1]
        val prilepene = m.groupValues[2]
        val samostatne = m.groupValues[3].trim()
        val zbytek = m.groupValues[4]
        // samostatný "token" bereme jako tlačítko jen když to není běžné slovo
        val kandidat = when {
            prilepene.isNotEmpty() -> prilepene
            samostatne.isNotEmpty() && samostatne.lowercase() !in setOf("to", "a", "an", "on", "up", "it", "in") -> samostatne
            else -> ""
        }
        val znak = when (kandidat.firstOrNull()) {
            null -> null
            'D', 'd', '□', '[' -> "□"
            'O', 'o', '0', '○', 'Q', '©' -> "○"
            'X', 'x', '✕', '×' -> "✕"
            'A', '△', '^' -> "△"
            'L', 'l', 'i', 'I', '1', '|' -> "L"
            'R', 'r' -> "R"
            else -> "🎮"
        }
        if (znak == null) return text to null
        val zbytekTextu = if (prilepene.isEmpty() && kandidat.isNotEmpty()) zbytek else (samostatne + " " + zbytek).trim()
        return "$sloveso $zbytekTextu".trim() to "[$znak]"
    }

    private fun ukazNapovedu(text: String, box: FloatArray?) {
        if (telefon) { TelefonTitulky.napoveda = text; return }
        val i = Intent(this, OverlayService::class.java)
            .setAction(OverlayService.ACTION_SET_NAPOVEDA)
            .putExtra(OverlayService.EXTRA_TEXT, text)
        if (box != null) i.putExtra(OverlayService.EXTRA_BOX, box)
        runCatching { startService(i) }
    }

    private var posledniZapis = ""

    /** Záznam, co aplikace četla a co s tím udělala → files/titulky.log (jen změny). */
    private fun zapis(radek: String) {
        if (radek.isBlank() || radek == posledniZapis) return
        posledniZapis = radek
        runCatching {
            val f = File(filesDir, "titulky.log")
            if (f.length() > 300_000) f.writeText("")
            val cas = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            f.appendText("$cas $radek\n")
        }
    }

    /** Přeloží každou repliku zvlášť (jméno postavy nechá) a ukáže je pod sebou. */
    /** Poslední anglický titulek (pro spojení věty rozdělené hrou na dva titulky). */
    private var predchoziAnglicky: String? = null
    private var predchoziKdy = 0L
    /** Kdy byl předchozí titulek naposledy vidět na obrazovce (pokračování věty přichází hned po něm). */
    @Volatile private var predchoziVidenKdy = 0L

    /** Titulek před tím posledním (pro opravu dočteného titulku) a jestli se poslední spojoval. */
    private var predPred: String? = null
    private var posledniSpojeno = false

    private fun prelozAUkaz(surove: List<String>, box: FloatArray?, radku: Int, barvyJmen: IntArray, cislo: Long, oprava: Boolean = false) {
        if (!nast.dialogy) return
        // číslice nalepená před slovem ("3Miss Grimshaw..") je smetí ze čtení
        val puvodni = surove.map { Regex("^[0-9](?=[A-Z][a-z])").replace(it, "") }
        // Hra (např. RDR2) dělí dlouhou větu na dva titulky: "we need to" → "get that fire lit quick."
        // Druhý kousek začíná malým písmenem → přeložit ho dohromady s předchozím (zatím jen telefon).
        var repliky = puvodni
        val ted = System.currentTimeMillis()
        // oprava = stejný titulek dočtený celý ("…they get her" → "…they get here...") – spojit stejně jako napoprvé
        if (!oprava) predPred = predchoziAnglicky
        val pred = predPred
        val nova = puvodni.singleOrNull()
        // Taky "well we're gonna be..." → "gone." (předchozí končí třemi tečkami) – i v televizi.
        val predKonec = pred?.trimEnd() ?: ""
        // čtečka občas přečte "..." jen jako ".." ("Oh yeah.." → "and Mr. Smith, get yourself indoors.")
        val predTecky = predKonec.endsWith("..") || predKonec.endsWith("…")
        val novaZacatek = nova?.trimStart() ?: ""
        val novaPokracuje = novaZacatek.firstOrNull()?.isLowerCase() == true ||
            novaZacatek.startsWith("...") || novaZacatek.startsWith("…")
        // jen když pokračování přišlo hned (do 2,5 s od zmizení předchozího titulku) a nic jiného mezi
        // tím nebylo – jinak by se spojilo s něčím, co k tomu nepatří ("I loved Davey…" + "they may be okay")
        val casOk = if (oprava) posledniSpojeno else ted - predchoziKdy < 15000 && ted - predchoziVidenKdy < 2500
        var spojeno = false
        if (nova != null && pred != null && casOk && novaPokracuje &&
            (predTecky || predKonec.lastOrNull() !in listOf('.', '!', '?', '"', '…'))
        ) {
            val zacatek = predKonec.trimEnd('.', '…', ' ')
            val konec = novaZacatek.trimStart('.', '…', ' ')
            // "Now all of you..." → "all of you..." je jen opakování, ne pokračování → nespojovat
            val opakovani = zacatek.lowercase().endsWith(konec.trimEnd('.', '…', ' ').lowercase())
            if (zacatek.isNotEmpty() && konec.isNotEmpty() && !opakovani) {
                // tři tečky nechat – Google pak ví, že jde o pauzu v řeči ("we've had... well, a bad couple of days")
                repliky = listOf(if (predTecky) "$zacatek... $konec" else "$zacatek $konec")
                zapis("  [spojuji rozdělenou větu]")
                spojeno = true
            }
        }
        posledniSpojeno = spojeno
        predchoziAnglicky = puvodni.lastOrNull()
        predchoziKdy = ted
        predchoziVidenKdy = ted
        // jazyk se mohl v aplikaci změnit
        val cil = nast.jazykNeboCs
        prekladac.nastavJazyk(cil)
        ctecky.forEach { it.cilovyJazyk = cil }
        var dobryHotov = false
        fun ukazJestli(cesky: String, dobry: Boolean) {
            if (cislo < posledniUkazany) return // mezitím se ukázala novější replika
            if (!dobry && dobryHotov) return     // rychlý překlad přišel až po dobrém → nic
            if (dobry) dobryHotov = true
            posledniUkazany = cislo
            ukazCesky(cesky, box, radku, barvyJmen)
        }
        // 1) hned rychlý překlad z televize (~0,2 s), 2) pak ho nahradí lepší z Googlu
        // rychlý překlad ukázat, jen když Google do 0,15 s neodpoví – jinak by titulek "problikl"
        // (nejdřív horší překlad z telefonu, za 50 ms jiný od Googlu)
        val zacatekNahledu = System.currentTimeMillis()
        if (RYCHLY_NAHLED && !uspora) prelozVse(repliky, rychle = true) { cesky ->
            val zbyva = 150 - (System.currentTimeMillis() - zacatekNahledu)
            if (zbyva <= 0) ukazJestli(cesky, dobry = false)
            else ui.postDelayed({ ukazJestli(cesky, dobry = false) }, zbyva)
        }
        prelozVse(repliky, rychle = false) { cesky ->
            zapis("  → ČESKY (čtení ${casCteni} ms, překlad ${System.currentTimeMillis() - zacatekPrekladu} ms ${prekladac.zdroj}, chyb Googlu ${prekladac.chybGooglu}): " + cesky.replace("\n", " / "))
            ukazJestli(cesky, dobry = true)
        }
    }

    /** Přeloží všechny repliky (jméno postavy nechá) a výsledek spojí pod sebe. */
    private fun prelozVse(repliky: List<String>, rychle: Boolean, hotovo: (String) -> Unit) {
        val vysledky = arrayOfNulls<String>(repliky.size)
        var zbyva = repliky.size
        var selhalo = false
        fun hotovaJedna() {
            zbyva--
            // rychlý náhled, který selhal (např. model ještě není stažený), radši vůbec neukazovat
            if (zbyva == 0 && !(rychle && selhalo)) hotovo(vysledky.filterNotNull().joinToString("\n"))
        }
        repliky.forEachIndexed { i, replika ->
            val shoda = Regex("^([A-Z][A-Za-z0-9 .'-]{1,24}):\\s*(.+)$").find(replika)
            val jmeno = shoda?.groupValues?.get(1)
            val veta = shoda?.groupValues?.get(2) ?: replika
            val ok: (String) -> Unit = { preklad ->
                var opraveno = opravJmena(veta, preklad)
                // anglická věta začíná velkým písmenem → česká taky ("mrazím." → "Mrazím.")
                if (veta.trimStart().firstOrNull()?.isUpperCase() == true)
                    opraveno = opraveno.replaceFirstChar { it.uppercaseChar() }
                vysledky[i] = if (jmeno != null) "$jmeno: $opraveno" else opraveno
                hotovaJedna()
            }
            val chyba: (String) -> Unit = { selhalo = true; vysledky[i] = replika; hotovaJedna() }
            if (rychle) prekladac.prelozRychle(veta, ok, chyba) else prekladac.preloz(veta, ok, chyba)
        }
    }

    // ---------- jak dlouho nechat český titulek na obrazovce ----------
    private var ukazanoKdy = 0L
    private var ukazanaDelka = 0
    private val schovatCesky = Runnable { ukaz("", 0) }

    private fun ukazCesky(cesky: String, box: FloatArray?, radku: Int, barvyJmen: IntArray) {
        ui.removeCallbacks(schovatCesky)
        if (ukazanoKdy == 0L || System.currentTimeMillis() - ukazanoKdy > 400) ukazanoKdy = System.currentTimeMillis()
        ukazanaDelka = cesky.length
        ukaz(cesky, 15000, box, radku, barvyJmen)
    }

    /**
     * Anglický titulek zmizel → český schovat, ale až si ho stihneš přečíst
     * (aspoň 1,8 s + 60 ms na každé písmeno od chvíle, kdy se ukázal).
     */
    private fun schovejCesky() {
        val minDoba = 1800L + 60L * ukazanaDelka
        val zbyva = ukazanoKdy + minDoba - System.currentTimeMillis()
        ui.removeCallbacks(schovatCesky)
        ukazanoKdy = 0L
        if (zbyva <= 0) ukaz("", 0) else ui.postDelayed(schovatCesky, zbyva)
    }

    /** Pás výřezu připravený ke čtení: [nahore] = kde ve výřezu začíná, [meritko] = zmenšení. */
    private class Pas(val bitmapa: Bitmap, val nahore: Int, val meritko: Float, val vlevo: Int = 0) {
        fun zpet(r: android.graphics.Rect) = android.graphics.Rect(
            (r.left / meritko).toInt() + vlevo, (r.top / meritko).toInt() + nahore,
            (r.right / meritko).toInt() + vlevo, (r.bottom / meritko).toInt() + nahore
        )
    }

    /**
     * Najde řádky výřezu, kde je světlé písmo s ostrým okrajem (titulek),
     * a vrátí jen ten pás, zmenšený na [MERITKO_CTENI]. Null = žádné písmo.
     */
    /** Hrubý otisk: kolik světlých (písmových) bodů je v každém políčku mřížky 96 × 24. */
    private fun otisk(v: Vyrez): IntArray {
        val sx = 96; val sy = 24
        val o = IntArray(sx * sy)
        val jas = v.jas
        var y = 0
        while (y < v.h) {
            val ry = y * sy / v.h
            val z = y * v.w
            var x = 0
            while (x < v.w) {
                // jen "okraje písma": světlý bod s tmavým okolím – sníh a nebe jsou světlé, ale hladké,
                // takže by jinak přehlušily změnu titulku
                val a = jas[z + x].toInt() and 0xFF
                if (a > 150 && x >= 2 && x < v.w - 2 &&
                    (a - (jas[z + x - 2].toInt() and 0xFF) > 45 || a - (jas[z + x + 2].toInt() and 0xFF) > 45))
                    o[ry * sx + x * sx / v.w]++
                x += 2
            }
            y += 2
        }
        return o
    }

    /** Liší se dva otisky jen málo (šum přenosu), nebo je na obrazovce jiný text? */
    private fun stejny(a: IntArray, b: IntArray): Boolean {
        var rozdil = 0; var celkem = 0
        for (i in a.indices) {
            rozdil += kotlin.math.abs(a[i] - b[i])
            celkem += maxOf(a[i], b[i])
        }
        return rozdil <= 20 + celkem / 20
    }

    private fun celaOblast(v: Vyrez): Pas {
        val mensi = Bitmap.createScaledBitmap(v.bitmapa, (v.w * meritkoCteni).toInt(), (v.h * meritkoCteni).toInt(), true)
        return Pas(mensi, 0, meritkoCteni)
    }

    // ---------- naučený pruh s dialogy ----------
    /** Kde (výška ve výřezu) byly poslední dialogy: dvojice (horní, dolní okraj). */
    private val dialogy = ArrayDeque<Pair<Int, Int>>()
    /** Kde (výška ve výřezu) byly poslední jisté nápovědy k ovládání. */
    private val napovedy = ArrayDeque<Pair<Int, Int>>()
    @Volatile private var posledniDialogKdy = 0L
    private var cteniCelkem = 0

    /**
     * Je text tam, kde se ukazují dialogy (podle posledních přeložených vět)?
     * Pak bereme i krátké repliky bez tečky ("Slow", "Hey", "Easy now").
     */
    private fun jeNaMisteDialogu(box: android.graphics.Rect): Boolean {
        val kopie = synchronized(dialogy) { dialogy.toList() }
        if (kopie.size < 3) return false
        val stred = (box.top + box.bottom) / 2
        val vyska = (box.bottom - box.top).coerceAtLeast(8)
        return kopie.any { (t, b) -> stred in (t - vyska)..(b + vyska) }
    }

    /** Zapamatovat polohu přeloženého dialogu. */
    private fun zapamatujDialog(box: android.graphics.Rect) {
        synchronized(dialogy) {
            dialogy.addLast(box.top to box.bottom)
            while (dialogy.size > 20) dialogy.removeFirst()
        }
        posledniDialogKdy = System.currentTimeMillis()
    }

    /**
     * Pruh, kde se ve hře ukazují dialogy (řádky výřezu), nebo null = číst celou oblast.
     * Celá oblast se čte, dokud se pruh nenaučí (5 dialogů), každé 8. čtení (kdyby se dialogy
     * přesunuly jinam) a když v pruhu 20 s nic nebylo.
     */
    private fun naucenyPruh(h: Int): IntRange? {
        cteniCelkem++
        if (!UCIT_PRUH_DIALOGU) return null
        val kopie = synchronized(dialogy) { dialogy.toList() }
        if (kopie.size < 5) return null
        if (cteniCelkem % 8 == 0) return null
        if (System.currentTimeMillis() - posledniDialogKdy > 20_000) return null
        val vyskaRadku = kopie.map { (it.second - it.first) }.minOrNull()?.coerceAtLeast(8) ?: 20
        val od = (kopie.minOf { it.first } - vyskaRadku).coerceAtLeast(0)
        val doo = (kopie.maxOf { it.second } + vyskaRadku).coerceAtMost(h - 1)
        return if (doo - od > 10) od..doo else null
    }

    /** V pruhu s dialogy nic není → nic nečíst (malý prázdný obrázek). */
    /** Průměrný jas výřezu (0–255), počítaný z každého 4. bodu. */
    private fun prumernyJas(v: Vyrez): Int {
        var soucet = 0L; var n = 0
        var i = 0
        while (i < v.jas.size) { soucet += v.jas[i].toInt() and 0xFF; n++; i += 4 }
        return if (n == 0) 0 else (soucet / n).toInt()
    }

    private var stKontrast = 0
    private var stNad = 0
    private var cteniNad = 0
    /** Kde (výška ve výřezu) začíná nápověda, která je teď na obrazovce, a kdy byla naposledy vidět. */
    @Volatile private var napovedaHore = 0
    @Volatile private var napovedaKdy = 0L

    /** Jen oblast nad nápovědou (řádky 0..[hore]), ve světlé scéně s vytaženým kontrastem. */
    private fun pasNadNapovedou(v: Vyrez, hore: Int, kontrast: Boolean): Pas {
        val vys = hore.coerceIn(12, v.h)
        val bmp = if (kontrast) {
            val px = IntArray(v.w * vys)
            for (i in px.indices) {
                val a = v.jas[i].toInt() and 0xFF
                val b = ((a - 175) * 32 / 10).coerceIn(0, 255)
                px[i] = (0xFF shl 24) or (b shl 16) or (b shl 8) or b
            }
            Bitmap.createBitmap(px, v.w, vys, Bitmap.Config.ARGB_8888)
        } else Bitmap.createBitmap(v.bitmapa, 0, 0, v.w, vys)
        val mensi = Bitmap.createScaledBitmap(bmp, (v.w * meritkoCteni).toInt(), (vys * meritkoCteni).toInt().coerceAtLeast(8), true)
        return Pas(mensi, 0, meritkoCteni)
    }

    /**
     * Celá oblast titulků s vytaženým kontrastem: světlé pozadí (mlha ~200) ztmavne,
     * bílé písmo (~250) zůstane bílé → čtečka ho najde i ve sněhu.
     */
    private fun kontrastniPas(v: Vyrez): Pas {
        val px = IntArray(v.w * v.h)
        for (i in px.indices) {
            val a = v.jas[i].toInt() and 0xFF
            val b = ((a - 175) * 32 / 10).coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (b shl 16) or (b shl 8) or b
        }
        val bmp = Bitmap.createBitmap(px, v.w, v.h, Bitmap.Config.ARGB_8888)
        val mensi = Bitmap.createScaledBitmap(bmp, (v.w * meritkoCteni).toInt(), (v.h * meritkoCteni).toInt(), true)
        return Pas(mensi, 0, meritkoCteni)
    }

    private fun prazdnyPas(v: Vyrez): Pas {
        val b = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888)
        return Pas(b, 0, 1f)
    }

    private fun pasSTextem(v: Vyrez, omezeni: IntRange? = null): Pas? {
        val w = v.w; val h = v.h; val jas = v.jas
        var prvni = -1; var posledni = -1
        // úplně dole bývá úkol mise / nápověda ("Catch up to Micah") – tu oblast nečíst
        val konec = ((DOLNI_HRANICE - (1 - SPODNI_CAST)) / SPODNI_CAST * h).toInt().coerceIn(1, h)
        val zacatekY = omezeni?.first ?: 0
        val konecY = omezeni?.let { minOf(it.last + 1, konec) } ?: konec
        for (y in zacatekY until konecY) {
            val z = y * w
            var hran = 0
            var x = 2
            while (x < w - 2) {
                val a = jas[z + x].toInt() and 0xFF
                if (a > 150) {
                    val l = jas[z + x - 2].toInt() and 0xFF
                    val p = jas[z + x + 2].toInt() and 0xFF
                    if (a - l > 45 || a - p > 45) hran++
                }
                x += 2
            }
            if (hran >= 4) { if (prvni < 0) prvni = y; posledni = y }
        }
        if (prvni < 0) return null
        val okraj = 20
        val od = (prvni - okraj).coerceAtLeast(zacatekY)
        val vyska = (posledni + okraj).coerceAtMost(konecY - 1) - od + 1
        if (vyska < 12) return null
        // i do šířky jen tam, kde je písmo – ale souměrně kolem středu (čtečka hledá titulky uprostřed)
        var levy = w; var pravy = -1
        for (y in od until od + vyska step 2) {
            val z = y * w
            var x = 2
            while (x < w - 2) {
                val a = jas[z + x].toInt() and 0xFF
                if (a > 150 && (a - (jas[z + x - 2].toInt() and 0xFF) > 45 || a - (jas[z + x + 2].toInt() and 0xFF) > 45)) {
                    if (x < levy) levy = x
                    if (x > pravy) pravy = x
                }
                x += 2
            }
        }
        val okrajX = 40
        val vlevo = if (pravy < 0) 0 else minOf(levy, w - 1 - pravy).minus(okrajX).coerceIn(0, w / 2 - 50)
        val sirka = w - 2 * vlevo
        val pas = Bitmap.createBitmap(v.bitmapa, vlevo, od, sirka, vyska)
        val mensi = Bitmap.createScaledBitmap(pas, (sirka * meritkoCteni).toInt(), (vyska * meritkoCteni).toInt().coerceAtLeast(8), true)
        return Pas(mensi, od, meritkoCteni, vlevo)
    }

    /** Jména postav, která překladač omylem přeloží ("Dutch" → "holandština") – vrátit zpět. */
    private fun opravJmena(anglicky: String, cesky: String): String {
        var t = cesky
        // jméno v angličtině → jak ho překladač omylem přeloží (slovník, doplňuje se)
        for ((jmeno, spatne) in CHYBNA_JMENA) {
            if (Regex("\\b$jmeno\\b").containsMatchIn(anglicky))
                t = t.replace(Regex("(?i)(?<![\\p{L}])($spatne)\\p{L}*"), jmeno)
        }
        // jména s velkým písmenem uprostřed věty ("Micah", "Arthur") vrátit, jak byla v angličtině
        val slova = anglicky.split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        // první slovo jen když za ním je čárka ("Micah, go…" = oslovení)
        val prvniJeOsloveni = Regex("^\\s*\\p{L}+,").containsMatchIn(anglicky)
        (if (prvniJeOsloveni) slova else slova.drop(1)).filter { it.length >= 3 && it[0].isUpperCase() && it.drop(1).all { c -> c.isLowerCase() } }
            .forEach { jm -> t = t.replace(Regex("(?i)(?<![\\p{L}])" + Regex.escape(jm) + "(?![\\p{L}])"), jm) }
        return t
    }

    private var prvniVidenoKdy = 0L
    private var casCteni = 0L
    private var zacatekPrekladu = 0L
    /** Texty, které visely na obrazovce moc dlouho (nápověda, název úkolu…) – příště je ignorujeme. */
    private val statickeTexty = mutableSetOf<String>()

    private fun klic(t: String) = t.lowercase().filter { it.isLetter() }
    private fun jeStaticky(t: String) = klic(t) in statickeTexty

    /**
     * Titulek = replika:
     * - se jménem postavy ("Jason: Yeah.") → bereme vždy, i když je krátká,
     * - bez jména → aspoň 3 slova, nebo kratší věta ukončená . ! ? ("Let's go!"),
     * - a nikdy seznam typu "Quick Attack / (Hold) Strong Attack / Block".
     */
    private fun vypadaJakoVeta(text: String): Boolean {
        val prvni = text.lineSequence().first()
        val maJmeno = Regex("^[A-Z][A-Za-z0-9 .'-]{1,24}:\\s*\\S").containsMatchIn(prvni)
        val bezJmena = Regex("^[A-Z][A-Za-z0-9 .'-]{1,24}:\\s*(.+)$").find(prvni)?.groupValues?.get(1) ?: text
        val znakyMenu = text.count { it == '/' || it == '(' || it == '|' || it == '[' }
        if (znakyMenu >= 2) return false
        if (maJmeno) return true
        val slova = bezJmena.split(Regex("\\s+")).count { sl -> sl.count { it.isLetter() } >= 2 }
        // úkoly mise a nápovědy už odděluje čtečka (žlutý box) – tady stačí:
        // aspoň 3 slova, nebo kratší věta ukončená . ! ? …
        if (slova >= 3) return true
        val konciVetou = bezJmena.trimEnd().lastOrNull() in setOf('.', '!', '?', '…', '"', '\'', '-', '—')
        return slova >= 1 && konciVetou
    }

    /** Stejný titulek čtený znovu se může lišit v drobnostech – ty nepřekládáme znovu. */
    private fun podobne(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.isEmpty() || b.isEmpty()) return false
        val ka = a.lowercase().filter { it.isLetter() }
        val kb = b.lowercase().filter { it.isLetter() }
        if (ka == kb) return true
        // vzdálenost úprav (kolik písmen se liší) – zvládne i chybějící písmeno ("He'l" × "He'll")
        val delka = maxOf(ka.length, kb.length)
        if (kotlin.math.abs(ka.length - kb.length) > delka / 8 + 1) return false
        var predchozi = IntArray(kb.length + 1) { it }
        for (i in 1..ka.length) {
            val radek = IntArray(kb.length + 1)
            radek[0] = i
            for (j in 1..kb.length) {
                val cena = if (ka[i - 1] == kb[j - 1]) 0 else 1
                radek[j] = minOf(predchozi[j] + 1, radek[j - 1] + 1, predchozi[j - 1] + cena)
            }
            predchozi = radek
        }
        return predchozi[kb.length] <= delka / 10 + 1
    }

    /**
     * Oblast titulků vyříznutá ze snímku: šedivý obrázek pro čtení textu
     * + barevná složka (U, V v polovičním rozlišení) pro zjištění barvy jmen postav.
     */
    private class Vyrez(
        val bitmapa: Bitmap,
        val jas: ByteArray, val w: Int, val h: Int,
        private val u: ByteArray, private val v: ByteArray, private val uw: Int, private val uh: Int
    ) {
        /**
         * Průměrná barva světlých (textových) pixelů v obdélníku → barva ARGB.
         * Vrátí 0, když je text bílý/šedý (bez výrazné barvy).
         */
        fun barvaTextu(r: android.graphics.Rect): Int {
            var su = 0L; var sv = 0L; var sy = 0L; var n = 0
            for (y in r.top.coerceAtLeast(0) until r.bottom.coerceAtMost(h)) {
                for (x in r.left.coerceAtLeast(0) until r.right.coerceAtMost(w)) {
                    val jj = jas[y * w + x].toInt() and 0xFF
                    if (jj < 110) continue // pozadí, ne písmo
                    val ci = (y / 2).coerceAtMost(uh - 1) * uw + (x / 2).coerceAtMost(uw - 1)
                    su += (u[ci].toInt() and 0xFF); sv += (v[ci].toInt() and 0xFF); sy += jj; n++
                }
            }
            if (n < 20) return 0
            val cu = su.toFloat() / n - 128f
            val cv = sv.toFloat() / n - 128f
            if (kotlin.math.abs(cu) + kotlin.math.abs(cv) < 10f) return 0 // skoro bez barvy → bílá
            val yy = (sy.toFloat() / n).coerceAtLeast(170f) // trochu zesvětlit, ať je to na tmavém pozadí čitelné
            // zesílit sytost (barvy v přenosu bývají vybledlé), ale ne přes míru
            val zesileni = (40f / (kotlin.math.abs(cu) + kotlin.math.abs(cv))).coerceIn(1f, 3f)
            val cu2 = cu * zesileni
            val cv2 = cv * zesileni
            val rr = (yy + 1.402f * cv2).toInt().coerceIn(0, 255)
            val gg = (yy - 0.344f * cu2 - 0.714f * cv2).toInt().coerceIn(0, 255)
            val bb = (yy + 1.772f * cu2).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
    }

    /**
     * Ze snímku YUV vyřízne oblast titulků.
     * Oblast = spodních [SPODNI_CAST] obrazu, jen prostředních [STRED_SIRKA] šířky
     * (po stranách bývá ovládání, mapa a ukazatele – ty číst nechceme).
     */
    private fun vyrizni(img: android.media.Image): Vyrez {
        val celaSirka = img.width
        val h = img.height
        val odX = (celaSirka * (1 - STRED_SIRKA) / 2).toInt() and 1.inv() // sudé kvůli barvám
        val w = (celaSirka * STRED_SIRKA).toInt()
        val od = (h * (1 - SPODNI_CAST)).toInt() and 1.inv()
        val vyska = h - od

        // jas (Y)
        val rovina = img.planes[0]
        val buf = rovina.buffer
        val radek = rovina.rowStride
        val krok = rovina.pixelStride
        val jas = ByteArray(w * vyska)
        val pixely = IntArray(w * vyska)
        val kopie = buf.duplicate()
        for (y in 0 until vyska) {
            val zaklad = (od + y) * radek
            if (krok == 1) {
                // celý řádek najednou – mnohem rychlejší než po jednom pixelu
                kopie.position(zaklad + odX)
                kopie.get(jas, y * w, w)
            } else {
                for (x in 0 until w) jas[y * w + x] = buf.get(zaklad + (odX + x) * krok)
            }
        }
        for (i in 0 until w * vyska) {
            val v = jas[i].toInt() and 0xFF
            pixely[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

        // barva (U, V) v polovičním rozlišení
        val uw = w / 2
        val uh = vyska / 2
        val uArr = ByteArray(uw * uh)
        val vArr = ByteArray(uw * uh)
        runCatching {
            val pu = img.planes[1]; val pv = img.planes[2]
            val bu = pu.buffer; val bv = pv.buffer
            for (y in 0 until uh) {
                val ry = od / 2 + y
                for (x in 0 until uw) {
                    val rx = odX / 2 + x
                    uArr[y * uw + x] = bu.get(ry * pu.rowStride + rx * pu.pixelStride)
                    vArr[y * uw + x] = bv.get(ry * pv.rowStride + rx * pv.pixelStride)
                }
            }
        }
        return Vyrez(Bitmap.createBitmap(pixely, w, vyska, Bitmap.Config.ARGB_8888), jas, w, vyska, uArr, vArr, uw, uh)
    }

    // ---------------------------------------------------------------- volby odpovědí (telefon)

    private val cteckaVoleb by lazy { Ctecka() }
    @Volatile private var volbyCtu = false
    @Volatile private var volbyOd = 0L
    private var volbyDalsi = 0L
    private var volbyOtisk = 0L
    private var volbyCtenoKdy = 0L
    /** Hlavní vlákno: anglické volby → česky (ať se při přejíždění nepřekládá pořád dokola). */
    private val prekladyVoleb = HashMap<String, String>()
    private var volbyPosledni: List<String> = emptyList()
    private var volbyVybrana = -1
    private var volbyPrazdneOd = 0L

    /** Volá se na vlákně GL u každého snímku, když je rámeček voleb zapnutý. */
    private fun ctiVolby(gl: GlSnimac) = ctiVolby(gl.sirka, gl.vyska) { x, y, w, h -> gl.precti(x, y, w, h) }

    /** Žlutý rámeček u Xboxu: barvy oblasti ze snímku WebRTC (I420 → RGB). */
    private fun ctiVolbyXbox(f: org.webrtc.VideoFrame) {
        val ted = System.currentTimeMillis()
        if (volbyCtu && ted - volbyOd < 3000) return
        if (ted < volbyDalsi || pozastaveno) return
        val i = f.buffer.toI420() ?: return
        try {
            ctiVolby(i.width, i.height) { x, y, w, h ->
                val px = IntArray(w * h)
                for (r in 0 until h) for (c in 0 until w) {
                    val yy = (i.dataY.get((y + r) * i.strideY + x + c).toInt() and 255) - 16
                    val uu = (i.dataU.get((y + r) / 2 * i.strideU + (x + c) / 2).toInt() and 255) - 128
                    val vv = (i.dataV.get((y + r) / 2 * i.strideV + (x + c) / 2).toInt() and 255) - 128
                    val rr = ((298 * yy + 409 * vv + 128) shr 8).coerceIn(0, 255)
                    val gg = ((298 * yy - 100 * uu - 208 * vv + 128) shr 8).coerceIn(0, 255)
                    val bb = ((298 * yy + 516 * uu + 128) shr 8).coerceIn(0, 255)
                    px[r * w + c] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                }
                px
            }
        } finally { i.release() }
    }

    private fun ctiVolby(sirka: Int, vyska: Int, precti: (Int, Int, Int, Int) -> IntArray) {
        val ted = System.currentTimeMillis()
        if (volbyCtu && ted - volbyOd < 3000) return
        if (ted < volbyDalsi || pozastaveno) return
        volbyDalsi = ted + 250
        val o = Volby.oblast
        val x = (o[0] * sirka).toInt().coerceIn(0, sirka - 16)
        val y = (o[1] * vyska).toInt().coerceIn(0, vyska - 16)
        val w = ((o[2] - o[0]) * sirka).toInt().coerceIn(16, sirka - x)
        val h = ((o[3] - o[1]) * vyska).toInt().coerceIn(16, vyska - y)
        val barvy = precti(x, y, w, h)
        // stejný obraz jako minule (hráč nic nezměnil) → nečíst znovu
        var otisk = 17L
        var i = 0
        while (i < barvy.size) { otisk = otisk * 31 + ((barvy[i] shr 4) and 0x0F0F0F); i += 97 }
        if (otisk == volbyOtisk && ted - volbyCtenoKdy < 800) return
        volbyOtisk = otisk
        volbyCtenoKdy = ted
        // 2× zvětšit – volby bývají drobné písmo a čtečka pak přečte i řádek na světlém podbarvení
        val bmp = Bitmap.createScaledBitmap(Bitmap.createBitmap(barvy, w, h, Bitmap.Config.ARGB_8888), w * 2, h * 2, true)
        volbyCtu = true
        volbyOd = ted
        cteckaVoleb.prectiVolby(bmp, { radky ->
            volbyCtu = false
            val boxy = radky.map { val r = it.second; android.graphics.Rect(r.left / 2, r.top / 2, r.right / 2, r.bottom / 2) }
            val vybrana = runCatching { zvyraznena(boxy, barvy, w, h) }.getOrDefault(-1)
            ui.post { if (Volby.rezimText) zpracujText(radky.map { it.first }) else zpracujVolby(radky.map { it.first }, vybrana) }
        }, { volbyCtu = false })
    }

    /**
     * Která volba je zvýrazněná? Hra vybranou volbu podbarví nebo jinak obarví → její řádek
     * má jinou průměrnou barvu než ostatní. Vrátí index, nebo -1 (nepoznáno).
     */
    private fun zvyraznena(boxy: List<android.graphics.Rect>, barvy: IntArray, w: Int, h: Int): Int {
        // jen jeden řádek = není z čeho vybírat (nápověda typu "Use L to follow Dutch") → nic nezvýrazňovat
        if (boxy.size < 2) return -1
        val prumery = boxy.map { r ->
            var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
            // celý řádek přes šířku oblasti (podbarvení bývá širší než text)
            val y0 = (r.top - 3).coerceAtLeast(0); val y1 = (r.bottom + 3).coerceAtMost(h)
            for (yy in y0 until y1 step 2) for (xx in 0 until w step 3) {
                val c = barvy[yy * w + xx]
                sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF; n++
            }
            if (n == 0) floatArrayOf(0f, 0f, 0f) else floatArrayOf(sr.toFloat() / n, sg.toFloat() / n, sb.toFloat() / n)
        }
        fun vzdal(a: FloatArray, b: FloatArray) =
            kotlin.math.abs(a[0] - b[0]) + kotlin.math.abs(a[1] - b[1]) + kotlin.math.abs(a[2] - b[2])
        if (prumery.size == 2) {
            // dvě volby: vybraná bývá ta světlejší
            val j0 = prumery[0].sum(); val j1 = prumery[1].sum()
            return if (kotlin.math.abs(j0 - j1) < 40f) -1 else if (j0 > j1) 0 else 1
        }
        // víc voleb: ta, která se nejvíc liší od průměru ostatních
        val odchylky = prumery.indices.map { i ->
            val ostatni = prumery.filterIndexed { j, _ -> j != i }
            val prumer = FloatArray(3) { k -> ostatni.map { it[k] }.average().toFloat() }
            vzdal(prumery[i], prumer)
        }
        val nej = odchylky.indices.maxByOrNull { odchylky[it] } ?: return -1
        val druha = odchylky.filterIndexed { j, _ -> j != nej }.maxOrNull() ?: 0f
        return if (odchylky[nej] > 30f && odchylky[nej] > druha * 1.5f) nej else -1
    }

    /** Hlavní vlákno: přeložit (jen nové) volby a poslat do televize. */
    /** Poslední přečtený text v režimu Text (dopis, SMS…). */
    private var textPosledni = ""

    /**
     * Režim Text: řádky ve žlutém rámečku spojit do jednoho textu, přeložit jako celek a poslat
     * na televizi jedním oknem (vedle originálu). Drobné rozdíly ve čtení se nepřekládají znovu.
     */
    private fun zpracujText(radky: List<String>) {
        val text = radky.map { it.trim() }.filter { it.isNotEmpty() }
            .fold("") { acc, r -> when { acc.isEmpty() -> r; acc.endsWith("-") -> acc.dropLast(1) + r; else -> "$acc $r" } }
            .replace(Regex("\\s+"), " ").trim()
        val ted = System.currentTimeMillis()
        if (text.count { it.isLetter() } < 3 || Ctecka.jeNesmysl(text)) {
            if (textPosledni.isNotEmpty()) {
                if (volbyPrazdneOd == 0L) volbyPrazdneOd = ted
                // text chvíli není vidět → schovat až po 1,5 s (ať okno nebliká)
                if (ted - volbyPrazdneOd > 1500) {
                    textPosledni = ""
                    volbyPosledni = emptyList()
                    Propojeni.Vysilac.nastavVolby(emptyList(), -1)
                    zapis("[text zmizel]")
                }
            }
            return
        }
        volbyPrazdneOd = 0L
        if (textPosledni.isNotEmpty() && podobne(text, textPosledni)) return
        textPosledni = text
        zapis("[text: ${text.take(120)}]")
        prekladyVoleb[text]?.let { Propojeni.Vysilac.nastavVolby(listOf(it), Volby.VYBRANA_TEXT); return }
        if (prekladyVoleb.size > 300) prekladyVoleb.clear()
        prekladac.nastavJazyk(nast.jazykNeboCs)
        prekladac.preloz(text, { cesky ->
            ui.post {
                prekladyVoleb[text] = cesky
                if (textPosledni == text) Propojeni.Vysilac.nastavVolby(listOf(cesky), Volby.VYBRANA_TEXT)
            }
        }, { e -> zapis("[text: chyba překladu $e]") })
    }

    private fun zpracujVolby(surove: List<String>, vybrana: Int) {
        // očistit: ikony a znaky na začátku ("[F]", "▶", "•"), šum s méně než 2 písmeny
        val volby = surove.map { it.trim().trimStart { ch -> !ch.isLetterOrDigit() && ch != '"' && ch != '„' }.trim() }
            .filter { v -> v.count { it.isLetter() } >= 2 }
            .take(8)
        // vybraný index přepočítat po vyřazení šumu
        val vyb = if (vybrana in surove.indices) {
            val puvodni = surove[vybrana].trim().trimStart { ch -> !ch.isLetterOrDigit() && ch != '"' && ch != '„' }.trim()
            volby.indexOf(puvodni)
        } else -1
        val ted = System.currentTimeMillis()
        // volby ve hře mají vždy jednu zvýrazněnou (vybranou) – když žádná zvýrazněná není, je to jiný
        // text (loga na načítací obrazovce, nápisy ve scéně) → neukazovat ("technologie od Amazonu")
        val zvyraznene = vyb >= 0 || (volby == volbyPosledni && volbyVybrana >= 0)
        if (volby.isEmpty() || !zvyraznene) {
            if (volbyPosledni.isNotEmpty()) {
                if (volbyPrazdneOd == 0L) volbyPrazdneOd = ted
                // volby chvíli nejsou vidět nebo se špatně přečetly → schovat až po 1 s (ať seznam nebliká)
                if (ted - volbyPrazdneOd > 1000) {
                    volbyPosledni = emptyList()
                    Propojeni.Vysilac.nastavVolby(emptyList(), -1)
                    zapis("[volby zmizely]")
                }
            }
            return
        }
        volbyPrazdneOd = 0L
        // vybraná nepoznaná → nechat minulou (když jsou volby stejné)
        volbyVybrana = if (vyb >= 0) vyb else if (volby == volbyPosledni) volbyVybrana else -1
        val novy = volby != volbyPosledni
        volbyPosledni = volby
        if (prekladyVoleb.size > 300) prekladyVoleb.clear()
        val chybi = volby.filter { it !in prekladyVoleb }
        fun posli() {
            if (volbyPosledni != volby) return
            Propojeni.Vysilac.nastavVolby(volby.map { prekladyVoleb[it] ?: it }, volbyVybrana)
        }
        if (chybi.isEmpty()) { posli(); return }
        if (novy) zapis("[volby: ${volby.joinToString(" | ")}]")
        prekladac.nastavJazyk(nast.jazykNeboCs)
        var zbyva = chybi.size
        for (v in chybi) {
            prekladac.preloz(v, { cesky ->
                ui.post {
                    // jména postav nepřekládat ("Dutch" ≠ "holandština") – stejně jako u titulků
                    var opraveno = opravJmena(v, cesky)
                    if (v.firstOrNull()?.isUpperCase() == true) opraveno = opraveno.replaceFirstChar { it.uppercaseChar() }
                    prekladyVoleb[v] = opraveno
                    if (--zbyva == 0) posli()
                }
            }, {
                ui.post { if (--zbyva == 0) posli() }
            })
        }
    }

    /** Telefon: výřez titulků z obrazu v grafickém čipu (OpenGL). */
    private fun vyrezZGl(gl: GlSnimac): Vyrez {
        val celaSirka = gl.sirka
        val h = gl.vyska
        val r = rucniOblast()
        val odX: Int; val w: Int; val od: Int; val vyska: Int
        if (r != null) {
            // ruční oblast titulků (modrý rámeček z telefonu) → číst jen v ní
            odX = (celaSirka * r[0]).toInt().coerceIn(0, celaSirka - 16) and 1.inv()
            w = ((celaSirka * (r[2] - r[0])).toInt().coerceIn(16, celaSirka - odX)) and 1.inv()
            od = (h * r[1]).toInt().coerceIn(0, h - 16) and 1.inv()
            vyska = ((h * (r[3] - r[1])).toInt().coerceIn(16, h - od)) and 1.inv()
        } else {
            odX = (celaSirka * (1 - STRED_SIRKA) / 2).toInt() and 1.inv()
            w = (celaSirka * STRED_SIRKA).toInt() and 1.inv()
            od = (h * (1 - SPODNI_CAST)).toInt() and 1.inv()
            vyska = (h - od) and 1.inv()
        }
        val barvy = gl.precti(odX, od, w, vyska)
        return vyrezZBarev(barvy, w, vyska)
    }

    /** Z barev RGB dopočítat jas (Y) a barvu (U, V) stejně, jako je dává televize. */
    private fun vyrezZBarev(barvy: IntArray, w: Int, vyska: Int): Vyrez {
        val jas = ByteArray(w * vyska)
        val pixely = IntArray(w * vyska)
        for (i in barvy.indices) {
            val c = barvy[i]
            val rr = (c shr 16) and 0xFF; val gg = (c shr 8) and 0xFF; val bb = c and 0xFF
            val y = ((66 * rr + 129 * gg + 25 * bb + 128) shr 8) + 16
            val v = y.coerceIn(0, 255)
            jas[i] = v.toByte()
            pixely[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val uw = w / 2
        val uh = vyska / 2
        val uArr = ByteArray(uw * uh)
        val vArr = ByteArray(uw * uh)
        for (y in 0 until uh) for (x in 0 until uw) {
            val c = barvy[(y * 2) * w + x * 2]
            val rr = (c shr 16) and 0xFF; val gg = (c shr 8) and 0xFF; val bb = c and 0xFF
            uArr[y * uw + x] = (((-38 * rr - 74 * gg + 112 * bb + 128) shr 8) + 128).coerceIn(0, 255).toByte()
            vArr[y * uw + x] = (((112 * rr - 94 * gg - 18 * bb + 128) shr 8) + 128).coerceIn(0, 255).toByte()
        }
        return Vyrez(Bitmap.createBitmap(pixely, w, vyska, Bitmap.Config.ARGB_8888), jas, w, vyska, uArr, vArr, uw, uh)
    }

    // ---------------------------------------------------------------- Xbox

    @Volatile private var xbox: XboxSpojeni.Relace? = null
    private var xboxKontrolaKdy = 0L
    private var xboxPosledniStav = ""
    @Volatile private var xboxVidenVypnuty = false
    @Volatile private var xboxZapnutOd = 0L

    private fun pripojeno() = session != null || xbox != null

    /**
     * Jsou zapnuté obě konzole? (jen když jsou obě spárované) – podle toho se v aplikaci
     * ukazuje tlačítko ⇄. Kontrola každých 20 s: PS5 v domácí síti, Xbox přes Microsoft.
     */
    @Volatile private var kontrolujiObe = false
    @Volatile private var zapornychObe = 0
    private val kontrolaObou: Runnable = object : Runnable {
        override fun run() {
            if (!aktivni) { obeZapnute = false; return }
            val ctx = this@RemotePlayService
            val udaje = RemotePlayUdaje(ctx)
            val xid = XboxUcet.konzole(ctx)?.first
            if (!udaje.sparovano || xid == null) obeZapnute = false
            else if (!kontrolujiObe) {
                kontrolujiObe = true
                Thread {
                    val ps = runCatching {
                        HledacPS.hledej(1500, primaIp = udaje.ipKonzole).any { it.typ == "PS5" && it.stav == "zapnutá" }
                    }.getOrDefault(false)
                    val xb = ps && runCatching {
                        XboxSpojeni.konzole(ctx).any { it.id == xid && it.stav == "On" }
                    }.getOrDefault(false)
                    // jedna nepovedená kontrola (Wi-Fi, Microsoft neodpověděl) tlačítko neschová –
                    // schová se až po 2 záporných kontrolách po sobě, ať neposkakuje
                    if (ps && xb) { obeZapnute = true; zapornychObe = 0 }
                    else if (++zapornychObe >= 2) obeZapnute = false
                    kontrolujiObe = false
                }.apply { isDaemon = true; name = "obe-konzole" }.start()
            }
            ui.postDelayed(this, 20_000)
        }
    }

    private fun spustXbox(id: String) {
        posledniHlaska = ""
        snimkuCelkem = 0
        naXboxu = true
        bezi = false
        stav = "Navazuji spojení s Xboxem…"
        zapis("[připojuji k Xboxu]")
        // Xbox pustí jen jedno spojení – když hraje tohle zařízení, druhé (televize / telefon)
        // se má odpojit, jinak se navzájem vyhazují (stejně jako u PS5)
        if (chciPS5) Predavani.posli()
        drzWifi(true)
        posledniSnimekKdy = System.currentTimeMillis()
        ui.post { zahrejCtecku(predPripojenim = true) }
        runCatching { prekladac.udrzujSpojeni() }
        var spojeno = false
        val r = XboxSpojeni.Relace(this, id, priStavu = { st ->
            ui.post {
                if (xbox == null) return@post
                when {
                    st.startsWith("✔") && !spojeno -> {
                        spojeno = true
                        zapis("[PŘIPOJENO k Xboxu]")
                        bezi = true
                        pripojenoKdy = System.currentTimeMillis()
                        posledniSnimekKdy = pripojenoKdy
                        stav = "Připojeno k Xboxu ✔ – čtu titulky"
                        neuspechu = 0
                        ui.postDelayed({ if (xbox != null) runCatching { AutoStart.uspech(this) } }, 60_000)
                        ui.removeCallbacks(ohrivac)
                        ui.post(ohrivac)
                        ui.postDelayed({ zahrejCtecku() }, 1500)
                    }
                    st.startsWith("✘") || st.startsWith("Xbox ukončil") -> {
                        zapis("[Xbox: $st]")
                        zastav(if (automat) "Spojení s Xboxem se přerušilo – připojím se znovu sám." else st, ukazat = !automat)
                        znovuPozdeji()
                    }
                    !spojeno -> stav = st
                }
            }
        }, priSnimku = { f -> snimekXbox(f) }, bezGpu = telefon)
        xbox = r
        r.spust()
    }

    /** Snímek z Xboxu (vlákno dekodéru WebRTC): vyříznout oblast titulků a číst na vlákně služby. */
    private fun snimekXbox(f: org.webrtc.VideoFrame) {
        // žlutý rámeček (volby, menu) – vlastní čtečka, titulky nijak nebrzdí
        if (Volby.zapnuto) runCatching { ctiVolbyXbox(f) }.onFailure { e -> ui.post { zapis("[CHYBA volby Xbox: ${e.message}]") } }
        try {
            val volna = volnaCteckaProSnimek() ?: return
            obsazeno[volna].set(true)
            obsazenoOd[volna] = System.currentTimeMillis()
            val w = f.rotatedWidth.toFloat(); val h = f.rotatedHeight.toFloat()
            val v = try { vyrezZI420(f) } catch (e: Throwable) { obsazeno[volna].set(false); throw e }
            bg.post {
                try { zpracuj(volna, w, h) { v } } catch (e: Throwable) { chybaObrazu(e) }
            }
        } catch (e: Throwable) { chybaObrazu(e) }
    }

    @Volatile private var pametZapsano = false

    /** PS5 na telefonu: snímek přímo z dekodéru (I420) – dál stejně jako u Xboxu. */
    private fun snimekPamet(f: org.webrtc.VideoFrame) {
        if (!pametZapsano) {
            pametZapsano = true
            val w = f.buffer.width; val h = f.buffer.height; val b = PametObraz.formatBarev()
            ui.post { zapis("[obraz: přímo do paměti ${w}x$h, barvy $b]") }
        }
        snimekXbox(f)
    }

    /** Oblast titulků ze snímku WebRTC (I420) – stejně jako [vyrizni] u PS5. */
    private fun vyrezZI420(f: org.webrtc.VideoFrame): Vyrez {
        val i = f.buffer.toI420() ?: error("toI420")
        try {
            val celaSirka = i.width
            val h = i.height
            sirkaXbox = celaSirka
            val r = rucniOblast()
            val odX: Int; val w: Int; val od: Int; val vyska: Int
            if (r != null) {
                odX = (celaSirka * r[0]).toInt().coerceIn(0, celaSirka - 16) and 1.inv()
                w = ((celaSirka * (r[2] - r[0])).toInt().coerceIn(16, celaSirka - odX)) and 1.inv()
                od = (h * r[1]).toInt().coerceIn(0, h - 16) and 1.inv()
                vyska = ((h * (r[3] - r[1])).toInt().coerceIn(16, h - od)) and 1.inv()
            } else {
                odX = (celaSirka * (1 - STRED_SIRKA) / 2).toInt() and 1.inv()
                w = (celaSirka * STRED_SIRKA).toInt() and 1.inv()
                od = (h * (1 - SPODNI_CAST)).toInt() and 1.inv()
                vyska = (h - od) and 1.inv()
            }
            val jas = ByteArray(w * vyska)
            val pixely = IntArray(w * vyska)
            val y = i.dataY.duplicate()
            for (rr in 0 until vyska) {
                y.position((od + rr) * i.strideY + odX)
                y.get(jas, rr * w, w)
            }
            for (k in 0 until w * vyska) {
                val v = jas[k].toInt() and 0xFF
                pixely[k] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            val uw = w / 2
            val uh = vyska / 2
            val uArr = ByteArray(uw * uh)
            val vArr = ByteArray(uw * uh)
            val bu = i.dataU.duplicate(); val bv = i.dataV.duplicate()
            for (rr in 0 until uh) {
                bu.position((od / 2 + rr) * i.strideU + odX / 2); bu.get(uArr, rr * uw, uw)
                bv.position((od / 2 + rr) * i.strideV + odX / 2); bv.get(vArr, rr * uw, uw)
            }
            return Vyrez(Bitmap.createBitmap(pixely, w, vyska, Bitmap.Config.ARGB_8888), jas, w, vyska, uArr, vArr, uw, uh)
        } finally { i.release() }
    }

    private fun onUdalost(udalost: Event) {
        when (udalost) {
            is ConnectedEvent -> {
                zapis("[PŘIPOJENO k PS5]")
                posledniSnimekKdy = System.currentTimeMillis()
                pripojenoKdy = posledniSnimekKdy
                // 60 s stabilního spojení = televize to zvládá → automatické spouštění povolit
                ui.postDelayed({ if (session != null) runCatching { AutoStart.uspech(this) } }, 60_000)
                ui.removeCallbacks(kontrolaObrazu)
                if (!telefon && !obrazPresGl) ui.postDelayed(kontrolaObrazu, 15_000)
                // druhé zařízení se mohlo mezitím samo připojit zpátky → ještě jednou ho odpojit
                if (chciPS5) Predavani.posli()
                stav = "Připojeno k PS5 ✔ – čtu titulky"
                neuspechu = 0
                ui.removeCallbacks(ohrivac)
                // spojení s překladačem připravit hned (ne až za minutu) a čtečku "zahřát",
                // ať jsou rychlé už první věty hry
                ui.post(ohrivac)
                ui.postDelayed({ zahrejCtecku() }, 1500)
                // hláška "překlad aktivní" se neukazuje – stav je vidět v aplikaci (zelený řádek)
                // za 10 s zkontrolujeme, jestli opravdu chodí obraz
                ui.postDelayed({
                    if (session != null && snimkuCelkem == 0) {
                        stav = "Připojeno, ale nechodí žádný obraz ✘"
                        ukaz(stav, 10000)
                    }
                }, 10000)
            }
            is LoginPinRequestEvent -> {
                automat = false
                zastav("PS5 chce PIN pro přihlášení – zatím nepodporováno ✘")
                stopSelf()
            }
            is QuitEvent -> {
                zapis("[UKONČENO: ${udalost.reason} / ${udalost.reasonString}]")
                // PS5 šla spát / vypnula se → potichu, aplikace zase čeká
                // srozumitelně česky a s tím, že se aplikace připojí znovu SAMA (lidé jinak hned
                // vynucují ukončení / restartují PS5 a nepočkají)
                val duvod = "${udalost.reasonString ?: ""} ${udalost.reason}".lowercase()
                val zprava = when {
                    // déle než ~45 s obsazeno → nejde o staré spojení, PS5 opravdu používá jiné zařízení
                    "already in use" in duvod && neuspechu >= 6 ->
                        "PS5 používá jiné zařízení: televize s PlayCaption v režimu „Jen televize“, aplikace PS Remote Play nebo PS Portal. Zavři ho (v televizi přepni na „Titulky z telefonu“) – pak se připojím sám."
                    "already in use" in duvod ->
                        "PS5 ještě drží předchozí spojení – za chvíli se připojím znovu sám, nic nedělej."
                    "session request" in duvod || "ctrl" in duvod ->
                        "PS5 spojení zatím odmítla – zkusím to znovu sám za pár vteřin."
                    else ->
                        "Spojení s PS5 se přerušilo (např. přepnutí účtu na PS5) – připojím se znovu sám."
                }
                zastav(if (automat) zprava else "Remote Play ukončeno: ${udalost.reasonString ?: udalost.reason}", ukazat = !automat)
                // PS5 drží staré spojení → zkoušet každých 5 s, ať se připojíme hned, jak ho pustí
                znovuPozdeji(rychle = "already in use" in duvod)
            }
            else -> {}
        }
    }

    /** Ukončit všechna spojení kromě aktuálního (visící spojení drží PS5 obsazenou). */
    private fun ukonciStareSpojeni() {
        val stara = synchronized(vsechnaSpojeni) {
            vsechnaSpojeni.filter { it !== session }.also { vsechnaSpojeni.removeAll(it) }
        }
        if (stara.isEmpty()) return
        zapis("[ukončuji ${stara.size} staré spojení s PS5]")
        ukonciVPozadi(stara)
    }

    private fun zastav(zprava: String, ukazat: Boolean = true) {
        drzWifi(false)
        bezi = false
        stav = zprava
        if (ukazat) ukaz(zprava, 10000) else ukaz("", 0)
        // telefon posílá titulky do televize → spojení s PS5 skončilo (PS5 usnula, přepnutí účtu…)
        // → smazat i nápovědu a volby, jinak by na televizi zůstaly viset
        if (telefon) runCatching {
            TelefonTitulky.napoveda = ""
            Propojeni.Vysilac.nastavVolby(emptyList(), -1)
        }
        val xb = xbox
        xbox = null
        xb?.zastav()
        val s = session
        session = null
        if (s != null) synchronized(vsechnaSpojeni) { vsechnaSpojeni.remove(s) }
        ukonciStareSpojeni()
        if (s != null) ukonciVPozadi(listOf(s))
    }

    /**
     * Ukončení spojení s PS5 může trvat dlouho (knihovna čeká na síť). Dřív běželo na stejném vlákně
     * jako hledání PS5 → hledání se zaseklo a aplikace „čekala navždy“ (pomohlo jen Vynutit ukončení).
     * Teď vlastní vlákno – hledání a nové připojení na něj nečekají.
     */
    private fun ukonciVPozadi(spojeni: List<Session>) {
        Thread {
            for (s in spojeni) {
                runCatching { s.setSurface(null) }
                runCatching { s.stop() }
                runCatching { s.dispose() }
            }
        }.apply { isDaemon = true; name = "ps5-konec" }.start()
    }

    /** Běží na telefonu → překlad se ukazuje na bílé obrazovce TelefonActivity, ne přes obraz. */
    private val telefon by lazy { Rezim.jeTelefon(this) }

    /** Ruční oblast titulků (jen telefon, obraz přes OpenGL) – null = automaticky. */
    private fun rucniOblast(): FloatArray? =
        if (telefon && OblastTitulku.rucne) OblastTitulku.oblast.copyOf() else null
    @Volatile private var oblastKlic = "auto"

    /**
     * Rozlišení obrazu z PS5. Telefon dostává 720p (ostřejší písmo, víc podobné tomu, co je
     * na televizi přes HDMI) – výkon i Wi-Fi telefonu to zvládnou. Televize zůstává na 540p.
     */
    private val sirkaStreamu get() = if (telefon) SIRKA_TELEFON else SIRKA
    private val vyskaStreamu get() = if (telefon) VYSKA_TELEFON else VYSKA
    /** Zvětšení pásu před čtením – u většího obrazu menší, ať čtení trvá stejně dlouho. */
    private val meritkoCteni get() = MERITKO_CTENI * SIRKA / (if (xbox != null && sirkaXbox > 0) sirkaXbox else sirkaStreamu)
    /** Šířka obrazu z Xboxu (720p = 1280) – čte se pak stejně velké písmo jako u PS5, ne větší a pomalejší. */
    @Volatile private var sirkaXbox = 0

    private fun ukaz(text: String, doba: Long = 6000, box: FloatArray? = null, radku: Int = 1, barvyJmen: IntArray? = null) {
        if (telefon) { TelefonTitulky.nastav(text, doba, barvyJmen); return }
        val i = Intent(this, OverlayService::class.java)
            .setAction(OverlayService.ACTION_SET_TEXT)
            .putExtra(OverlayService.EXTRA_TEXT, text)
            .putExtra(OverlayService.EXTRA_DOBA, doba)
        if (box != null) {
            i.putExtra(OverlayService.EXTRA_BOX, box)
            i.putExtra(OverlayService.EXTRA_RADKU, radku)
        }
        if (barvyJmen != null) i.putExtra(OverlayService.EXTRA_BARVY_JMEN, barvyJmen)
        // overlay většinou už běží → stačí startService; když ne, zkusit jako foreground službu
        runCatching { startService(i) }.onFailure { runCatching { startForegroundService(i) } }
    }

    private fun startAsForeground() {
        val kanal = "titulky_remote"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(kanal, "Remote Play", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, kanal)
            .setContentTitle("Titulky TV – čtu titulky z PS5")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(3, n)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        drzWifi(false)
        automat = false
        aktivni = false
        ui.removeCallbacks(hlidac)
        posluchac?.zavri()
        posluchac = null
        if (pripojeno()) zastav("Remote Play zastaveno.")
        bg.post {
            runCatching { reader?.close() }
            runCatching { snimac?.zavri() }
            ctecky.forEach { it.zavri() }
            prekladac.zavri()
            vlakno.quitSafely()
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "cz.titulky.tv.REMOTE_STOP"
        const val ACTION_PREPNOUT = "cz.titulky.tv.REMOTE_PREPNOUT"

        /** Volba tlačítkem ⇄: když jsou zapnuté obě konzole, hrát na Xboxu (jinak PS5). */
        fun preferXbox(context: android.content.Context) =
            context.getSharedPreferences("konzole", android.content.Context.MODE_PRIVATE).getBoolean("xbox", false)

        /** Přepnout překlad na druhou konzoli (a připojit se k ní, když překlad běží). */
        fun prepni(context: android.content.Context, naXbox: Boolean) {
            context.getSharedPreferences("konzole", android.content.Context.MODE_PRIVATE).edit().putBoolean("xbox", naXbox).apply()
            naXboxu = naXbox
            if (aktivni) runCatching {
                context.startForegroundService(Intent(context, RemotePlayService::class.java).setAction(ACTION_PREPNOUT))
            }
        }
        /** Jak často kontrolovat, jestli už je PS5 zapnutá. */
        const val CEKANI_NA_PS5_MS = 10_000L
        /** Po výpadku spojení počkat, než si PS5 uklidí (jinak "Server shutting down"). */
        const val PO_VYPADKU_MS = 45_000L
        /** Po zapnutí PS5 počkat, než na ní naběhne Remote Play. */
        const val ROZBEH_PS5_MS = 20_000L
        /** Po probuzení Xboxu počkat, než se zapne ovladač hráče (jinak hra hlásí „reconnect the controller“). */
        const val ROZBEH_XBOX_MS = 6_000L

        /** Spustí overlay i čtení titulků (z aplikace i po zapnutí televize). */
        /** Extra: spuštěno tlačítkem Připojit → převzít PS5 od druhého zařízení. */
        const val EXTRA_PREVZIT = "prevzit"

        /**
         * Všechna spojení s PS5, která služba otevřela a ještě neukončila – i z dřívějšího běhu služby
         * (zůstávají v paměti aplikace, dokud se aplikace úplně nezavře).
         */
        private val vsechnaSpojeni = mutableListOf<Session>()

        fun spustVse(context: android.content.Context, prevzit: Boolean = false) {
            // na telefonu se přes obraz nekreslí – overlay nespouštět
            if (!Rezim.jeTelefon(context))
                runCatching { context.startForegroundService(Intent(context, OverlayService::class.java)) }
            runCatching { context.startForegroundService(Intent(context, RemotePlayService::class.java).putExtra(EXTRA_PREVZIT, prevzit)) }
        }
        const val SIRKA = 960
        const val VYSKA = 540
        const val SIRKA_TELEFON = 1280
        const val VYSKA_TELEFON = 720
        /** kb/s – pro titulky stačí málo (dřív 10000 při 1080p, 4000 při 720p). */
        const val DATOVY_TOK = 2000
        /** Slabá televize: menší datový tok = méně práce s dekódováním a méně ztrát po Wi-Fi. */
        const val DATOVY_TOK_USPORA = 1500
        /** Telefon: víc dat = ostřejší písmo i při výbuších a rychlém pohybu (Battlefield).
         *  Při 2000 se titulky v akci rozmazávaly; telefon i 5 GHz Wi-Fi 5000 zvládnou bez problému. */
        const val DATOVY_TOK_TELEFON = 5000
        const val SPODNI_CAST = 0.3f
        const val STRED_SIRKA = 0.9f
        /** Replika nevisí na obrazovce déle než tohle – co visí déle, je nápověda/menu. */
        const val MAX_DELKA_TITULKU_MS = 15_000L
        const val INTERVAL_MS = 250L
        /** Slabá televize: dívat se na obraz méně často. */
        const val INTERVAL_MS_USPORA = 600L
        const val INTERVAL_MS_TELEFON = 100L

        /** Jména postav, která překladač překládá jako běžná slova. */
        val CHYBNA_JMENA = listOf(
            "Dutch" to "holandsk|holandštin|holandčin|holanďan|nizozemsk|nizozemštin|nizozemčin",
            "Jack" to "zvedák|zdvihák",
            "Bill" to "účet|účtu|účtem|účtom|bankovk",
            "Strauss" to "pštros",
            "Swanson" to "labuť|labut",
            "Boobie" to "prsa|prsia|kozy|kozičk"
        )
        /** Zmenšení obrazu před čtením (méně bodů = rychlejší čtení, písmo je pořád dost velké). */
        const val MERITKO_CTENI = 1.4f
        /** Ukázat hned rychlý překlad z televize a pak ho nahradit lepším z Googlu. */
        const val RYCHLY_NAHLED = true
        /** Při nápovědě číst jen oblast nad ní? Vypnuto – v záznamech nenašlo nikdy nic a jen zdržovalo čtečku. */
        const val CIST_NAD_NAPOVEDOU = false
        /** Číst jen naučený pruh s dialogy? Vypnuto – v RDR2 se dialogy ukazují v různé výšce a pak se ztrácely. */
        const val UCIT_PRUH_DIALOGU = false // 720p se už nezmenšuje (písmo je velké zhruba jako dřív po zmenšení)
        /** Překládat spodní nápovědy / úkoly mise žlutě? (vypnuto – přání uživatele) */
        const val PREKLADAT_NAPOVEDY = false
        /** Pod touto výškou obrazovky (podíl) se nečte – je tam úkol mise / nápověda, ne dialog. */
        const val DOLNI_HRANICE = 0.97f

        @Volatile var bezi = false
        /** Zapnuté jsou PS5 i Xbox (→ v aplikaci tlačítko ⇄). */
        @Volatile var obeZapnute = false
        /** Právě se hraje / připojuje na Xboxu (jinak PS5). */
        @Volatile var naXboxu = false
        /** Služba běží (připojená, nebo čeká na PS5). */
        @Volatile var aktivni = false
        /** Překlad vypnutý tlačítkem v aplikaci (spojení s PS5 drží dál → zapnutí je okamžité). */
        @Volatile var pozastaveno = false
        @Volatile var stav = "Remote Play neběží."
    }
}
