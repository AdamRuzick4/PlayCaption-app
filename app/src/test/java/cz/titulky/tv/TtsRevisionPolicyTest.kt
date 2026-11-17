package cz.titulky.tv

import org.junit.Assert.*
import org.junit.Test

class TtsRevisionPolicyTest {
    @Test fun log120731To120738ReplaysSentenceOnlyOnceAndKeepsNewInformation() {
        // Replay actual accepted translations and completion order, using the same
        // queue planner and playback guard as TelefonActivity, not just pair matching.
        val spoken = mutableListOf<TtsRevisionPolicy.Peer>()
        val pending = mutableListOf<TtsRevisionPolicy.Peer>()
        val reads = mutableListOf<String>()
        var active: TtsRevisionPolicy.Peer? = null
        fun submit(input: String, output: String) {
            val item = TtsRevisionPolicy.Peer(input, output)
            val plan = TtsRevisionPolicy.plan(item, pending, listOfNotNull(active) + spoken)
            when (plan.action) {
                TtsRevisionPolicy.Action.DROP -> Unit
                TtsRevisionPolicy.Action.REPLACE -> pending[plan.pendingIndex] = item.copy(text = plan.text!!)
                else -> pending.add(item.copy(text = plan.text!!))
            }
        }
        fun startNext() {
            assertNull(active)
            while (pending.isNotEmpty() && TtsRevisionPolicy.covered(pending.first(), spoken)) pending.removeAt(0)
            if (pending.isNotEmpty()) active = pending.removeAt(0).also { reads += it.text }
        }
        val firstSource = "-l can see that. -Sarah Ann Connor, secretary, 35,"
        val firstOutput = "-To vidím. Sarah Ann Connor, sekretářka, 35,"
        submit(firstSource, firstOutput) // OCR#1762 12:07:31.972
        startNext()
        submit("-I can see that.", "-To vidím.") // OCR#1778 12:07:33.110
        submit("-I can see that. -Sarah Ann Connor, secretary, 35,", firstOutput) // #1787
        assertTrue("Partial OCR and its recovery must not add duplicate pending speech", pending.isEmpty())
        spoken.add(active!!); active = null // #1762 DONE 12:07:34.970
        startNext()
        assertNull(active)
        submit("-I can see that. -Sarah Ann Connor, secretary, 35, shot six times at less than ten feet. Large caliber weapon.",
            "-To vidím. Sarah Ann Connorová, sekretářka, 35 let, vystřelila šestkrát z méně než deseti stop. Velkorážní zbraň.")
        startNext()
        assertEquals(listOf(firstOutput,
            "let, vystřelila šestkrát z méně než deseti stop. Velkorážní zbraň."), reads)
    }

    @Test fun queuedFragmentCannotOverrideActiveFullSentence() {
        val full = TtsRevisionPolicy.Peer("I can see that Sarah is here", "Vidím, že Sarah je tady")
        val fragment = TtsRevisionPolicy.Peer("I can see that", "To vidím")
        assertEquals(TtsRevisionPolicy.Action.DROP,
            TtsRevisionPolicy.plan(full, listOf(fragment), listOf(full)).action)
        assertTrue(TtsRevisionPolicy.covered(fragment, listOf(full)))
    }

    @Test fun ocrIAndLConfusionRequiresContextAndNeverChangesStoryIds() {
        assertEquals(TtsRevisionPolicy.Relation.SAME,
            TtsRevisionPolicy.relation("l can see that", "I can see that", false))
        assertEquals(TtsRevisionPolicy.Relation.DIFFERENT, TtsRevisionPolicy.relation("l", "I", false))
        assertEquals(TtsRevisionPolicy.Relation.DIFFERENT,
            TtsRevisionPolicy.relation("pribeh:l.1#1", "pribeh:I.1#1", true))
    }

    @Test fun pendingGrowthWithoutAnySpokenPrefixRetainsWholeSentence() {
        val first = TtsRevisionPolicy.Peer("I can see that", "To vidím")
        val full = TtsRevisionPolicy.Peer("I can see that Sarah is here", "Vidím, že Sarah je tady")
        val plan = TtsRevisionPolicy.plan(full, listOf(first), emptyList())
        assertEquals(TtsRevisionPolicy.Action.REPLACE, plan.action)
        assertEquals(full.output, plan.text)
        assertFalse(TtsRevisionPolicy.covered(full, listOf(first)))
    }

    @Test fun log115023RewordedPrefixMustNotDropCorpseContinuation() {
        val source = "Himmi, so whnat I saw the otier day was like"
        val d = TtsRevisionPolicy.resolve(source, "Himmi, jak jsem viděl ten otierský den",
            "$source when a corpse briefly reanimates itself and terrorizes everyone, right?",
            "Himmi, tak co jsem viděl, ten otierský den byl, jako když se mrtvola nakrátko oživí a všechny terorizuje, že?",
            false)
        assertTrue(d.related)
        assertEquals("byl, jako když se mrtvola nakrátko oživí a všechny terorizuje, že?", d.text)
    }

