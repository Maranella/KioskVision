package com.maranella.kioskvision.voice

/** Which side of the conversation a transcript chunk belongs to. */
enum class TranscriptSpeaker { User, Model }

/**
 * Rebuilds whole utterances from the Live API's streamed transcript chunks.
 *
 * The API delivers a transcript a few words at a time, so one spoken sentence arrives as several
 * callbacks. Shown raw, a single reply looked like a model rambling across five lines, and a
 * scored evaluation run could not be read at all. Consecutive chunks from the same speaker are
 * therefore joined, and a new entry starts only when the speaker changes.
 *
 * Joining inserts a space when neither side of the boundary already has whitespace. Chunks
 * observed so far break at word boundaries without carrying the space, so the alternative —
 * verbatim concatenation — ran words together. The residual risk is a chunk that splits inside a
 * word, which would gain a space that was not spoken; that has not been observed, and a wrong
 * space is visible on screen rather than silently corrupting a score.
 *
 * Holds user speech, so it lives in memory only and is never logged.
 */
class TranscriptAssembler(private val limit: Int = 12) {

    /** Newest utterance first, each prefixed with its speaker, for direct display. */
    private val lines = ArrayDeque<String>()

    /** The speaker whose utterance is still being appended to, if any. */
    private var open: TranscriptSpeaker? = null

    var lastUserText: String? = null
        private set
    var lastModelText: String? = null
        private set

    val history: List<String> get() = lines.toList()

    /** Adds one streamed chunk and returns the utterance it now belongs to. */
    fun add(speaker: TranscriptSpeaker, chunk: String): String {
        if (chunk.isEmpty()) return currentBody(speaker).orEmpty()

        if (speaker == open && lines.isNotEmpty()) {
            lines.addFirst(join(lines.removeFirst(), chunk))
        } else {
            lines.addFirst(prefix(speaker) + chunk)
            open = speaker
            while (lines.size > limit) lines.removeLast()
        }

        val body = lines[0].removePrefix(prefix(speaker))
        when (speaker) {
            TranscriptSpeaker.User -> lastUserText = body
            TranscriptSpeaker.Model -> lastModelText = body
        }
        return body
    }

    private fun currentBody(speaker: TranscriptSpeaker): String? = when (speaker) {
        TranscriptSpeaker.User -> lastUserText
        TranscriptSpeaker.Model -> lastModelText
    }

    private fun join(existing: String, chunk: String): String {
        val needsSpace = existing.lastOrNull()?.isWhitespace() == false &&
            chunk.firstOrNull()?.isWhitespace() == false
        return if (needsSpace) "$existing $chunk" else existing + chunk
    }

    private fun prefix(speaker: TranscriptSpeaker) = when (speaker) {
        TranscriptSpeaker.User -> "you: "
        TranscriptSpeaker.Model -> "bot: "
    }
}
