package cz.titulky.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Otevře systémový výběr cíle a uloží úplný soubor TTS logu. */
class TtsLogExportActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (state != null) return
        val log = File(filesDir, "tts.log")
        if (!log.isFile || log.length() == 0L) {
            Toast.makeText(this, "TTS log je prázdný", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "PlayCaption-tts-$timestamp.log")
            }, REQUEST_SAVE)
        } catch (_: Exception) {
            Toast.makeText(this, "Výběr umístění pro TTS log se nepodařil", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SAVE) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            finish()
            return
        }
        Thread {
            val ok = runCatching {
                val source = File(filesDir, "tts.log")
                val output = contentResolver.openOutputStream(uri, "wt") ?: error("Nelze otevřít cílový soubor")
                output.use { destination -> source.inputStream().use { it.copyTo(destination) } }
            }.isSuccess
            runOnUiThread {
                if (!isDestroyed) {
                    val zprava = if (ok) "TTS log uložen" else "Uložení TTS logu se nepodařilo"
                    Toast.makeText(this, zprava, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    private companion object {
        const val REQUEST_SAVE = 1
    }
}
