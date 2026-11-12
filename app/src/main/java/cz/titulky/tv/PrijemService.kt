package cz.titulky.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Televize v režimu "titulky z telefonu": k PS5 se nepřipojuje, jen přijímá hotový
 * český text z telefonu a ukazuje ho přes obraz (stejně jako při čtení televizí).
 */
class PrijemService : Service() {

    private val ui = Handler(Looper.getMainLooper())
    private var prijimac: Propojeni.Prijimac? = null
    private val popredi by lazy { Popredi(this) }
    private val nast by lazy { Nastaveni(this) }

    // co je teď na televizi vidět – aby nic "nezamrzlo" (telefon zhasl, hra stojí na pauze…)
    private var napovedaTed = ""
    private var napovedaOd = 0L
    private var volbyVidet = false
    private var volbyOd = 0L
    private var textVidet = false

    /**
     * Hlídač "zamrznutí": každé 2 s zkontroluje, jestli něco nevisí na televizi zbytečně.
     * - telefon 5 s nic neposlal (zhasl, odešel z Wi-Fi, aplikace zavřená) → schovat vše
     * - stejná nápověda / úkol víc než 20 s → schovat (hra stojí, člověk už si ji přečetl)
     * - stejné volby víc než 45 s → schovat (hra je na pauze nebo se nehraje)
     */
    private val hlidacZamrznuti = object : Runnable {
        override fun run() {
            val ted = System.currentTimeMillis()
            val p = prijimac
            val ticho = p == null || p.posledniZprava == 0L || ted - p.posledniZprava > 5000
            if (ticho && (textVidet || napovedaTed.isNotEmpty() || volbyVidet)) {
                schovejText(); schovejNapovedu(); schovejVolby()
            } else {
                if (napovedaTed.isNotEmpty() && ted - napovedaOd > 20_000) schovejNapovedu(prosla = true)
                if (volbyVidet && ted - volbyOd > 45_000) schovejVolby()
            }
            if (bezi) ui.postDelayed(this, 2000)
        }
    }

    private fun schovejText() {
        textVidet = false
        posli(Intent(this, OverlayService::class.java)
            .setAction(OverlayService.ACTION_SET_TEXT)
            .putExtra(OverlayService.EXTRA_TEXT, "")
            .putExtra(OverlayService.EXTRA_DOBA, 0L))
    }

    /** Nápověda schovaná po 20 s – stejná se už znovu neukáže (dokud hra neukáže jinou). */
    private var napovedaSchovana = ""

    private fun schovejNapovedu(prosla: Boolean = false) {
        if (prosla) napovedaSchovana = napovedaTed
        napovedaTed = ""
        posli(Intent(this, OverlayService::class.java)
            .setAction(OverlayService.ACTION_SET_NAPOVEDA)
            .putExtra(OverlayService.EXTRA_TEXT, ""))
    }

    private fun schovejVolby() {
        volbyVidet = false
        posli(Intent(this, OverlayService::class.java)
            .setAction(OverlayService.ACTION_SET_VOLBY)
            .putExtra(OverlayService.EXTRA_VOLBY, emptyArray<String>())
            .putExtra(OverlayService.EXTRA_RAMECEK, false))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (intent?.action == ACTION_STOP) {
            zastav()
            stopSelf()
            return START_NOT_STICKY
        }
        if (prijimac == null) {
            bezi = true
            // PS5 si bere telefon → vlastní čtení televizí vypnout (kdyby běželo)
            // zastavit jen když opravdu běží – jinak by se čtečka zbytečně spouštěla (slabou TV to shodí)
            if (RemotePlayService.aktivni) runCatching { startService(Intent(this, RemotePlayService::class.java).setAction(RemotePlayService.ACTION_STOP)) }
            AutoStart.nastavZapnuto(this, false)
            prijimac = Propojeni.Prijimac(this,
                priTitulku = { text, doba, napoveda, rozvrh -> ui.post { zobraz(text, doba, napoveda, rozvrh) } },
                priVolbach = { volby, vybrana, oblast, ramecek -> ui.post { zobrazVolby(volby, vybrana, oblast, ramecek) } },
                priOblasti = { oblast, ukazat -> ui.post {
                    posli(Intent(this, OverlayService::class.java)
                        .setAction(OverlayService.ACTION_SET_OBLAST_TITULKU)
                        .putExtra(OverlayService.EXTRA_OBLAST, oblast)
                        .putExtra(OverlayService.EXTRA_RAMECEK, ukazat))
                } })
            ui.removeCallbacks(hlidacZamrznuti)
            ui.postDelayed(hlidacZamrznuti, 2000)
        }
        return START_STICKY
    }

