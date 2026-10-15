package com.maranella.kioskvision.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Live API streams a transcript in fragments. These pin the reassembly, because an
 * evaluation run is scored by comparing a whole utterance against what was actually spoken.
 */
class TranscriptAssemblerTest {

    @Test
    fun `consecutive chunks from one speaker become a single utterance`() {
        val assembler = TranscriptAssembler()
        assembler.add(TranscriptSpeaker.Model, "კომუნალური გადასახადების")
        assembler.add(TranscriptSpeaker.Model, "გადახდით")
        assembler.add(TranscriptSpeaker.Model, "სიამოვნებით დაგეხმარებით.")

        assertEquals(
            listOf("bot: კომუნალური გადასახადების გადახდით სიამოვნებით დაგეხმარებით."),
            assembler.history,
        )
        assertEquals(
            "კომუნალური გადასახადების გადახდით სიამოვნებით დაგეხმარებით.",
            assembler.lastModelText,
        )
    }

    @Test
    fun `a change of speaker starts a new utterance`() {
        val assembler = TranscriptAssembler()
        assembler.add(TranscriptSpeaker.Model, "გისმენთ.")
        assembler.add(TranscriptSpeaker.User, "დენის")
        assembler.add(TranscriptSpeaker.User, "გადახდა")
        assembler.add(TranscriptSpeaker.Model, "რა თანხა?")

        assertEquals(
            listOf("bot: რა თანხა?", "you: დენის გადახდა", "bot: გისმენთ."),
            assembler.history,
        )
        assertEquals("დენის გადახდა", assembler.lastUserText)
        assertEquals("რა თანხა?", assembler.lastModelText)
    }

    @Test
    fun `whitespace already carried by a chunk is not doubled`() {
        val assembler = TranscriptAssembler()
        assembler.add(TranscriptSpeaker.User, "დენის")
        assembler.add(TranscriptSpeaker.User, " გადახდა")

        assertEquals("დენის გადახდა", assembler.lastUserText)
    }

    @Test
    fun `the same speaker resuming after the other speaker starts a separate utterance`() {
        val assembler = TranscriptAssembler()
        assembler.add(TranscriptSpeaker.User, "დენი")
        assembler.add(TranscriptSpeaker.Model, "კარგი")
        assembler.add(TranscriptSpeaker.User, "ორმოცი ლარი")

        assertEquals(
            listOf("you: ორმოცი ლარი", "bot: კარგი", "you: დენი"),
            assembler.history,
        )
        // The newest user utterance replaces the older one; they are never merged across a turn.
        assertEquals("ორმოცი ლარი", assembler.lastUserText)
    }

    @Test
    fun `history is capped at the limit, dropping the oldest utterance`() {
        val assembler = TranscriptAssembler(limit = 2)
        assembler.add(TranscriptSpeaker.User, "ერთი")
        assembler.add(TranscriptSpeaker.Model, "ორი")
        assembler.add(TranscriptSpeaker.User, "სამი")

        assertEquals(listOf("you: სამი", "bot: ორი"), assembler.history)
    }

    @Test
    fun `an empty chunk changes nothing`() {
        val assembler = TranscriptAssembler()
        assembler.add(TranscriptSpeaker.User, "დენი")
        assembler.add(TranscriptSpeaker.User, "")

        assertEquals(listOf("you: დენი"), assembler.history)
        assertEquals("დენი", assembler.lastUserText)
    }
}
