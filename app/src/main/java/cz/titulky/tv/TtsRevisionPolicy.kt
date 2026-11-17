package cz.titulky.tv

import java.text.Normalizer
import java.util.Locale

/** Local subtitle revision matching. Never applies fuzzy matching to story page IDs. */
internal object TtsRevisionPolicy {
    enum class Relation { DIFFERENT, SAME, EXTENDED, SHORTER, OVERLAP }
    data class Decision(val related: Boolean, val text: String?, val reason: String)
    data class Peer(val input: String, val output: String, val text: String = output, val story: Boolean = false)
    enum class Action { NEW, DROP, REPLACE, CONTINUE }
    data class Plan(val action: Action, val text: String?, val reason: String, val pendingIndex: Int = -1)
    private val word = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}]+)?")
    private fun key(s: String) = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace('’', '\'')
    private fun words(s: String) = word.findAll(s).map { key(it.value) }.toList()
    private fun sourceWords(s: String): List<String> {
        val tokens = words(s)
        // OCR's isolated lowercase l commonly stands for English I. Require surrounding
        // words; never apply this to IDs, numbers, or an isolated one-letter utterance.
        return if (tokens.size >= 3) tokens.map { if (it == "l") "i" else it } else tokens
    }

    private fun sameWord(a: String, b: String): Boolean = a == b ||
        (a.length >= 5 && b.length >= 5 && a.none(Char::isDigit) && b.none(Char::isDigit) &&
            OcrDedup.podobne(a, b, 35))

    fun relation(previous: String, current: String, story: Boolean): Relation {
        if (previous.isBlank() || current.isBlank()) return Relation.DIFFERENT
        if (story) return if (previous == current) Relation.SAME else Relation.DIFFERENT
        val a = sourceWords(previous)
        val b = sourceWords(current)
        if (a.isEmpty() || b.isEmpty()) return Relation.DIFFERENT
        if (a == b) return Relation.SAME
        val shorter = if (a.size <= b.size) a else b
        val longer = if (a.size <= b.size) b else a
        if (shorter.size == longer.size) {
            // Do not erase a changed number, negation, or short command through edit-distance matching.
            if (shorter.size >= 2 && shorter.indices.all { sameWord(shorter[it], longer[it]) })
                return Relation.SAME
        } else if (shorter.sumOf { it.length } >= 4 &&
            shorter.indices.all { sameWord(shorter[it], longer[it]) }) {
            // A confirmed translated prefix can be a single word ("Okay..." / "Dobře...").
            return if (b.size > a.size) Relation.EXTENDED else Relation.SHORTER
        }
        // Translation uses a rolling pair: A+B, then B+C. The shared B is already covered.
        // Require an exact source overlap; spoken overlap is independently checked below.
        for (count in minOf(a.size, b.size - 1) downTo 1) {
            val suffix = a.takeLast(count)
            if (suffix.sumOf { it.length } >= 4 && suffix == b.take(count)) return Relation.OVERLAP
        }
        return Relation.DIFFERENT
    }

    private fun spokenWord(s: String) = when (val k = key(s)) {
        "bratre", "bracho" -> "bratre"
        else -> k
    }

    /** Keeps punctuation and original spelling in speech; normalisation is comparison-only. */
    fun tail(previous: String, current: String): String? {
        val a = word.findAll(previous).toList()
        val b = word.findAll(current).toList()
        if (a.isEmpty() || b.size < a.size) return null
        if (!a.indices.all { spokenWord(a[it].value) == spokenWord(b[it].value) }) return null
        if (a.size == b.size) return ""
        return current.substring(b[a.size].range.first).trim()
    }

    private fun overlapTail(previous: String, current: String): String? {
        val a = word.findAll(previous).map { spokenWord(it.value) }.toList()
        val b = word.findAll(current).toList()
        for (count in minOf(a.size, b.size - 1) downTo 1) {
            if (a.takeLast(count) == b.take(count).map { spokenWord(it.value) })
                return current.substring(b[count].range.first).trim()
        }
        return null
    }

    /** A growing source may rephrase the translated prefix. Align its terminal phrase,
     * supported by ordered matches across the old text, before cutting anything away. */
    private fun revisedTail(previous: String, current: String): String? {
        val a = word.findAll(previous).map { spokenWord(it.value) }.toList()
        val matches = word.findAll(current).toList()
        val b = matches.map { spokenWord(it.value) }
        if (a.size < 3 || b.size <= a.size) return null
        // A preceding proper name may inflect (Connor → Connorová) while the final
        // two words remain unchanged (sekretářka, 35). Keep the global coverage checks.
        var ends = emptyList<Int>()
        for (anchorSize in minOf(3, a.size) downTo 2) {
            val anchor = a.takeLast(anchorSize)
            if (anchor.sumOf { it.length } < 8) continue
            ends = (anchorSize until b.size).filter { end ->
                end <= a.size * 2 + 3 && b.subList(end - anchorSize, end) == anchor
            }
            if (ends.isNotEmpty()) break
        }
        // Repeated anchors are ambiguous: do not guess which occurrence was already spoken.
        if (ends.size != 1) return null
        val end = ends.single()
        var row = IntArray(end + 1)
        for (token in a) {
            val next = IntArray(end + 1)
            for (j in 1..end) next[j] = if (token == b[j - 1]) row[j - 1] + 1
                else maxOf(row[j], next[j - 1])
            row = next
        }
        if (row[end] * 100 < a.size * 65 || row[end] * 100 < end * 60) return null
        return current.substring(matches[end].range.first).trim()
    }

    fun resolve(previousInput: String, previousOutput: String, input: String, output: String,
        story: Boolean, pendingText: String? = null): Decision {
        val relation = relation(previousInput, input, story)
        if (relation == Relation.DIFFERENT) return Decision(false, output, "nový titulek")
        if (relation == Relation.SHORTER) return Decision(true, null, "starší/kratší verze stejného titulku")
        val addition = if (relation == Relation.OVERLAP) overlapTail(previousOutput, output)
            else tail(previousOutput, output) ?: if (relation == Relation.EXTENDED)
                revisedTail(previousOutput, output) else null
        if (relation == Relation.OVERLAP) {
            // No matching spoken overlap: the shared English word alone is not enough
            // evidence to silence a separate sentence. Keep it as independent speech.
            if (addition.isNullOrBlank()) return Decision(false, output, "překryv zdroje bez shodného českého úseku")
            return Decision(true, if (pendingText == null) addition else "$pendingText $addition",
                "překryv konce a začátku titulků; společný úsek se neopakuje")
        }
        if (pendingText != null && words(pendingText) == words(previousOutput)) {
            if (addition == "" || (relation != Relation.EXTENDED && tail(output, previousOutput)?.isNotBlank() == true))
                return Decision(true, null, "čekající titulek už obsahuje tento text")
            return Decision(true, output, "novější překlad nahrazuje dosud nečtený titulek")
        }
        if (!addition.isNullOrBlank()) {
            return Decision(true, if (pendingText == null) addition else "$pendingText $addition",
                "doplněná věta; již čtený začátek se neopakuje")
        }
        if (relation == Relation.EXTENDED && addition == null) {
            // Proven source growth is new dialogue, not a duplicate. If alignment is
            // ambiguous, preserve it even at the cost of repeating part of the sentence.
            return Decision(true, if (pendingText == null) output else "$pendingText $output",
                "nový obsah ve zdroji; nejasně přeformulovaný začátek, zachovávám celé pokračování")
        }
        return Decision(true, null, "stejný zdroj; oprava překladu bez spolehlivě oddělitelného nového konce")
    }

    /** The same reducer drives the Android queue and timed log-replay regression tests. */
    fun plan(current: Peer, pending: List<Peer>, spoken: List<Peer>): Plan {
        val prior = spoken.filter { it.story == current.story }.map {
            resolve(it.input, it.output, current.input, current.output, current.story)
        }.filter { it.related }
        // A queued fragment is not permission to repeat a sentence already being spoken.
        prior.firstOrNull { it.text == null }?.let {
            return Plan(Action.DROP, null, "již čtený obsah má přednost před frontou; ${it.reason}")
        }
        for (i in pending.indices.reversed()) {
            val p = pending[i]
            if (p.story != current.story) continue
            val d = resolve(p.input, p.output, current.input, current.output, current.story, p.text)
            if (!d.related) continue
            return Plan(if (d.text == null) Action.DROP else Action.REPLACE, d.text, d.reason, i)
        }
        prior.firstOrNull()?.let { return Plan(Action.CONTINUE, it.text, it.reason) }
        return Plan(Action.NEW, current.text, "nový titulek")
    }

    fun covered(current: Peer, spoken: List<Peer>): Boolean = spoken.any {
        it.story == current.story && resolve(it.input, it.output, current.input, current.output, current.story)
            .let { d -> d.related && d.text == null }
    }

    fun rate(base: Float, lagMs: Long, backlogMs: Long): Float {
        val pressure = maxOf(lagMs, (backlogMs - 3_000L).coerceAtLeast(0L))
        val multiplier = when {
            pressure >= 15_000L -> 2.0f
            pressure >= 10_000L -> 1.75f
            pressure >= 6_000L -> 1.5f
            pressure >= 3_000L -> 1.25f
            else -> 1f
        }
        return (base.coerceIn(0.5f, 2f) * multiplier).coerceAtMost(3f)
    }
}
