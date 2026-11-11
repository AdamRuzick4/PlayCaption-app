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
    // statistika do záznamu (jednou za 30 s): kolikrát se četlo / přeskočilo
    @Volatile private var stCteni = 0
    @Volatile private var stSkipStejne = 0
    @Volatile private var stSkipVynoreni = 0
    private var stOd = 0L
    private fun statistika(ted: Long) {
        if (stOd == 0L) { stOd = ted; return }
        if (ted - stOd < 30_000) return
        val zprava = "[za 30 s: čteno $stCteni×, beze změny $stSkipStejne×, čekání na titulek $stSkipVynoreni×, druhý pokus $stKontrast×, nad nápovědou $stNad×, snímků $snimkuCelkem]"
        ui.post { zapis(zprava) }
        stCteni = 0; stSkipStejne = 0; stSkipVynoreni = 0; stKontrast = 0; stNad = 0; stOd = ted
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
            ACTION_STOP -> {
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
                if (session != null && System.currentTimeMillis() - posledniSnimekKdy > 8000) {
                    zapis("[staré spojení bez obrazu – připojuji znovu]")
                    zastav("", ukazat = false)
                }
                ui.removeCallbacks(hlidacSpojeni)
                ui.postDelayed(hlidacSpojeni, 5000)
                if (session == null) {
                    ui.removeCallbacks(hlidac)
                    // stisknuto Připojit → druhé zařízení (TV/telefon) se odpojí; chvilku mu dát
                    val prevzit = intent?.getBooleanExtra(EXTRA_PREVZIT, false) == true
                    if (prevzit) chciPS5 = true
                    if (prevzit) {
                        zapis("[přebírám PS5 od jiného zařízení]")
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
     * Hlídač "mrtvého" spojení: když televize nebo PS5 usnuly, spojení se často neukončí samo
     * (nepřijde žádná zpráva o konci) – aplikace si pak myslí, že je připojená, a nic nedělá.
     * Když 30 s nepřišel žádný obraz, spojení zahodit a zkusit se připojit znovu.
     */
    private val hlidacSpojeni: Runnable = object : Runnable {
        override fun run() {
            if (!aktivni || !automat) return
            val ted = System.currentTimeMillis()
            if (session != null && posledniSnimekKdy > 0 && ted - posledniSnimekKdy > 30_000) {
                zapis("[žádný obraz ${(ted - posledniSnimekKdy) / 1000} s – spojení je mrtvé, připojuji znovu]")
                zastav("", ukazat = false)
                neuspechu = 0
                ui.removeCallbacks(hlidac)
                ui.postDelayed(hlidac, 3000)
            }
            ui.postDelayed(this, 5000)
        }
    }

    private fun hlidej() {
        if (!automat || session != null || hledamPrave) return
        // překladač "rozehřát" hned (první překlad jinak trvá ~4 s)
        if (!rozehrato) {
            rozehrato = true
            prekladac.priprav(hotovo = { prekladac.preloz("Hello, friend.", {}, {}) }, chyba = {})
        }
        val udaje = RemotePlayUdaje(this)
        val ip = udaje.ipKonzole
        if (!udaje.sparovano || ip == null) {
            stav = "Televize ještě není spárovaná s PS5 (tlačítko 7)."
            ukazJednou(stav)
            return
        }
        hledamPrave = true
        // pojistka: zpráva "odpoj se" se mohla ztratit → při každém pokusu poslat znovu
        if (chciPS5) Predavani.posli()
        bg.post {
            // PS5 může po restartu routeru dostat jinou IP adresu → najít ji znovu a adresu si opravit
            val zapnuta = runCatching {
                val seznam = HledacPS.hledej(2000)
                val ps5 = seznam.firstOrNull { it.ip == ip } ?: seznam.firstOrNull { it.typ == "PS5" }
                if (ps5 != null && ps5.ip != ip) udaje.ipKonzole = ps5.ip
                ps5?.stav == "zapnutá"
            }.getOrDefault(false)
            ui.post {
                hledamPrave = false
                if (!automat || session != null) return@post
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
                    stav = "Čekám na zapnutí PS5…" // potichu, nic neukazovat přes televizi
                    ui.postDelayed(hlidac, CEKANI_NA_PS5_MS)
                }
            }
        }
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
    private fun znovuPozdeji() {
        if (!automat) { stopSelf(); return }
        posledniHlaska = ""
        ui.removeCallbacks(hlidac)
        // poprvé zkusit brzo, při dalších neúspěších čekat déle (PS5 si musí uklidit staré spojení)
        neuspechu++
        // PS5 po odpojení drží staré spojení ~1–2 min ("already in use") → zkoušet často
        val cekat = when (neuspechu) { 1 -> 10_000L; 2, 3, 4 -> 15_000L; in 5..8 -> 20_000L; else -> PO_VYPADKU_MS }
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
        bezi = true
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
        if (telefon) {
            zapis("[obraz: OpenGL (telefon)]")
            val g = GlSnimac(SIRKA, VYSKA) { gl ->
                try {
                    val volna = volnaCteckaProSnimek()
                    if (volna != null) zpracuj(volna, SIRKA.toFloat(), VYSKA.toFloat()) { vyrezZGl(gl) }
                } catch (e: Throwable) { chybaObrazu(e) }
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
        val profil = ConnectVideoProfile(SIRKA, VYSKA, 30, tok, Codec.CODEC_H264)
        val info = ConnectInfo(ps5 = true, host = ip, registKey = registKey, morning = rpKey, videoProfile = profil)
        try {
            val logSoubor = File(filesDir, "chiaki.log").absolutePath
            val s = Session(info, logSoubor, false)
            s.eventCallback = { udalost -> ui.post { onUdalost(udalost) } }
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

    private var snimac: GlSnimac? = null

    /** Rozhodne, jestli tenhle snímek číst; vrátí volnou čtečku, nebo null. */
    private fun volnaCteckaProSnimek(): Int? {
        snimkuCelkem++
        val ted = System.currentTimeMillis()
        posledniSnimekKdy = ted
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
        val odX = sirkaObrazu * (1 - STRED_SIRKA) / 2
        val odY = vyskaObrazu * (1 - SPODNI_CAST)
        val vyrez = try { ziskejVyrez() } catch (e: Throwable) { obsazeno[c].set(false); throw e }
        // na obrazovce se nic nezměnilo (pořád stejný titulek) → nečíst znovu,
        // čtečka je pak volná a nový titulek přečte hned, jak se objeví
        val otisk = otisk(vyrez)
        val stary = posledniOtisk
        statistika(zacatek)
        // (když minule nebylo nic, kontrolovat stejnou obrazovku méně často → čtečka je volná pro nový titulek)
        if (stary != null && zacatek - posledniCteniKdy < 3000 && stejny(stary, otisk)) {
            stSkipStejne++
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
                    pomalaCteni = if (casCteni > 2500) pomalaCteni + 1 else 0
                    if (pomalaCteni >= 3) zapniUsporu("pomalé čtení (${casCteni} ms)")
                    // v úsporném režimu, ale čte rychle (10× po sobě pod 1,5 s) → televize není slabá,
                    // příště úsporný režim nezapínat hned od začátku
                    if (uspora) {
                        rychlaCteni = if (casCteni < 1500) rychlaCteni + 1 else 0
                        if (rychlaCteni == 10) runCatching {
                            getSharedPreferences("uspora", MODE_PRIVATE).edit().putBoolean("slaba", false).apply()
                            zapis("[televize čte rychle – příště bez úsporného režimu]")
                        }
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
                    // na obrazovce už je něco jiného → starý český titulek hned schovat
                    if (prectenoPosledne.isNotEmpty() && !podobne(text, prectenoPosledne)) {
                        prectenoPosledne = ""
                        schovejCesky()
                    }
                } else {
                    prazdnychZaSebou = 0
                    val ted = System.currentTimeMillis()
                    if (podobne(text, prectenoPosledne)) {
                        // stejný text visí na obrazovce moc dlouho → není to replika, ale něco statického
                        if (ted - prvniVidenoKdy > MAX_DELKA_TITULKU_MS) {
                            statickeTexty += klic(text)
                            zapis("  → od teď ignorováno (na obrazovce přes 15 s)")
                            prectenoPosledne = ""
                            ukaz("", 0)
                        }
                        } else {
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
        if ((!predPripojenim && session == null) || !obsazeno[c].compareAndSet(false, true)) return
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
                        if (kolikrat < 3 && !uspora && (predPripojenim || session != null)) jednou() else { zahrivani = false; obsazeno[c].set(false) }
                    },
                    chyba = { zahrivani = false; obsazeno[c].set(false) })
            }.onFailure { zahrivani = false; obsazeno[c].set(false) }
        }
        jednou()
    }

    private val ohrivac: Runnable = object : Runnable {
        override fun run() {
            if (session == null) return
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

    private fun prelozAUkaz(puvodni: List<String>, box: FloatArray?, radku: Int, barvyJmen: IntArray, cislo: Long) {
        if (!nast.dialogy) return
        // Hra (např. RDR2) dělí dlouhou větu na dva titulky: "we need to" → "get that fire lit quick."
        // Druhý kousek začíná malým písmenem → přeložit ho dohromady s předchozím (zatím jen telefon).
        var repliky = puvodni
        val ted = System.currentTimeMillis()
        val pred = predchoziAnglicky
        val nova = puvodni.singleOrNull()
        if (telefon && nova != null && pred != null && ted - predchoziKdy < 8000 &&
            nova.trimStart().firstOrNull()?.isLowerCase() == true &&
            pred.trimEnd().lastOrNull() !in listOf('.', '!', '?', '"', '…')
        ) {
            repliky = listOf(pred.trimEnd() + " " + nova.trim())
            zapis("  [spojuji rozdělenou větu]")
        }
        predchoziAnglicky = puvodni.lastOrNull()
        predchoziKdy = ted
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
        if (RYCHLY_NAHLED && !uspora) prelozVse(repliky, rychle = true) { cesky -> ukazJestli(cesky, dobry = false) }
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
        val mensi = Bitmap.createScaledBitmap(v.bitmapa, (v.w * MERITKO_CTENI).toInt(), (v.h * MERITKO_CTENI).toInt(), true)
        return Pas(mensi, 0, MERITKO_CTENI)
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
        val mensi = Bitmap.createScaledBitmap(bmp, (v.w * MERITKO_CTENI).toInt(), (vys * MERITKO_CTENI).toInt().coerceAtLeast(8), true)
        return Pas(mensi, 0, MERITKO_CTENI)
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
        val mensi = Bitmap.createScaledBitmap(bmp, (v.w * MERITKO_CTENI).toInt(), (v.h * MERITKO_CTENI).toInt(), true)
        return Pas(mensi, 0, MERITKO_CTENI)
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
        val mensi = Bitmap.createScaledBitmap(pas, (sirka * MERITKO_CTENI).toInt(), (vyska * MERITKO_CTENI).toInt().coerceAtLeast(8), true)
        return Pas(mensi, od, MERITKO_CTENI, vlevo)
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

    /** Telefon: výřez titulků z obrazu v grafickém čipu (OpenGL). */
    private fun vyrezZGl(gl: GlSnimac): Vyrez {
        val celaSirka = gl.sirka
        val h = gl.vyska
        val odX = (celaSirka * (1 - STRED_SIRKA) / 2).toInt() and 1.inv()
        val w = (celaSirka * STRED_SIRKA).toInt() and 1.inv()
        val od = (h * (1 - SPODNI_CAST)).toInt() and 1.inv()
        val vyska = (h - od) and 1.inv()
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

    private fun onUdalost(udalost: Event) {
        when (udalost) {
            is ConnectedEvent -> {
                zapis("[PŘIPOJENO k PS5]")
                posledniSnimekKdy = System.currentTimeMillis()
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
                zastav("Remote Play ukončeno: ${udalost.reasonString ?: udalost.reason}", ukazat = !automat)
                znovuPozdeji()
            }
            else -> {}
        }
    }

    private fun zastav(zprava: String, ukazat: Boolean = true) {
        drzWifi(false)
        bezi = false
        stav = zprava
        if (ukazat) ukaz(zprava, 10000) else ukaz("", 0)
        val s = session
        session = null
        if (s != null) {
            bg.post {
                runCatching { s.setSurface(null) }
                runCatching { s.stop() }
                runCatching { s.dispose() }
            }
        }
    }

    /** Běží na telefonu → překlad se ukazuje na bílé obrazovce TelefonActivity, ne přes obraz. */
    private val telefon by lazy { Rezim.jeTelefon(this) }

    private fun ukaz(text: String, doba: Long = 6000, box: FloatArray? = null, radku: Int = 1, barvyJmen: IntArray? = null) {
        if (telefon) { TelefonTitulky.nastav(text, doba); return }
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
        if (session != null) zastav("Remote Play zastaveno.")
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
        /** Jak často kontrolovat, jestli už je PS5 zapnutá. */
        const val CEKANI_NA_PS5_MS = 10_000L
        /** Po výpadku spojení počkat, než si PS5 uklidí (jinak "Server shutting down"). */
        const val PO_VYPADKU_MS = 45_000L
        /** Po zapnutí PS5 počkat, než na ní naběhne Remote Play. */
        const val ROZBEH_PS5_MS = 20_000L

        /** Spustí overlay i čtení titulků (z aplikace i po zapnutí televize). */
        /** Extra: spuštěno tlačítkem Připojit → převzít PS5 od druhého zařízení. */
        const val EXTRA_PREVZIT = "prevzit"

        fun spustVse(context: android.content.Context, prevzit: Boolean = false) {
            // na telefonu se přes obraz nekreslí – overlay nespouštět
            if (!Rezim.jeTelefon(context))
                runCatching { context.startForegroundService(Intent(context, OverlayService::class.java)) }
            runCatching { context.startForegroundService(Intent(context, RemotePlayService::class.java).putExtra(EXTRA_PREVZIT, prevzit)) }
        }
        const val SIRKA = 960
        const val VYSKA = 540
        /** kb/s – pro titulky stačí málo (dřív 10000 při 1080p, 4000 při 720p). */
        const val DATOVY_TOK = 2000
        /** Slabá televize: menší datový tok = méně práce s dekódováním a méně ztrát po Wi-Fi. */
        const val DATOVY_TOK_USPORA = 1500
        const val DATOVY_TOK_TELEFON = 2000
        const val SPODNI_CAST = 0.3f
        const val STRED_SIRKA = 0.9f
        /** Replika nevisí na obrazovce déle než tohle – co visí déle, je nápověda/menu. */
        const val MAX_DELKA_TITULKU_MS = 15_000L
        const val INTERVAL_MS = 250L
        /** Slabá televize: dívat se na obraz méně často. */
        const val INTERVAL_MS_USPORA = 600L
        const val INTERVAL_MS_TELEFON = 140L

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
        /** Služba běží (připojená, nebo čeká na PS5). */
        @Volatile var aktivni = false
        /** Překlad vypnutý tlačítkem v aplikaci (spojení s PS5 drží dál → zapnutí je okamžité). */
        @Volatile var pozastaveno = false
        @Volatile var stav = "Remote Play neběží."
    }
}
