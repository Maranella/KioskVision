package com.maranella.kioskvision.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maranella.kioskvision.ui.theme.HighContrastAccent
import com.maranella.kioskvision.ui.theme.HighContrastOnAccent
import com.maranella.kioskvision.ui.theme.HighContrastOnSurface
import com.maranella.kioskvision.ui.theme.HighContrastSurface

/**
 * Full-screen, high-contrast explanation shown before a system permission dialog, or after a
 * denial. TalkBack reads it as: title (heading), message, primary action, secondary action.
 */
@Composable
internal fun PermissionExplanation(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(HighContrastSurface)
            .safeDrawingPadding()
            .padding(24.dp)
            // TalkBack announces the pane title once when this screen appears or changes
            // state (e.g. the system dialog closes with a denial), without an app-driven
            // live region competing with its own speech.
            .semantics { paneTitle = title },
    ) {
        // Scrollable so the text stays reachable at the largest font sizes; the buttons
        // stay last in both visual and TalkBack order.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = title,
                color = HighContrastOnSurface,
                fontSize = 30.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = message,
                color = HighContrastOnSurface,
                fontSize = 22.sp,
                lineHeight = 32.sp,
            )
        }
        LargeActionButton(
            label = actionLabel,
            onClick = onAction,
            modifier = Modifier.padding(top = 16.dp),
        )
        if (secondaryLabel != null) {
            OutlinedButton(
                onClick = onSecondary,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = HighContrastOnSurface),
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .heightIn(min = 72.dp),
            ) {
                Text(text = secondaryLabel, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun LargeActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = HighContrastAccent,
    contentColor: Color = HighContrastOnAccent,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp),
    ) {
        Text(text = label, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}
