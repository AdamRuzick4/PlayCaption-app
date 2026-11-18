package cz.titulky.tv

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StoryPrekladOvladaniTest {
    @Test fun automatickyAOfflineSeUloziAPoZavreniDialoguZustanou() {
        assumeFalse("Test spouštět mimo hraní", RemotePlayService.aktivni)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nast = Nastaveni(context)
        val puvodni = nast.motorPribehu
        try {
            var zmen = 0
            ActivityScenario.launch<TelefonActivity>(Intent(context, TelefonActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    val offline = PrekladPribehuDialog(activity, nast) { zmen++ }.ukaz()
                    offline.listView.performItemClick(offline.listView, 2, 2)
                    assertFalse(offline.isShowing)
                    assertEquals(MotorPribehu.OFFLINE, Nastaveni(context).motorPribehu)
                    val auto = PrekladPribehuDialog(activity, nast) { zmen++ }.ukaz()
                    auto.listView.performItemClick(auto.listView, 0, 0)
                    assertEquals(MotorPribehu.AUTO, Nastaveni(context).motorPribehu)
                    assertEquals(2, zmen)
                }
            }
        } finally { nast.motorPribehu = puvodni }
    }
}
