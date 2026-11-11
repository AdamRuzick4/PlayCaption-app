package cz.titulky.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View

/**
 * Volby odpovědí ve hře (dialogové volby – "Vykrást banku / Nevykrást").
 *
 * Uživatel si v telefonu (mini televize) posune žlutý šrafovaný rámeček na místo, kde hra
 * ukazuje volby, a zapne ho. Telefon pak tu oblast čte, pozná, na které volbě je hráč
 * (hra ji zvýrazní – jiná barva / podbarvení), přeloží je a pošle do televize.
 * Televize ukáže český seznam VEDLE anglických voleb (nezakrývá je).
 */
object Volby {
    /** Čte se oblast voleb? (vypínač pod mini televizí) */
    @Volatile var zapnuto = false
    /** Kde ve hře jsou volby: vlevo, nahoře, vpravo, dole – podíl šířky/výšky obrazu (0..1). */
    @Volatile var oblast = VYCHOZI.copyOf()
    /** Ukazovat žlutý rámeček na televizi pořád (jinak jen při posouvání). */
    @Volatile var ukazatNaTv = false

    val VYCHOZI get() = floatArrayOf(0.55f, 0.45f, 0.95f, 0.80f)

    private fun prefs(c: Context) = c.getSharedPreferences("gamelingo", Context.MODE_PRIVATE)

    fun nacti(c: Context) {
        val p = prefs(c)
        zapnuto = p.getBoolean("volbyZap", false)
        ukazatNaTv = p.getBoolean("volbyUkazat", false)
        val v = VYCHOZI
        oblast = floatArrayOf(
            p.getFloat("volbyL", v[0]), p.getFloat("volbyT", v[1]),
            p.getFloat("volbyR", v[2]), p.getFloat("volbyB", v[3]))
    }

    fun uloz(c: Context) {
        val o = oblast
        prefs(c).edit().putBoolean("volbyZap", zapnuto).putBoolean("volbyUkazat", ukazatNaTv)
            .putFloat("volbyL", o[0]).putFloat("volbyT", o[1])
            .putFloat("volbyR", o[2]).putFloat("volbyB", o[3]).apply()
    }

    /** Barva rámečku voleb (stejná žlutá jako nápověda). */
    val ZLUTA = 0xFFFFD54A.toInt()
}

/**
 * Žlutý šrafovaný, lehce průhledný rámeček (oblast čtení voleb).
 * Používá se v mini televizi v telefonu i na skutečné televizi při nastavování.
 */
class SrafovanyRamecek(c: Context, private val popisek: String? = null) : View(c) {
    private val d = c.resources.displayMetrics.density
    private val vypln = Paint().apply { color = 0x33FFD54A; style = Paint.Style.FILL }
    private val cara = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x77FFD54A; strokeWidth = 2f * d }
    private val okraj = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Volby.ZLUTA; style = Paint.Style.STROKE; strokeWidth = 3f * d
        pathEffect = DashPathEffect(floatArrayOf(10f * d, 6f * d), 0f)
    }
    private val pismo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Volby.ZLUTA; textSize = 12f * d; isFakeBoldText = true
        setShadowLayer(3f, 0f, 0f, 0xFF000000.toInt())
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        cv.drawRect(0f, 0f, w, h, vypln)
        // šikmé šrafování
        cv.save()
        cv.clipRect(0f, 0f, w, h)
        val krok = 14f * d
        var x = -h
        while (x < w) { cv.drawLine(x, h, x + h, 0f, cara); x += krok }
        cv.restore()
        val o = okraj.strokeWidth / 2
        cv.drawRect(o, o, w - o, h - o, okraj)
        if (popisek != null && h > pismo.textSize * 1.6f) cv.drawText(popisek, 6f * d, pismo.textSize + 4f * d, pismo)
    }
}
