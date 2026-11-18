package cz.titulky.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Párové čtení stejných herních výřezů přes produkční ML Kit včetně filtrů dialogu. */
class SvetlyDialogBenchmark {
    @Test fun porovnej() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val dir = File(inst.targetContext.filesDir, "lg-ocr")
        assumeTrue(File(dir, "manifest.json").isFile)
        val cases = JSONArray(File(dir, "manifest.json").readText())
        val out = JSONArray()
        val reader = Ctecka()
        fun read(b: Bitmap): String {
            val future = CompletableFuture<String>()
            reader.prectiPosledniTitulek(b, { future.complete(it.text) }, { future.completeExceptionally(IllegalStateException(it)) })
            return future.get(30, TimeUnit.SECONDS)
        }
        try {
            for (i in 0 until cases.length()) {
                val row = cases.getJSONObject(i)
                val bmp = BitmapFactory.decodeFile(File(dir, row.getString("id") + ".png").path)
                val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * 1.05f).toInt(), (bmp.height * 1.05f).toInt(), true)
                val w = scaled.width
                val h = scaled.height
                val raw = read(scaled)
                val pixels = IntArray(w * h)
                scaled.getPixels(pixels, 0, w, 0, 0, w, h)
                val alt = SvetlyDialog.priprav(pixels, w, h)?.let {
                    val processed = Bitmap.createBitmap(it, w, h, Bitmap.Config.ARGB_8888)
                    try { read(processed) } finally { processed.recycle() }
                }
                out.put(row.put("raw", raw).put("enhanced", alt ?: JSONObject.NULL))
                if (scaled !== bmp) scaled.recycle()
                bmp.recycle()
            }
            File(dir, "result.json").writeText(out.toString())
        } finally { reader.zavri() }
    }
}