    @Test fun unalignableGrowthPreservesNewDialogue() {
        val d = TtsRevisionPolicy.resolve("Wait brother", "Počkej, bratře",
            "Wait brother we need to leave now", "Brácho, musíme okamžitě odejít, počkej na mě.", false)
        assertEquals("Brácho, musíme okamžitě odejít, počkej na mě.", d.text)
    }

    @Test fun unalignableGrowthKeepsPendingUnspokenTextToo() {
        val d = TtsRevisionPolicy.resolve("Wait brother come here", "Počkej, bratře, pojď sem",
            "Wait brother come here we need to leave now", "Brácho, přistup blíž, musíme pryč.", false, "pojď sem")
        assertEquals("pojď sem Brácho, přistup blíž, musíme pryč.", d.text)
    }

    @Test fun repeatedAnchorDoesNotCutAwayNewWordsByGuessing() {
        val full = "Já dnes opravdu přišel za tebou a zítra přišel za tebou znovu."
        assertEquals(full, TtsRevisionPolicy.resolve("I came to you", "Já přišel za tebou",
            "I came to you and will come again tomorrow", full, false).text)
    }
    // Real 0.21.20 log, 2026-11-17 11:33:30–11:34:10. Sources below include
    // the previous segment, as RemotePlayService now passes to both translation and TTS.
    @Test fun logOkayIsNotReadTwice() {
        assertEquals("nazvěte to, jak chcete.", TtsRevisionPolicy.resolve(
            "Okay..", "Dobře..", "Okay... you call it whatever you wanna call it.",
            "Dobře... nazvěte to, jak chcete.", false).text)
    }

    @Test fun logSlippersRollingOverlapIsNotReadTwice() {
        assertEquals("andl zíral na zasraný západ slunce?", TtsRevisionPolicy.resolve(
            "Airn't you a bit younig for the pipe, slippers,",
            "Nevdechni si trochu mladej na dýmku, pantofle,",
            "slippers, andl starin' at a fuckin' sunset?",
            "pantofle, andl zíral na zasraný západ slunce?", false).text)
    }

    @Test fun logYearsPendingSentenceReplacedBeforeItStarts() {
        val old = "ach jo, v této hře jsem už mnoho let,"
        val full = "ach jo, v této hře jsem už mnoho let a vyvázl jsem živý."
        assertEquals(full, TtsRevisionPolicy.resolve(
            "oukhow, I've been in this game fora lot of years,", old,
            "oukhow, I've been in this game fora lot of years, and I got out alive.", full, false, old).text)
        assertEquals("a vyvázl jsem živý.", TtsRevisionPolicy.resolve(
            "oukhow, I've been in this game fora lot of years,", old,
            "oukhow, I've been in this game fora lot of years, and I got out alive.", full, false).text)
    }

    @Test fun logWorkHardIsNotReadTwice() {
        assertEquals("znič každého, koho máš", TtsRevisionPolicy.resolve(
            "Xou work hard,", "Xou tvrdě pracuj,",
            "Xou work hard, screw over everybody that you-ve",
            "Xou tvrdě pracuj, znič každého, koho máš", false).text)
    }

    @Test fun rollingPendingMergeKeepsUnreadBeginningThroughThreeSegments() {
        val b = TtsRevisionPolicy.resolve("Bring shoes and slippers", "Přines boty a pantofle",
            "slippers and socks", "pantofle a ponožky", false, "Přines boty a pantofle")
        assertEquals("Přines boty a pantofle a ponožky", b.text)
        val c = TtsRevisionPolicy.resolve("slippers and socks", "pantofle a ponožky",
            "socks and gloves", "ponožky a rukavice", false, b.text)
        assertEquals("Přines boty a pantofle a ponožky a rukavice", c.text)
    }

    @Test fun sourceOverlapWithoutSpokenOverlapDoesNotSilenceDialogue() {
        assertFalse(TtsRevisionPolicy.resolve("Look at that door", "Podívej se na ty dveře",
            "Door number four is locked", "Čtvrté dveře jsou zamčené", false).related)
    }

    @Test fun overlappingWordsNeverMergeDistinctStoryPages() {
        assertFalse(TtsRevisionPolicy.resolve("pribeh:s.1#1", "Přines boty a pantofle",
            "pribeh:s.1#2", "pantofle a ponožky", true).related)
    }

