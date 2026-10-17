package com.maranella.kioskvision.speech

import android.content.res.Resources
import com.maranella.kioskvision.R
import com.maranella.kioskvision.assist.GuidanceDirection
import com.maranella.kioskvision.assist.RecognitionFailure
import com.maranella.kioskvision.assist.SpokenMessage

/** Renders a [SpokenMessage] with [resources] localized for the voice (or the UI). */
object SpokenMessageText {

    fun render(resources: Resources, message: SpokenMessage): String = when (message) {
        is SpokenMessage.Direction -> resources.getString(directionText(message.direction))
        is SpokenMessage.TargetSelected -> resources.getString(
            if (message.isCritical) R.string.speak_target_selected_critical else R.string.speak_target_selected,
            message.label,
        )
        is SpokenMessage.TargetNotFound -> resources.getString(R.string.speak_target_not_found, message.query)
        is SpokenMessage.TargetAmbiguous ->
            resources.getString(R.string.speak_target_ambiguous, message.first, message.second)
        is SpokenMessage.ScreenContents -> buildString {
            if (message.reading.lines.isEmpty()) {
                append(resources.getString(R.string.speak_no_text_visible))
            } else {
                append(resources.getString(R.string.speak_screen_contents, message.reading.lines.joinToString("; ")))
            }
            if (message.reading.omittedUncertain) {
                append(' ')
                append(resources.getString(R.string.speak_screen_some_unreadable))
            }
        }
        SpokenMessage.NoTextVisible -> resources.getString(R.string.speak_no_text_visible)
        SpokenMessage.TextRecognitionNotReady -> resources.getString(R.string.speak_ocr_not_ready)
        SpokenMessage.ScreenChanged -> resources.getString(R.string.speak_screen_changed)
        SpokenMessage.ScreenOutOfFocus -> resources.getString(R.string.speak_screen_out_of_focus)
        SpokenMessage.CannotReadText -> resources.getString(R.string.speak_cannot_read_text)
        SpokenMessage.TargetPositionLost -> resources.getString(R.string.speak_target_position_lost)
        SpokenMessage.ScreenNotTracked -> resources.getString(R.string.speak_screen_not_tracked)
        is SpokenMessage.TargetOffered -> resources.getString(
            if (message.isCritical) R.string.speak_target_offered_critical else R.string.speak_target_offered,
            message.label,
        )
        is SpokenMessage.GoalSuggestion ->
            resources.getString(R.string.speak_goal_suggestion, message.label)
        SpokenMessage.GoalNoMatchHere -> resources.getString(R.string.speak_goal_no_match)
        SpokenMessage.ReadingScreen -> resources.getString(R.string.speak_reading_screen)
        SpokenMessage.HoldStill -> resources.getString(R.string.speak_hold_still)
        SpokenMessage.GuidanceStopped -> resources.getString(R.string.speak_guidance_stopped)
        SpokenMessage.NotUnderstood -> resources.getString(R.string.speak_not_understood)
        is SpokenMessage.RecognitionFailed -> resources.getString(failureText(message.reason))
    }

    fun directionText(direction: GuidanceDirection): Int = when (direction) {
        GuidanceDirection.LEFT -> R.string.speak_direction_left
        GuidanceDirection.RIGHT -> R.string.speak_direction_right
        GuidanceDirection.UP -> R.string.speak_direction_up
        GuidanceDirection.DOWN -> R.string.speak_direction_down
        GuidanceDirection.NEAR_TARGET -> R.string.speak_direction_near
        GuidanceDirection.ON_TARGET -> R.string.speak_direction_on_target
        GuidanceDirection.HAND_NOT_DETECTED -> R.string.speak_hand_not_detected
        GuidanceDirection.TARGET_UNCERTAIN -> R.string.speak_target_uncertain
    }

    private fun failureText(reason: RecognitionFailure): Int = when (reason) {
        RecognitionFailure.NoSpeech -> R.string.speak_recognition_no_speech
        RecognitionFailure.NoMatch -> R.string.speak_not_understood
        RecognitionFailure.NetworkRequired -> R.string.speak_recognition_network
        RecognitionFailure.LanguageUnsupported -> R.string.speak_recognition_language
        RecognitionFailure.ServiceUnavailable -> R.string.speak_recognition_unavailable
        RecognitionFailure.PermissionDenied -> R.string.speak_recognition_permission
        RecognitionFailure.Busy, RecognitionFailure.Other -> R.string.speak_recognition_failed
    }
}
