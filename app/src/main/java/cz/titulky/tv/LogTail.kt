package cz.titulky.tv

import java.io.File
import java.io.RandomAccessFile

/** Read only a bounded suffix, even when the native log has grown very large. */
object LogTail {
    fun read(file: File, maxLines: Int, maxBytes: Int = 128 * 1024): String {
        require(maxLines > 0 && maxBytes > 0)
        if (!file.exists()) return "(soubor ${file.name} neexistuje)"
        return RandomAccessFile(file, "r").use { input ->
            val start = maxOf(0L, input.length() - maxBytes)
            input.seek(start)
            val data = ByteArray((input.length() - start).toInt())
            input.readFully(data)
            val decoded = data.toString(Charsets.UTF_8)
            val complete = if (start == 0L) decoded else decoded.substringAfter('\n', "")
            val lines = java.util.ArrayDeque<String>()
            java.io.BufferedReader(java.io.StringReader(complete)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    if (lines.size == maxLines) lines.removeFirst()
                    lines.addLast(line)
                }
            }
            lines.joinToString("\n").ifBlank { "(prázdný nebo příliš dlouhý řádek)" }
        }
    }
}
