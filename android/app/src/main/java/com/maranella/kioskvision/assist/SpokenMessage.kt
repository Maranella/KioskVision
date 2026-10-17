package com.maranella.kioskvision.assist

/**
 * Everything the app may say, as semantic values. The Android layer turns these into text in
 * the language of the available voice, so the logic never deals with strings.
 */
sealed interface SpokenMessage {
    data class Direction(val direction: GuidanceDirection) : SpokenMessage
    data class TargetSelected(val label: String, val isCritical: Boolean) : SpokenMessage

    /**
     * A control was found and the user is being asked whether to go ahead.
     *
     * Distinct from [TargetSelected], which announces a decision already taken. This one is a
     * question, and nothing moves until it is answered.
     */
    data class TargetOffered(val label: String, val isCritical: Boolean) : SpokenMessage

    /** A goal is still active and this screen has something that matches it. */
    data class GoalSuggestion(val label: String) : SpokenMessage

    /** The goal is still active but nothing on this screen matches it. */
    data object GoalNoMatchHere : SpokenMessage
    data class TargetNotFound(val query: String) : SpokenMessage
    data class TargetAmbiguous(val first: String, val second: String) : SpokenMessage
    data class ScreenContents(val reading: ScreenReading) : SpokenMessage
    data object NoTextVisible : SpokenMessage
    data object TextRecognitionNotReady : SpokenMessage
    data object ScreenChanged : SpokenMessage

    /** The camera cannot read the screen sharply enough to guide from it. */
    data object ScreenOutOfFocus : SpokenMessage

    /**
     * The screen is in view but OCR cannot read it — usually because the text is too small at
     * this distance. Distinct from losing the screen, and the fix is different.
     */
    data object CannotReadText : SpokenMessage

    /** The chosen button is still selected, but its position is no longer current enough. */
    data object TargetPositionLost : SpokenMessage

    /**
     * The kiosk screen itself is not being tracked, so nothing on it can be pointed at.
     *
     * Separate from [TargetPositionLost] because the user has to do something different:
     * re-frame the kiosk, rather than hold still and wait for a fresh reading.
     */
    data object ScreenNotTracked : SpokenMessage

    /** A request arrived before the screen was understood; it is being read now. */
    data object ReadingScreen : SpokenMessage

    /** The label is probably there, but the picture is moving too much to act on it. */
    data object HoldStill : SpokenMessage
    data object GuidanceStopped : SpokenMessage
    data object NotUnderstood : SpokenMessage
    data class RecognitionFailed(val reason: RecognitionFailure) : SpokenMessage
}

enum class RecognitionFailure {
    NoSpeech,
    NoMatch,
    NetworkRequired,
    LanguageUnsupported,
    ServiceUnavailable,
    PermissionDenied,
    Busy,
    Other,
}
