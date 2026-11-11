package cz.titulky.tv

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import java.io.File

/**
 * TEST: zkusí vyfotit celou obrazovku televize (i když běží HDMI s PS5).
 * Počká [ZPOZDENI_MS], aby šlo přepnout na HDMI, udělá snímek, uloží ho
 * a pokusí se z něj přečíst a přeložit titulek. Výsledek ukáže v overlayi.
 */
class SnimekService : Service() {

    private var projekce: MediaProjection? = null
    private var displej: VirtualDisplay? = null
    private var ctecka: ImageReader? = null
    private val vlakno = HandlerThread("snimek").apply { start() }
    private val handler = Handler(vlakno.looper)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        val kod = intent?.getIntExtra(EXTRA_KOD, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)

        if (kod != Activity.RESULT_OK || data == null) {
            ukaz("Test snímku: nepovoleno."); stopSelf(); return START_NOT_STICKY
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(kod, data)
        if (p == null) { ukaz("Test snímku: televize nedovolila snímání."); stopSelf(); return START_NOT_STICKY }
        projekce = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { uklid() }
        }, handler)

        val reader = ImageReader.newInstance(SIRKA, VYSKA, PixelFormat.RGBA_8888, 2)
        ctecka = reader
        displej = p.createVirtualDisplay(
            "titulky-snimek", SIRKA, VYSKA, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler
        )

        ukaz("Za ${ZPOZDENI_MS / 1000} s vyfotím obrazovku – přepni teď na PS5 (HDMI)!")
        handler.postDelayed({ vyfot() }, ZPOZDENI_MS)
        return START_NOT_STICKY
    }

    private fun vyfot() {
        val image = ctecka?.acquireLatestImage()
        if (image == null) {
            ukaz("Test snímku: nepřišel žádný obraz (systém snímání zablokoval).")
            uklid(); stopSelf(); return
        }
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * SIRKA
        val sirokaBitmapa = Bitmap.createBitmap(SIRKA + rowPadding / pixelStride, VYSKA, Bitmap.Config.ARGB_8888)
        sirokaBitmapa.copyPixelsFromBuffer(plane.buffer)
        image.close()
        val bitmapa = Bitmap.createBitmap(sirokaBitmapa, 0, 0, SIRKA, VYSKA)
        uklid()

        // uložíme, ať se dá snímek prohlédnout v aplikaci
        runCatching {
            File(filesDir, SOUBOR).outputStream().use { bitmapa.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        val jas = prumernyJas(bitmapa)
        if (jas < 8) {
            ukaz("Snímek je ČERNÝ (jas $jas) – televize obraz z HDMI vyfotit nedovolí. Zbývá Remote Play.")
            stopSelf(); return
        }

        // obraz máme! zkusíme rovnou přečíst a přeložit titulek
        ukaz("Snímek má obraz (jas $jas)! Čtu titulek…")
        val ocr = Ctecka()
        ocr.prectiTitulek(bitmapa,
            hotovo = { text ->
                ocr.zavri()
                if (text.isBlank()) {
                    ukaz("Obraz z HDMI JDE vyfotit 🎉 (titulek jsem v tu chvíli nenašel).")
                    stopSelf()
                } else {
                    val prekladac = Prekladac()
                    prekladac.preloz(text,
                        hotovo = { cesky -> prekladac.zavri(); ukaz(cesky, 10000); stopSelf() },
                        chyba = { prekladac.zavri(); ukaz("Přečteno: $text"); stopSelf() })
                }
            },
            chyba = { ocr.zavri(); ukaz("Obraz z HDMI JDE vyfotit 🎉 (čtení selhalo)."); stopSelf() })
    }

    private fun prumernyJas(b: Bitmap): Int {
        var soucet = 0L; var pocet = 0
        for (y in 0 until b.height step 20) for (x in 0 until b.width step 20) {
            val c = b.getPixel(x, y)
            soucet += ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3
            pocet++
        }
        return if (pocet == 0) 0 else (soucet / pocet).toInt()
    }

    private fun ukaz(text: String, doba: Long = 8000) {
        startForegroundService(
            Intent(this, OverlayService::class.java)
                .setAction(OverlayService.ACTION_SET_TEXT)
                .putExtra(OverlayService.EXTRA_TEXT, text)
                .putExtra(OverlayService.EXTRA_DOBA, doba)
        )
    }

    private fun uklid() {
        runCatching { displej?.release() }; displej = null
        runCatching { ctecka?.close() }; ctecka = null
        runCatching { projekce?.stop() }; projekce = null
    }

    private fun startAsForeground() {
        val kanal = "titulky_snimek"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(kanal, "Snímek obrazovky", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, kanal)
            .setContentTitle("Titulky TV – test snímku")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(2, n)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        uklid()
        vlakno.quitSafely()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_KOD = "kod"
        const val EXTRA_DATA = "data"
        const val SOUBOR = "snimek.png"
        const val SIRKA = 1280
        const val VYSKA = 720
        const val ZPOZDENI_MS = 10_000L
    }
}
