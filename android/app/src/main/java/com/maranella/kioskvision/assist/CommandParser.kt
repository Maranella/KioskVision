package com.maranella.kioskvision.assist

sealed interface AssistCommand {
    /** Find a visible option. [query] is normalized and has filler words removed. */
    data class SelectTarget(val query: String) : AssistCommand
    data object ReadScreen : AssistCommand
    data object Repeat : AssistCommand
    data object Stop : AssistCommand

    /**
     * The user agreed to be guided to the control that was just offered.
     *
     * Needed because nothing is guided to until they say so (M6), and without a local word for
     * "yes" the offline path could offer a control and then have no way to accept it — „დიახ"
     * would be matched against the screen as if it were a button.
     */
    data object Confirm : AssistCommand

    /** The user declined the offered control. */
    data object Cancel : AssistCommand
    data object Unknown : AssistCommand
}

/**
 * Deterministic, local parsing of short spoken commands. No network and no language model:
 * every decision here can be reproduced from the recognized text alone.
 */
object CommandParser {

    // Checked in this order: "stop" must win over everything so it is never misread as a target.
    private val stopPhrases = listOf("გაჩერდი", "გაჩერდით", "შეჩერდი", "გააჩერე", "საკმარისია", "stop")
    private val repeatPhrases = listOf("გაიმეორე", "გაიმეორეთ", "კიდევ ერთხელ", "repeat")
    private val readScreenPhrases = listOf(
        "რა წერია", "რა არის ეკრანზე", "წაიკითხე", "წამიკითხე", "რა ღილაკებია", "what is on the screen", "read",
    )

    // Checked before a target query, so an answer to the app's own question is never searched
    // for on the screen. Cancel comes first: „არა, სხვა" is a refusal, not a request for
    // something called "სხვა".
    private val cancelPhrases = listOf(
        "არა", "არ მინდა", "არ არის", "სხვა მინდა", "არა სხვა", "no", "cancel it",
    )
    private val confirmPhrases = listOf(
        "დიახ", "კი", "ხო", "კარგი", "დამეთანხმე", "დადასტურე", "მიმიყვანე", "yes", "ok", "okay",
    )

    /** Words that express wanting/asking rather than naming an option. */
    private val fillerWords = setOf(
        "მინდა", "მსურს", "მჭირდება", "გთხოვ", "გთხოვთ", "მაჩვენე", "მიჩვენე", "მიპოვე", "მომიძებნე",
        "სად", "არის", "ღილაკი", "ღილაკზე", "ღილაკს", "დააჭირე", "მე", "რომ", "და", "ერთი",
        "i", "want", "need", "please", "the", "button", "find", "to",
    )

    fun parse(recognized: String): AssistCommand {
        val text = TextNormalizer.normalize(recognized)
        if (text.isEmpty()) return AssistCommand.Unknown
        if (stopPhrases.any { text.containsPhrase(it) }) return AssistCommand.Stop
        if (repeatPhrases.any { text.containsPhrase(it) }) return AssistCommand.Repeat
        if (readScreenPhrases.any { text.containsPhrase(it) }) return AssistCommand.ReadScreen
        if (cancelPhrases.any { text.containsPhrase(it) }) return AssistCommand.Cancel
        if (confirmPhrases.any { text.containsPhrase(it) }) return AssistCommand.Confirm

        val query = TextNormalizer.tokens(text).filterNot { it in fillerWords }.joinToString(" ")
        return if (query.isEmpty()) AssistCommand.Unknown else AssistCommand.SelectTarget(query)
    }

    private fun String.containsPhrase(phrase: String): Boolean =
        this == phrase || startsWith("$phrase ") || endsWith(" $phrase") || contains(" $phrase ")
}