    private fun zobraz(text: String, doba: Long, napoveda: String, rozvrh: FloatArray?) {
        // na televizi je YouTube / hlavní obrazovka → titulky z PS5 neukazovat
        // výjimka: právě upravuješ polohu v telefonu → ukázku s rámečkem ukázat vždy (i na hlavní obrazovce)
        val uprava = rozvrh != null && rozvrh.size >= 5 && rozvrh[4] == 1f
        val vstup = uprava || runCatching { popredi.jeVstupTv() }.getOrDefault(true)
        posli(Intent(this, OverlayService::class.java)
            .setAction(OverlayService.ACTION_SET_TEXT)
            .putExtra(OverlayService.EXTRA_TEXT, if (vstup) text else "")
            .putExtra(OverlayService.EXTRA_DOBA, if (doba > 0) doba else 6000L)
            .apply { if (rozvrh != null) putExtra(OverlayService.EXTRA_VLASTNI, rozvrh) })
        textVidet = vstup && text.isNotEmpty()
        var n = if (vstup && nast.napovedy) napoveda else ""
        if (n.isNotEmpty() && n == napovedaSchovana) n = "" else if (n != napovedaSchovana) napovedaSchovana = ""
        if (n != napovedaTed) {
            // nová nápověda → ukázat (a počítat 20 s od teď); stejná znovu se už po 20 s neukáže
            napovedaTed = n
            napovedaOd = System.currentTimeMillis()
            posli(Intent(this, OverlayService::class.java)
                .setAction(OverlayService.ACTION_SET_NAPOVEDA)
                .putExtra(OverlayService.EXTRA_TEXT, n))
        }
    }

    /** Volby odpovědí z telefonu → česky vedle anglických voleb (+ žlutý rámeček při nastavování). */
    private fun zobrazVolby(volby: List<String>, vybrana: Int, oblast: FloatArray, ramecek: Int) {
        val vstup = ramecek > 0 || runCatching { popredi.jeVstupTv() }.getOrDefault(true)
        val videt = vstup && volby.isNotEmpty()
        // nové volby (nebo jiná vybraná) → počítat 45 s od teď; při nastavování rámečku nehlídat
        if (videt) volbyOd = if (ramecek > 0) Long.MAX_VALUE / 2 else System.currentTimeMillis()
        volbyVidet = videt
        posli(Intent(this, OverlayService::class.java)
            .setAction(OverlayService.ACTION_SET_VOLBY)
            .putExtra(OverlayService.EXTRA_VOLBY, if (vstup) volby.toTypedArray() else emptyArray<String>())
            .putExtra(OverlayService.EXTRA_VYBRANA, vybrana)
            .putExtra(OverlayService.EXTRA_OBLAST, oblast)
            .putExtra(OverlayService.EXTRA_RAMECEK, ramecek > 0)
            .putExtra(OverlayService.EXTRA_RAMECEK_TRVALE, ramecek == 2))
    }

    private fun posli(i: Intent) {
        runCatching { startService(i) }.onFailure { runCatching { startForegroundService(i) } }
    }

    private fun zastav() {
        bezi = false
        ui.removeCallbacks(hlidacZamrznuti)
        napovedaTed = ""; volbyVidet = false; textVidet = false
        prijimac?.zavri()
        prijimac = null
        posli(Intent(this, OverlayService::class.java)
            .setAction(OverlayService.ACTION_SET_TEXT)
            .putExtra(OverlayService.EXTRA_TEXT, "")
            .putExtra(OverlayService.EXTRA_DOBA, 0L))
        posli(Intent(this, OverlayService::class.java)
            .setAction(OverlayService.ACTION_SET_VOLBY)
            .putExtra(OverlayService.EXTRA_VOLBY, emptyArray<String>())
            .putExtra(OverlayService.EXTRA_RAMECEK, false))
    }

    override fun onDestroy() {
        instance = null
        zastav()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val kanal = "titulky_prijem"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(kanal, "Titulky z telefonu", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, kanal)
            .setContentTitle("PlayCaption – titulky z telefonu")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(4, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(4, n)
    }

    companion object {
        const val ACTION_STOP = "cz.titulky.tv.PRIJEM_STOP"

        /** Běží režim "titulky z telefonu"? */
        @Volatile var bezi = false
            private set

        /** Kdy naposledy přišla zpráva od telefonu – pro stav na obrazovce. */
        fun telefonPripojen(): Boolean = instance?.prijimac?.let {
            it.posledniZprava > 0 && System.currentTimeMillis() - it.posledniZprava < 5000
        } ?: false

        @Volatile private var instance: PrijemService? = null

        fun spust(c: Context) {
            runCatching { c.startForegroundService(Intent(c, PrijemService::class.java)) }
        }

        fun zastav(c: Context) {
            // hned (ne až služba zprávu zpracuje) – jinak by hlavní obrazovka skočila zpátky na čekání
            bezi = false
            runCatching { c.startService(Intent(c, PrijemService::class.java).setAction(ACTION_STOP)) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Pady.hlidej(this)
    }
}
