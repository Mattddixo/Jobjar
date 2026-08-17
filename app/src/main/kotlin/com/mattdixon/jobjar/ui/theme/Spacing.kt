package com.mattdixon.jobjar.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The app's spacing scale - every padding value and gap between elements should come from here
 * rather than a one-off dp literal, so the layout rhythm stays consistent across screens and
 * adjusting the overall density means changing one file instead of hunting through every
 * screen. Deliberately scoped to margins/padding/gaps - a control's own size (an icon, a button
 * height, the jar glyph) is a property of that control, not a spacing value, so those stay
 * contextual rather than being forced into this scale.
 */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 6.dp
    val md = 8.dp
    val lg = 12.dp
    val xl = 16.dp
    val xxl = 20.dp
    val xxxl = 24.dp
}
