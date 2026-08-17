package com.mattdixon.jobjar.ui.theme

import androidx.compose.ui.graphics.Color

// A genuinely monochrome palette - black, white, and grey - rather than any hue-based accent.
// Two attempts at a colorful palette both read as "too much" for what's meant to be a clean,
// production-feeling app, so this drops color entirely instead of trying to tone it down again.
// The one exception is deliberately tiny: a drawn job's card uses a slightly warm-toned grey
// instead of the cool-neutral grey everywhere else - a gentle tonal shift, not a color, so it's
// distinct without competing for attention.

// Ink - primary. Charcoal/off-white rather than literal black/white - carries the "this is the
// main action" weight that would normally come from a brand color, without the harsher contrast
// of pure black-on-white (light) or pure white-on-black (dark). Softened a second time - the
// first pass (near-black/near-white to charcoal/light-grey) still read as too intense.
val InkPrimaryLight = Color(0xFF42413F)
val InkOnPrimaryLight = Color(0xFFFFFFFF)
val InkContainerLight = Color(0xFFE4E4E4)
val InkOnContainerLight = Color(0xFF42413F)

val InkPrimaryDark = Color(0xFFC5C5C2)
val InkOnPrimaryDark = Color(0xFF1A1A1A)
val InkContainerDark = Color(0xFF333333)
val InkOnContainerDark = Color(0xFFC5C5C2)

// Slate - secondary, a cool-neutral mid grey for badges/tags.
val SlateSecondaryLight = Color(0xFF5B5B5B)
val SlateOnSecondaryLight = Color(0xFFFFFFFF)
val SlateContainerLight = Color(0xFFE9E9E9)
val SlateOnContainerLight = Color(0xFF262626)

val SlateSecondaryDark = Color(0xFF9F9F9F)
val SlateOnSecondaryDark = Color(0xFF2E2E2E)
val SlateContainerDark = Color(0xFF3A3A3A)
val SlateOnContainerDark = Color(0xFFE9E9E9)

// Taupe - tertiary, reserved solely for a drawn job's card. The only place in the app where the
// grey shifts warm instead of staying perfectly neutral - subtle side-by-side, but still a
// distinct greytone rather than either "the same grey as everything else" or an actual color.
val TaupeTertiaryLight = Color(0xFF6B6055)
val TaupeOnTertiaryLight = Color(0xFFFFFFFF)
val TaupeContainerLight = Color(0xFFEDE7E1)
val TaupeOnContainerLight = Color(0xFF2A231D)

val TaupeTertiaryDark = Color(0xFFB0A498)
val TaupeOnTertiaryDark = Color(0xFF352D25)
val TaupeContainerDark = Color(0xFF3D362F)
val TaupeOnContainerDark = Color(0xFFEDE7E1)

// Neutrals - background/surface family. Clean, true-ish greys (a hair warmer than pure cold
// grey, not the tan/parchment of an earlier attempt).
val NeutralBackgroundLight = Color(0xFFFAFAF9)
val NeutralOnBackgroundLight = Color(0xFF1C1C1B)
val NeutralSurfaceVariantLight = Color(0xFFE3E2E0)
val NeutralOnSurfaceVariantLight = Color(0xFF484745)
val NeutralSurfaceContainerLowLight = Color(0xFFF4F4F3)
val NeutralSurfaceContainerLight = Color(0xFFEEEEEC)
val NeutralSurfaceContainerHighLight = Color(0xFFE8E8E6)
val NeutralSurfaceContainerHighestLight = Color(0xFFE2E2E0)
val NeutralOutlineLight = Color(0xFF787774)
val NeutralOutlineVariantLight = Color(0xFFC7C6C3)

val NeutralBackgroundDark = Color(0xFF141413)
val NeutralOnBackgroundDark = Color(0xFFE5E4E2)
val NeutralSurfaceVariantDark = Color(0xFF48473F)
val NeutralOnSurfaceVariantDark = Color(0xFFC7C6C3)
val NeutralSurfaceContainerLowestDark = Color(0xFF0E0E0D)
val NeutralSurfaceContainerLowDark = Color(0xFF1C1C1B)
val NeutralSurfaceContainerDark = Color(0xFF202020)
val NeutralSurfaceContainerHighDark = Color(0xFF2A2A29)
val NeutralSurfaceContainerHighestDark = Color(0xFF353533)
val NeutralOutlineDark = Color(0xFF929190)
val NeutralOutlineVariantDark = Color(0xFF48473F)

// Error - kept close to Material's default red family (the one deliberate spot of real color,
// reserved for destructive actions like delete - a functional signal, not decoration).
val ErrorLight = Color(0xFFBA1A1A)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFDAD6)
val OnErrorContainerLight = Color(0xFF410002)

val ErrorDark = Color(0xFFFFB4AB)
val OnErrorDark = Color(0xFF690005)
val ErrorContainerDark = Color(0xFF93000A)
val OnErrorContainerDark = Color(0xFFFFDAD6)
