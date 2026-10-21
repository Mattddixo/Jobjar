package com.mattdixon.jobjar.ui.theme

import androidx.compose.ui.graphics.Color

// Clean and airy: a soft cool off-white page, bright white cards with hairline borders, and
// exactly one calm accent (teal) for primary actions. The earlier all-grey palette made cards
// and buttons read as heavy charcoal/grey blocks; this keeps the restraint (no rainbow of
// colors) but lets the screens breathe. Dark mode is a soft blue-grey charcoal rather than
// near-black, with surfaces stepping visibly lighter instead of disappearing into each other.

// Teal - primary. Buttons, FAB, selected chips, switches.
val TealPrimaryLight = Color(0xFF1F7A6D)
val TealOnPrimaryLight = Color(0xFFFFFFFF)
val TealContainerLight = Color(0xFFD3EFE9)
val TealOnContainerLight = Color(0xFF00382F)

val TealPrimaryDark = Color(0xFF7FD3C3)
val TealOnPrimaryDark = Color(0xFF00382F)
val TealContainerDark = Color(0xFF1B5249)
val TealOnContainerDark = Color(0xFFD3EFE9)

// Slate - secondary, a cool blue-grey for badges/tags.
val SlateSecondaryLight = Color(0xFF56636F)
val SlateOnSecondaryLight = Color(0xFFFFFFFF)
val SlateContainerLight = Color(0xFFE6EBF0)
val SlateOnContainerLight = Color(0xFF1F2A33)

val SlateSecondaryDark = Color(0xFFB4C0CC)
val SlateOnSecondaryDark = Color(0xFF1F2A33)
val SlateContainerDark = Color(0xFF38434E)
val SlateOnContainerDark = Color(0xFFE6EBF0)

// Honey - tertiary, reserved for a drawn job's card: the one warm spot, so whatever came out
// of the jar stands apart from everything else on screen.
val HoneyTertiaryLight = Color(0xFF9A5F0E)
val HoneyOnTertiaryLight = Color(0xFFFFFFFF)
val HoneyContainerLight = Color(0xFFFFEFD6)
val HoneyOnContainerLight = Color(0xFF3B2600)

val HoneyTertiaryDark = Color(0xFFF2BE6B)
val HoneyOnTertiaryDark = Color(0xFF3B2600)
val HoneyContainerDark = Color(0xFF55401C)
val HoneyOnContainerDark = Color(0xFFFFE6BE)

// Neutrals - background/surface family, a hair cool so the white cards and teal sit cleanly.
val NeutralBackgroundLight = Color(0xFFF5F7F9)
val NeutralOnBackgroundLight = Color(0xFF1B1F24)
val NeutralSurfaceVariantLight = Color(0xFFE2E7EC)
val NeutralOnSurfaceVariantLight = Color(0xFF5A636D)
val NeutralSurfaceContainerLowestLight = Color(0xFFFFFFFF)
val NeutralSurfaceContainerLowLight = Color(0xFFF0F3F6)
val NeutralSurfaceContainerLight = Color(0xFFEBEFF3)
val NeutralSurfaceContainerHighLight = Color(0xFFE6EAEF)
val NeutralSurfaceContainerHighestLight = Color(0xFFE0E5EA)
val NeutralOutlineLight = Color(0xFF8A939D)
val NeutralOutlineVariantLight = Color(0xFFDCE1E7)

val NeutralBackgroundDark = Color(0xFF1A1D21)
val NeutralOnBackgroundDark = Color(0xFFE6E8EB)
val NeutralSurfaceVariantDark = Color(0xFF3A4148)
val NeutralOnSurfaceVariantDark = Color(0xFFB9C1C9)
val NeutralSurfaceContainerLowestDark = Color(0xFF15181B)
val NeutralSurfaceContainerLowDark = Color(0xFF1F2327)
val NeutralSurfaceContainerDark = Color(0xFF23282D)
val NeutralSurfaceContainerHighDark = Color(0xFF2A2F35)
val NeutralSurfaceContainerHighestDark = Color(0xFF32383E)
val NeutralOutlineDark = Color(0xFF878F98)
val NeutralOutlineVariantDark = Color(0xFF353B42)

// Cards - see AppColors in Theme.kt. White on the light page; a step lighter than the page in dark.
val CardLight = Color(0xFFFFFFFF)
val CardBorderLight = Color(0xFFE3E7EC)
val CardDark = Color(0xFF23282D)
val CardBorderDark = Color(0xFF2F353B)

// Error - Material's default red family, reserved for destructive actions like delete.
val ErrorLight = Color(0xFFBA1A1A)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFDAD6)
val OnErrorContainerLight = Color(0xFF410002)

val ErrorDark = Color(0xFFFFB4AB)
val OnErrorDark = Color(0xFF690005)
val ErrorContainerDark = Color(0xFF93000A)
val OnErrorContainerDark = Color(0xFFFFDAD6)
