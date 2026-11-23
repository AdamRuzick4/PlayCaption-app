package cz.titulky.tv

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class LgPruvodceIpTest {
    private fun prvky(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { prvky(v.getChildAt(it)) } else emptyList()

    @Test fun navratZJinehoKrokuZachovaAdresuAChybaNesmazePassphrase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("lg_pruvodce", Context.MODE_PRIVATE)
        val puvodni = prefs.all["krok"] as? Int
        try {
            prefs.edit().putInt("krok", 3).commit()
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    LgPruvodce(activity) {}.ukaz()
                    fun pole() = WindowInspector.getGlobalWindowViews().flatMap(::prvky)
                        .filterIsInstance<EditText>().filter { it.contentDescription == Texty.t(activity, "lgIp") ||
                            it.contentDescription == Texty.t(activity, "lgPassphrase") }
                    fun klik(klic: String) {
                        WindowInspector.getGlobalWindowViews().flatMap(::prvky).filterIsInstance<TextView>()
                            .last { it.isClickable && it.text.toString() == Texty.t(activity, klic) }.performClick()
                    }
                    pole().first().setText("192.168.1.23")
                    klik("lgRucniZaloha")
                    klik("lgZpetAutomatika")
                    assertEquals("192.168.1.23", pole().first().text.toString())
                    klik("zpet")
                    klik("lgDalsi")
                    assertEquals("192.168.1.23", pole().first().text.toString())
                    pole().first().setText("192.168.1.999")
                    pole().last().setText("ABC123")
                    klik("lgOveritTv")
                    assertEquals(Texty.t(activity, "lgChybaADRESA"), pole().first().error.toString())
                    assertEquals("ABC123", pole().last().text.toString())
                    pole().first().setText("")
                    klik("lgOveritTv")
                    assertEquals(Texty.t(activity, "lgIpChybi"), pole().first().error.toString())
                    klik("lgZavrit")
                }
            }
        } finally {
            val edit = prefs.edit()
            if (puvodni == null) edit.remove("krok") else edit.putInt("krok", puvodni)
            edit.commit()
        }
    }
}