    @Test fun growingSubtitleReadsOnlyNewEnd() {
        val d = TtsRevisionPolicy.resolve("Wait a minute", "Počkej přece",
            "Wait a minute you cannot mean that", "Počkej přece, to tak nemyslíš!", false)
        assertTrue(d.related)
        assertEquals("to tak nemyslíš!", d.text)
    }

    @Test fun translationSynonymDoesNotReplaySameSource() {
        assertNull(TtsRevisionPolicy.resolve("Come here brother", "Pojď sem, bratře.",
            "Come here brother", "Pojď sem, brácho!", false).text)
    }

    @Test fun entirelyRewordedTranslationDoesNotReplaySameSource() {
        assertNull(TtsRevisionPolicy.resolve("Come here brother", "Pojď sem, bratře.",
            "Come here brother", "Brácho, pojď ke mně.", false).text)
    }

    @Test fun synonymInGrowingPrefixKeepsNewWords() {
        assertEquals("pojď sem!", TtsRevisionPolicy.resolve("Wait brother", "Počkej, bratře",
            "Wait brother come here", "Počkej brácho, pojď sem!", false).text)
    }

    @Test fun pendingFullSentenceIsReplaced() {
        assertEquals("Počkej přece, to tak nemyslíš!", TtsRevisionPolicy.resolve(
            "Wait a minute", "Počkej přece", "Wait a minute you cannot mean that",
            "Počkej přece, to tak nemyslíš!", false, "Počkej přece").text)
    }

    @Test fun successiveExtensionsNeverReintroduceSpokenPrefix() {
        val second = TtsRevisionPolicy.resolve("Wait a minute", "Počkej přece",
            "Wait a minute you cannot mean that", "Počkej přece, to tak nemyslíš", false)
        val third = TtsRevisionPolicy.resolve("Wait a minute you cannot mean that",
            "Počkej přece, to tak nemyslíš", "Wait a minute you cannot mean that brother",
            "Počkej přece, to tak nemyslíš, bratře!", false, second.text)
        assertEquals("to tak nemyslíš bratře!", third.text)
        assertNull(TtsRevisionPolicy.resolve("Wait a minute you cannot mean that brother",
            "Počkej přece, to tak nemyslíš, bratře!", "Wait a minute you cannot mean that brother",
            "Počkej přece, to tak nemyslíš, brácho!", false).text)
    }

    @Test fun staleShorterVersionDoesNotReplacePendingExtension() {
        assertNull(TtsRevisionPolicy.resolve("Wait a minute come here", "Počkej přece, pojď sem",
            "Wait a minute", "Počkej přece", false, "pojď sem").text)
    }

    @Test fun realChangesAreNotMerged() {
        for ((a,b) in listOf("Open the door" to "Close the door", "Take 3 coins" to "Take 4 coins",
                "I can do this" to "I cannot do this", "Go" to "No")) {
            assertEquals(TtsRevisionPolicy.Relation.DIFFERENT, TtsRevisionPolicy.relation(a,b,false))
        }
    }

    @Test fun smallOcrTypoStillMatchesSource() {
        assertEquals(TtsRevisionPolicy.Relation.SAME,
            TtsRevisionPolicy.relation("Wait brother come here", "Wait brotber come here", false))
    }

    @Test fun storyPageIdentityIsExactEvenWhenTextRepeats() {
        assertFalse(TtsRevisionPolicy.resolve("pribeh:session.1#1", "Počkej!",
            "pribeh:session.1#2", "Počkej!", true).related)
        assertNull(TtsRevisionPolicy.resolve("pribeh:session.1#1", "Počkej!",
            "pribeh:session.1#1", "Počkej!", true).text)
    }

    @Test fun unknownSourcesDoNotSilenceNewDialogue() {
        assertFalse(TtsRevisionPolicy.resolve("", "Pojď sem", "", "Pojď sem", false).related)
    }

    @Test fun punctuationDoesNotChangeSuffixBoundaries() {
        assertEquals("pojď sem.", TtsRevisionPolicy.tail("Počkej... přece!", "Počkej přece — pojď sem."))
    }

    @Test fun catchingUpMultipliesEverySliderSpeedAndReturnsToBase() {
        for (base in listOf(0.5f, 1f, 1.4f, 1.8f, 2f)) {
            assertEquals(base, TtsRevisionPolicy.rate(base, 0, 0), 0.001f)
            assertTrue(TtsRevisionPolicy.rate(base, 6_000, 0) > base)
            assertTrue(TtsRevisionPolicy.rate(base, 30_000, 0) <= 3f)
        }
        assertEquals(1.75f, TtsRevisionPolicy.rate(1.4f, 3_000, 0), 0.001f)
        assertEquals(2.1f, TtsRevisionPolicy.rate(1.4f, 6_000, 0), 0.001f)
        assertTrue(TtsRevisionPolicy.rate(1.4f, 0, 12_000) > 1.4f)
    }
}
