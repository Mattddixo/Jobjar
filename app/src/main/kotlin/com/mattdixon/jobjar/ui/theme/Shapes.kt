package com.mattdixon.jobjar.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * The app's corner-rounding scale. Every Card/Button/Surface shape should reference one of
 * these instead of an inline `RoundedCornerShape(N.dp)`, so the rounding language stays
 * consistent instead of drifting screen by screen as new UI gets added.
 */
object AppShapes {
    /** Small interactive controls - buttons that aren't the primary call to action. */
    val control = RoundedCornerShape(10.dp)

    /** Compact result/list cards - a drawn job, anything meant to read as a quick, skimmable unit. */
    val card = RoundedCornerShape(14.dp)

    /** Primary call-to-action buttons - the one action per screen that should stand out most. */
    val action = RoundedCornerShape(16.dp)

    /** Full panels and sheets - the largest surfaces on a screen. */
    val panel = RoundedCornerShape(20.dp)

    /** Fully-rounded pill shape for badges and chips. */
    val pill = RoundedCornerShape(50)

    /** Hairline elements like a progress bar's fill. */
    val hairline = RoundedCornerShape(3.dp)
}
