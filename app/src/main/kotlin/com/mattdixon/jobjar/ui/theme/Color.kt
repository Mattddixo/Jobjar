package com.mattdixon.jobjar.ui.theme

import androidx.compose.ui.graphics.Color

// A single confident hero color plus two genuinely muted supporting ones, instead of three
// competing saturated hues fighting for attention. That was the problem with the first pass at
// this: amber controls, minty badges, and a hot-pink drawn-job card all at once reads as loud
// and uncoordinated, not "fun." Production apps that feel fun (and not chaotic) generally commit
// to one brand color and use everything else as quiet support - that's the model here.
//
//   - Teal (primary)   - the one brand color: every main action, filter, and control.
//   - Sand (secondary) - a low-saturation warm neutral for badges/tags - support, not a second
//     lead. Nearly a neutral on purpose.
//   - Honey (tertiary) - reserved solely for a drawn job's card on the Jar tab, and kept soft
//     (a gentle cream-gold tint, not a saturated block) so it reads as a warm highlight rather
//     than "a different colored box."
// Neutrals are clean and barely warm - not the tan/parchment of the first attempt, not cold grey.

// Teal - primary
val TealPrimaryLight = Color(0xFF0B7A6B)
val TealOnPrimaryLight = Color(0xFFFFFFFF)
val TealContainerLight = Color(0xFFB7F0E1)
val TealOnContainerLight = Color(0xFF00201A)

val TealPrimaryDark = Color(0xFF52DBC4)
val TealOnPrimaryDark = Color(0xFF00382F)
val TealContainerDark = Color(0xFF005143)
val TealOnContainerDark = Color(0xFFB7F0E1)

// Sand - secondary, deliberately low-saturation so it never competes with teal
val SandSecondaryLight = Color(0xFF6F5F4F)
val SandOnSecondaryLight = Color(0xFFFFFFFF)
val SandContainerLight = Color(0xFFEFE0CE)
val SandOnContainerLight = Color(0xFF271A09)

val SandSecondaryDark = Color(0xFFD9C4AC)
val SandOnSecondaryDark = Color(0xFF3D2E1B)
val SandContainerDark = Color(0xFF554530)
val SandOnContainerDark = Color(0xFFEFE0CE)

// Honey - tertiary, reserved for the drawn-job spotlight only, kept soft rather than saturated
val HoneyTertiaryLight = Color(0xFF8A5D19)
val HoneyOnTertiaryLight = Color(0xFFFFFFFF)
val HoneyContainerLight = Color(0xFFFFE7C2)
val HoneyOnContainerLight = Color(0xFF2C1800)

val HoneyTertiaryDark = Color(0xFFFFB955)
val HoneyOnTertiaryDark = Color(0xFF482A00)
val HoneyContainerDark = Color(0xFF6A3F00)
val HoneyOnContainerDark = Color(0xFFFFE7C2)

// Warm neutrals - background/surface family
val WarmBackgroundLight = Color(0xFFFBFAF6)
val WarmOnBackgroundLight = Color(0xFF1B1C18)
val WarmSurfaceVariantLight = Color(0xFFE2E2D7)
val WarmOnSurfaceVariantLight = Color(0xFF45473C)
val WarmSurfaceContainerLowLight = Color(0xFFF5F4EF)
val WarmSurfaceContainerLight = Color(0xFFEFEEE8)
val WarmSurfaceContainerHighLight = Color(0xFFE9E8E1)
val WarmSurfaceContainerHighestLight = Color(0xFFE3E3DB)
val WarmOutlineLight = Color(0xFF767669)
val WarmOutlineVariantLight = Color(0xFFC6C6B7)

val WarmBackgroundDark = Color(0xFF13140F)
val WarmOnBackgroundDark = Color(0xFFE3E3DA)
val WarmSurfaceVariantDark = Color(0xFF45473C)
val WarmOnSurfaceVariantDark = Color(0xFFC6C6B7)
val WarmSurfaceContainerLowestDark = Color(0xFF0D0F0A)
val WarmSurfaceContainerLowDark = Color(0xFF1B1C17)
val WarmSurfaceContainerDark = Color(0xFF1F211B)
val WarmSurfaceContainerHighDark = Color(0xFF292B24)
val WarmSurfaceContainerHighestDark = Color(0xFF34362E)
val WarmOutlineDark = Color(0xFF90917F)
val WarmOutlineVariantDark = Color(0xFF45473C)

// Error - kept close to Material's default red family (no reason to reinvent this one)
val ErrorLight = Color(0xFFBA1A1A)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFDAD6)
val OnErrorContainerLight = Color(0xFF410002)

val ErrorDark = Color(0xFFFFB4AB)
val OnErrorDark = Color(0xFF690005)
val ErrorContainerDark = Color(0xFF93000A)
val OnErrorContainerDark = Color(0xFFFFDAD6)
