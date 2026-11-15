package cz.titulky.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Uloží přibalený nepodepsaný WGT tam, kam uživatel ukáže; sám nic neinstaluje. */
class SamsungExportActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (state != null) return
        try {
            val balicek = SamsungBalicek.zAssets(this)
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, balicek.soubor)
            }, 1)
        } catch (_: Exception) { chyba(); finish() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request != 1) return
        val uri = data?.data
        if (result != RESULT_OK || uri == null) { finish(); return }
        Thread {
            val ok = runCatching {
                val balicek = SamsungBalicek.zAssets(this)
                val output = contentResolver.openOutputStream(uri, "wt") ?: error("No document stream")
                output.use { it.write(balicek.data) }
            }.isSuccess
            runOnUiThread {
                if (!isDestroyed) {
                    if (ok) Toast.makeText(this, Texty.t(this, "smWgtUlozeno"), Toast.LENGTH_LONG).show() else chyba()
                    finish()
                }
            }
        }.start()
    }

    private fun chyba() = Toast.makeText(this, Texty.t(this, "smWgtChyba"), Toast.LENGTH_LONG).show()
}
