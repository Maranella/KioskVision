package com.maranella.kioskvision.ai

/**
 * Every model and provider identifier the app uses, in one place.
 *
 * The Live API is a Preview product: identifiers change and have no SLA, so they must not be
 * scattered through the codebase. Verified against the official documentation on 2026-10-16:
 * https://firebase.google.com/docs/ai-logic/live-api
 */
object AiConfig {

    /**
     * Gemini Live model for bidirectional audio. Preview — re-check before any release.
     *
     * Its language list includes Georgian (`ka`) for audio-generating 3.x models, but a list
     * entry is not evidence of usable quality: that is what the M1 spike measures.
     */
    const val LIVE_MODEL = "gemini-3.8-live"

    /**
     * Also tried: `gemini-3.1-flash-live-preview`. It connects and speaks Georgian correctly,
     * but its input transcription never produced Georgian — it auto-detected Portuguese,
     * Italian, German and English across eight utterances (R-18).
     *
     * `gemini-3.8-live` was adopted because it appeared not to have that fault. It does, in a
     * milder form: transcripts still drift to Spanish, German, Turkish and Devanagari (R-20).
     * It is kept because its *comprehension* of Georgian is stable, and comprehension — not
     * the transcript — is what the product acts on.
     */

    /**
     * The 2.5-generation identifiers in the official Android sample
     * (`gemini-2.5-flash-native-audio-preview-12-2025`, and `gemini-2.5-flash` for text) are
     * **retired for new projects**: the server answers "no longer available to new users, use
     * models/gemini-3.8-flash". The published samples are behind the service, so the model name
     * has to be taken from what the API actually accepts, not from the sample.
     */

    /** Ordinary text model, used only to turn an opaque socket close into a readable error. */
    const val TEXT_MODEL = "gemini-3.8-flash"

    /** BCP-47 code the Live API documents for Georgian. Note: `ka`, not `ka-GE`. */
    const val SPOKEN_LANGUAGE = "ka"

    /**
     * Prebuilt voice name, uppercase as the official Android sample writes it. The SDK takes
     * a free-form string, so a wrong name fails at connect time rather than at compile time.
     * Whether this voice is intelligible in Georgian is a separate question, answerable only
     * by listening to it.
     */
    const val VOICE_NAME = "FENRIR"

    /**
     * Georgian words the kiosk domain actually uses, given to the model as a vocabulary hint.
     *
     * The SDK exposes no input-language or phrase-list field (checked against the firebase-ai
     * 18.0.0 API surface), so the system instruction is the only place a decoder bias can be
     * expressed. It is needed: short utterances come back wrong or transliterated — "დენი"
     * (electricity) was transcribed as "ტენისი" (tennis), and "კომუნალური" came back as Latin
     * "comunaluri". One- and two-syllable words carry no context for the decoder to lean on, and
     * they are exactly the words a kiosk user says.
     *
     * Whether this helps is an open measurement, not an assumption; see R-19.
     */
    val DOMAIN_VOCABULARY = listOf(
        // What is being paid for.
        "დენი", "ელექტროენერგია", "გაზი", "წყალი", "ინტერნეტი", "ტელეფონი", "მობილური",
        "კომუნალური", "ტელევიზია", "დასუფთავება",
        // The transaction itself.
        "გადახდა", "გადასახადი", "ბალანსი", "თანხა", "ქვითარი", "აბონენტი", "ანგარიში",
        "ლარი", "თეთრი",
        // What the user is told to do at the screen.
        "ღილაკი", "ეკრანი", "დადასტურება", "გაუქმება", "დაბრუნება", "შეყვანა",
    )

    /**
     * Documented Live API session limits, used to drive rollover rather than discovered by
     * being disconnected. Audio-only sessions last ~15 min; a single connection ~10 min, with
     * a "going away" signal about 60 s before it ends.
     */
    const val CONNECTION_LIMIT_MINUTES = 10
    const val SESSION_LIMIT_MINUTES = 15
}
