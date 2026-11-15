package cz.titulky.tv

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LogTailTest {
    private fun withFile(body: (File) -> Unit) {
        val f = File.createTempFile("bravia-log", ".txt")
        try { body(f) } finally { f.delete() }
    }
    @Test fun boundsLargeLogAndPreservesCzechText() = withFile { f ->
        f.bufferedWriter().use { w -> repeat(100_000) { w.append("řádek $it\n") } }
        val text = LogTail.read(f, 3, 1024)
        assertEquals("řádek 99997\nřádek 99998\nřádek 99999", text)
        assertTrue(text.toByteArray().size <= 1024)
    }
    @Test fun dropsCutFirstLine() = withFile { f ->
        f.writeText("abcdef\nlast\n")
        assertEquals("last", LogTail.read(f, 20, 8))
    }
    @Test fun handlesEmptyFile() = withFile { f ->
        assertTrue(LogTail.read(f, 10).contains("prázdný"))
    }
}
