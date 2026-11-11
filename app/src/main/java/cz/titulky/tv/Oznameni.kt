package cz.titulky.tv

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/** Krátké oznámení přes celou televizi (černé pole, zelený text), samo zmizí. */
object Oznameni {
    fun ukaz(context: Context, text: String, dobaMs: Long = 4000) {
        val c = context.applicationContext
        if (!Settings.canDrawOverlays(c)) return
        val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = c.resources.displayMetrics.density
        val pole = TextView(c).apply {
            this.text = text
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#3CFF6E"))
            gravity = Gravity.CENTER
            // lišta "vyjíždí" z horního okraje: nahoře rovná, dole kulatá
            val r = 26 * d
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
                setStroke((2 * d).toInt(), Color.parseColor("#3CFF6E"))
            }
            setPadding((40 * d).toInt(), (18 * d).toInt(), (40 * d).toInt(), (18 * d).toInt())
            elevation = 12 * d
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = -(3 * d).toInt()   // horní zelený okraj schovat za hranu obrazovky
        }
        runCatching {
            pole.visibility = android.view.View.INVISIBLE
            wm.addView(pole, lp)
            pole.post {
                // vyjet shora dolů…
                pole.translationY = -pole.height.toFloat()
                pole.visibility = android.view.View.VISIBLE
                pole.animate().translationY(0f).setDuration(450)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                // …chvíli zůstat a zase zajet nahoru
                Handler(Looper.getMainLooper()).postDelayed({
                    pole.animate().translationY(-pole.height.toFloat()).setDuration(400)
                        .setInterpolator(android.view.animation.AccelerateInterpolator())
                        .withEndAction { runCatching { wm.removeView(pole) } }.start()
                }, dobaMs)
            }
        }
    }
}
