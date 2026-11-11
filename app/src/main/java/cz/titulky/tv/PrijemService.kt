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
            runCatching { startService(Intent(this, RemotePlayService::class.java).setAction(RemotePlayService.ACTION_STOP)) }
            AutoStart.nastavZapnuto(this, false)
            prijimac = Propojeni.Prijimac(this,
                priTitulku = { text, doba, napoveda, rozvrh -> ui.post { zobraz(text, doba, napoveda, rozvrh) } },
                priVolbach = { volby, vybrana, oblast, ramecek -> ui.post { zobrazVolby(volby, vybrana, oblast, ramecek) } })
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
        posli(Intent(this, OverlayService::class.java)
            .setAction(OverlayService.ACTION_SET_NAPOVEDA)
            .putExtra(OverlayService.EXTRA_TEXT, if (vstup && nast.napovedy) napoveda else ""))
    }

    /** Volby odpovědí z telefonu → česky vedle anglických voleb (+ žlutý rámeček při nastavování). */
    private fun zobrazVolby(volby: List<String>, vybrana: Int, oblast: FloatArray, ramecek: Int) {
        val vstup = ramecek > 0 || runCatching { popredi.jeVstupTv() }.getOrDefault(true)
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
